# 素の Linux サーバーへのデプロイ — Spring Boot と Nginx + Laravel

EC2 やオンプレの Linux サーバーに、コンテナを使わずアプリを置いて公開するときの手順の流れ。**コンテナでのデプロイしか経験がない人向け**に、「ECS が肩代わりしていた作業が、素の Linux では誰の仕事になるのか」という視点で整理する。Spring Boot(Java)と Nginx + Laravel(PHP)の 2 通りを、それぞれ通しで扱う。

> **この文書は実測ではない。** 同じ `docs/notes/` にある [os-users-and-file-locations.md](./os-users-and-file-locations.md) や [file-permissions-and-exec-bit.md](./file-permissions-and-exec-bit.md) は手元の環境で測った結果を載せているが、この文書は**手順の解説**であって、コマンドの出力を実機で確認したものではない。パッケージ名など間違えると詰まる箇所は AWS 公式ドキュメントで裏を取ってあるが、**php-fpm の既定値のように環境で変わりうるものには「確認コマンド」を添えた**ので、実際にやるときは自分の目で確かめてほしい。

要点は 5 つ。

1. **決定的な違いは「HTTP を誰が受けるか」。** Spring Boot は jar 自身が HTTP を受けられる(組み込み Tomcat)ので nginx は**任意**。PHP は自分では受けられないので nginx + php-fpm が**必須**。この 1 点から、常駐プロセスの数・ユーザーの構成・設定ファイルの枚数が全部決まる
2. **コンテナがやっていたことは消えるのではなく、ホスト側の設定ファイルに分解される。** `FROM` は `dnf install`、`ENTRYPOINT` は systemd の `ExecStart=`、restart policy は `Restart=`、compose のネットワークは `127.0.0.1` とファイアウォールになる
3. **リリース方式は 6 つあり、本質的な線引きは 1 つだけ** — 「今動いているものを直接書き換えるか、別の場所に完成させてから参照先を一斉に切り替えるか」。**コンテナは後者の系譜の一番新しい形でしかない**
4. **リリース方式の選択は PHP ではシビア、Java では緩い。** PHP はリクエストのたびにファイルを読むので、書き換え途中の混ざった状態が本番に露出する。Java は起動時に jar を読み切るので露出しない
5. **コンテナだと踏まない罠が 3 つある** — SELinux、ファイルの所有者と権限、そして「**サーバーを再起動したらアプリが上がってこない**」

権限やユーザーの基礎(なぜ専用ユーザーを作るのか、`.service` ファイルの読み方)は [os-users-and-file-locations.md](./os-users-and-file-locations.md) の第 5 章で扱った。この文書はその続きで、**配置から公開・更新・運用までの一本の流れ**を扱う。

## 0. この文書の前提

| | 前提 |
|---|---|
| OS | **Amazon Linux 2023(AL2023)**。EC2 とオンプレの両方を想定 |
| Ubuntu との差 | AL2023 は Fedora/RHEL 系。`dnf`・SELinux・firewalld・php-fpm が `apache` ユーザーで動く点は **Rocky / RHEL とほぼ同じ**なので、オンプレでもそのまま通じる。Ubuntu との差は表で注記する |
| DB | **RDS か別サーバーに用意済み**とする。同居させる場合の要点は 6-7 に短くまとめる |
| Spring Boot 編の題材 | **このリポジトリの `backend/`**。`.env.example` と `application.yml` がそのまま登場する |
| Laravel 編の題材 | 一般的な Laravel アプリ(このリポジトリには無いため) |
| 比較対象のコンテナ構成 | **ECS Fargate**(→ [docs/infrastructure/README.md](../infrastructure/README.md)) |

## 1. 全体像 — ECS が肩代わりしていたものを、素の Linux では誰がやるのか

### 1-1. 対応表

コンテナでのデプロイは、実は**同じ仕事を別の場所に書いていただけ**で、仕事自体は消えていない。対応させるとこうなる。

| やるべきこと | コンテナ(ECS)ではどこに書いたか | 素の Linux では |
|---|---|---|
| 実行に必要なランタイムを用意する | Dockerfile の `FROM eclipse-temurin:21-jre` | `dnf install java-21-amazon-corretto-headless` |
| アプリの成果物を配置する | Dockerfile の `COPY app.jar /app/` | `scp` / `rsync` でサーバーへ送る |
| どのユーザーで動かすか | Dockerfile の `USER appuser` | `useradd --system` + systemd の `User=` |
| 起動コマンド | Dockerfile の `ENTRYPOINT` | systemd の `ExecStart=` |
| 落ちたら再起動する | ECS サービスがタスクを再起動 | systemd の `Restart=on-failure` |
| サーバー起動時に立ち上げる | ECS が常に必要数を維持 | `systemctl enable` |
| 環境変数を渡す | タスク定義の `environment` / `secrets` | systemd の `EnvironmentFile=` |
| ポートを公開する | タスク定義の `portMappings` + セキュリティグループ | ファイアウォール(+ nginx の前段) |
| TLS 終端 | ALB + ACM(証明書は自動更新) | **nginx + certbot(自分で設定する)** |
| ログを集める | `awslogs` ドライバ → CloudWatch Logs | journald(+ 必要なら CloudWatch エージェント) |
| 新しい版に切り替える | 新しいタスク定義リビジョンでサービス更新 | シンボリックリンクの張り替え + 再起動 |
| 前の版に戻す | 前のタスク定義リビジョンに戻す | 前のリリースディレクトリにリンクを戻す |
| 死活監視 | ALB のヘルスチェック + ECS のタスク監視 | **誰もやらない。自分で用意する** |
| OS のパッチ当て | **不要**(Fargate は AWS 管理) | `dnf update` と再起動の運用が発生する |
| ディスクが溢れないようにする | タスクは使い捨てなので考えなくてよい | ログローテートが要る |

右端が空白ではなく**全部埋まる**のがポイント。素の Linux が「面倒」なのは、これらが 1 つのファイル(Dockerfile / タスク定義)にまとまっておらず、**`/etc` の下に散らばった別々の設定ファイルに分解される**ため。

### 1-2. 決定的な違い — Java は自分で HTTP を受けられる、PHP は受けられない

2 つの構成の違いは無数にあるように見えるが、**原因は 1 つ**。

```
Spring Boot:
  ブラウザ ──HTTP──> [ java プロセス(組み込み Tomcat) ] ──> DB
                      ↑ これ 1 つで HTTP を受けられる

Laravel:
  ブラウザ ──HTTP──> [ nginx ] ──FastCGI──> [ php-fpm ] ──> DB
                      ↑ HTTP は受けるが      ↑ PHP は動かすが
                        PHP は動かせない       HTTP は受けられない
```

Java の jar には **Tomcat が同梱**されていて(Spring Boot の `spring-boot-starter-web` が引き込んでいる)、`java -jar` するだけでポートを開いて待ち受ける。だから前段は要らない。

PHP は違う。PHP は元々「Web サーバーから呼ばれて 1 リクエストぶん処理して終わる」設計の言語で、**自分で待ち受け続けるプロセスを持たない**。そこで、

- **nginx** が HTTP を受ける
- 拡張子が `.php` のリクエストだけを **FastCGI** というプロトコルで **php-fpm**(PHP FastCGI Process Manager)に渡す
- php-fpm が PHP プロセスを常駐させておき、渡されたリクエストを処理して結果を返す

という 2 段構えになる。**「Laravel には Nginx が必要」と言われるのは、Laravel の都合ではなく PHP の都合**で、この分業が動く仕組みそのもの。

この 1 点から、以下が全部派生する。

| | Spring Boot | Nginx + Laravel |
|---|---|---|
| 常駐プロセスの数(最小) | **1**(java) | **2**(nginx, php-fpm) |
| systemd ユニットの数(最小) | 1(自分で書く) | 2(どちらもパッケージ付属をそのまま使う) |
| OS ユーザーの数 | 1(`appuser`) | 2〜3(`nginx`、php-fpm 用、デプロイ用) |
| 前段の Web サーバー | 任意(TLS のために置くことが多い) | **必須** |
| アプリの設定ファイル | jar 同梱の `application.yml` + 環境変数 | `.env` ファイル(実体をサーバーに置く) |
| 公開するディレクトリ | 無い(全部 java が返す) | **`public/` だけ**(ここを間違えるとソースが読まれる) |
| コード更新の反映 | プロセス再起動が**必須** | ファイルを置けば次のリクエストから反映(だから危ない) |
| バックグラウンド処理 | アプリ内(`@Scheduled` / `@Async`) | **別プロセス**(キューワーカー + cron) |

---

## 2. 共通の下ごしらえ

Spring Boot でも Laravel でも同じことをやる部分。ここが済んでいれば、あとはランタイムの違いだけになる。

### 2-1. サーバーを用意する

EC2 なら AL2023 の AMI でインスタンスを起動し、SSH で入る。オンプレなら Rocky / RHEL を入れて同じ状態にする。

```bash
# まず OS を最新にする(EC2 の AMI も焼かれた時点のもの)
sudo dnf update -y
```

**コンテナとの最初の違いがここ。** Fargate では OS のパッチは AWS の仕事だったが、EC2 やオンプレでは**自分の仕事**になる。この時点で「定期的に `dnf update` して再起動する」という運用が発生することが確定する(→ 6-3)。

### 2-2. ファイアウォール — 誰が塞いでいるのか

「どのポートを外から見せるか」の管理者は、環境によって別物になる。

| 環境 | 誰が塞ぐか | 開け方 |
|---|---|---|
| **EC2** | **セキュリティグループ**(EC2 の外側にある) | AWS 側の設定。OS の中では何もしない |
| **オンプレ RHEL / Rocky** | **firewalld**(OS の中) | `firewall-cmd` で開ける |
| Ubuntu | `ufw`(既定では無効なことが多い) | `ufw allow` |

EC2 では OS 側のファイアウォールを触らずセキュリティグループだけで済ませるのが普通。オンプレはこう:

```bash
sudo firewall-cmd --permanent --add-service=http
sudo firewall-cmd --permanent --add-service=https
sudo firewall-cmd --reload
sudo firewall-cmd --list-all      # 確認
```

**開けるのは 80 と 443 だけ。** アプリが待ち受ける 8080 や、DB の 3306 は**外に開けない**。これがコンテナ構成での「compose のネットワークに繋がっているコンテナ同士だけが喋れる」に当たる。素の Linux では、同じことを

- アプリを `127.0.0.1:8080` にだけバインドする(外部 NIC で待ち受けない)
- ファイアウォールで 8080 を閉じておく

の二重で実現する。**片方だけだと事故る**ので両方やる。

> Spring Boot を `127.0.0.1` だけで待ち受けさせるには `application.yml` に `server.address: 127.0.0.1` を足す。nginx を前段に置く構成なら、これを入れておくと「8080 を直接叩かれる」経路が消える。

### 2-3. SELinux — コンテナだと絶対に踏まなかった罠

SELinux は「どのプロセスがどのファイルに触ってよいか」を、`rwx` の権限とは**別に**強制する Linux カーネルの仕組み。`rwx` 的には読めるファイルでも、SELinux のラベルが合わなければ拒否される。

状態は 3 つある。

| モード | 挙動 | 既定でこうなっている環境 |
|---|---|---|
| `enforcing` | ルールに反する操作を**拒否する** | **RHEL / Rocky**(オンプレで多い) |
| `permissive` | 拒否せず、**ログに記録するだけ** | **AL2023** |
| `disabled` | 何もしない | 手動で無効化した環境 |

```bash
$ getenforce
Permissive
$ sestatus            # 詳しく見る
```

つまり **AL2023 上では「動くけどログに警告が出る」で済み、そのまま Rocky に持っていくと動かなくなる**。この落差が事故の元なので、AL2023 で開発していても対処は入れておく。

この構成で必ず引っかかるのは 2 つ。

**(1) nginx が別プロセスへプロキシできない**

SELinux は既定で「Web サーバーからのネットワーク接続」を禁止している。nginx から `127.0.0.1:8080`(Spring Boot)へ `proxy_pass` すると、enforcing 環境では **502 Bad Gateway** になる。

```bash
sudo setsebool -P httpd_can_network_connect 1
```

`-P` は「再起動しても残す(permanent)」の意味。**これを付け忘れると再起動で元に戻る**ので必ず付ける。

**(2) 標準以外の場所に置いたファイルを nginx が読めない**

SELinux は「Web で配信してよいファイル」に `httpd_sys_content_t` というラベルが付いていることを要求する。`/usr/share/nginx/html` のような標準の場所には最初から付いているが、**`/srv/myapp` のような自分で作ったディレクトリには付いていない**。

```bash
# 「このパス以下は Web コンテンツである」というルールを登録し、
sudo semanage fcontext -a -t httpd_sys_content_t "/srv/myapp(/.*)?"
# 実際のファイルにラベルを貼り直す
sudo restorecon -Rv /srv/myapp
```

Laravel の場合、`storage/` は**書き込み**も必要なので、そこだけ別のラベルを付ける:

```bash
sudo semanage fcontext -a -t httpd_sys_rw_content_t "/srv/myapp/shared/storage(/.*)?"
sudo restorecon -Rv /srv/myapp/shared/storage
```

拒否されたときの調べ方も覚えておく。「設定は合っているはずなのに動かない」の犯人がこれであることは非常に多い:

```bash
sudo ausearch -m AVC -ts recent      # 直近の拒否ログを見る
```

> **なぜコンテナだと踏まないのか。** コンテナイメージの中身は 1 つのアプリ専用に作られていて、ファイルもプロセスも隔離済みという前提がある。SELinux が守ろうとしている「1 台のサーバーに同居している複数のサービスが、互いのファイルに手を出さないようにする」という課題自体が、コンテナでは薄い。素の Linux はその課題がある世界なので、この仕組みが効いている。

### 2-4. アプリ専用ユーザーを作る

root で動かさない。理由と `useradd` の各オプションの意味は [os-users-and-file-locations.md](./os-users-and-file-locations.md) の 5-2 に書いたのでここでは繰り返さない。

```bash
sudo useradd --system --shell /usr/sbin/nologin appuser
```

Laravel 側は少し事情が違う。nginx は `nginx` ユーザーで動き、php-fpm は既定では `apache` ユーザーで動く。後述の 4-5 では、この既定を使わず**アプリ専用の php-fpm プールを作って `appuser` で動かす**形にする。

**なぜ既定の `apache` のままではいけないのか。** `apache` は Apache や PHP のパッケージが作る **Web 系の汎用ユーザー**で、このアプリのために存在しているわけではない。ここにアプリのファイルを持たせると、

- `.env`(DB パスワードが入っている)の所有者が `apache` になる
- 将来 `apache` で動く何か(phpMyAdmin、他のツール)を入れた瞬間、**そこから `.env` が読める**
- 「このファイルに触れるのは誰か」を自分でコントロールできなくなる

これは第 1 章の「`www-data` のような汎用ユーザーを使い回さず、サービスごとに専用ユーザーを立てる」という Linux の慣習そのもので、**アプリが 1 つしか載っていなくても変わらない**(→ [os-users-and-file-locations.md](./os-users-and-file-locations.md) の「理由 3: 最小権限」)。加えて `ps` やログで見たときに `apache` ではなく `appuser` と出るので、どのプロセスが自分のアプリかが一目で分かる(同「理由 2: 責任の追跡」)。

> **1 台に複数のアプリを載せる前提なのか?** 現代のクラウド構成では「1 台(1 コンテナ)1 アプリ」が普通だが、**素の Linux / オンプレの世界では 1 台に複数が同居しているほうが歴史的には多い** — 社内サーバーに業務システムが数個、VPS に複数サイト、staging と本番の相乗り、共有ホスティング。**Laravel Forge も 1 台に複数サイトを載せる前提の設計**になっている。同居している場合は、上の理由に「互いのファイルを読めなくする」が加わる。ただし 1 台 1 アプリなら、その理由は成立しない。**専用ユーザーで動かす根拠は、あくまで上の 2 つ。**

| ユーザー | 何のために存在するか |
|---|---|
| `appuser` | アプリ本体を動かす。Java プロセス / php-fpm プールの実行ユーザー |
| `nginx` | nginx が動くユーザー(パッケージが自動で作る) |
| `deploy` | CI や手元から SSH でファイルを送るためのユーザー。ログインできる必要があるので `appuser` とは別に作る |

`appuser` はログインできないので、**ファイルを送る作業には使えない**。ここが「デプロイ用のユーザーが別に要る」理由。

### 2-5. 置き場所を決める

`/opt/<アプリ名>` か `/srv/<アプリ名>` に置き、`/home` には置かない(理由 → [os-users-and-file-locations.md](./os-users-and-file-locations.md) 5-3)。

最終的にはこういう形にする。**この形が後のリリース方式(第 5 章)の土台**になる:

```
/srv/myapp/
├── releases/                       ← 過去の版がそのまま残る
│   ├── 20260809-120000/
│   ├── 20260809-133000/
│   └── 20260809-150000/
├── shared/                         ← 版をまたいで共有するもの
│   ├── .env                        (Laravel の場合)
│   └── storage/                    (アップロードファイル、ログ)
└── current -> releases/20260809-150000     ← シンボリックリンク
```

`current` が**今動いている版を指す矢印**で、この矢印を張り替えることが「デプロイ」になる。ECS でいう「サービスが指しているタスク定義のリビジョン」に当たる。

```bash
sudo mkdir -p /srv/myapp/{releases,shared}
sudo chown -R appuser:appuser /srv/myapp
sudo chmod 750 /srv/myapp
```

> **`chmod 750` にすると nginx が読めなくなるのでは？** Laravel の場合はそのとおりで、nginx が `public/` を読むために `appuser` グループに `nginx` を入れるか、`755` にするなどの調整が要る(→ 4-5)。Spring Boot の場合は nginx がファイルを直接読まない(全部プロキシする)ので `750` のままでよい。**ここも「Java と PHP で構成が変わる」箇所の 1 つ。**

### 2-6. 設定と秘密の置き方

`.env.example` にある 12 個の変数を、サーバーではどう渡すか。**Java と PHP でここが根本的に違う。**

| | Spring Boot | Laravel |
|---|---|---|
| 読み方 | **環境変数**として受け取る(`application.yml` の `${DB_HOST}`) | **`.env` ファイル**を自分で読む |
| ファイルの置き場所 | `/etc/myapp/env`(systemd が読んでプロセスに渡す) | `/srv/myapp/shared/.env`(アプリが直接読む) |
| ファイルが無いとどうなるか | 既定値(`${DB_HOST:localhost}`)が使われる | 起動しない |

Spring Boot 側は `/etc/myapp/env` を作る。中身は `.env.example` から開発専用の値を実際の接続先に差し替えたもの:

```bash
sudo mkdir -p /etc/myapp
sudo vi /etc/myapp/env
```

```ini
DB_HOST=myapp-db.xxxxx.ap-northeast-1.rds.amazonaws.com
DB_PORT=3306
DB_NAME=app
DB_USER=app
DB_PASSWORD=（実際のパスワード）
S3_ENDPOINT=
S3_BUCKET=myapp-images
SMTP_HOST=email-smtp.ap-northeast-1.amazonaws.com
SMTP_PORT=587
MAIL_FROM=no-reply@example.com
APP_BASE_URL=https://example.com
```

権限を絞る。**これが秘密を守っている実体**:

```bash
sudo chown root:appuser /etc/myapp/env
sudo chmod 640 /etc/myapp/env
```

`640` = 所有者(root)は読み書き、グループ(`appuser`)は**読むだけ**、その他は**何もできない**。アプリからは読めるが、サーバーに入った他のユーザーからは読めない。

環境変数がプロセスに渡る仕組みそのものは [env-vars-basics.md](./env-vars-basics.md) に整理してある。

> **AWS を使うなら、そもそも置かないという手もある。** DB パスワードを Secrets Manager や SSM パラメータストアに入れ、起動時に取りに行く形にすると、ディスク上に秘密が残らない。ECS のタスク定義で `secrets` を使っているのがまさにこれ。素の Linux でも、起動スクリプトで `aws ssm get-parameter` して環境変数に入れれば同じことができる。認証情報を「置かない」考え方は [os-users-and-file-locations.md](./os-users-and-file-locations.md) 第 6 章に整理した。

---

## 3. Spring Boot 編

### 3-1. ランタイムを入れる

```bash
sudo dnf install -y java-21-amazon-corretto-headless
java -version
```

パッケージ名の意味:

| パッケージ | 中身 | いつ使うか |
|---|---|---|
| `java-21-amazon-corretto-headless` | GUI 用の依存(X11 / ALSA)を省いた実行環境 | **サーバーはこれ** |
| `java-21-amazon-corretto` | 上記 + X11 / ALSA | デスクトップ用途 |
| `java-21-amazon-corretto-devel` | `javac` などの開発ツール | **サーバーでビルドするなら必要** |

インストール先は `/usr/lib/jvm/java-21-amazon-corretto.<CPU アーキテクチャ>` で、`/usr/bin/java` からは alternatives 経由でリンクされる。

**サーバーには `-headless` だけ入れれば足りる**、つまり **`javac` も Gradle も要らない**。これが次の話に繋がる。

> Ubuntu なら `sudo apt install openjdk-21-jre-headless`。Corretto は Amazon が配布する OpenJDK ビルドで、Temurin などと中身は実質同じ(→ [java-build-and-run.md](./java-build-and-run.md))。AL2023 で Corretto を使うのは、単に OS のリポジトリに最初から入っているから。

### 3-2. どこでビルドするか

**Java の大きな利点がここに出る。**

```
【手元 or CI でビルド】
  ソース ──gradle build──> app-0.0.1-SNAPSHOT.jar  ← これ 1 個だけをサーバーへ送る
                                                      サーバーには JRE だけあればいい

【サーバーでビルド】
  ソース ──git clone──> サーバー上で gradle build ← JDK と Gradle と依存の
                                                    ダウンロードがサーバーに必要
```

| | 手元 / CI でビルド | サーバーでビルド |
|---|---|---|
| サーバーに要るもの | JRE だけ | JDK + Gradle + ネットワーク |
| 送るもの | jar 1 個(数十 MB) | ソース一式 |
| ビルド失敗の影響 | サーバーに届かない(**安全**) | サーバーが中途半端な状態になる |
| ビルド中のサーバー負荷 | なし | CPU とメモリを食う |
| 再現性 | CI で毎回同じ環境 | サーバーの状態に依存する |

**推奨は手元 / CI でビルドして jar だけ送る。** これが「コンテナイメージを CI でビルドして ECR に push する」に一番近い形で、コンテナのメリットの大半(**成果物が固まっていて、動かす側は中身を知らなくていい**)を、コンテナ無しでも得られる。

このリポジトリの場合、jar を作る前に**フロントの SSG ビルドが要る**ことに注意:

```bash
# 1. Nuxt を SSG ビルドして、出力を Spring Boot の static/ へ
cd frontend && npm ci && npm run generate
cp -r .output/public/* ../backend/src/main/resources/static/

# 2. jar を作る(この中に静的ファイルも入る)
cd ../backend && ./gradlew clean build

# 3. できあがり
ls build/libs/app-0.0.1-SNAPSHOT.jar
```

**この 2 段構えは、サーバーでビルドする方式を選びにくくする理由でもある**(サーバーに Node.js まで要ることになる)。

### 3-3. 配置する

```bash
# 手元から
REL=$(date +%Y%m%d-%H%M%S)
scp backend/build/libs/app-0.0.1-SNAPSHOT.jar deploy@example.com:/tmp/app.jar

# サーバー側で
sudo mkdir -p /srv/myapp/releases/$REL
sudo mv /tmp/app.jar /srv/myapp/releases/$REL/app.jar
sudo chown -R appuser:appuser /srv/myapp/releases/$REL
sudo ln -sfn /srv/myapp/releases/$REL /srv/myapp/current
```

初回はこれで十分。**2 回目以降の「安全な切り替え」は第 5 章**で扱う。

### 3-4. systemd ユニットを書く

`/etc/systemd/system/myapp.service`:

```ini
[Unit]
Description=myapp (Spring Boot)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=appuser
Group=appuser
WorkingDirectory=/srv/myapp/current
EnvironmentFile=/etc/myapp/env
ExecStart=/usr/bin/java -jar /srv/myapp/current/app.jar
SuccessExitStatus=143
Restart=on-failure
RestartSec=5
TimeoutStopSec=30

# 権限をさらに絞る(コンテナが自動でくれていたものを手で足す)
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/srv/myapp/shared

[Install]
WantedBy=multi-user.target
```

`.service` ファイルの基本的な読み方(`ExecStart=` はコマンドラインそのもの、絶対パスで書く理由、`daemon-reload` / `enable` / `start` の 3 手順)は [os-users-and-file-locations.md](./os-users-and-file-locations.md) 5-4 に書いた。ここでは**そこに書かなかった行**だけ説明する。

| 行 | 意味 | なぜ要るか |
|---|---|---|
| `After` / `Wants=network-online.target` | ネットワークが使える状態になってから起動する | 起動時に DB へ繋ぎに行くので、ネットワーク前に起動すると失敗する |
| `SuccessExitStatus=143` | 終了コード 143 も正常終了扱いにする | **`SIGTERM` で終わった JVM の終了コードが 143**。これが無いと、正常な停止のたびに `Restart=on-failure` で再起動してしまう |
| `RestartSec=5` | 再起動まで 5 秒待つ | 起動に失敗し続けるアプリが 1 秒間隔で再起動を繰り返してサーバーを潰すのを防ぐ |
| `TimeoutStopSec=30` | 停止に 30 秒待って、それでも終わらなければ強制終了 | 処理中のリクエストを終わらせる猶予 |
| `NoNewPrivileges=true` | このプロセスとその子は、これ以上権限を得られない | `sudo` や setuid で権限昇格される経路を塞ぐ |
| `PrivateTmp=true` | `/tmp` をこのサービス専用にする | 他のサービスが `/tmp` に置いたファイルを読めなくする |
| `ProtectSystem=strict` | **ファイルシステム全体を読み取り専用にする** | 乗っ取られてもファイルを書き換えられない |
| `ReadWritePaths=` | `ProtectSystem=strict` の例外。ここだけ書ける | アップロード先やログの置き場所を指定する |

**下の 4 行がまさに「コンテナが暗黙にやっていたこと」。** コンテナは最初からファイルシステムが隔離されていて、`/tmp` も専用で、ホストの `/etc` は見えない。素の Linux ではそれが**既定では効いていない**ので、`systemd` の機能で 1 つずつ足すことになる。

反映:

```bash
sudo systemctl daemon-reload
sudo systemctl enable myapp
sudo systemctl start myapp
sudo systemctl status myapp
```

### 3-5. 起動確認とログ

```bash
# 状態
sudo systemctl status myapp

# ログ(docker logs -f に当たる)
sudo journalctl -u myapp -f

# 直近 100 行
sudo journalctl -u myapp -n 100

# 今日のぶんだけ
sudo journalctl -u myapp --since today
```

**`docker logs` に相当するのが `journalctl -u <サービス名>`。** アプリが標準出力に出したものを systemd が journald に流し込んでいるので、Spring Boot 側でログファイルの設定をしていなくてもこれで読める。

このリポジトリの `backend` なら、ここで確認すべきことが 2 つある:

1. **Flyway のマイグレーションが走ったか** — 起動ログに `Migrating schema` や `Successfully applied N migrations` が出る。出ていなければ DB に繋がっていない
2. **`ddl-auto: validate` が通ったか** — エンティティと DB のスキーマが食い違っていると**起動時にエラーで落ちる**。これは意図した設計(`application.yml` のコメント参照)なので、落ちたらスキーマ側を疑う

```bash
# 実際に応答するか(まだ nginx を置いていないので直接叩く)
curl -i http://127.0.0.1:8080/
```

### 3-6. nginx を前段に置く

Spring Boot は単体で HTTP を受けられるので nginx は必須ではない。それでも置く理由:

1. **TLS を終端させる**(証明書の管理を nginx + certbot に任せられる)
2. **80 / 443 で待ち受けられる**(1024 未満のポートは特別扱いで、アプリ本体が扱うには追加設定が要る → 3-8)
3. 静的ファイルの配信、gzip、アクセスログ、レート制限などを前段で処理できる

> **このリポジトリでは「Nginx は使わない」と決めている**(→ [CLAUDE.md](../../CLAUDE.md))。ただしそれは **ALB がいるから**で、ALB が TLS 終端と 443 の受け口を担当している。ALB がいない素の Linux / オンプレでは前提が変わるので、この判断はそのまま持ち込めない。

```bash
sudo dnf install -y nginx
```

`/etc/nginx/conf.d/myapp.conf`:

```nginx
server {
    listen 80;
    server_name example.com;

    location / {
        proxy_pass http://127.0.0.1:8080;

        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        proxy_http_version 1.1;
        proxy_read_timeout 60s;
    }
}
```

```bash
sudo nginx -t                    # 設定ファイルの構文チェック。必ずやる
sudo systemctl enable --now nginx
```

**`proxy_set_header` の 4 行を落とすと何が起きるか。** nginx がリクエストを中継すると、Spring Boot から見た接続元は**全部 `127.0.0.1`**、プロトコルは**全部 `http`** になる。つまり:

- アクセスログのクライアント IP が全部 `127.0.0.1` になる
- レート制限や IP 制限が機能しなくなる
- アプリが自分の URL を組み立てると `http://` になる(HTTPS でアクセスされているのに)

そこで nginx が「本当の接続元はこれ、本当のプロトコルはこれ」を `X-Forwarded-*` ヘッダーで伝える。**そして受け取る側にも設定が要る:**

```yaml
# application.yml
server:
  forward-headers-strategy: framework
```

これを入れないと Spring Boot は `X-Forwarded-*` を**無視する**(信用できない送信元から偽装される可能性があるので、既定では無視する設計)。

> **これは ALB を使う本番構成でも同じ問題が起きる。** このリポジトリは URL 組み立てを `APP_BASE_URL` 環境変数で明示的にやっているので「HTTPS の URL が http:// になる」事故は起きないが、**クライアント IP は現状 ALB のものになっている**はず。IP を使った制限やログ分析をするなら、この設定が要る。

そして **SELinux**。2-3 で書いたとおり、これを忘れると enforcing 環境で 502 になる:

```bash
sudo setsebool -P httpd_can_network_connect 1
```

### 3-7. TLS を有効にする(certbot)

**ACM が自動でやってくれていた「証明書の取得と更新」を、自分で用意する部分。**

AL2023 のコアリポジトリには certbot が無いので、Python の仮想環境に入れる(AWS 公式ドキュメントがこの手順を案内している):

```bash
sudo dnf install -y python3 augeas-libs
sudo python3 -m venv /opt/certbot/
sudo /opt/certbot/bin/pip install --upgrade pip
sudo /opt/certbot/bin/pip install certbot certbot-nginx
sudo ln -sf /opt/certbot/bin/certbot /usr/bin/certbot
```

証明書を取る。**事前に DNS の A レコードがこのサーバーを指していて、80 番が外から届く**必要がある(Let's Encrypt がその経路で所有確認をするため):

```bash
sudo certbot --nginx -d example.com
```

これが済むと certbot が `/etc/nginx/conf.d/myapp.conf` を**自動で書き換え**、443 の `server` ブロックと証明書のパス、80 → 443 のリダイレクトを足してくれる。

**自動更新の設定を忘れないこと。** Let's Encrypt の証明書は 90 日で切れる:

```bash
# systemd timer で 1 日 2 回更新を試みる
sudo tee /etc/systemd/system/certbot-renew.service > /dev/null <<'EOF'
[Unit]
Description=Certbot renew
[Service]
Type=oneshot
ExecStart=/usr/bin/certbot renew --quiet --deploy-hook "systemctl reload nginx"
EOF

sudo tee /etc/systemd/system/certbot-renew.timer > /dev/null <<'EOF'
[Unit]
Description=Run certbot renew twice daily
[Timer]
OnCalendar=*-*-* 00,12:00:00
RandomizedDelaySec=3600
Persistent=true
[Install]
WantedBy=timers.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now certbot-renew.timer

# 更新が動くかを実際に叩かずに試す
sudo certbot renew --dry-run
```

`--deploy-hook` は「証明書が実際に更新されたときだけ実行する」指定。**新しい証明書を読み直させるために nginx の reload が要る**が、毎回 reload する必要はないのでフックにしている。

`.timer` は systemd 版の cron。`os-users-and-file-locations.md` で「拡張子が種類を表す」と書いた `.timer` の実例がこれ。

> **ACM と比べると。** ACM は「証明書を発行し、期限が来たら自動で更新し、ALB に配る」までを全部やる。証明書の実体がサーバーに置かれることすらない。**上の 30 行は、その代わりに自分で書いているもの**であり、「細かい設定が必要になる」の代表例。

### 3-8. 併記: nginx を置かず、Spring Boot が直接 443 を受ける場合

このリポジトリの方針(nginx を使わない)を素の Linux でも貫くなら、こうなる。

**問題 1: 証明書を Java が読める形にする。** nginx は PEM 形式を読むが、Java は伝統的に PKCS12(または JKS)を使う:

```bash
sudo openssl pkcs12 -export \
  -in  /etc/letsencrypt/live/example.com/fullchain.pem \
  -inkey /etc/letsencrypt/live/example.com/privkey.pem \
  -out /etc/myapp/keystore.p12 -name myapp \
  -passout pass:（パスワード）
sudo chown root:appuser /etc/myapp/keystore.p12
sudo chmod 640 /etc/myapp/keystore.p12
```

```yaml
# application.yml
server:
  port: 443
  ssl:
    key-store: /etc/myapp/keystore.p12
    key-store-type: PKCS12
    key-store-password: ${KEYSTORE_PASSWORD}
    key-alias: myapp
```

**問題 2: 1024 未満のポートは一般ユーザーが開けない。** `User=appuser` で動かしている以上、443 番を bind しようとすると `Permission denied` になる。解決は 2 つ:

```ini
# myapp.service に足す
AmbientCapabilities=CAP_NET_BIND_SERVICE
```

`CAP_NET_BIND_SERVICE` は「低いポートを開いてよい」という権限だけを切り出したもの。root 権限まるごとではなく**この 1 つだけ**を `appuser` に渡す。`os-users-and-file-locations.md` 5-4 で触れた行の実際の使い所がこれ。

もう 1 つは、8080 で待ち受けたまま **firewalld で 443 → 8080 に転送**する方法:

```bash
sudo firewall-cmd --permanent --add-forward-port=port=443:proto=tcp:toport=8080
```

**問題 3: 証明書の更新時に自分で反映する。** certbot が更新しても、PKCS12 への変換と Java プロセスの再起動は自動では起きない。`--deploy-hook` に変換スクリプトと `systemctl restart myapp` を書く必要があり、**そのたびにアプリが数十秒落ちる**。

| | nginx を前段に置く | Spring Boot が直接 443 |
|---|---|---|
| 証明書の形式 | PEM(certbot がそのまま置く) | **PKCS12 への変換が要る** |
| 低いポート | nginx は root で起動して自分で降格するので問題なし | **`AmbientCapabilities` が要る** |
| 更新時の反映 | `nginx -s reload`(**無停止**) | **アプリの再起動**(落ちる) |
| Laravel 編との共通化 | できる | できない |

**結論として、素の Linux では nginx を前段に置くほうが素直。** このリポジトリが nginx を使わないと決めているのは ALB が同じ役割を果たしているからで、**ALB がいない環境では「Nginx は使わない」の前提が成立しない**。

---

## 4. Nginx + Laravel 編

### 4-1. なぜ nginx が必須なのか

1-2 で書いたとおり、**PHP は自分で HTTP を待ち受けられない**。`php -S` という簡易サーバーは存在するが、公式ドキュメントに「開発用であり本番で使うな」と明記されている(同時に 1 リクエストしか処理できない、セキュリティ面の考慮がない)。

だから本番では必ず、

- HTTP を受ける役(**nginx**)
- PHP を実行する役(**php-fpm**)

の 2 つが要る。**この 2 つは別のプロセスで、別のユーザーで動き、別の設定ファイルを持つ。**

### 4-2. nginx / php-fpm / PHP の関係

```
ブラウザ
   │ HTTP  GET /posts
   ▼
[ nginx ]                             ← nginx ユーザーで動く
   │  URL を見て振り分ける
   │  ・/css/app.css のような実ファイル → 自分で読んで返す
   │  ・それ以外 → /index.php に集約して php-fpm へ
   │
   │ FastCGI(Unix ソケット経由)
   ▼
[ php-fpm マスター ]                  ← root で起動
   │  └ [ ワーカー ] [ ワーカー ] …   ← appuser に降格して動く
   │       index.php を実行 → Laravel が動く
   ▼
  HTML / JSON を nginx に返す
   │
[ nginx ] ──> ブラウザ
```

用語の整理:

| 語 | 何か |
|---|---|
| **FastCGI** | Web サーバーとアプリを繋ぐプロトコル。「このリクエストのメソッドは GET、パスは /posts、実行するファイルは /srv/.../index.php」といった情報を渡す約束事 |
| **php-fpm** | FastCGI を喋る PHP のプロセス管理役。PHP プロセスをあらかじめ複数起動して待たせておき、リクエストが来たら空いているものに割り当てる |
| **プール(pool)** | php-fpm が管理するワーカーの集団。**アプリごとに別のプールを作れて、プールごとに実行ユーザーを変えられる** |
| **Unix ソケット** | 同じマシンの中でプロセス同士が通信する仕組み。ファイルとして見える(`/run/php-fpm/myapp.sock`)。TCP と違い**ネットワークに露出しない**ので、同一サーバー内ならこちらが安全で速い |

**Spring Boot にはこの層が全部無い**、というのが Java 側との一番の違い。「組み込み Tomcat」が php-fpm + FastCGI の役割を jar の中で兼ねている。

### 4-3. 入れる

AL2023 の PHP パッケージは**バージョンが名前に入る**(`php` ではなく `php8.3`)。AL2023 は PHP 8.1 / 8.2 / 8.3 / 8.4 / 8.5 を提供している:

```bash
# 何が入るか確認してから
dnf search php8

sudo dnf install -y nginx \
  php8.3 php8.3-fpm php8.3-cli \
  php8.3-mysqlnd php8.3-mbstring php8.3-xml php8.3-bcmath \
  php8.3-opcache php8.3-intl php8.3-gd php8.3-zip

php -v
```

Laravel が要求する拡張(`mbstring`、`xml`、`bcmath`、`ctype`、`json`、`openssl`、`pdo`、`tokenizer`)は、上のセットでだいたい揃う。**「コンテナなら `FROM php:8.3-fpm` の 1 行で済んでいた部分」がこの行**。

Composer も入れる:

```bash
curl -sS https://getcomposer.org/installer | php
sudo mv composer.phar /usr/local/bin/composer
```

`/usr/local/bin` に置くのは「パッケージ管理の外で手動インストールしたもの」だから(→ [os-users-and-file-locations.md](./os-users-and-file-locations.md) 3 章)。

> Ubuntu なら `sudo apt install php8.3-fpm php8.3-mysql ...`。パッケージ名が微妙に違う(`php8.3-mysqlnd` ではなく `php8.3-mysql`)。

### 4-4. 配置する

```bash
REL=$(date +%Y%m%d-%H%M%S)
sudo mkdir -p /srv/myapp/releases/$REL
# 手元 / CI からソース一式を送る(vendor/ と .env は除く)
rsync -az --exclude vendor --exclude .env --exclude node_modules \
  ./ deploy@example.com:/srv/myapp/releases/$REL/
```

**ここが Java との大きな違い。** Java は jar 1 個で済んだが、PHP は**ソースツリーをそのまま置く**。そのうえで、依存(`vendor/`)をどこで用意するかを選ぶ:

| | サーバーで `composer install` | 手元 / CI で入れて `vendor/` ごと送る |
|---|---|---|
| サーバーに要るもの | Composer + ネットワーク(Packagist へ接続) | 不要 |
| ビルド失敗の影響 | サーバー上で中途半端な状態になる | 届かない(安全) |
| 転送量 | 小さい | 大きい(数千ファイル) |
| 一般的か | **こちらが普通**(Forge の既定もこれ) | CI を組んでいるなら選択肢 |

サーバーで入れる場合、本番では必ずこの 2 つのオプションを付ける:

```bash
cd /srv/myapp/releases/$REL
composer install --no-dev --optimize-autoloader --no-interaction
```

| オプション | 意味 | 付けないと |
|---|---|---|
| `--no-dev` | `require-dev` の依存を入れない | **PHPUnit や開発用ツールが本番サーバーに入る**(攻撃対象が増える、容量も増える) |
| `--optimize-autoloader` | クラス名 → ファイルパスの対応表を事前に作る | 毎リクエストでファイルを探しに行くので遅い |

**公開するディレクトリを間違えないこと。** Laravel は `public/` だけを Web に晒す構造になっている:

```
/srv/myapp/current/
├── app/            ← アプリのコード。公開してはいけない
├── config/         ← 設定。公開してはいけない
├── vendor/         ← 依存。公開してはいけない
├── .env            ← 秘密。絶対に公開してはいけない
└── public/         ← ここだけが Web ルート
    ├── index.php
    └── css/, js/, ...
```

**nginx の `root` を `/srv/myapp/current` にしてしまう事故が定番**で、こうすると `https://example.com/.env` で DB パスワードが読めてしまう。`root` は必ず `public` まで含める。

### 4-5. 権限とプールの設定

php-fpm の既定の実行ユーザーを確認する。**環境によって `apache` だったり `nginx` だったりするので、必ず自分の目で見る:**

```bash
grep -E '^(user|group|listen)' /etc/php-fpm.d/www.conf
```

既定のプール(`www`)をそのまま使うのではなく、**アプリ専用のプールを作って `appuser` で動かす**。理由は 2-4 に書いたとおり、`apache` が「このアプリのユーザー」ではなく Web 系の汎用ユーザーだから。

> **別ファイルにするのは必須ではない。** 1 台 1 アプリなら、既定の `/etc/php-fpm.d/www.conf` の `user` / `group` を `appuser` に書き換えるだけでも同じ効果になる。別ファイルにしているのは、**パッケージが配った既定ファイルを直接編集しない**という作法に従っているだけ(`/usr/bin` と `/usr/local/bin`、`/usr/lib/systemd` と `/etc/systemd` の住み分けと同じ発想 → [os-users-and-file-locations.md](./os-users-and-file-locations.md))。`dnf update` で `www.conf` が上書きされても、自分のプールは影響を受けない。なお別ファイルにする場合は、**使わない既定プールを無効化する**(`www.conf` をリネームするか `sudo systemctl disable` 相当の措置を取る)のを忘れないこと — 放置すると `apache` で動くプールが待機し続ける。

`/etc/php-fpm.d/myapp.conf`:

```ini
[myapp]
user = appuser
group = appuser

; nginx がこのソケットに書き込めるようにする
listen = /run/php-fpm/myapp.sock
listen.owner = nginx
listen.group = nginx
listen.mode = 0660

pm = dynamic
pm.max_children = 20
pm.start_servers = 4
pm.min_spare_servers = 2
pm.max_spare_servers = 6
pm.max_requests = 500

; PHP からは環境変数が見えないのが既定。必要なら明示的に渡す
clear_env = no

php_admin_value[error_log] = /var/log/php-fpm/myapp-error.log
php_admin_flag[log_errors] = on
```

ポイントは 3 つ:

1. **`user` / `group` がプールごとに指定できる。** これで「nginx は `nginx` ユーザー、アプリは `appuser`」という分離ができる。1 台に複数の PHP アプリを載せたとき、互いのファイルを読めなくなる
2. **`listen.owner = nginx`** — ソケットはファイルなので、権限が要る。**nginx がこのソケットに書けないと 502 になる**。`0660` は「所有者(`appuser`)とグループ(`nginx`)は読み書き、その他は不可」
3. **`pm.max_children`** — 同時に処理できるリクエスト数の上限。**ここが「コンテナならタスク数で調整していたもの」に当たる**。1 プロセスが使うメモリ × この数がサーバーのメモリを超えないように決める

Laravel が**書き込む必要のあるディレクトリ**は 2 つだけ:

```bash
# storage/ は shared/ に置いて版をまたいで共有する
sudo mkdir -p /srv/myapp/shared/storage
sudo chown -R appuser:appuser /srv/myapp/shared/storage
sudo chmod -R 775 /srv/myapp/shared/storage

# bootstrap/cache は各リリースに属する(キャッシュなので共有しない)
sudo chown -R appuser:appuser /srv/myapp/releases/$REL/bootstrap/cache
sudo chmod -R 775 /srv/myapp/releases/$REL/bootstrap/cache
```

`storage/` に入るもの: アップロードファイル、ログ、セッション、ビューのコンパイル結果、キャッシュ。**これらは版が変わっても引き継ぎたい**ので `shared/` に置き、各リリースからシンボリックリンクを張る。

そして **nginx が `public/` を読める**ようにする。`nginx` ユーザーは `/srv/myapp/current/public/` まで**辿り着けなければならない**ので、途中のディレクトリすべてに実行権(`x`)が要る:

```bash
sudo usermod -aG appuser nginx        # nginx を appuser グループに入れる
sudo chmod 750 /srv/myapp             # 所有者とグループだけ通れる
sudo systemctl restart nginx          # グループの変更は再起動しないと効かない
```

SELinux のラベルも忘れずに(2-3 参照):

```bash
sudo semanage fcontext -a -t httpd_sys_content_t "/srv/myapp(/.*)?"
sudo semanage fcontext -a -t httpd_sys_rw_content_t "/srv/myapp/shared/storage(/.*)?"
sudo restorecon -Rv /srv/myapp
```

### 4-6. `.env` の置き方

**Laravel は `.env` ファイルを自分で読む。** ここが Spring Boot(環境変数として受け取る)と決定的に違う。

```bash
sudo vi /srv/myapp/shared/.env
sudo chown appuser:appuser /srv/myapp/shared/.env
sudo chmod 640 /srv/myapp/shared/.env

# 各リリースから shared の .env にリンクを張る
sudo -u appuser ln -sfn /srv/myapp/shared/.env /srv/myapp/releases/$REL/.env
```

```ini
APP_NAME=myapp
APP_ENV=production
APP_KEY=base64:...
APP_DEBUG=false
APP_URL=https://example.com

DB_CONNECTION=mysql
DB_HOST=myapp-db.xxxxx.ap-northeast-1.rds.amazonaws.com
DB_DATABASE=app
DB_USERNAME=app
DB_PASSWORD=（実際のパスワード）
```

**`APP_DEBUG=false` を絶対に忘れないこと。** `true` のままだとエラー画面に**スタックトレースと環境変数の中身(DB パスワードを含む)**が表示される。Laravel の本番事故で最も多いパターン。

`.env` を `shared/` に置く理由は 2 つ。**版をまたいで同じ設定を使いたい**ことと、**リリースのたびに書き直したくない**こと。

| | Spring Boot | Laravel |
|---|---|---|
| 秘密の実体 | `/etc/myapp/env`(**アプリの外**) | `/srv/myapp/shared/.env`(**アプリのディレクトリの中**) |
| 誰が読むか | systemd → 環境変数 → アプリ | アプリが直接ファイルを開く |
| 公開ディレクトリとの距離 | 完全に別の場所 | **`public/` の 1 つ上の階層**(だから `root` の設定ミスが致命傷になる) |

### 4-7. nginx の設定

`/etc/nginx/conf.d/myapp.conf`:

```nginx
server {
    listen 80;
    server_name example.com;

    root  /srv/myapp/current/public;   # public まで含める
    index index.php;

    charset utf-8;
    client_max_body_size 20M;          # アップロードの上限

    # 実ファイルがあればそれを返し、無ければ index.php に集約する
    location / {
        try_files $uri $uri/ /index.php?$query_string;
    }

    location ~ \.php$ {
        fastcgi_pass unix:/run/php-fpm/myapp.sock;
        fastcgi_index index.php;

        # $document_root ではなく $realpath_root を使う(理由は 5-5)
        fastcgi_param SCRIPT_FILENAME $realpath_root$fastcgi_script_name;
        fastcgi_param DOCUMENT_ROOT   $realpath_root;
        include fastcgi_params;
    }

    # .env や .git などのドットファイルを塞ぐ(.well-known だけは通す)
    location ~ /\.(?!well-known).* {
        deny all;
    }

    access_log /var/log/nginx/myapp-access.log;
    error_log  /var/log/nginx/myapp-error.log;
}
```

重要な行の説明:

| 行 | 意味 |
|---|---|
| `root .../public` | **Web ルート。ここより上は外から見えない** |
| `try_files $uri $uri/ /index.php?$query_string` | 実ファイル(CSS、画像)があれば nginx が直接返し、無ければ全部 `index.php` に流す。**Laravel のルーティングが動く仕組みの実体** |
| `location ~ \.php$` | 拡張子 `.php` のリクエストだけを php-fpm に渡す |
| `fastcgi_pass unix:...` | 4-5 で作ったソケットを指す。**パスがずれていると 502** |
| `$realpath_root` | シンボリックリンクを解決した**実際のパス**。`$document_root` だと `/srv/myapp/current/public` のまま(リンクのパス)になる |
| `location ~ /\.` | `/.env` や `/.git/config` への直アクセスを塞ぐ保険 |

```bash
sudo nginx -t
sudo systemctl enable --now nginx php-fpm
```

**Spring Boot の設定(3-6)と比べると:**

| | Spring Boot 用 | Laravel 用 |
|---|---|---|
| 中心の指令 | `proxy_pass http://127.0.0.1:8080` | `fastcgi_pass unix:/run/php-fpm/myapp.sock` |
| プロトコル | HTTP(そのまま中継) | FastCGI(変換する) |
| `root` | **不要**(全部アプリが返す) | **必須**(nginx が静的ファイルを返す) |
| `try_files` | 不要 | 必須 |
| ヘッダーの引き継ぎ | `proxy_set_header` を自分で書く | `fastcgi_params` に既に書かれている |

nginx から見ると **`proxy_pass` は「別の HTTP サーバーに丸投げ」、`fastcgi_pass` は「PHP の実行を依頼」** という違い。

### 4-8. 本番用の最適化コマンド

Laravel には「本番ではこれを実行しておく」というコマンド群がある。**設定ファイルやルート定義を毎リクエスト読み直すのをやめ、1 つのファイルにまとめてキャッシュする**もの:

```bash
cd /srv/myapp/releases/$REL
sudo -u appuser php artisan config:cache   # config/*.php を 1 ファイルに固める
sudo -u appuser php artisan route:cache    # ルート定義を固める
sudo -u appuser php artisan view:cache     # Blade テンプレートを事前コンパイル
sudo -u appuser php artisan event:cache    # イベントリスナーの対応表を固める
```

**`config:cache` には重大な副作用がある。** これを実行すると、**以降 `.env` は読まれなくなる**(キャッシュに焼き込まれた値が使われる)。つまり:

- `.env` を書き換えても反映されない。`php artisan config:cache` をやり直す必要がある
- アプリのコードで `env('FOO')` を直接呼んでいると **`null` が返る**。`config/` 経由(`config('app.foo')`)で読まないといけない

キャッシュを消すには:

```bash
sudo -u appuser php artisan config:clear
```

> **Spring Boot に対応するものは無い。** Java は起動時に設定を読んで JVM のメモリに載せたら、それ以降ファイルを読み直さない。**「毎リクエストで全部読み直す」という PHP の性質に対する後付けの最適化**が上のコマンド群であり、Java 側では最初から不要。逆に言うと **Java は設定を変えたら再起動が必須**で、PHP は再起動せずに反映できる(キャッシュしていなければ)。得失は裏返しの関係になっている。

### 4-9. 追加の常駐プロセス — Laravel だけに必要なもの

**ここが「アプリを 1 つ起動すれば終わり」ではなくなる部分。**

#### キューワーカー

Laravel の非同期処理(メール送信、画像処理など)はキューに積まれるが、**積まれたものを取り出して実行する常駐プロセスを、自分で立てないといけない**。

`/etc/systemd/system/myapp-queue.service`:

```ini
[Unit]
Description=myapp Laravel queue worker
After=network-online.target

[Service]
Type=simple
User=appuser
Group=appuser
WorkingDirectory=/srv/myapp/current
ExecStart=/usr/bin/php /srv/myapp/current/artisan queue:work \
          --sleep=3 --tries=3 --max-time=3600
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

`--max-time=3600` は「1 時間動いたら自分で終了する」指定。**`Restart=always` と組み合わせると 1 時間ごとに勝手に再起動する**ことになり、これは意図的な設計。PHP は長時間動かし続けるとメモリリークしやすいので、定期的に作り直す。

複数のワーカーを走らせたいときは、systemd の**テンプレートユニット**を使う。ファイル名を `myapp-queue@.service` にすると:

```bash
sudo systemctl enable --now myapp-queue@1 myapp-queue@2 myapp-queue@3
```

**キューワーカーには重大な注意点がある。** `queue:work` はコードをメモリに読み込んだまま動き続けるので、**デプロイしても古いコードで動き続ける**。デプロイのたびに必ず再起動が要る:

```bash
sudo systemctl restart 'myapp-queue*'
```

#### スケジューラ

Laravel の定期実行(`app/Console/Kernel.php` に書くもの)は、**毎分 `schedule:run` を叩いてもらう前提**で設計されている。叩く役は自分で用意する:

```bash
sudo crontab -u appuser -e
```

```
* * * * * /usr/bin/php /srv/myapp/current/artisan schedule:run >> /dev/null 2>&1
```

systemd の `.timer` でも同じことができる(certbot の 3-7 と同じ形)。

#### Spring Boot 側はどうなるか

| | Spring Boot | Laravel |
|---|---|---|
| 定期実行 | `@Scheduled` — **アプリのプロセス内**のスレッドで動く | 外部の cron が毎分叩く |
| 非同期処理 | `@Async` / `ApplicationEvent` — **アプリのプロセス内** | 別プロセス(キューワーカー) |
| 立てる systemd ユニット | **1 本**(アプリ本体だけ) | **2〜3 本**(nginx / php-fpm / キューワーカー)+ cron |
| デプロイ時に再起動するもの | アプリ 1 つ | php-fpm(reload)+ キューワーカー(restart) |

**PHP には「リクエストが終わったら死ぬ」という前提があるので、常駐が必要な処理は必ずフレームワークの外に出る。** Java は最初から常駐しているので中で完結する。この違いが、素の Linux では systemd ユニットの本数の差として現れる。

> ただし Java 側にも落とし穴がある。`@Scheduled` はアプリのプロセス内で動くので、**サーバーを 2 台に増やすと同じ処理が 2 回走る**。Laravel の cron も同じ問題を持つ(→ 6-6)。

### 4-10. TLS

Spring Boot 編の 3-7 とまったく同じ。**nginx を前段に置く構成に揃えた最大のメリットがこれで、certbot の設定は 1 度覚えれば両方に効く。**

```bash
sudo certbot --nginx -d example.com
```

---

## 5. リリース方式 — 何を、どう切り替えるのか

初回は動いた。問題は 2 回目以降。

### 5-1. 方式は 6 つある

| 方式 | やること | 切り替えが一瞬か | ロールバック | 主な使いどころ |
|---|---|---|---|---|
| ① **手で scp / rsync して上書き** | 成果物を今の場所に直接上書き | ✗ | 手で戻す | 個人・検証環境 |
| ② **サーバーで `git pull`** | pull → 依存導入 → 再起動 | ✗ | `git checkout`(依存は戻らない) | 小〜中規模。**Laravel Forge の既定** |
| ③ **リリースディレクトリ + symlink** | 別ディレクトリに完成させてリンクを張り替え | ○ | リンクを戻すだけ | Capistrano / Deployer / **Laravel Envoyer** |
| ④ **OS パッケージ化(rpm / deb)** | 成果物を rpm にして `dnf install` | ○ | 旧バージョンを install | 大規模・監査の厳しい組織、オンプレ |
| ⑤ **マシンイメージ差し替え** | AMI を焼いて Auto Scaling Group ごと入れ替え | ○ | 旧 AMI に戻す | AWS、イミュータブルインフラ |
| ⑥ **コンテナイメージ** | ECR に push → タスク定義を更新 | ○ | 旧リビジョンに戻す | **このリポジトリ** |

**`git pull` 方式(②)は「使われていない古いやり方」ではない。** Laravel 公式のサーバー管理サービスである **Laravel Forge の既定のデプロイスクリプトがまさにこれ**で、内容はだいたいこうなっている:

```bash
cd /home/forge/example.com
git pull origin main
composer install --no-dev --optimize-autoloader
php artisan migrate --force
```

一方、同じく Laravel 公式の **Envoyer** は「無停止デプロイ」を売りにした別サービスで、そちらは③を採用している。**公式から 2 つが並んで提供されていること自体が、「どちらも実務で使われている」という答え**になっている。

②の弱点は 4 つ:

1. **切り替えが一瞬ではない** — `git pull` はファイルを 1 個ずつ書き換えるので、その間**新旧のファイルが混ざった状態**でリクエストが処理される
2. **ロールバックが不完全** — `git checkout` でソースは戻るが、`composer install` で入った依存や実行済みマイグレーションは戻らない
3. **サーバーに `.git` が残る** — `root` の設定を間違えると `https://example.com/.git/config` が読めてしまう
4. **ビルドがサーバー上で走る** — 途中で失敗すると中途半端な状態で止まる

### 5-2. 本質的な線引きは 1 つ

6 つあるように見えて、分かれ目は**「今動いているものを直接書き換えるか、別の場所に完成させてから参照先を一斉に切り替えるか」**だけ。

```
①② 直接書き換える方式
   [ 動いているもの ] ←── 上書き
   → 書き換えている途中の状態が本番に露出する
   → 失敗すると壊れた状態が残る

③④⑤⑥ 参照先を切り替える方式
   [ 動いているもの ]        [ 新しいものを別の場所に完成させる ]
                       ↓ 完成を確認してから
   [ 動いているもの ] <────── 参照を切り替える(一瞬)
   → 途中の状態が露出しない
   → 失敗したら参照を戻すだけ
```

**あなたが慣れているコンテナ(⑥)は、この後者の系譜の一番新しい形でしかない。** 「別の場所に完成させる」がイメージのビルドで、「参照を切り替える」がタスク定義の更新。③はそれをファイルシステムのシンボリックリンクでやっているだけで、**発想は同じ**。

だから「コンテナじゃないとロールバックできない」わけではない。**必要なのはコンテナではなく「参照先の切り替え」という形**。

### 5-3. ③の実際 — `releases/` と `current`

```
/srv/myapp/
├── releases/
│   ├── 20260809-120000/     ← 2 世代前
│   ├── 20260809-133000/     ← 1 世代前(戻すならここ)
│   └── 20260809-150000/     ← 今
├── shared/
│   ├── .env
│   └── storage/
└── current -> releases/20260809-150000
```

**切り替えはアトミックにやる。** `ln -sfn` は「既存のリンクを消してから新しく作る」ため、**ごく短時間 `current` が存在しない瞬間がある**。`mv -T` を使うと本当に一瞬で入れ替わる:

```bash
# 一時的な名前でリンクを作り、それを current に「移動」して上書きする
ln -sfn /srv/myapp/releases/$REL /srv/myapp/current.tmp
mv -Tf /srv/myapp/current.tmp /srv/myapp/current
```

`mv -T` は `rename(2)` システムコールを呼ぶだけで、これはファイルシステムのレベルで**不可分**(途中の状態が他のプロセスから見えない)。

**古いリリースの掃除**も忘れずに。これをやらないとディスクが埋まる:

```bash
cd /srv/myapp/releases && ls -1t | tail -n +6 | xargs -r rm -rf   # 最新 5 世代だけ残す
```

ロールバック:

```bash
ln -sfn /srv/myapp/releases/20260809-133000 /srv/myapp/current.tmp
mv -Tf /srv/myapp/current.tmp /srv/myapp/current
sudo systemctl restart myapp                    # Spring Boot なら
sudo systemctl reload php-fpm                   # Laravel なら
```

### 5-4. Spring Boot の更新 — symlink の価値は薄い

```bash
# 1. 新しい jar を新しいリリースディレクトリに置く
# 2. current を張り替える
# 3. 再起動する
sudo systemctl restart myapp
```

**Java では③のアトミック性にあまり意味がない。** 理由は、

- jar は**起動時に一括で読み込まれる**ので、動作中に jar ファイルを差し替えても走っているプロセスには影響しない。つまり「新旧が混ざった状態」がそもそも発生しない
- どちらにせよ `systemctl restart` でプロセスを作り直すので、**数秒〜数十秒のダウンタイムが必ず発生する**。切り替えの一瞬性を追求しても、その後の再起動で台無しになる

それでも `releases/` の形にする価値はある。**「過去の jar が残っているので、すぐ戻せる」**という点だけで十分に元が取れる。ECS が過去のタスク定義リビジョンを持っているのと同じ役割。

**ダウンタイムを減らしたいなら**、選択肢は 2 つ:

1. **graceful shutdown を有効にする** — 処理中のリクエストを最後まで返してから終了する。**落ちる時間は減らないが、処理中のリクエストがエラーにならない**

   ```yaml
   # application.yml
   server:
     shutdown: graceful
   spring:
     lifecycle:
       timeout-per-shutdown-phase: 30s
   ```

   systemd 側の `TimeoutStopSec=30`(3-4)はこれと揃える。

2. **サーバーを 2 台以上にして、ロードバランサ配下で 1 台ずつ入れ替える** — これが本当の無停止。ただし複数台構成には別の問題が付いてくる(→ 6-6)。**ECS のローリング更新がやっているのはこれ**

### 5-5. Laravel の更新 — ③の価値が高く、罠も多い

**PHP はリクエストのたびにファイルを読む。** だから `git pull` や `rsync` で上書きしている最中に来たリクエストは、**新しい `routes/web.php` と古い `app/Http/Controllers/PostController.php` が混ざった状態**で実行される。クラスが見つからない、メソッドのシグネチャが合わない、といったエラーが実際に出る。

だから Laravel では③のアトミック切り替えに意味がある。手順:

```bash
REL=$(date +%Y%m%d-%H%M%S)
NEW=/srv/myapp/releases/$REL

# 1. 新しいリリースを用意する(この間、current は古いまま動き続ける)
rsync -az --exclude vendor --exclude .env ./ deploy@example.com:$NEW/
cd $NEW
ln -sfn /srv/myapp/shared/.env      $NEW/.env
rm -rf $NEW/storage
ln -sfn /srv/myapp/shared/storage   $NEW/storage
composer install --no-dev --optimize-autoloader --no-interaction

# 2. キャッシュを作る
php artisan config:cache
php artisan route:cache
php artisan view:cache

# 3. マイグレーション(→ 5-6 の注意点を読むこと)
php artisan migrate --force

# 4. 切り替える(ここが一瞬)
ln -sfn $NEW /srv/myapp/current.tmp && mv -Tf /srv/myapp/current.tmp /srv/myapp/current

# 5. PHP 側に新しいパスを認識させる
sudo systemctl reload php-fpm

# 6. キューワーカーを再起動する(古いコードを持ったままなので必須)
sudo systemctl restart 'myapp-queue*'
```

#### 罠 1: OPcache がシンボリックリンクを追わない

**OPcache** は「PHP ファイルをコンパイルした結果をメモリに保持して、次回はコンパイルを省く」仕組みで、本番では必ず有効にする(でないと遅い)。ところがこれが③と噛み合わない。

OPcache はキャッシュのキーに**ファイルパス**を使う。`current` のパスは切り替え前後で変わらない(`/srv/myapp/current/app/...`)ので、**`current` を張り替えても OPcache は「同じファイルだ」と判断して古いコードを返し続ける。**

対処は 2 つあり、**両方やるのが確実**:

1. **nginx 側で `$realpath_root` を使う**(4-7 で書いた行)

   ```nginx
   fastcgi_param SCRIPT_FILENAME $realpath_root$fastcgi_script_name;
   ```

   `$realpath_root` はシンボリックリンクを解決した実パス(`/srv/myapp/releases/20260809-150000/public`)になるので、**リリースごとにパスが変わり、OPcache のキーも変わる**。結果として古いキャッシュが自然に使われなくなる

2. **php-fpm を reload する**

   ```bash
   sudo systemctl reload php-fpm
   ```

   `reload` は `restart` と違い、**処理中のリクエストを終わらせてからワーカーを作り直す**ので実質無停止。新しいワーカーは空の OPcache で始まる

**これはコンテナだと絶対に踏まない罠。** コンテナは新しいイメージで新しいプロセスが立つので、OPcache も当然まっさらから始まる。

#### 罠 2: メンテナンスモード

マイグレーションを伴うデプロイなど、切り替え中にアクセスさせたくない場合:

```bash
php artisan down --render="errors::503" --retry=60
# ... デプロイ作業 ...
php artisan up
```

ただし `down` は「ファイルを 1 個置く」だけの仕組みで、③の構成では**どのリリースに置くか**に注意が要る(`storage/` を共有しているなら shared 側に効く)。

### 5-6. マイグレーションとロールバック — 最大の落とし穴

**「参照先を切り替えるだけでロールバックできる」は、DB については成立しない。**

```
デプロイ:  コード v1 → v2   スキーマ v1 → v2   (両方進む)
ロールバック: コード v2 → v1   スキーマ v2 のまま  ← 戻らない
             ↑ v1 のコードが v2 のスキーマを見る = 壊れる可能性
```

シンボリックリンクを戻してもテーブルは元に戻らない。**これはコンテナでも ECS でもまったく同じ問題**で、素の Linux 固有の話ではない。ただし「イメージを戻せば全部戻る」と思っていると事故る。

このリポジトリの Spring Boot と Laravel では、マイグレーションの走るタイミングが違う:

| | Spring Boot(Flyway) | Laravel |
|---|---|---|
| いつ走るか | **アプリの起動時に自動**(Flyway が `db/migration` を見る) | **`php artisan migrate --force` を明示的に叩いたとき** |
| 制御できるか | `spring.flyway.enabled: false` で止められる | 叩かなければ走らない |
| デプロイ手順への影響 | **jar を差し替えて起動した瞬間にスキーマが変わる** | デプロイ手順の中に 1 行入れる |
| 複数台のとき | 全台が同時に起動するとマイグレーションが競合しうる(Flyway はロックを取るので通常は安全) | 1 回だけ叩けばよい |

**Flyway が起動時に自動で走る構成は、ロールバックのとき特に注意が要る。** 古い jar に戻して起動すると、Flyway は「DB には V4 まで適用済みなのに、jar には V3 までしか無い」という状態を検出してエラーになることがある。

実務での対処は、**マイグレーションを「前方互換」に保つ**こと:

| やり方 | 例 |
|---|---|
| **列を消さない** | 使わなくなった列は残しておき、次の次のリリースで消す |
| **列を追加するときは NULL 許容かデフォルト付き** | 古いコードがその列を知らなくても INSERT できる |
| **リネームは「追加 → 両方書く → 読み替え → 削除」の 4 段階に分ける** | 1 回のリリースでリネームしない |

こうしておけば「新しいスキーマ + 古いコード」でも動くので、コードだけロールバックできる。

### 5-7. ECS ではどうだったのか

| 素の Linux(③) | ECS |
|---|---|
| `releases/20260809-150000/` を作る | 新しいイメージをビルドして ECR に push |
| `current` を張り替える | 新しいタスク定義**リビジョン**を登録し、サービスを更新 |
| `systemctl restart` / `reload php-fpm` | ECS が新タスクを起動 → ヘルスチェック通過 → 旧タスクを停止 |
| 古い `releases/` を残しておく | 古いタスク定義リビジョンが残っている |
| リンクを前のリリースに戻す | サービスを前のリビジョンに戻す |
| 古いリリースを消してディスクを空ける | ECR のライフサイクルポリシーで古いイメージを消す |
| **切り替え中にダウンタイムがある**(1 台構成なら) | **ローリング更新なのでダウンタイムが無い** |
| マイグレーションは手順に自分で組み込む | マイグレーションは手順に自分で組み込む(**同じ**) |

**唯一 ECS が明確に勝っているのは最後から 2 行目のダウンタイム。** それも「サーバーを複数台にしてロードバランサを置く」ことで素の Linux でも実現できる — つまり ECS は**その手間を肩代わりしている**のであって、原理的にできないことをやっているわけではない。

---

## 6. 動かし続けるための設定

公開して終わりではない部分。**ここがコンテナ(特に Fargate)だと大幅に減る。**

### 6-1. ログ

素の Linux ではログが**3 か所に分かれる**。

| ログ | 場所 | 見方 |
|---|---|---|
| アプリの標準出力 | journald | `journalctl -u myapp -f` |
| nginx のアクセス / エラー | `/var/log/nginx/*.log` | `tail -f /var/log/nginx/myapp-error.log` |
| php-fpm のエラー | `/var/log/php-fpm/*.log` | `tail -f /var/log/php-fpm/myapp-error.log` |
| Laravel のアプリログ | `storage/logs/laravel.log` | `tail -f /srv/myapp/shared/storage/logs/laravel.log` |

**502 の原因を追うときは nginx のエラーログを見る**(「php-fpm のソケットに繋がらない」「upstream から応答がない」がここに出る)。アプリのエラーはアプリ側のログにしか出ない。

#### ログローテート — 放っておくとディスクが埋まる

**これがコンテナだと考えなくてよかったもの。** コンテナは使い捨てで、ログは即座に CloudWatch へ流れていくので、ディスクに溜まらない。

`logrotate` は AL2023 に最初から入っていて、`/etc/logrotate.d/` に設定を置く。nginx と php-fpm のぶんはパッケージが用意してくれるが、**アプリのログは自分で書く**:

`/etc/logrotate.d/myapp`:

```
/srv/myapp/shared/storage/logs/*.log {
    daily
    rotate 14
    missingok
    notifempty
    compress
    delaycompress
    su appuser appuser
    create 0640 appuser appuser
}
```

| 指定 | 意味 |
|---|---|
| `daily` / `rotate 14` | 毎日切り替えて 14 世代残す(= 2 週間ぶん) |
| `compress` / `delaycompress` | 古いものを gzip する。`delaycompress` は 1 世代遅らせる(書き込み中のファイルを圧縮しないため) |
| `create 0640 appuser appuser` | 新しいログファイルをこの所有者と権限で作る。**これを書かないと root 所有で作られてアプリが書けなくなる** |

journald 側は自分で上限を持っているが、既定はディスクの 10% と大きめ。絞るなら `/etc/systemd/journald.conf`:

```ini
SystemMaxUse=500M
```

#### 複数台になったらログを集約する

サーバーが 2 台になった瞬間、**「どっちのサーバーでエラーが出たか」を探す作業が発生する**。CloudWatch エージェントを入れて集約するのが現実的:

```bash
sudo dnf install -y amazon-cloudwatch-agent
```

**ECS では `awslogs` ログドライバの 1 行で済んでいた部分**が、これに相当する。

### 6-2. 再起動しても上がってくるか — 最も多い事故

**素の Linux で最も多い事故が「サーバーを再起動したらアプリが上がってこない」。** ECS では常に必要数を維持してくれるので、この概念自体が無かった。

原因はほぼ 1 つで、**`systemctl enable` を忘れている**こと。`start` は「今すぐ起動」、`enable` は「次回の起動から自動で立ち上げる登録」で、**別の操作**(→ [os-users-and-file-locations.md](./os-users-and-file-locations.md) 5-4)。

確認方法:

```bash
# enabled になっているか
systemctl is-enabled myapp nginx php-fpm

# 起動時に立ち上がる予定のものを全部見る
systemctl list-unit-files --state=enabled
```

**一番確実な確認は、実際に再起動してみること:**

```bash
sudo reboot
# 数分待って
systemctl is-active myapp nginx php-fpm
curl -I https://example.com/
```

本番に出す前に必ず 1 回やっておく。「デプロイ直後は動いていたのに、3 か月後の OS 更新で再起動したら全部止まった」というのが典型的な壊れ方。

もう 1 つ、起動順序の問題もある。**DB が同じサーバーにある場合**、アプリが DB より先に起動すると接続に失敗する:

```ini
[Unit]
After=network-online.target mysqld.service
Wants=network-online.target
```

ただし `After=` は「起動の順序」を保証するだけで、「MySQL が接続を受け付けられる状態になった」ことは保証しない。**アプリ側にリトライを持たせるほうが確実**(`Restart=on-failure` + `RestartSec=5` がその役割も果たしている)。

### 6-3. OS の更新

**Fargate では AWS の仕事だったもの。** EC2 / オンプレでは自分の仕事になる。

```bash
# 何が更新されるか見る
sudo dnf check-update

# セキュリティ更新だけ当てる
sudo dnf update --security -y

# カーネルが更新されたか(= 再起動が必要か)を判定する
sudo dnf needs-restarting -r
```

`needs-restarting -r` は「再起動が必要なら終了コード 1 を返す」ので、スクリプトで判定できる。

自動化するなら `dnf-automatic`:

```bash
sudo dnf install -y dnf-automatic
sudo vi /etc/dnf/automatic.conf     # apply_updates = yes, upgrade_type = security
sudo systemctl enable --now dnf-automatic.timer
```

**ただし自動適用には判断が要る。** セキュリティ更新を自動で当てるのは一般に推奨されるが、「勝手に更新が入ってアプリが動かなくなる」リスクとの引き換えになる。**検証環境で先に当ててから本番に当てる**のが原則。

> **⑤の AMI 差し替え方式が魅力的に見えるのはここ。** OS を更新した AMI を焼き直してインスタンスごと入れ替えれば、「稼働中のサーバーにパッチを当てる」という作業自体が消える。これは**コンテナの考え方をサーバーごと適用したもの**(イミュータブルインフラ)で、発想は 5-2 の「参照先を切り替える」と同じ。

### 6-4. 死活監視とヘルスチェック

**ALB が黙ってやっていたことが、丸ごと消える。**

ALB は数十秒ごとにヘルスチェックのパスを叩き、応答しなくなったターゲットへの振り分けを止め、ECS はタスクを作り直す。**素の 1 台構成では、これを誰もやらない。**「アプリが落ちていることに、ユーザーからの連絡で気づく」という状態が既定になる。

最低限やっておくこと:

1. **`Restart=on-failure` を入れておく**(3-4 で入れた)。プロセスが異常終了したら systemd が起こしてくれる。ただし**「プロセスは生きているが応答しない」状態は救えない**
2. **外形監視を置く** — 外部から定期的に URL を叩いて、応答しなければ通知する。CloudWatch Synthetics、UptimeRobot など
3. **アプリにヘルスチェック用のエンドポイントを用意する** — Spring Boot なら Actuator:

   ```groovy
   // build.gradle
   implementation 'org.springframework.boot:spring-boot-starter-actuator'
   ```

   ```yaml
   # application.yml
   management:
     endpoints.web.exposure.include: health
     endpoint.health.probes.enabled: true
   ```

   `/actuator/health` が DB 接続まで含めて確認してくれるので、**「プロセスは生きているが DB に繋がっていない」を検出できる**

4. **systemd の watchdog** — 応答しないプロセスを systemd に殺させる仕組みもあるが、アプリ側の対応が要るので導入のハードルは高い

`Restart=` の効き方も確認しておく:

```bash
systemctl show myapp -p NRestarts     # これまでに何回再起動したか
```

**この数字が増え続けているのに気づかない、というのが一番怖い状態。** 「動いているように見えるが、実は 5 分おきにクラッシュして再起動している」というケースがある。

### 6-5. バックアップ

**ここも Fargate では考えなくてよかった部分**(コンテナは使い捨てで、守るべきデータはすべて RDS と S3 の側にあるので、AWS のマネージド機能に任せられる)。

素の Linux では、守るべきものが 3 種類ある。

| 対象 | RDS / S3 を使う場合 | サーバー内に持つ場合 |
|---|---|---|
| **DB のデータ** | RDS の自動スナップショット(既定で有効) | **`mysqldump` を cron で回して S3 に上げる。完全に自分の責任** |
| **アップロードファイル** | S3(11 ナインの耐久性) | `storage/app/` を rsync か S3 に同期する |
| **設定ファイル**(`/etc/myapp/env`、nginx 設定、`.service`) | — | **Git 管理外の秘密が含まれるので、別途保管が必要** |

3 行目が抜けやすい。**サーバーが飛んだときに一番困るのは、実はアプリのコードではなく「どう設定したか」の記憶**。対策は 2 つ:

- **設定ファイルを構成管理ツール(Ansible など)で管理する** — サーバーを作り直せる状態にしておく。**Dockerfile が果たしていた「環境の定義がコードとして残る」役割を、これで補う**
- 秘密は Secrets Manager / SSM に置き、サーバーには置かない(2-6 の補足)

**コンテナ構成の隠れた利点がここにある。** Dockerfile と compose ファイルがリポジトリにあるので、「どう設定したか」が自動的にコード化されている。素の Linux では、意識して同じ状態を作らないと**サーバーの中にしか存在しない知識**が溜まっていく。

DB を同居させている場合の最低限:

```bash
# /etc/cron.daily/myapp-dbdump
mysqldump --single-transaction --routines --triggers app \
  | gzip > /var/backups/app-$(date +\%F).sql.gz
aws s3 cp /var/backups/app-$(date +\%F).sql.gz s3://myapp-backups/
find /var/backups -name '*.sql.gz' -mtime +7 -delete
```

`--single-transaction` は「ダンプ中もテーブルをロックしない」指定(InnoDB の場合)。これが無いと**バックアップ中にサイトが止まる**。

**そして最も重要なこと: 復元を試したことがないバックアップは、バックアップではない。** 別のサーバーに戻せるかを一度は試す。

### 6-6. 複数台にすると壊れるもの

1 台では動いていたのに、2 台に増やした途端に壊れるものがある。**ECS でタスク数を 2 にしたときも同じ問題が起きる**ので、これはコンテナかどうかとは無関係な話。

| 壊れるもの | なぜ | 対処 |
|---|---|---|
| **セッション** | サーバーのメモリやローカルファイルに保存していると、別のサーバーに振り分けられた瞬間にログアウトする | 共有ストアに置く。**このリポジトリは Spring Session JDBC で DB に置いているので既に対応済み**(`SPRING_SESSION` テーブル → `V3__create_spring_session_tables.sql`)。Laravel なら `SESSION_DRIVER=database` か `redis` |
| **アップロードされた画像** | 1 台目に保存した画像が 2 台目から見えない | **S3 に置く。このリポジトリは既にそうなっている**(`S3_BUCKET`) |
| **定期実行** | `@Scheduled` も cron も全台で走るので、**同じ処理が台数分実行される** | ShedLock(Java)などで分散ロックを取る。Laravel なら `onOneServer()`(要 Redis / DB キャッシュ) |
| **ローカルのファイルキャッシュ** | 台ごとに別の内容を持つ | Redis / ElastiCache などの共有キャッシュに寄せる |
| **ログ** | 台ごとに分散して追えなくなる | CloudWatch などに集約(6-1) |

**このリポジトリは 1 台目と 2 台目の問題が既に解決済み**なのは偶然ではなく、ECS で複数タスクを動かす前提で設計されているから。**素の Linux に持っていっても、そのまま複数台にできる状態**になっている。

3 行目の定期実行だけは、まだ対応が入っていない点に注意(現状 1 タスク構成なので問題は出ていない)。

### 6-7. 補足: DB を同じサーバーに置く場合

本文は「DB は RDS か別サーバーにある」前提で書いたが、オンプレや検証用の 1 台構成では同居させることもある。最低限これだけは:

```bash
sudo dnf install -y mariadb105-server
sudo systemctl enable --now mariadb
sudo mysql_secure_installation      # root パスワード設定、匿名ユーザー削除、テスト DB 削除
```

| 項目 | やること | なぜ |
|---|---|---|
| **外から見えなくする** | `/etc/my.cnf.d/` で `bind-address = 127.0.0.1` | **3306 が外に開いていると総当たり攻撃を受ける**。同じサーバーのアプリからしか繋がないなら localhost で十分 |
| **アプリ用ユーザーを最小権限で作る** | `GRANT SELECT, INSERT, UPDATE, DELETE ON app.* TO 'app'@'localhost'` | root で接続しない。SQL インジェクションが起きても被害を絞れる |
| **文字コード** | `utf8mb4` を明示する | 絵文字が入らない事故を防ぐ |
| **バックアップ** | 6-5 の `mysqldump` を cron で | **RDS の自動スナップショットに当たるものが無い。完全に自分の責任** |
| **メモリ設定** | `innodb_buffer_pool_size` をサーバーのメモリに合わせる | 既定値は小さいので性能が出ない |

**同居させると、DB がアプリのメモリを奪い合う**という問題も出る。アプリのメモリ設定(JVM の `-Xmx`、php-fpm の `pm.max_children`)と合わせて考える必要がある。

---

## 7. 2 つの構成の対比

| 観点 | Spring Boot | Nginx + Laravel |
|---|---|---|
| **HTTP を受ける** | jar 自身(組み込み Tomcat) | nginx |
| **前段の Web サーバー** | 任意(TLS のために置くのが素直) | **必須** |
| **サーバーに要るランタイム** | JRE(`java-21-amazon-corretto-headless`) | PHP + php-fpm + 多数の拡張 |
| **サーバーに要るビルドツール** | 不要(jar を送る) | Composer(サーバーで入れるなら) |
| **送るもの** | **jar 1 個** | ソースツリー一式 |
| **常駐プロセス(最小)** | 1 | 2(nginx / php-fpm)+ キューワーカー |
| **書く systemd ユニット** | 1(自分で書く) | 1〜3(キューワーカーぶん。nginx と php-fpm はパッケージ付属) |
| **cron** | 不要(`@Scheduled` で完結) | **必要**(`schedule:run` を毎分) |
| **秘密の置き場所** | `/etc/myapp/env` → 環境変数 | `shared/.env` をアプリが直接読む |
| **公開ディレクトリの指定** | 不要 | **必須**(`public/` のみ。間違えると `.env` が漏れる) |
| **コード更新の反映** | プロセス再起動が必須 | ファイルを置けば次のリクエストから(だから危ない) |
| **デプロイのダウンタイム** | 数秒〜数十秒(必ず発生) | ほぼゼロ(③なら) |
| **リリース方式③の価値** | 低い(ロールバック用としてなら有用) | **高い**(混ざった状態を防ぐ) |
| **キャッシュの罠** | なし | **OPcache がシンボリックリンクを追わない** |
| **マイグレーション** | 起動時に Flyway が自動実行 | `artisan migrate` を明示的に叩く |
| **設定の事前最適化** | 不要 | `config:cache` などが要る(`.env` が読まれなくなる副作用つき) |

**総じて、Spring Boot のほうが素の Linux へのデプロイは単純。** 「jar を 1 個置いて systemd で起動する」で終わり、公開ディレクトリの事故も、キャッシュの罠も、追加の常駐プロセスも無い。

その代わり **Laravel のほうがデプロイのダウンタイムは短い**。プロセスの再起動が要らないので、③の方式なら実質無停止でコードを入れ替えられる。「Java は簡単だが必ず落ちる、PHP は罠が多いが落ちない」という得失になっている。

## 8. で、コンテナに戻ると何が消えるのか

ここまでの作業を、コンテナ(ECS Fargate)構成と突き合わせるとこうなる。

**消えるもの(やらなくてよくなるもの)**

| 消える作業 | 章 |
|---|---|
| ランタイムを OS に入れる(`dnf install`) | 3-1 / 4-3 |
| SELinux のラベル付けと boolean 設定 | 2-3 |
| 専用ユーザーを `useradd` する(Dockerfile の `USER` になる) | 2-4 |
| systemd ユニットを書く(タスク定義になる) | 3-4 |
| certbot と証明書の自動更新(ACM になる) | 3-7 |
| ログローテート(コンテナは使い捨て) | 6-1 |
| **OS のパッチ当てと再起動**(Fargate なら丸ごと不要) | 6-3 |
| 死活監視の仕組み(ALB + ECS が持っている) | 6-4 |
| リリースディレクトリの管理と掃除 | 5-3 |
| 「再起動したら上がってこない」問題 | 6-2 |

**消えないもの**

| 消えない作業 | なぜ |
|---|---|
| **root で動かさない** | コンテナの中の root は「壁がもう 1 枚ある」だけ。壁を越える手段は存在する(→ [os-users-and-file-locations.md](./os-users-and-file-locations.md) 5-5) |
| **秘密の管理** | 置き場所が `/etc/myapp/env` から Secrets Manager に変わるだけで、管理という仕事は残る |
| **マイグレーションとロールバックの整合** | 5-6。**イメージを戻してもスキーマは戻らない** |
| **複数台にしたときのセッション・ファイル・定期実行** | 6-6。タスク数を増やしたときに同じ問題が起きる |
| **バックアップと復元テスト** | RDS / S3 に任せられる部分は増えるが、「戻せるか試す」は残る |
| **アプリのログを読む** | `journalctl` が `docker logs` / CloudWatch になるだけ |

**そして、コンテナで一番大きいのは「消える」ことではなく「コード化される」こと。** 素の Linux でやった作業は `/etc` の下に散らばって残り、**サーバーの中にしか存在しない知識**になる。Dockerfile とタスク定義は同じ内容をリポジトリの中に置くので、レビューでき、差分が追え、作り直せる。

裏を返すと、**素の Linux でも Ansible などで同じ状態を作れば、その利点は再現できる**。コンテナが本質的に持っていて他で代替しにくいのは「ランタイムごと固めて持ち運べる」ことのほうで、それ以外の利点の多くは**やり方の問題**でしかない。

## まとめ

| 質問 | 答え |
|---|---|
| 素の Linux だとどういう手順になるのか | ランタイムを入れる → 専用ユーザーを作る → 成果物を置く → systemd で常駐させる → nginx で前段と TLS → 更新の仕組みを用意する |
| 細かい設定は必要になるのか | なる。特に **SELinux・ファイルの所有者と権限・`systemctl enable` の 3 つ**は、コンテナでは踏まないのに素の Linux では必ず引っかかる |
| Spring Boot と Laravel で何が一番違うか | **HTTP を誰が受けるか**。Java は jar 自身が受けられるので nginx は任意、PHP は受けられないので nginx + php-fpm が必須。ここから全部の違いが派生する |
| どちらが簡単か | **Spring Boot**。jar 1 個で済み、公開ディレクトリの事故もキャッシュの罠も無い。ただしデプロイのたびに必ず落ちる |
| リリース方式は何があるか | 6 つ(上書き / `git pull` / symlink / rpm / AMI / コンテナ)。本質は「**直接書き換えるか、別の場所に完成させて参照先を切り替えるか**」の 2 分類 |
| `git pull` 方式は実務で使われるか | **使われる。Laravel Forge の既定がこれ**。弱点は「切り替えが一瞬でない」「ロールバックが不完全」「`.git` が残る」「ビルドがサーバーで走る」の 4 つ |
| symlink 方式は必須か | **PHP では価値が高い**(混ざった状態を防げる)。**Java では価値が薄い**(どうせ再起動する)が、ロールバック用としては有用 |
| コンテナに戻ると何が消えるか | ランタイム導入・SELinux・systemd・certbot・ログローテート・OS パッチ。**消えないのは、権限設計・秘密管理・マイグレーションの整合・複数台の問題** |

## 用語集

- **組み込み Tomcat** — Spring Boot の jar に同梱される Web サーバー。これがあるので `java -jar` だけで HTTP を待ち受けられる。PHP には対応するものが無い
- **php-fpm(FastCGI Process Manager)** — PHP のプロセスを常駐させて管理し、Web サーバーから FastCGI で渡されたリクエストを処理する仕組み。PHP 自身が HTTP を受けられないことを補う
- **FastCGI** — Web サーバーとアプリケーションを繋ぐプロトコル。nginx の `fastcgi_pass` がこれを使う。`proxy_pass`(HTTP をそのまま中継)とは別物
- **プール(php-fpm)** — php-fpm が管理するワーカーの集団。`/etc/php-fpm.d/*.conf` で定義し、**プールごとに実行ユーザーとソケットを変えられる**
- **Unix ソケット** — 同じマシン内のプロセス間通信の仕組み。ファイルとして見え、権限で保護できる。ネットワークに露出しないので TCP より安全で速い
- **`$realpath_root`(nginx)** — シンボリックリンクを解決した実際のパスを返す変数。`$document_root` の代わりに使うと、OPcache が古いコードを掴み続ける問題を回避できる
- **OPcache** — PHP のコンパイル結果をメモリに保持する仕組み。キーにファイルパスを使うため、シンボリックリンク方式のデプロイと相性が悪い
- **SELinux** — `rwx` とは別に「どのプロセスがどのファイルに触ってよいか」を強制するカーネルの仕組み。AL2023 は `permissive`(記録のみ)、RHEL / Rocky は `enforcing`(拒否する)
- **`httpd_can_network_connect`** — SELinux の設定項目。Web サーバーから他のプロセスへネットワーク接続することを許可する。`proxy_pass` を使うなら `setsebool -P` で有効化が要る
- **`AmbientCapabilities=CAP_NET_BIND_SERVICE`** — 一般ユーザーのプロセスに「1024 未満のポートを開く」権限だけを渡す systemd の指定。root 権限をまるごと渡さずに 443 番を開ける
- **`ProtectSystem=strict` / `PrivateTmp` / `NoNewPrivileges`** — systemd がプロセスを隔離するための指定。**コンテナが暗黙に提供していた隔離を、素の Linux で手作業で足すためのもの**
- **`SuccessExitStatus=143`** — `SIGTERM` で終了した JVM の終了コード 143 を正常扱いにする指定。無いと正常な停止のたびに再起動がかかる
- **graceful shutdown** — 処理中のリクエストを最後まで返してからプロセスを終了させること。Spring Boot は `server.shutdown: graceful` で有効になる
- **リリースディレクトリ方式(Capistrano 方式)** — `releases/<日時>/` に新しい版を展開し、`current` シンボリックリンクを張り替えて切り替える方式。Deployer / Envoyer / Laravel の定番
- **アトミックな切り替え** — 途中の状態が他のプロセスから見えない切り替え。シンボリックリンクなら `ln -sfn` ではなく `mv -T` を使う(`rename(2)` が不可分なため)
- **`--no-dev` / `--optimize-autoloader`** — 本番で `composer install` するときに必ず付けるオプション。開発用依存を入れないこと、クラスの対応表を事前に作ることを指示する
- **`config:cache`(Laravel)** — 設定を 1 ファイルに固めて高速化するコマンド。**実行後は `.env` が読まれなくなる**副作用がある
- **前方互換なマイグレーション** — 「新しいスキーマ + 古いコード」でも動くようにマイグレーションを設計すること。列を消さない、追加は NULL 許容にする、リネームは段階的に行う。**コードだけロールバックできる状態を保つための作法**
- **イミュータブルインフラ** — 稼働中のサーバーに変更を加えず、新しいサーバー(AMI / コンテナイメージ)を作って入れ替える考え方。「参照先を切り替える」方式をサーバー単位に適用したもの

## 関連

- OS ユーザーと権限、専用ユーザーの作り方、`.service` ファイルの基本的な読み方 → [os-users-and-file-locations.md](./os-users-and-file-locations.md)
- ファイル権限そのものの仕組み(`rwx`、`umask`) → [file-permissions-and-exec-bit.md](./file-permissions-and-exec-bit.md)
- 環境変数がプロセスに渡る仕組みと `.env` の扱い → [env-vars-basics.md](./env-vars-basics.md)
- JVM・Corretto・ビルドと実行の関係 → [java-build-and-run.md](./java-build-and-run.md)
- CI(GitHub Actions)でビルドとテストを回す構成 → [ci-with-github-actions.md](./ci-with-github-actions.md)
- このリポジトリの本番構成(ECS Fargate / ALB / ACM / OIDC) → [docs/infrastructure/README.md](../infrastructure/README.md)
- 開発環境の 5 コンテナ構成と環境変数の方針 → [docs/development/README.md](../development/README.md)
- CloudFormation を選んだ理由(構成をコード化する意義) → [ADR-0001](../adr/0001-cloudformation-yaml-over-terraform.md)
