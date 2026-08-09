# OS のユーザーとファイルの置き場所 — なぜ分かれているのか、どこに置くべきなのか

「Windows も Linux もユーザーごとにフォルダを作る方式なのはなぜか」という疑問から出発して、インストール先の使い分け、`C:\` 直下にフォルダを作ると何が起きるか、Linux サーバーへのアプリ配置、プロセスの実行ユーザーとファイル権限の噛み合い方、AWS CLI の認証情報の置き場所までを、手元の PC で実測しながら整理した学習メモ。

要点は 6 つ。

1. **OS のユーザーは「人」ではなく「権限の主体」。** 手元の WSL には 28 個のユーザーがいるが、そのうち **25 個はログインできない**(実測)。人間は 1 人しかいない
2. **プロセスにも uid があり、それはプログラムの性質ではなく起動のされ方で決まる。** 同じ `systemd` が root としても一般ユーザーとしても動いている(実測)。**アプリをどのユーザーで動かすかは選べるが、選べるのは root だけ**
3. **インストール先の分岐点は 1 つだけ** — 「書き込む先のフォルダに自分の権限があるか」。全体インストールとユーザー別インストールの違いはこれに尽きる
4. **`C:\` 直下に作ったフォルダは、他のユーザーから読めるどころか書き換えもできる**(実測)。一方 `C:\Users\<ユーザー名>` には他の一般ユーザーは**入れない**(実測)。だからユーザー配下に作るほうがいい
5. **Linux サーバーへの配置では、そのアプリ専用のユーザーを作るのが定石。** 人が使うためではなく、乗っ取られたときの被害範囲を縛るため
6. **AWS CLI の認証情報がユーザーごとに分かれるのは事実だが、「ユーザーで分ける」が目的ではない。** 分離の軸は 3 層あり、サーバー・CI での正解は「そもそも置かない」

権限そのもの(`rwx` の 9 ビットがどこに記録されているか、`umask` とは何か)は [file-permissions-and-exec-bit.md](./file-permissions-and-exec-bit.md) で扱った。ここではその上の階層、**「誰が」に当たるユーザーの話**を扱う。

## この文書での「ユーザー」の呼び分け

このテーマでは「ユーザー」という言葉が 3 つの別物を指してしまう。以下、必ず次のように呼び分ける。

| 呼び方 | 何を指すか | 例 |
|---|---|---|
| **OS ユーザー** | OS がファイルやプロセスの権限を判定するために持つアカウント | `masanoriadachi`(uid 1000)、`www-data`、Windows の `masanori.adachi` |
| **IAM ユーザー** | AWS 側のアカウント。OS とは無関係 | IAM コンソールで作るユーザー |
| **アプリのユーザー** | このアプリにメールとパスワードで登録した利用者 | [CONTEXT.md](../../CONTEXT.md) の「ユーザー(User)」 |

この 3 つはまったく別のもので、対応関係もない。以降、単に「ユーザー」と書いたら **OS ユーザー**を指す。

## 1. OS ユーザーとは「権限の主体」であって、人ではない

最初にひっくり返しておきたい前提がある。**OS ユーザー = パソコンを使う人間、ではない。**

OS から見たユーザーの正体は、**ファイルの所有者やプロセスの実行者として権限判定に使われる ID** でしかない。Linux では `uid`(数値)、Windows では `SID`(文字列)がその実体で、名前は人間向けの表示にすぎない。

実測。まず Linux 側:

```
$ id
uid=1000(masanoriadachi) gid=1000(masanoriadachi) groups=1000(masanoriadachi),27(sudo),1001(docker)
```

`uid=1000` が本体で、`masanoriadachi` はその表示名。Windows 側も構造は同じ:

```
$ whoami /user
User Name                       SID
=============================== ==============================================
desktop-tennuhg\masanori.adachi S-1-5-21-4090821595-3417302871-1960351343-1002
```

### 手元の PC にいるユーザーの大半は人間ではない

`/etc/passwd` には OS ユーザーが 1 行 1 件で並んでいる。この WSL 環境で数えると:

```
$ wc -l < /etc/passwd
28
$ awk -F: '$7 ~ /(nologin|false)$/' /etc/passwd | wc -l
25
```

**28 個のユーザーのうち 25 個は、ログインシェルが `nologin` / `false` に設定されていてログインできない。**(`/etc/passwd` は `:` 区切りで、7 番目のフィールドがログインシェル。`awk` でそこだけを見ている)中身を見ると理由がわかる:

```
$ grep -E "^(root|daemon|www-data|nobody|masanoriadachi):" /etc/passwd
root:x:0:0:root:/root:/bin/bash
daemon:x:1:1:daemon:/usr/sbin:/usr/sbin/nologin
www-data:x:33:33:www-data:/var/www:/usr/sbin/nologin
nobody:x:65534:65534:nobody:/nonexistent:/usr/sbin/nologin
masanoriadachi:x:1000:1000:,,,:/home/masanoriadachi:/bin/bash
```

`www-data`(Web サーバー用)、`daemon`、`nobody` — これらは**プログラムを動かすために存在するユーザー**で、対応する人間はいない。実際に動いているプロセスを見ると、それぞれ別のユーザーで動いていることがわかる:

```
$ ps -eo user:16,comm --sort=user
USER             COMMAND
masanoriadachi   systemd
messagebus       dbus-daemon
polkitd          polkitd
root             systemd
syslog           rsyslogd
systemd-resolve  systemd-resolve
systemd-timesync systemd-timesyn
```

DBus には `messagebus`、ログ収集には `syslog`、名前解決には `systemd-resolve` と、**サービスごとに専用ユーザーが割り当てられている**。これが後の「サーバーへの配置」の章で効いてくる。

つまり `/etc/passwd` は「この PC を使う人の名簿」ではなく、**「この OS の中で権限を持ちうる主体の一覧」**。人間はそのうちの一部でしかない。

### ホームディレクトリは「その主体の作業場所」

各ユーザーには**ホームディレクトリ**が割り当てられる(`/etc/passwd` の 6 番目の項目)。人間のユーザーなら `/home/masanoriadachi`、`www-data` なら `/var/www`、`nobody` は `/nonexistent`(存在しないパス — 作業場所が要らないので)。

Linux ではこのパスが環境変数 `HOME` に入り、`~` はその展開結果として扱われる:

```
$ echo $HOME
/home/masanoriadachi
```

Windows なら `C:\Users\<ユーザー名>` が同じ役割で、環境変数 `USERPROFILE` に入る。

**このあと出てくる「ユーザー配下に置く」は、すべて「このホームディレクトリの下に置く」という意味。** そして `~/.aws` のようなパスがユーザーごとに別物になるのは、`~` の展開先がユーザーごとに違うから、というだけの話になる。

### プロセスもユーザーを持つ — そして「どのユーザーで動くか」は起動のされ方で決まる

ここまでは「ファイルの持ち主」としてのユーザーを見てきたが、**動いているプログラム(プロセス)にも、必ず uid が付いている。** そして重要なのは、**その uid はプログラムの性質ではなく、起動のされ方で決まる**という点。

実測すると、それがはっきり出る:

```
$ ps -eo user:16,pid,comm --sort=comm
USER                 PID COMMAND
messagebus           249 dbus-daemon
polkitd             5929 polkitd
syslog               280 rsyslogd
root                   1 systemd
masanoriadachi       493 systemd     ← 同じ systemd が
root                 615 systemd     ← 別のユーザーでも動いている
```

**同じ `systemd` というプログラムが、`root` としても `masanoriadachi` としても動いている。** つまり「このプログラムは root で動くもの」という決まりは存在せず、**起動した側が決めている**。

決まり方の原則は 1 つだけ:

> **プロセスの uid は、親プロセスからそのまま引き継がれる。それを変えられるのは root だけ。**

社員証をコピーして子どもに渡すようなもので、子は親と同じ社員証を持って生まれる。別の社員証に差し替えられるのは、発行権限を持つ側(root)だけ。**一度降りたら戻れない**のもポイント。

| 起動のしかた | プロセスの uid はどうなるか |
|---|---|
| 自分のシェルで `java -jar app.jar` | **自分の uid**。選んだのではなく、シェルから引き継いだだけ |
| systemd が `User=appuser` 付きで起動 | systemd は **root** なので切り替える権限がある。`ExecStart` を実行する直前に `appuser` へ落とす |
| `sudo -u appuser コマンド` | `sudo` が root 権限で切り替えてから実行する |
| nginx / php-fpm | **マスターは root で起動**(80 番を開くため)し、実際に処理する**ワーカーだけを一般ユーザーに落とす** |

第 5 章の `User=appuser` は 2 行目のこと。**「アプリをどのユーザーで動かすか選べるのか」への答えは「選べる。ただし選べるのは root だけ」**になる。

### プロセスの uid とファイルの権限は、まったく別のもの

ここは混ざりやすいので分けておく。

| | どこに記録されるか | 何を表すか | 変える手段 |
|---|---|---|---|
| **プロセスの uid** | プロセス(メモリ上) | このプログラムは**誰として**動いているか | 起動時に root が決める(`User=`、`sudo -u`) |
| **ファイルの所有者・グループ・モード** | inode(ディスク上) | このファイルは**誰の持ち物で、どう扱えるか** | `chown` / `chmod` |

**この 2 つは独立に設定される。** アクセスが起きるたびに、カーネルが両方を突き合わせて判定する:

```
【プロセス側】                        【ファイル側】
myapp.service                        /etc/myapp/env
  User=appuser  ────────┐    ┌────── chown root:appuser
                        ↓    ↓       chmod 640
                    カーネルが照合
              「uid=appuser は所有者(root)ではない。
                でもグループ(appuser)には一致する。
                → グループの権限 r-- を適用 → 読める」
```

判定は**上から順に見て、最初に当てはまった枠だけ**を使う:

```
プロセスの uid = ファイルの所有者か？
   Yes → 「所有者」の rwx で判定して終わり   ← ここで打ち切り
   No  ↓
プロセスの所属グループ = ファイルのグループか？
   Yes → 「グループ」の rwx で判定して終わり
   No  ↓
        「その他」の rwx で判定
```

**「最初に当てはまった枠だけ」というのが意外な結果を生む。** 例えば `chmod 046`(所有者 `---`、グループ `r--`、その他 `rw-`)にすると、**所有者だけが読めないファイル**が作れる。所有者に該当した時点で判定が打ち切られ、グループの権限を見てもらえないため。「所有者は一番偉いから全部できる」わけではない。

(例外が 1 つあり、**root はこの判定を素通りする**。5-1 で「root で動かさない」と書いたのはこれが理由。)

`rwx` の 9 ビットや 644 / 755 の読み方そのものは [file-permissions-and-exec-bit.md](./file-permissions-and-exec-bit.md) の第 2 章にある。ここで押さえたいのは、**ファイル側だけ整えても、プロセス側の uid が噛み合っていなければ意味がない**という関係のほう。よくある失敗:

| 設定ミス | 何が起きるか |
|---|---|
| `User=appuser` にしたが、ファイルは `root:root 600` のまま | アプリが設定ファイルを読めず起動に失敗する |
| ファイルは `root:appuser 640` にしたが、`User=` を書き忘れた | **root で動いてしまう。** root は判定を素通りするので**動いてはしまい、気づきにくい** |
| 動かないので `chmod 644` にした | 動くが、**サーバー上の全ユーザーが DB パスワードを読める** |

### Windows では: タスクマネージャーの「ユーザー名」列

`ps -eo user` に当たるものは Windows にもある。**タスクマネージャーの「詳細」タブの「ユーザー名」列**がそれで、コマンドラインなら `tasklist /v`:

```
$ tasklist /v /fo list
イメージ名:          System Idle Process
PID:                 0
セッション名:        Services
メモリ使用量:        8 K
ユーザー名:          NT AUTHORITY\SYSTEM      ← ps の USER 列に相当
CPU 時間:            854:55:12
```

`NT AUTHORITY\SYSTEM` は Windows でサービスを動かすためのアカウントで、**Linux の `root` にあたる**。人間のいないアカウントでサービスを動かすという発想は Windows も同じ(ほかに `LOCAL SERVICE`、`NETWORK SERVICE` があり、こちらは `www-data` のような**権限を絞った**サービス用アカウント)。

ただし **`ps` と挙動が違う点が 1 つある**。実行ユーザーごとに数えてみると:

```
$ tasklist /v /fo csv   (ユーザー名の列だけ集計)
    237 DESKTOP-TENNUHG\masanori.adachi
    157 N/A                                ← 見えない
      1 NT AUTHORITY\SYSTEM
```

**157 個が `N/A` になっている。** 昇格していないプロセスからは、他のユーザー(SYSTEM など)のプロセスの所有者情報を取得できないため(実測結果からの推論。管理者として実行すれば見えるようになる)。第 2 章の UAC の話と地続きで、**Windows は「見る」こと自体にも権限が要る**という設計になっている。

一方 **Linux の `ps` は既定で全プロセスの実行ユーザーが見える。** 上の実測で `messagebus` や `polkitd` のプロセスが一般ユーザーから普通に見えていたとおり。

| | Linux (`ps`) | Windows (タスクマネージャー / `tasklist`) |
|---|---|---|
| 他ユーザーのプロセスの存在 | 見える | 見える |
| その実行ユーザー名 | **見える** | **昇格しないと `N/A`** |
| サービス用の特権アカウント | `root` | `NT AUTHORITY\SYSTEM` |
| 権限を絞ったサービス用アカウント | `www-data`、`syslog` など | `LOCAL SERVICE`、`NETWORK SERVICE` |

## 2. なぜユーザーが分かれているのか

「1 台の PC を複数人で使うため」は理由の一部でしかない。一人しか使わない PC でもユーザーは分かれている。本当の理由は 3 つある。

### 理由 1: 隔離 — 壊せる範囲を限定する

あるユーザーの権限で動いているプログラムが暴走しても、そのユーザーが書き込める場所しか壊せない。OS の本体(`/usr`、`C:\Windows`)は別の所有者(`root` / `TrustedInstaller`)のものなので手を出せない。

実測。共有領域の所有者はすべて `root` になっている:

```
$ ls -ld /usr/bin /usr/local/bin /opt /etc /root
drwxr-xr-x  2 root root  36864 /usr/bin
drwxr-xr-x  2 root root   4096 /usr/local/bin
drwxr-xr-x  2 root root   4096 /opt
drwxr-xr-x 99 root root   4096 /etc
drwx------  7 root root   4096 /root
```

`drwxr-xr-x` の後半 `r-x` `r-x` は「グループとその他は読める・入れるが、**書けない**」。だから一般ユーザーが `/usr/bin` にファイルを置こうとすると `Permission denied` になり、`sudo` が要る。

### 理由 2: 責任の追跡 — 誰がやったかを OS が記録できる

ファイルの所有者もプロセスの実行者もユーザー単位で記録される。全部が同じユーザーで動いていたら、ログを見ても「何が」書いたのか区別がつかない。

### 理由 3: 最小権限 — プログラムに必要な分だけ渡す

これが `www-data` のような**人間のいないユーザー**が存在する理由。Web サーバーを `root` で動かすと、脆弱性を突かれた瞬間に OS 全体を取られる。専用ユーザーで動かしておけば、取られてもそのユーザーが触れる範囲で止まる。

**ユーザーを分ける目的は「人を分ける」ことではなく「権限を分ける」こと**、と捉えるとこの先が全部つながる。

### 補足: Windows の管理者は、いつも管理者として動いているわけではない

Windows で自分が Administrators グループに入っていても、**普段のプロセスは管理者権限を外した状態で動いている**(UAC)。実測すると、Administrators は「拒否のみに使用するグループ」として無効化されている:

```
$ whoami /groups
BUILTIN\Administrators   エイリアス   S-1-5-32-544   拒否のみに使用するグループ
```

「管理者として実行」や UAC のダイアログで承認すると、初めて Administrators が有効なプロセスが起動する。**管理者アカウントでも、昇格していないプロセスは一般ユーザーと同じ権限で動く** — インストーラが権限を要求してくる場面はこれが理由。

Linux の `sudo` も発想は同じで、普段は uid 1000 のまま、必要なコマンドだけ root として実行する。

## 3. インストール先を分ける基準は「書き込む先に権限があるか」だけ

「すべてのユーザー向けインストール」と「このユーザーのみのインストール」は、機能の違いではない。**ファイルをどこに書くかの違いで、そこから権限の要否がすべて決まる。**

| | 全体インストール | ユーザー別インストール |
|---|---|---|
| 書き込む先(Windows) | `C:\Program Files` | `%LOCALAPPDATA%\Programs` |
| 書き込む先(Linux) | `/usr/local/bin`、`/opt` | `~/.local`、`~/.config` 配下 |
| 管理者権限 | **要る**(UAC / `sudo`) | **要らない** |
| 誰が使えるか | 全ユーザー | インストールした人だけ |
| 他ユーザーへの影響 | ある(バージョンが共有される) | ない(各自が別バージョンを持てる) |
| 設定の保存先 | どちらもユーザー配下(`~/.config` など) | 同左 |

最後の行が見落としやすい。**全体インストールしたソフトでも、設定ファイルは各ユーザーのホーム配下に作られる。** プログラム本体は共有、設定は個別、という分業になっている。

### `/usr/bin` と `/usr/local/bin` の違い — 持ち主が違う

Linux 側の共有領域には、表に挙げなかったものがもう 1 つある。`/usr/bin` だ。ここも root 所有の共有領域だが、**`/usr/local/bin` とは持ち主が違う。**

| | `/usr/bin` | `/usr/local/bin` |
|---|---|---|
| 誰が置くか | **`apt` などのパッケージ管理システム** | **人間**(管理者が手で入れたもの) |
| パッケージ更新の影響 | 上書き・削除される | **触られない** |
| `dpkg` が中身を把握しているか | している | していない |
| 件数(この環境の実測) | 1035 | 12 |

`/usr/local` の `local` は「このマシンで独自に入れたもの」の意味。**パッケージ管理システムが管理する領域と、人間が手で入れたものの領域を分けておくための境界線**で、これがないと `apt upgrade` のたびに手で入れたファイルが消えたり衝突したりする。

実測すると、持ち主の違いがはっきり出る:

```
$ dpkg -S /usr/bin/git
git: /usr/bin/git                                       ← apt が把握している

$ dpkg -S /usr/local/bin/aws
dpkg-query: no path found matching pattern /usr/local/bin/aws
                                                        ← apt は知らない = 手で入れたもの
```

`/usr/local/bin` の中身を見ると、確かに手で入れたものばかりが並んでいる:

```
$ ls /usr/local/bin
aws  aws_completer  cagent  corepack  kubectl  n  node  npm  npx  sam  session-manager-plugin  stripe.old
```

### 同名のコマンドがあったらどちらが動くか — PATH の順序で決まる

2 つの領域に同じ名前のコマンドがあった場合、**先に見つかったほうが実行される**。`PATH` は「実行ファイルを探すディレクトリの並び」で、前から順に探すため。

```
$ echo $PATH | tr ':' '\n'
...
/usr/local/bin      ← 11 番目
/usr/sbin
/usr/bin            ← 13 番目
```

**`/usr/local/bin` のほうが前にある。** これは意図された順序で、「手で入れた新しいバージョンが、パッケージ版より優先される」ようになっている。

この環境には `node` が 3 つあり、実際に優先順位が働いている:

```
$ which -a node
/run/user/1000/fnm_multishells/1893_.../bin/node    ← これが実行される(PATH の 2 番目)
/run/user/1000/fnm_multishells/896_.../bin/node
/usr/local/bin/node
```

`which` は最初の 1 つ、`which -a` は全部を表示する。**「入れたはずのバージョンと `node -v` の結果が食い違う」ときは、たいていこの優先順位が原因**なので、`which -a` で重複を疑う。

なお、この住み分けは慣習であって OS が強制するものではない。実測でも例外が 1 つ見つかった:

```
$ dpkg -S /usr/local
session-manager-plugin: /usr/local     ← deb パッケージなのに /usr/local へ入れている
```

AWS の session-manager-plugin は `.deb` で配られながら `/usr/local` に置く作りになっている。**慣習を破るパッケージも実在する**、という例。

まとめると、**手動インストールは `/usr/local/bin`(単体の実行ファイル)か `/opt`(ディレクトリ一式)に置き、`/usr/bin` には置かない。**

### 実測: 同じ PC の中に両方が共存している

この WSL 環境では、AWS CLI と Node.js が対照的な入り方をしている。

```
$ which aws
/usr/local/bin/aws          ← 共有領域。インストールに sudo が要った
$ which node
/run/user/1000/fnm_multishells/59204_1786189542574/bin/node
                            ← パスに uid の 1000 が入っている。完全にユーザー専用
```

Node.js は fnm(バージョン管理ツール)経由で入れているため、実体はユーザー配下にある。`npm -g` のインストール先も同様:

```
$ npm config get prefix
/home/masanoriadachi/.local/share/fnm/node-versions/v22.14.0/installation
```

**`npm install -g` の `-g`(global)は「OS 全体に」という意味ではなく「このプロジェクトではなく、この Node 環境全体に」という意味。** 書き込む先がユーザー配下なので `sudo` は要らない。逆に、Node.js を `apt` で入れていると `-g` の先が `/usr/lib/node_modules` になり `sudo` が必要になる — 同じコマンドなのに要否が変わるのは、インストール方法によって書き込む先が変わるから。

`/run/user/1000` の権限も見ておくと、思想がはっきりする:

```
$ ls -ld /run/user/1000
drwx------ 8 masanoriadachi masanoriadachi 760 /run/user/1000
```

`drwx------` = root を除く他のユーザーは読むことすらできない。ディレクトリ名が uid そのものになっているのも、これが「uid 1000 専用の領域」だから。

Windows 側でも同じ二分法が実測できる:

```
$ icacls "C:\Program Files"
C:\PROGRA~1 NT SERVICE\TrustedInstaller:(F)
            BUILTIN\Administrators:(M)
            BUILTIN\Users:(RX)              ← 一般ユーザーは読み・実行のみ。書けない
```

```
$ ls C:\Users\masanori.adachi\AppData\Local\Programs
Antigravity
Microsoft VS Code
Notion
cursor
```

**VS Code も Cursor も Notion も、`Program Files` ではなくユーザー配下に入っている。** これらは意図的にユーザー別インストールを既定にしているツールで、そのおかげで管理者権限のない環境でも入り、自動アップデートも昇格なしで走る。

### では、全体インストールされるのは何か — 基準はサイズではない

同じ PC の `Program Files` を見ると、性格のはっきり違う顔ぶれが並んでいる:

```
$ ls "C:\Program Files"
DBeaver  DIFX  Docker  Google  HP  HPCommRecovery  Hyper-V  Intel
WSL  Windows Defender  WindowsApps  WindowsPowerShell  nodejs ...
```

ユーザー配下(`VS Code` / `Cursor` / `Notion`)と見比べると、線引きが読み取れる。**全体インストールになるのは、「そうしないと機能しないもの」**であって、大きいものではない。

| 全体インストールになる理由 | この PC での実例 |
|---|---|
| **OS の機能そのもの** — カーネルや OS の一部として組み込まれる | `WSL`、`Hyper-V`、`Windows Defender` |
| **デバイスドライバ** — ハードウェアを扱うのでカーネル空間に入る | `Intel`、`HP`、`DIFX` |
| **サービスとして常駐する** — ログインしていなくても動く必要がある | `Docker`(バックグラウンドサービス + WSL/Hyper-V 連携) |
| **全ユーザーで共有したい** — 各自が別々に持つ意味がない | `nodejs`(MSI 版)、`DBeaver`、`HeidiSQL` |

いずれも**管理者権限が必要な操作を含んでいる**のが共通点。ドライバはカーネルに登録し、サービスは OS のサービス一覧に登録し、OS 機能は `C:\Windows` を触る。ユーザー配下に置いても、これらの登録はできない。**「全体インストールを選んだ」のではなく「そうするしかない」**という順序になっている。

裏を返すと、**エディタやチャットアプリのように「起動して使うだけ」のソフトには全体インストールにする理由がない。** VS Code や Cursor がユーザー別を既定にしているのはそのため。

### サイズは基準になるのか — ならない

ゲームのような容量の大きいものが全体インストールになるのでは、という発想は自然だが、**サイズと権限は無関係**。10GB のソフトでもドライバを積まないならユーザー配下に置ける。

実際、大きいソフトは**第 3 の選択肢**を取ることが多い。プログラム本体と巨大なデータを分け、データの置き場所をユーザーに選ばせる方式で、Steam がその典型:

| | 置き場所 | サイズ |
|---|---|---|
| Steam 本体 | `C:\Program Files (x86)\Steam`(既定) | 数百 MB |
| ゲームのデータ | ライブラリフォルダ(**任意のドライブを指定可能**) | 数十 GB〜 |

つまり容量が大きいことは、**全体インストールを選ぶ理由ではなく、「置き場所を選べるようにする」理由**になっている。同じ発想は Docker のイメージ置き場や、動画編集ソフトのキャッシュ設定にも出てくる。

なお、容量が大きいものをユーザー配下に置くと問題になる環境は実在する。**企業でよく使われる移動ユーザープロファイル**(ログインのたびにユーザー配下をネットワーク経由で同期する仕組み)がそれで、この場合は大きなデータをユーザー配下から外す。ただしこれは「サイズ」ではなく「その環境がユーザー配下を同期対象にしているから」という別の事情。

### どちらを選ぶか

- **迷ったらユーザー別。** 管理者権限が要らず、他の人に影響せず、消すときもフォルダごと消せる
- **全体インストールを選ぶのは、複数ユーザーで共有する必要があるとき**か、OS のサービスとして常駐させる必要があるとき(後述の Linux サーバーはこちら)
- 開発ツールのバージョン管理(fnm、SDKMAN、rustup など)は**ユーザー別が前提**。プロジェクトごとに違うバージョンを使い分けたいのに、OS 全体で 1 つに固定されては困るため

## 4. Windows で `C:\` 直下にフォルダを作るとどうなるか

結論から言うと、**作れるが、他のユーザーから読めるうえに書き換えもできる状態になる。**

### なぜ管理者でなくても作れるのか

`C:\` 自体の ACL(アクセス制御リスト)を実測すると、その許可が明示的に入っている:

```
$ icacls C:\
C:\ BUILTIN\Administrators:(OI)(CI)(F)
    NT AUTHORITY\SYSTEM:(OI)(CI)(F)
    BUILTIN\Users:(OI)(CI)(RX)
    NT AUTHORITY\Authenticated Users:(OI)(CI)(IO)(M)
    NT AUTHORITY\Authenticated Users:(AD)
```

記号の読み方:

| 記号 | 意味 |
|---|---|
| `(F)` / `(M)` / `(RX)` | フルコントロール / 変更 / 読み取りと実行 |
| `(AD)` | サブフォルダの作成を許可 |
| `(OI)(CI)` | 配下のファイル・フォルダに継承させる |
| `(IO)` | このフォルダ自身には適用せず、継承先にだけ適用する |
| `(I)` | この許可は親から継承されたもの |

4 行目と 5 行目が答え。**`Authenticated Users:(AD)` によって、ログインしている任意のユーザーが `C:\` 直下にフォルダを作れる**(継承フラグが付いていないので `C:\` 直下限定)。だから昇格なしで作れる。

### 作ったフォルダは誰から見えるのか

`C:\` 直下に作られたフォルダの ACL を実測すると(フォルダ名は `C:\myfolder` として伏せる):

```
$ icacls C:\myfolder
C:\myfolder BUILTIN\Administrators:(I)(OI)(CI)(F)
            NT AUTHORITY\SYSTEM:(I)(OI)(CI)(F)
            BUILTIN\Users:(I)(OI)(CI)(RX)      ← 全ユーザーが読める
            NT AUTHORITY\Authenticated Users:(I)(M)  ← 変更もできる
```

すべての行に `(I)` が付いている = **`C:\` の許可がそのまま継承されている**。結果として:

- `BUILTIN\Users:(RX)` — この PC の**すべてのユーザーが中身を読める**
- `Authenticated Users:(M)` — 読むだけでなく**書き換え・削除もできる**

質問への直接の答えは「見られる」で、しかも見られるだけでは済まない。`C:\` の ACL にあった `(OI)(CI)(IO)(M)` の `(IO)` が効いていて、`C:\` 自体は変更不可でも**その配下は変更可**になる、という設計のため。

### ユーザー配下と比べる

同じコマンドをホームディレクトリに対して実行すると、様子がまったく違う:

```
$ icacls C:\Users\masanori.adachi
C:\Users\masanori.adachi NT AUTHORITY\SYSTEM:(OI)(CI)(F)
                         BUILTIN\Administrators:(OI)(CI)(F)
                         DESKTOP-TENNUHG\masanori.adachi:(OI)(CI)(F)
```

**`BUILTIN\Users` の行も `Authenticated Users` の行も存在しない。** 許可されているのは SYSTEM、管理者、そして本人だけ。他の一般ユーザーは、このフォルダに**入ることすらできない**。

`(I)` が付いていない = 親(`C:\Users`)からの継承ではなく、このフォルダに明示的に設定された許可であることも読み取れる。Windows はホームディレクトリを作るときに、継承を断ち切って本人専用の許可を設定している。

Linux も同じ設計になっている:

```
$ ls -ld /home /home/masanoriadachi
drwxr-xr-x  3 root           root           /home
drwxr-x--- 40 masanoriadachi masanoriadachi /home/masanoriadachi
```

`/home` 自体は誰でも通れる(`r-x`)が、`/home/masanoriadachi` は `drwxr-x---` で **その他のユーザーには権限がゼロ**。**Ubuntu 21.04 から新規ユーザーのホームは 750 が既定**になっている(それ以前は 755 で、他のユーザーから中身を読めた)。

この既定値は設定ファイルに書かれていて、手元でも確認できる:

```
$ grep -A1 "^# Default: DIR_MODE" /etc/adduser.conf
# Default: DIR_MODE=0750
#DIR_MODE=0750
$ grep "^HOME_MODE" /etc/login.defs
HOME_MODE	0750
```

`adduser` を使うときは `adduser.conf` の `DIR_MODE`、`useradd` を直接使うときは `login.defs` の `HOME_MODE` が効く。どちらも 0750 で揃っている。

### 結論: ユーザー配下に作るべき

| 置き場所 | 他ユーザーから | 適した用途 |
|---|---|---|
| `C:\` 直下 | 読める・書き換えられる | 意図的に全ユーザーで共有したいものだけ |
| `C:\Users\<自分>` 配下 | 入れない | **通常はこちら**。作業フォルダ、リポジトリ、設定、認証情報 |
| `C:\Program Files` | 読める・書けない | 全ユーザー向けにインストールされたプログラム本体 |
| `C:\ProgramData` | 読める・(既定では)書ける | 全ユーザー共有のアプリデータ |

`C:\` 直下を選びたくなる動機は主に「パスが短くなる」「日本語ユーザー名を含むパスを避けたい」あたりで、これらは実際にツールの不具合を避ける目的では正当な理由になる。ただし**その場合でも、置くものが他人に読まれて困る内容でないかは別途確認が必要**。認証情報・鍵ファイル・個人情報を含むものを `C:\` 直下に置くのは避ける。

置いてしまったものを後から守るなら、フォルダの継承を切って許可を自分だけにする(エクスプローラーのプロパティ → セキュリティ → 詳細設定 → 「継承の無効化」)。ただし**最初からユーザー配下に置くほうが確実**。

Linux 側でも同じ判断になる。誰でも書ける `/tmp` は特別扱いで、末尾の `t`(スティッキービット)により「自分が作ったファイルしか消せない」制約が付いている:

```
$ ls -ld /tmp
drwxrwxrwt 16 root root /tmp
```

これがないと、共有の書き込み可能フォルダは誰かが他人のファイルを消せてしまう。**「みんなが書ける場所」を安全に運用するには追加の仕掛けが要る**、ということの実例。

## 5. Linux サーバーへアプリを配置するとき

EC2 やオンプレの Linux サーバーにアプリを置く場合、**そのアプリ専用のユーザーを作ってから配置するのが定石**。ただし「人が使うため」ではないので、作り方が普通のユーザーとは違う。

### 5-1. なぜ root で動かさないのか

`root` は権限判定を素通りする特別なユーザー(uid 0)。root で動かしたアプリが乗っ取られると、攻撃者はそのサーバーの全ファイルを読み書きでき、他のサービスも止められ、ログも消せる。

**アプリを専用ユーザーで動かしておけば、被害はそのユーザーが触れる範囲で止まる。** これは「攻撃されない」ための対策ではなく「攻撃されたときの被害範囲を縛る」ための対策。

もう一つ実務的な理由として、80 番など 1024 未満のポートを開くには root 権限が要る。これは「起動だけ root で行い、ポートを確保したら一般ユーザーに降格する」設計や、リバースプロキシ・ロードバランサに前段を任せる設計で回避する。**このリポジトリは後者**で、TLS 終端と 443 番の受け口は ALB が担当し、アプリ自身は 8080 番で動く(→ [CLAUDE.md](../../CLAUDE.md) のアーキテクチャ上の決定事項)。

### 5-2. サービス専用ユーザーはログインできないように作る

サービス用ユーザーには、人間向けのユーザーには付ける 3 つのものを**あえて付けない**。

```bash
sudo useradd --system --no-create-home --shell /usr/sbin/nologin appuser
```

| オプション | 意味 | なぜそうするか |
|---|---|---|
| `--system` | システムユーザーとして作る(uid が 1000 未満になる) | 人間用の uid 帯と混ざらない |
| `--no-create-home` | ホームディレクトリを作らない | 作業場所が要らない |
| `--shell /usr/sbin/nologin` | ログインシェルを与えない | **このユーザーでログインさせない** |

ただし **`--no-create-home` は実は冗長**。`--system` を付けた時点でホームは作られないためで、`man useradd` にそう書いてある:

> Note that useradd will not create a home directory for such a user, regardless of the default setting in /etc/login.defs (CREATE_HOME). You have to specify the -m options if you want a home directory for a system account to be created.

`login.defs` 側にも「この設定はシステムユーザーには適用されない」と明記されている。**システムユーザーはホームを作らないのが既定で、欲しい場合は逆に `-m` を付ける**、という関係。上のコマンドで書いているのは意図を読み手に示すための明示指定で、外しても結果は変わらない。

3 行目が要点。仮にパスワードが漏れても、SSH でこのユーザーとしてログインすることはできない。第 1 章で見た `www-data` がまさにこの形で作られている:

```
www-data:x:33:33:www-data:/var/www:/usr/sbin/nologin
```

uid が 33(1000 未満)、シェルが `nologin`。手元の WSL で見た `messagebus`、`polkitd`、`syslog` も同じ作りで、**サービスごとに専用ユーザーを立てる**のは Linux 全体で一貫した慣習になっている。

### 5-3. アプリ本体はどこに置くか

**`/opt/<アプリ名>` か `/srv/<アプリ名>` に置き、`/home` には置かない。**

| 置き場所 | 使うべきか | 理由 |
|---|---|---|
| `/opt/myapp` | **推奨** | 「OS のパッケージ管理外で入れたソフト」の標準的な置き場所 |
| `/srv/myapp` | 可 | サーバーが外部に提供するデータ用。Web サイトの実体などはこちら |
| `/home/appuser/myapp` | **避ける** | `/home` は人間の作業領域。人を消すとアプリごと消える運用事故になりやすい |
| `/usr/local/bin` | 単一の実行ファイルなら可 | ディレクトリ一式を置く場所ではない |

第 3 章の「ユーザー別インストールが無難」とは逆の結論になるのが要注意な点。**サーバー上のアプリは特定の人間に属さない共有の資産**なので、共有領域に置き、所有者だけをサービス専用ユーザーにする、という形を取る。

#### 所有者は appuser にしない — コードは読み取り専用にする

ここで直感に反するのが、**アプリのディレクトリの所有者を `appuser` にはしない**という点。

```bash
sudo mkdir -p /opt/myapp
sudo chown -R root:appuser /opt/myapp        # コードは root 所有。アプリは読むだけ
sudo chmod -R u=rwX,g=rX,o= /opt/myapp       # その他には一切見せない
```

`chown -R appuser:appuser` にしたくなるが、そうすると **乗っ取られたアプリが自分のコードを書き換えられる**。`app.jar` や PHP ファイルをバックドア入りに差し替えられ、しかも再起動しても残る。5-4 で設定ファイルの所有者を root にするのと**まったく同じ理由**(所有者は `chmod` で自分の鍵を外せる)。

**「デプロイのたびに書き換わるのだから appuser が書けないと困るのでは」というのは誤解。** 書き換えるのは**デプロイする側**(`deploy` ユーザーや `sudo`)であって、**動いているアプリ自身ではない**。アプリはコードを読んで実行できれば足りる。

実運用のソフトが実際どうなっているかを見ると、この分け方が標準だと分かる:

```
$ ls -l /usr/sbin/rsyslogd /var/log/syslog
-rwxr-xr-x 1 root   root  790192 /usr/sbin/rsyslogd   ← コードは root 所有
-rw-r----- 1 syslog adm   876171 /var/log/syslog      ← 書くデータだけ syslog 所有
```

**`rsyslogd` は `syslog` ユーザーで動いているが、自分の実行ファイルは所有していない。** 書くのはログだけ。これが「**コードは読み取り専用、データだけ書き込み可**」という原則で、第 1 章で見た `messagebus` や `polkitd` も同じ形になっている。

そのうえで、**アプリが書く必要のある場所だけ**を `appuser` 所有にする:

```bash
sudo mkdir -p /opt/myapp/storage             # ログ、アップロード、キャッシュなど
sudo chown -R appuser:appuser /opt/myapp/storage
sudo chmod -R u=rwX,g=rX,o= /opt/myapp/storage
```

| 対象 | 所有者 | アプリ(`appuser`)にできること |
|---|---|---|
| コード(jar、PHP ファイル) | **root**(または `deploy`) | 読む・実行する |
| 設定と秘密(`/etc/myapp/env`) | **root** | 読むだけ |
| データ(ログ、アップロード、キャッシュ) | **appuser** | 読み書きする |

なお **`chmod -R 750` ではなく `u=rwX,g=rX,o=` を使っている**のにも理由がある。実測:

```
=== chmod -R 750 を当てる ===
-rwxr-x---  app.jar        ← ただのデータなのに x が付いてしまう
-rwxr-x---  bin/run.sh

=== chmod -R u=rwX,g=rX,o= を当てる ===
drwxr-x---  （ディレクトリ）
-rw-r-----  app.jar        ← x は付かない
-rwxr-x---  bin/run.sh     ← 元から実行可能だったものだけ残る
```

**大文字の `X` は「ディレクトリと、元から実行ビットが立っているファイルにだけ `x` を付ける」**という指定。`-R` で数字を使うと、jar や `.php` のようなただのデータにまで実行ビットが付いてしまう(実行ビットの意味は [file-permissions-and-exec-bit.md](./file-permissions-and-exec-bit.md) を参照)。

> **これは systemd の `ProtectSystem=strict` + `ReadWritePaths=` と同じ発想**を、ファイルの所有者と権限のレイヤーでやったもの。両方かけておくと、片方を突破されてももう片方が残る。

### 5-4. systemd の `User=` で権限を落とす

作ったユーザーでアプリを動かす指定は、サービス定義ファイル(`/etc/systemd/system/myapp.service`)の 1 行で行う:

```ini
[Unit]
Description=My App
After=network.target

[Service]
User=appuser
Group=appuser
WorkingDirectory=/opt/myapp
ExecStart=/usr/bin/java -jar /opt/myapp/app.jar
EnvironmentFile=/etc/myapp/env
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

**systemd 自体は root で動いているが、`User=appuser` があるとプロセスを起動する直前に権限を `appuser` に落とす。** これが「1024 未満のポートは root が要る」問題と両立できる仕組みでもある(`AmbientCapabilities=CAP_NET_BIND_SERVICE` を足すと、一般ユーザーのまま低いポートを開ける)。

`EnvironmentFile` の指す `/etc/myapp/env` に DB のパスワードなどを置き、このファイルだけ `chmod 640` かつ `chown root:appuser` にしておく — アプリからは読めるが他のユーザーからは読めない、という形が定番。環境変数の渡し方そのものは [env-vars-basics.md](./env-vars-basics.md) に整理してある。

#### `chown root:appuser` の `appuser` は、ユーザーではなくグループ

この 1 行は誤読しやすい。**`chown` の書式は `chown 所有者:グループ` なので、`root:appuser` は「所有者は root ユーザー、グループは appuser グループ」という意味。** `appuser` という**ユーザー**には、直接は何も与えていない。

```
chown root:appuser /etc/myapp/env
      ~~~~ ~~~~~~~
       |      └─ グループ名(appuser という名前のグループ)
       └──────── ユーザー名(root という名前のユーザー)
```

**では、なぜ `appuser` ユーザーで動くアプリがこのファイルを読めるのか。** 答えは、**`useradd` がユーザーを作るときに、同じ名前のグループも一緒に作り、そのユーザーを所属させるから**。実測できる:

```
$ grep "^USERGROUPS_ENAB" /etc/login.defs
USERGROUPS_ENAB yes          ← 「ユーザーと同名のグループを作る」設定
```

結果として、**同じ名前のユーザーとグループが両方存在する**状態になる。第 1 章で見た `www-data` で確かめると:

```
$ grep "^www-data:" /etc/passwd
www-data:x:33:33:www-data:/var/www:/usr/sbin/nologin
              ~~ ~~
              |  └─ gid 33 = 所属する主グループ
              └──── uid 33 = このユーザー自身の ID

$ grep "^www-data:" /etc/group
www-data:x:33:               ← 同名のグループが実在する
```

**`www-data` という名前のユーザー(uid 33)と、`www-data` という名前のグループ(gid 33)は別物。** 名前が同じなので同一視しがちだが、`/etc/passwd` と `/etc/group` という別のファイルに、別の存在として登録されている。この「ユーザーと同名の専用グループ」を**ユーザープライベートグループ**と呼ぶ。

ここで見落としやすいのが、**`/etc/group` の行末が空になっている**こと(`www-data:x:33:` の最後にメンバー名が並んでいない)。所属の記録は 2 か所に分かれていて、

- **主グループ** — `/etc/passwd` の 4 番目のフィールド(gid)で指定する。`/etc/group` のメンバー欄には書かれない
- **補助グループ** — `/etc/group` のメンバー欄に名前が並ぶ。`usermod -aG` で追加するのはこちら

`id` で見ると両方まとめて出る:

```
$ id
uid=1000(masanoriadachi) gid=1000(masanoriadachi) groups=1000(masanoriadachi),27(sudo),1001(docker)
                         ~~~~~~~~~~~~~~~~~~~~~~~~ ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                         主グループ                 所属する全グループ(補助グループを含む)
```

つまり `chown root:appuser` + `chmod 640` が成立する経路は、こう繋がっている:

```
appuser ユーザー ──(useradd が同名グループに所属させた)──> appuser グループ
                                                              │
/etc/myapp/env のグループが appuser  ────────────────────────┘
   → グループの権限 r-- が適用される → 読める
```

#### なぜ `chown appuser:appuser` ではなく `chown root:appuser` なのか

どちらでもアプリは読める。違いは**書けるかどうか**にある。

| | 所有者 | アプリ(`appuser`)にできること |
|---|---|---|
| `chown appuser:appuser` + `640` | appuser | **読み書きできる**(所有者の `rw-` が適用される) |
| `chown root:appuser` + `640` | root | **読むだけ**(グループの `r--` が適用される) |

**アプリが自分の設定ファイルを書き換えられる必要はない。** 所有者を root にしておけば、仮にアプリが乗っ取られても、DB 接続先を攻撃者のサーバーに書き換えるといった改ざんができない。**読めれば十分なものは、読めるだけにしておく**という最小権限の適用例(第 2 章の理由 3)。

#### では `chown appuser:appuser` + `chmod 440` ならどうか — 破れる

`440`(`r--r-----`)にすれば所有者も書けなくなるので、`root:appuser` と同じ効果が得られそうに見える。**だが破れる。所有者は `chmod` で自分の鍵を外せるから。**

実測すると 3 手で書き換えられる:

```
$ ls -l mydir/env
-r--r----- 1 masanoriadachi masanoriadachi 19 mydir/env

$ echo "DB_PASSWORD=hacked" > mydir/env
bash: mydir/env: Permission denied          ← ① 確かに書けない

$ chmod 640 mydir/env                        ← ② でも所有者なので権限は変えられる
$ ls -l mydir/env
-rw-r----- 1 masanoriadachi masanoriadachi 19 mydir/env

$ echo "DB_PASSWORD=hacked" > mydir/env      ← ③ 緩めてから書けばいい
$ cat mydir/env
DB_PASSWORD=hacked
```

**`chmod` を実行できるのは、そのファイルの所有者か root だけ**というのが Linux の決まり(`man 2 chmod`: 呼び出しプロセスの実効 uid がファイルの所有者と一致するか、特権が必要)。つまり:

> **権限ビットを守っているのは所有権であって、その逆ではない。**

`440` は**自分で自分に鍵をかけている**状態で、鍵の持ち主が本人なのでいつでも外せる。所有者を root にすると、この経路がふさがる。同じ操作を root 所有のファイルに対してやると拒否される:

```
$ ls -l /etc/shadow
-rw-r----- 1 root shadow 810 /etc/shadow

$ chmod 640 /etc/shadow
chmod: changing permissions of '/etc/shadow': Operation not permitted
```

**もう 1 つ、`440` でも防げないことがある — 削除。** ファイルを消せるかどうかは、**そのファイルの権限ではなく、置いてあるディレクトリの `w` で決まる**:

```
$ ls -ld mydir
drwxr-xr-x 2 masanoriadachi masanoriadachi 4096 mydir     ← 自分が書けるディレクトリ
$ ls -l mydir/env
-r--r----- 1 masanoriadachi masanoriadachi 19 mydir/env   ← 440

$ rm -f mydir/env
$ ls mydir/env
ls: cannot access 'mydir/env': No such file or directory   ← 消せてしまった
```

だから **`/etc/myapp/` というディレクトリ自体も root 所有にしておく**必要がある。設定ファイルを `/etc` の下に置くのは、慣習だからというだけでなく、**`/etc` が root 所有(`drwxr-xr-x root root`、第 2 章の実測)なので、そこにファイルを作ったり消したりできるのが root だけになる**、という実利がある。

整理すると:

| | アプリ(`appuser`)にできること |
|---|---|
| `appuser:appuser` + `640` | 読める・**書ける** |
| `appuser:appuser` + `440` | 読める・書けない — **ただし `chmod` で自分で緩められるので実質書ける** |
| `root:appuser` + `640` | 読める・書けない・**権限も変えられない**(所有者ではないため) |
| 上に加えて `/etc/myapp` が root 所有 | さらに**削除・差し替えもできない** |

**守る対象が「読ませるが変えさせない」なら、所有者を相手にしないこと。** これは設定ファイルに限らず一般的に成り立つ原則で、`/usr/bin` が root 所有になっているのも同じ理由(第 2 章の理由 1)。

**この判断基準は設定ファイルに限らない。** 5-3 でアプリのコードも `root:appuser` にしたのは同じ理由で、`appuser` 所有にすると乗っ取られたアプリが自分のコードを差し替えられるため。逆にログやアップロード先は `appuser` 所有にする — アプリが書けないと機能しないから。

**「アプリ自身に書かせる必要があるか」だけで所有者が決まる**、と覚えておくとよい:

| | 所有者 | 例 |
|---|---|---|
| アプリは読むだけでよい | **root** | 設定ファイル、秘密、アプリのコード |
| アプリが書く必要がある | **appuser** | ログ、アップロード先、キャッシュ |

#### `chmod appuser:appuser` ではダメなのか — コマンドが違う

`chmod` と `chown` は名前が似ているが、**変える対象がまったく違う**。

| コマンド | 何を変えるか | 引数の形 | 語源 |
|---|---|---|---|
| **`chown`** | **誰の物か**(所有者とグループ) | `chown 所有者:グループ` | change **own**er |
| **`chmod`** | **どう扱えるか**(rwx の 9 ビット) | `chmod 640` / `chmod +x` | change **mod**e |

`chmod` にユーザー名を渡しても、解釈のしようがないので構文エラーになる:

```
$ chmod masanoriadachi:masanoriadachi demo.txt
chmod: invalid mode: ‘masanoriadachi:masanoriadachi’
```

**この 2 つは必ずセットで使う。** `chown` で「誰の物か」を決め、`chmod` で「その誰に何を許すか」を決める。片方だけでは意味をなさない:

```bash
sudo chown root:appuser /etc/myapp/env   # 誰の物かを決める
sudo chmod 640          /etc/myapp/env   # その誰に何を許すかを決める
```

#### 「このユーザーとこのユーザーに読ませたい」はどう書くか

**`chown` では書けない。** ファイルが記録できる「誰」は **所有者 1 人・グループ 1 つ・その他**の 3 枠しかなく、ユーザーを 2 人並べる場所がない。実際に試すと弾かれる:

```
$ chown masanoriadachi:daemon:nobody demo.txt
chown: invalid group: ‘masanoriadachi:daemon:nobody’
```

3 つ並べると、**2 つ目以降がまとめて「グループ名」として扱われて弾かれる**。`:` はあくまで「所有者とグループの区切り」であって、リストの区切りではない。

やり方は 3 つある。

**1. グループを使う(標準的なやり方)**

グループは**複数のユーザーを入れられる箱**なので、これが本来の解法:

```bash
sudo groupadd readers
sudo usermod -aG readers alice     # -a を忘れると既存の所属が消えるので必須
sudo usermod -aG readers bob
sudo chown root:readers secret.txt
sudo chmod 640 secret.txt
```

**注意点が 1 つ。グループを追加しても、既に動いているプロセスには反映されない。** プロセスの所属グループは**起動時に決まって固定される**ため、サービスなら `systemctl restart`、自分のシェルならログインし直すか `newgrp` が要る。

**2. ACL — 3 枠に収まらないときだけ**

「alice は読み取り、bob は読み書き」のようにユーザーごとに違う権限を与えたいなら、**ACL(アクセス制御リスト)**を使う。第 4 章で `icacls` を使って Windows の ACL を見たが、**Linux にも同じ仕組みがある**:

```bash
setfacl -m u:alice:r  secret.txt
setfacl -m u:bob:rw   secret.txt
getfacl secret.txt
```

ACL が付いたファイルは `ls -l` の権限表示の末尾に `+` が付く(`-rw-r-----+`)。

ただし**この WSL 環境には `setfacl` / `getfacl` が入っていなかった**(実測。`acl` パッケージが未導入。ファイルシステムは ext4 なのでカーネル側は対応しており、`apt install acl` で使えるようになる)。**サーバー運用でも ACL はあまり使われない** — `ls -l` の `+` を見落とすと実態が分からず、バックアップや `rsync` でオプションを付け忘れると消えるため。**まずグループで解決できないかを考えるのが定石。**

**3. その他(o)に許可を出す** — 「全員に許可」と同義なので、秘密が入るファイルでは選ばない。

#### ファイルを置いただけでは反映されない

`.service` ファイルを `/etc/systemd/system/` に置くと自動で有効になる、**わけではない**。置いたあとに 3 つのコマンドが要る。

```bash
sudo systemctl daemon-reload    # 1. systemd に設定ファイルを読み直させる
sudo systemctl enable myapp     # 2. サーバー起動時に自動で立ち上がるようにする
sudo systemctl start myapp      # 3. 今すぐ起動する
```

それぞれ役割が違う。

1. **`daemon-reload`** — systemd はファイルの変更を自動では検知しない。置いた・書き換えたあとにこれを実行しないと、古い内容のまま動き続ける(新しい systemd は `systemctl status` に「Unit file changed on disk」と警告を出してくれる)
2. **`enable`** — **これが `[Install] WantedBy=` を書く理由。** 起動時に立ち上げる登録を行う
3. **`start`** — 今このプロセスを起動する。`enable` は「次回の起動から」なので、両方必要

`enable` が何をしているかは実測すると分かりやすい。**シンボリックリンクを 1 本張っているだけ**:

```
$ ls -l /etc/systemd/system/multi-user.target.wants/
cron.service -> /usr/lib/systemd/system/cron.service
console-setup.service -> /usr/lib/systemd/system/console-setup.service
...
```

`WantedBy=multi-user.target` と書いておくと、`enable` したときに `multi-user.target.wants/` の中へこのリンクが作られる。**「multi-user 状態になったときに起動してほしいサービス」の一覧がこのディレクトリ**で、systemd は起動時にここを見る。`disable` はこのリンクを消すだけなので、設定ファイル本体は残る。

#### 起動の流れ — 何が引き金で、何が起きるのか

**起点は 2 つあり、どちらも読むファイルは同じ。**

| 起点 | 誰が起動するか | いつ |
|---|---|---|
| `sudo systemctl start myapp` | 人が手で | 今すぐ起動したいとき |
| サーバーの起動 | **systemd 自身** | `enable` 済みなら毎回 |

注意したいのは、**サーバー起動時に `systemctl start` が実行されるわけではない**こと。systemd が `multi-user.target.wants/` のリンクを見て、自分で起動する。**引き金は違うが、読まれる設定ファイルも、そこから先の流れもまったく同じ。**

その流れが次のとおり:

```
systemctl start myapp   (またはサーバー起動 → systemd が自分で)
  → systemd が myapp.service を読む
      User=appuser          … 権限を appuser に落とす
      WorkingDirectory=     … カレントディレクトリを /opt/myapp にする
      EnvironmentFile=      … /etc/myapp/env の中身を環境変数として持たせる
  → その状態で ExecStart の行を実行する
      /usr/bin/java -jar /opt/myapp/app.jar
  → java が起動し、jar の中のアプリが動き出す
  → Spring Boot が application.yml を読む
      ${DB_HOST} の穴に、systemd から渡された環境変数が入る
```

**`User=` などの行は「プロセスをどんな状態で生ませるか」を決めていて、`ExecStart=` は「何を生むか」を決めている。** 権限を落とすのもカレントディレクトリを移すのも環境変数を持たせるのも、すべて `ExecStart` の実行より**前**に済ませてある。

#### `ExecStart=` はコマンドラインそのもの

`ExecStart=` は「このファイルにこの設定を使わせる」という対応づけではなく、**systemd が実行するコマンドを 1 行で書いたもの**。

```
ExecStart=/usr/bin/java -jar /opt/myapp/app.jar
          ├────────────┤ ├──────────────────┤
          実行プログラム      それに渡す引数
```

`man systemd.service` の説明では「The first item becomes the command to execute, and the subsequent items the arguments」。つまりターミナルで手で打つ次の 1 行と同じ意味で、それを systemd に代行させているだけ。

```
$ /usr/bin/java -jar /opt/myapp/app.jar
```

ここで **`app.jar` は設定ファイルではなく、アプリのプログラム本体**である点に注意。`java` は Java のプログラムを動かす実行係で、それ単体では何もできない。`-jar /opt/myapp/app.jar` は `java` に渡す引数で「この jar に入っているプログラムを動かせ」という指示になる。`-jar` は `java` のオプションであって、**systemd は中身を解釈していない**(systemd から見ればただの文字列)。

#### 設定は 2 層に分かれている

「設定ファイル」と呼べるものが 2 つ登場するので、読む人で区別する。

| | `myapp.service` | `application.yml`(jar に同梱) |
|---|---|---|
| 読む人 | **systemd** | **Spring Boot(アプリ自身)** |
| 決めること | どのユーザーで、どのコマンドを、どんな環境で起動するか | どの DB に繋ぐか、何番ポートで待つか |
| いつ読まれるか | プロセスを起動する直前 | アプリが起動したあと |

**2 つを繋いでいるのが `EnvironmentFile=` → 環境変数 → `${DB_HOST}` という経路。** systemd はアプリの設定を直接書き換えるのではなく、環境変数として渡すところまでをやり、アプリが自分でそれを拾う。この受け渡しの仕組みは [env-vars-basics.md](./env-vars-basics.md) に整理してある。

#### `ExecStart=` で引っかかりやすい点が 2 つ

**1. シェルではない。** `man systemd.service` に明記されている。

> redirection using "<", "<<", ">", and ">>", pipes using "|", running programs in the background using "&", and other elements of shell syntax are not supported.

`ExecStart=java -jar app.jar > log.txt` のような書き方は動かない。使いたければ `ExecStart=sh -c '...'` と明示的にシェルを呼ぶ。

**2. パスは絶対パスで書く。** ここで使われるのは**自分のシェルの `PATH` ではない**。

> If the command is not a full (absolute) path, it will be resolved to a full path using a **fixed search path determined at compilation time**.

systemd 固有の固定リスト(`/usr/local/bin`、`/usr/bin`、`/bin` など)が使われるので、第 3 章で見た `fnm` の `node` のように**ユーザー配下にあるものは名前だけでは見つからない**。`ExecStart=/usr/bin/java` と絶対パスで書いているのはこのため。

#### ファイル名は自由 — ただしそれがサービス名になる

ファイル名はアプリのフォルダ名と一致している必要はない。**ファイル名から `.service` を除いた部分が、そのまま `systemctl` で指定する名前になる**というだけの関係:

```
/etc/systemd/system/myapp.service  →  sudo systemctl start myapp
```

拡張子のほうは自由ではなく、**種類を表す決められた名前**。`.service`(常駐プロセス)のほかに `.timer`(定期実行、cron の代わり)、`.socket`、`.mount` などがあり、systemd は拡張子で扱いを変える。

#### 置き場所も `/usr/bin` と `/usr/local/bin` と同じ関係になっている

`.service` ファイルの置き場所は 2 つあり、第 3 章で見た住み分けとまったく同じ構造をしている:

| ディレクトリ | 誰が置くか | この環境の実測 |
|---|---|---|
| `/usr/lib/systemd/system/` | **パッケージ管理システム**(`apt` など) | 172 個の `.service` |
| `/etc/systemd/system/` | **人間**(管理者が手で書いたもの) | 管理者が置いたファイルと、`enable` のリンク |

`/etc` 側が優先されるので、**パッケージ版の設定を上書きしたいときも `/etc` 側に置く**(元ファイルは編集しない)。`apt upgrade` で上書きされないのも `/usr/local/bin` と同じ理屈。自分のアプリの `.service` を `/etc/systemd/system/` に置くのは、この住み分けに従っている。

### 5-5. コンテナだとどう変わるか

このリポジトリは EC2 に直接置く構成ではなく、**ECS Fargate でコンテナとして動かす**(→ [docs/infrastructure/README.md](../infrastructure/README.md))。この場合、話は次のように変わる。

**変わること** — ユーザーを作る作業が `useradd` から Dockerfile の記述に移る。`/opt` に置くかどうかも意味が薄れる(コンテナのファイルシステムはそのアプリ専用で、他のアプリと共有していない)。systemd も使わず、コンテナのプロセス 1 番がアプリ本体になる。

**変わらないこと** — **root で動かさない、という原則は変わらない。** コンテナの中の root は、コンテナの外から見ると隔離されているが、それは「壁がもう 1 枚ある」だけで、壁を越える手段(カーネルの脆弱性、特権モード、ホストのボリュームマウント)は存在する。

Dockerfile では次のように書く:

```dockerfile
FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home --shell /usr/sbin/nologin appuser
COPY --chown=appuser:appuser build/libs/app.jar /app/app.jar
USER appuser
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

`USER appuser` 以降の命令と、起動されるプロセスがこのユーザーで動く。

**このリポジトリの現状**: `docker/backend/Dockerfile` と `docker/frontend/Dockerfile` には `USER` の指定がなく、**コンテナ内は root で動いている**(実測)。これは開発用コンテナとしては妥当な選択で、ホスト側のファイルを bind mount して編集する構成では、ユーザーを絞ると uid の不一致で書き込めなくなる問題が出るため。ただし**本番用イメージを作る段階では `USER` を入れる**必要がある。

## 6. 認証情報とユーザー — 分離の軸は 3 層ある

「AWS CLI などの認証情報はユーザーごとに分けるのが普通」という理解は、**事実としては正しいが、目的の捉え方を一段変えたほうがいい。**

### 6-1. `~/.aws` がユーザー配下にあるのはなぜか

AWS CLI が認証情報を `~/.aws` に置くのは、**`~` の展開先がユーザーごとに違うから、結果としてユーザーごとに別々になる**という順序。「ユーザーで分けるために `~/.aws` にしている」のではなく、「ユーザーの持ち物はホーム配下に置く」という OS の作法に従った結果、自動的に分かれている。

実測:

```
$ ls -l ~/.aws/
drwxr-xr-x 3 masanoriadachi masanoriadachi 4096 cli
-rw------- 1 masanoriadachi masanoriadachi  365 config
-rw------- 1 masanoriadachi masanoriadachi   57 credentials
drwxr-xr-x 3 masanoriadachi masanoriadachi 4096 sso
```

`credentials` と `config` が `-rw-------`(600) = **root を除いて所有者以外は読めない**。

この 600 は偶然ではない、と実測から言える。**この環境の `umask` は 022 なので、普通にファイルを作れば 644 になるはず**だからだ。実際、同じ階層のディレクトリは `drwxr-xr-x`(755)で umask どおりの値になっている。ファイルだけが 600 になっているのは umask では説明がつかないので、**AWS CLI 側がファイルを作るときに権限を明示的に設定していると考えられる**(AWS CLI v2 は実行ファイルに固めて配布されているためソースでは確認できなかった。ここは実測からの推論)。

同じことは SSO のトークンキャッシュでも起きている:

```
$ ls -ld ~/.aws/sso/cache; ls -l ~/.aws/sso/cache/
drwxr-xr-x 2 masanoriadachi masanoriadachi 4096 /home/masanoriadachi/.aws/sso/cache
-rw------- 1 masanoriadachi masanoriadachi 3665 774ba089....json
```

ディレクトリは 755、中の JSON は 600。**秘密が入るファイルだけを狙って絞っている**という意図が読み取れる。

では、755 のままのディレクトリは危なくないのか。**ディレクトリの権限だけ見れば確かに緩い**が、それでも安全なのは、第 4 章で見たとおり親の `/home/masanoriadachi` が 750 で他ユーザーを弾いているから。

**権限は上から順に評価される。** `/home/masanoriadachi` に入れなければ、その下がどうなっていようと辿り着けない。逆に言うと、**ホームディレクトリの権限を緩めると下の対策がまとめて無意味になる**ので、`chmod 755 ~` のようなことは軽率にやらない。

### 6-2. 分離の軸は 3 層ある

「認証情報を分ける」と言ったとき、実際には次の 3 つの別々の軸がある。混同しやすいので分けて考える。

| 層 | 分離する単位 | 何のためか | 使う場面 |
|---|---|---|---|
| ① OS ユーザー | 人 | **人同士**が互いの認証情報を読めないようにする | 1 台を複数人で使う、踏み台サーバー |
| ② プロファイル | 権限セット・環境 | **1 人が**本番と検証、複数アカウントを取り違えないようにする | 個人の開発 PC。ほぼ全員に該当 |
| ③ ロール(置かない) | — | **そもそもディスクに長期の秘密を残さない** | サーバー、CI/CD |

**①は「複数人が同じ OS を使う」ときにだけ意味を持つ。** 個人の PC で自分しかログインしないなら、OS ユーザーをさらに分ける必要はない — すでに分かれている状態が既定なので、追加で何かする話ではない。

**個人開発で日常的に使うのは②。** `~/.aws/config` に複数のプロファイルを書き、`--profile` で切り替える:

```bash
aws sts get-caller-identity --profile masanori-sso
```

このリポジトリでの具体的な設定手順は [docs/setup/new-machine.md](../setup/new-machine.md) に書いてある。

**そして、サーバーと CI での正解は③。**

### 6-3. サーバー・CI では「置かない」が正解

EC2 やコンテナ、GitHub Actions に**長期のアクセスキーをファイルとして置くのは避ける**。理由は単純で、失効しない秘密がディスクに残り続けるから。漏れたときに被害が止まらず、定期的な差し替えも運用の負担になる。

代わりに、実行環境そのものに権限を持たせる:

| 実行環境 | 使う仕組み | 認証情報の形 |
|---|---|---|
| EC2 | インスタンスプロファイル(IAM ロール) | メタデータサービスから一時認証情報を自動取得 |
| ECS / Fargate | タスクロール | 同上。コンテナに自動で渡る |
| GitHub Actions | OIDC | 実行のたびに短命の一時認証情報を発行 |
| 開発 PC | IAM Identity Center(SSO) | 数時間で失効するトークンをキャッシュ |

いずれも AWS SDK / CLI が自動的に拾いに行くので、**アプリ側のコードは何も変えなくていい**。`~/.aws/credentials` があればそれを、なければメタデータサービスを、という順で探す仕組み(認証情報プロバイダチェーン)になっている。

**このリポジトリは③を採用している。** GitHub Actions から AWS への認証はアクセスキーではなく OIDC で行う(→ [docs/infrastructure/README.md](../infrastructure/README.md))。開発 PC 側も SSO を優先しており、理由は同じ — ディスクに残るのが失効済みトークンだけになるため(→ [docs/setup/new-machine.md](../setup/new-machine.md))。

### 6-4. だから、質問への答えは

「認証情報はユーザーごとに分けるのが普通ですよね？」に対しては:

- **手元の PC では、そうなっている** — ただし意図して分けているというより、`~` 配下に置く作法の結果としてそうなる。追加でやるべきことは「ホームディレクトリの権限を緩めない」くらい
- **1 人で使う PC で本当に必要なのは、OS ユーザーの分割ではなくプロファイルの分割** — 本番と検証を取り違えないこと、が実際のリスク
- **サーバー・CI では「ユーザーごとに分ける」という発想自体を使わない** — 置かないのが正解

## まとめ

| 質問 | 答え |
|---|---|
| OS はユーザーごとにフォルダを作る方式か | そう。ただしユーザー = 人ではなく「権限の主体」。28 個中 25 個はログインできないユーザーだった(実測) |
| なぜユーザーが分かれているのか | 人を分けるためではなく**権限を分ける**ため。隔離・責任追跡・最小権限の 3 つ |
| アプリをどのユーザーで動かすか選べるのか | **選べる。ただし選べるのは root だけ。** プロセスの uid は親から引き継がれ、変更できるのは root のみ。systemd の `User=` がその実例 |
| プロセスの実行ユーザーとファイルの権限は別物か | **完全に別物。** 前者はプロセスの属性、後者は inode の属性。**アクセスのたびにカーネルが両方を突き合わせる**ので、片側だけ整えても動かない |
| `ps` の実行ユーザー情報は Windows だとどれか | **タスクマネージャーの「詳細」タブの「ユーザー名」列**(`tasklist /v`)。ただし昇格していないと他ユーザーのぶんは `N/A` になる(実測)。Linux の `ps` は既定で全部見える |
| `chown root:appuser` は appuser ユーザーに権限を与えているのか | **与えていない。** `appuser` はここでは**グループ名**。読めるのは、`useradd` がユーザーと同名のグループを作って所属させているから(実測: `USERGROUPS_ENAB yes`) |
| `chmod appuser:appuser` ではダメなのか | **ダメ。構文エラーになる**(実測)。`chown` が「誰の物か」、`chmod` が「どう扱えるか」を変えるコマンドで、対象が違う。必ずセットで使う |
| `chown appuser:appuser` + `chmod 440` ならよいか | **破れる**(実測)。**所有者は `chmod` で自分の鍵を外せる**ので、緩めてから書けばいい。さらに 440 でも、置いてあるディレクトリに書ければ**削除**できる。**権限ビットを守っているのは所有権のほう** |
| 「このユーザーとこのユーザーに読ませる」は書けるか | **`chown` では書けない**(枠が 3 つしかない)。**グループを使う**のが標準。ユーザーごとに違う権限を与えたいときだけ ACL(`setfacl`) |
| ユーザー別インストールと全体インストールの違い | 書き込む先が共有領域(要管理者権限)かユーザー配下(不要)か。それだけ |
| サーバー配置でもユーザーを作るのか | 作る。ただし `nologin` の**ログインできない専用ユーザー**。root で動かさないため |
| `C:\` 直下に作ると他ユーザーから見られるか | 見られる。読めるうえに**書き換えもできる**(実測)。`C:\Users\<自分>` は他の一般ユーザーが入れない(実測) |
| ユーザー配下に作るほうがいいか | 通常はそう。共有が目的でないなら `C:\` 直下は選ばない |
| 認証情報はユーザーごとに分けるのが普通か | 手元では結果的にそうなる。ただし実際に効くのはプロファイル分割で、サーバー・CI では**置かない**のが正解 |

## 用語集

- **uid / gid** — Linux がユーザーとグループを識別する数値。名前は表示用で、権限判定に使われるのはこの数値のほう
- **SID** — Windows でユーザーやグループを識別する文字列(`S-1-5-21-...`)。uid に相当する
- **プロセスの uid** — 動いているプログラムが「誰として」動いているかを表す ID。**親プロセスから引き継がれ、変更できるのは root だけ**。プログラムの性質ではなく起動のされ方で決まる
- **権限判定の打ち切り** — カーネルは所有者 → グループ → その他の順に見て、**最初に一致した枠だけ**で判定する。所有者に一致したらグループの権限は見ないので、「所有者だけが読めないファイル」が作れてしまう
- **所有権は権限ビットより上位** — `chmod` を実行できるのは所有者か root だけなので、**所有者は自分にかけた制限をいつでも外せる**。「読ませるが書かせない」を成立させるには、権限ビットを絞るのではなく**所有者を相手にしない**(root 所有にする)必要がある
- **削除はディレクトリの権限で決まる** — ファイルを消せるかどうかは、そのファイルの `w` ではなく**置いてあるディレクトリの `w`** で決まる。だから `440` のファイルでも、書けるディレクトリにあれば消される。設定ファイルを root 所有の `/etc` 配下に置くのはこのため
- **`chown` と `chmod`** — `chown`(change owner)は**誰の物か**を、`chmod`(change mode)は**どう扱えるか**を変える別のコマンド。`chown 所有者:グループ`、`chmod 640` と引数の形も違い、必ずセットで使う
- **ユーザープライベートグループ** — `useradd` がユーザーと同時に作る、同じ名前のグループ(`/etc/login.defs` の `USERGROUPS_ENAB yes` による)。`chown root:appuser` の `appuser` は**このグループ**であって、ユーザーの `appuser` ではない
- **主グループ / 補助グループ** — 主グループは `/etc/passwd` の 4 番目のフィールド(gid)で指定し、`/etc/group` のメンバー欄には現れない。補助グループは `/etc/group` のメンバー欄に並び、`usermod -aG` で追加する。`id` を打つと両方まとめて見える
- **`usermod -aG`** — 補助グループを追加するコマンド。**`-a` を忘れると既存の所属が消える**。また**既に動いているプロセスには反映されない**(所属グループは起動時に固定される)ので、サービスなら再起動が要る
- **POSIX ACL(`setfacl` / `getfacl`)** — 所有者・グループ・その他の 3 枠に収まらない権限を、ユーザーごとに名指しで設定する Linux の仕組み。Windows の ACL に相当する。ACL が付くと `ls -l` の末尾に `+` が出る。運用では見落としやすいので、まずグループで解決できないか考える
- **`NT AUTHORITY\SYSTEM`** — Windows でサービスを動かす特権アカウント。Linux の `root` に相当する。権限を絞った `LOCAL SERVICE` / `NETWORK SERVICE` もあり、これは `www-data` に近い位置づけ
- **システムユーザー** — 人間ではなくプログラムを動かすために存在するユーザー。uid が 1000 未満で、ログインシェルに `nologin` が設定される(`www-data`、`syslog` など)
- **ログインシェル** — そのユーザーでログインしたときに起動されるプログラム。`/etc/passwd` の 7 番目のフィールド。`nologin` を指定するとログインできなくなる
- **ホームディレクトリ** — そのユーザーの作業領域。`/etc/passwd` の 6 番目のフィールドで、環境変数 `HOME`(Windows は `USERPROFILE`)に入り、`~` の展開先になる
- **`DIR_MODE` / `HOME_MODE`** — 新規ユーザーのホームに付ける権限の既定値。`adduser` は `adduser.conf` の `DIR_MODE`、`useradd` は `login.defs` の `HOME_MODE` を見る。Ubuntu 21.04 から 0750
- **UAC(ユーザーアカウント制御)** — Windows が管理者ユーザーにも普段は管理者権限を外したトークンを渡す仕組み。昇格して初めて Administrators が有効になる
- **ACL(アクセス制御リスト)** — Windows の権限の実体。「誰に何を許可するか」の並び。`icacls` で確認できる
- **継承(ACL の)** — 親フォルダの許可が配下に自動で適用される仕組み。`(OI)(CI)` が継承の指定、`(I)` は継承された結果であることを示す
- **スティッキービット** — 誰でも書けるディレクトリで「自分が作ったファイルしか消せない」制約を加える権限。`ls` で末尾が `t` になる(`/tmp` の `drwxrwxrwt`)
- **`umask`** — 新しく作るファイル・ディレクトリから落とす権限のマスク。022 ならファイルは 644、ディレクトリは 755 になる
- **`/opt` と `/usr/local`** — パッケージ管理の外で手動インストールしたものを置く共有領域。`/opt` はディレクトリ一式、`/usr/local/bin` は単体の実行ファイル向け。`/usr/bin` は `apt` の管理領域なので使わない
- **`PATH`** — 実行ファイルを探すディレクトリの並び。前から順に探し、最初に見つかったものが実行される。`/usr/local/bin` が `/usr/bin` より前にあるので、手で入れたものがパッケージ版より優先される
- **`which -a`** — 同名のコマンドを PATH 上から全部挙げる。バージョンが食い違うときの調べ方
- **サービス専用ユーザー** — アプリ 1 つのために作る、ログインできないシステムユーザー。乗っ取られたときの被害範囲を縛るために使う
- **ユニットファイル(`.service`)** — systemd にサービスの起動方法を教える設定ファイル。拡張子が種類を表し(`.service` / `.timer` など)、ファイル名から拡張子を除いた部分が `systemctl` で使うサービス名になる
- **`daemon-reload` / `enable` / `start`** — ユニットファイルを置いたあとに要る 3 手順。順に「設定を読み直す」「起動時に立ち上げる登録(`WantedBy` の `.wants` へシンボリックリンクを張る)」「今すぐ起動する」。**置いただけでは反映されない**
- **`ExecStart=`** — systemd が実行するコマンドラインそのもの。最初の項目が実行プログラム、残りがその引数。シェルではないのでパイプやリダイレクトは使えず、パスは絶対パスで書く
- **systemd の `User=`** — サービス定義でプロセスの実行ユーザーを指定する行。systemd 自体は root で動くが、起動直前に権限をこのユーザーへ落とす
- **プロファイル(AWS CLI)** — `~/.aws/config` に複数書ける設定の束。`--profile` で切り替える。1 人が複数アカウント・複数環境を取り違えないための仕組み
- **インスタンスプロファイル / タスクロール** — EC2 や ECS に IAM ロールを紐付ける仕組み。長期の秘密鍵をディスクに置かず、一時認証情報が自動で渡る
- **認証情報プロバイダチェーン** — AWS SDK / CLI が認証情報を探す順序の決まり。環境変数 → `~/.aws` → メタデータサービスと順に探すので、置き場所が変わってもアプリのコードは変えなくてよい

## 関連

- **この章(第 5 章)の続き — 配置から公開・更新・運用までの通しの手順** → [deploying-to-bare-linux.md](./deploying-to-bare-linux.md)(Spring Boot と Nginx + Laravel の 2 通り)
- 権限そのものの仕組み(`rwx` がどこに記録されるか、`umask`、git とファイルモード) → [file-permissions-and-exec-bit.md](./file-permissions-and-exec-bit.md)
- 環境変数と `.env`、秘密情報をプロセスへ渡す方法 → [env-vars-basics.md](./env-vars-basics.md)
- AWS CLI / SSO の設定手順と、`~/.aws` が Windows 側へのリンクになる問題 → [docs/setup/new-machine.md](../setup/new-machine.md)
- GitHub Actions から AWS への OIDC 認証(この文書の「置かない」の実例) → [docs/infrastructure/README.md](../infrastructure/README.md)
- docker-compose 開発環境の 5 コンテナ構成と環境変数の方針 → [docs/development/README.md](../development/README.md)
