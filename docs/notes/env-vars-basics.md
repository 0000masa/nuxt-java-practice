# 環境変数と設定ファイルの基礎 — .env は誰が読むのか、Docker なしではどう渡すのか

`application.yml` の `${DB_USER:app}` はどこから値を得るのか。「設定ファイルにはビルド時用と実行時用がある」「そもそも環境変数とは何か」「.env ファイルとの関係」「Docker(compose / ECS)を使わない構成ではどう渡すのか」をまとめた学習メモ。このリポジトリでの**方針**(.env で一元管理・本番は ECS 注入)は [development/README.md](../development/README.md) の「環境変数の方針」を参照。ここでは**仕組み**を扱う。

## 前提: 設定ファイルは 2 種類 — ビルド時(Gradle)と実行時(Spring Boot)

backend には「設定ファイル」と呼べるものが 3 つあるが、**読む人と読まれるタイミング**で 2 グループに分かれる。読まれる順番に並べると:

```
【ビルド時】Gradle(ビルドツール)が読む —「アプリを作る工程」への指示
  1. settings.gradle   このビルドの名前は?(どのプロジェクトが参加する?)
  2. build.gradle      どう作る? — Java 21 で、これらの依存ライブラリを使って…
        ↓ コンパイル・jar 詰め
【実行時】Spring Boot(アプリ本体)が読む —「アプリが動くとき」への指示
  3. application.yml   どう動く? — この DB に繋いで、8080 番で待ち受けて…
```

両者の決定的な差は **jar の中に入るかどうか**:

- `application.yml` は `src/main/resources/` にあり、ビルド時に **jar へ同梱され、アプリと一緒に本番環境まで配布される**(だから実行時に読める)
- `settings.gradle` / `build.gradle` は jar に**入らない**。ビルド環境でだけ使われるファイルで、本番コンテナに Gradle も build.gradle も存在しないのはこのため

Laravel でいうと `build.gradle` ≈ `composer.json`(依存ツールが読む)、`application.yml` ≈ `config/` 一式(アプリが実行時に読む)。settings.gradle に当たるものは PHP にはほぼ無い(ビルド工程自体が無いから)。

紛らわしい点を 2 つ:

- `settings.gradle` の `rootProject.name = 'app'` と `application.yml` の `spring.application.name: app` は、偶然同じ `app` だが**別物**。前者は**ビルド成果物の名前**(jar が `app-0.0.1-SNAPSHOT.jar` になる)、後者は Spring が実行時にログ等で名乗るアプリ名
- **変更の反映方法も別**。`application.yml` はアプリの再起動(devtools の高速 restart)で反映されるが、`.gradle` 側は**ビルドのやり直し**が必要(このリポジトリでは `docker compose restart backend`)

そして本題との接続: **環境変数で外から値を差し替えられるのは「実行時」側だけ。** `${DB_HOST:localhost}` の穴が開いているのは application.yml であって、build.gradle に DB 接続情報を書きたくなったら領分違い(ビルド作業への指示ではなく、実行時の動作設定)を疑う。以降はこの「実行時側」の話。

## 環境変数の正体 — プロセス起動時に親から手渡される key=value の表

環境変数はファイルでも DB でもなく、**OS がプロセスごとに持たせている key=value の表**。重要な性質は 2 つ:

- **起動の瞬間に、親プロセスから子プロセスへコピーして手渡される。** 家を出るときに持たされる弁当のようなもので、出発(起動)後に親が中身を変えても届かない。**変更を反映するにはプロセスの再起動が必要**なのはこのため
- 手渡されたあとは**そのプロセス専用**。他のプロセスと共有していない

読む側の API は言語ごとに名前が違うだけで、やることは「自分の弁当箱を開ける」で完全に同一:

```java
System.getenv("DB_USER")   // Java
```
```php
getenv('DB_USER')          // PHP
```
```javascript
process.env.DB_USER        // Node.js
```

ここまでは **Spring Boot / Laravel / Node.js で共通**。違いが生まれるのは次の「.env ファイル」の扱い。

## .env はただのテキストファイル — 「誰が環境変数に変換するか」が三者三様

`.env` は `DB_USER=app` と並べただけのテキストファイルであり、置いただけでは環境変数にならない。**誰かが読んで、環境変数(またはそれ相当)に変換する**必要がある。その変換係が言語文化によって違う:

| | 変換係 | 仕組み |
|---|---|---|
| Laravel | **アプリ自身** | 同梱の phpdotenv が起動時に `.env` を読み、`env()` で参照できるようにする |
| Node.js | ライブラリ or 実行環境 | dotenv ライブラリ、`node --env-file`、Nuxt/Vite の内蔵機能など |
| Spring Boot | **居ない(読まない)** | アプリは本物の環境変数だけを見る。だから**外側の誰か**(compose / ECS / systemd)が変換係を務める |

文化が分かれた背景: PHP は伝統的に Web サーバーの下で動き、プロセスに環境変数を渡す設定が面倒だったため「アプリ内で .env を読む」文化が育った。Java は「起動コマンド側で環境を整えてから JVM を立てる」文化なので、Spring Boot は「環境変数は外で用意されているもの」という前提に立つ。

ここから出る実践上の結論: **「Laravel の感覚」で Spring Boot に .env を書いても、アプリは読んでくれない。** このリポジトリで `.env` が効いているのは、Spring Boot ではなく **docker compose が変換係を務めている**から。

## このリポジトリでの値の旅路

開発時(`.env` → backend コンテナ):

```
.env(リポジトリ直下。gitignore 対象)
  ↓ docker compose が読む(docker-compose.yml の env_file: .env)
backend コンテナのプロセス環境変数になる
  ↓ JVM が起動時に親(コンテナのエントリプロセス)から受け取る
Spring Boot が application.yml の ${DB_USER:app} を解決するときに参照
  ↓ 見つかれば .env の値 / 見つからなければデフォルト値 app
```

- mysql / minio コンテナへは `env_file` ではなく compose 内の `${...}` 展開で**個別マッピング**している。公式イメージは `MYSQL_USER` のような決まった変数名しか見ないため(docker-compose.yml のコメント参照)
- 本番はこの最初の 2 段が **ECS タスク定義**(環境変数の直書き、または Secrets Manager 参照)に入れ替わるだけで、`application.yml` は**一文字も変えずに**別の DB に繋がる。「設定の構造(yml・Git 管理)」と「環境ごとの値(.env / タスク定義・Git 管理外)」を分ける狙いがこれ

## Docker を使わない場合・EC2 の場合 — 「オプションで渡す」のではなく「親に持たせてから起動する」

compose も ECS も無い構成(ホストで直接 `./gradlew bootRun`、EC2 に jar を置いて動かす等)では、**起動コマンドの親となるプロセスに環境変数を持たせてから起動する**。環境変数は親→子への手渡しなので、「親を整える」が基本形。

**1. インライン指定(その 1 回の実行だけ)** — コマンドの前に `KEY=VALUE` を置く shell の記法。gradlew → JVM へと弁当が受け継がれる:

```bash
DB_HOST=127.0.0.1 DB_USER=app DB_PASSWORD=secret ./gradlew bootRun
```

**2. export(そのシェルのセッションの間)** — シェル自体の環境変数にしておき、以後の起動すべてに効かせる:

```bash
export DB_HOST=127.0.0.1
./gradlew bootRun        # このシェルから起動する限り毎回渡る
```

**3. systemd の EnvironmentFile(EC2 での定石)** — サービスとして常駐させる場合、起動係の systemd に「このファイルを環境変数として注入して起動せよ」と書く:

```ini
# /etc/systemd/system/myapp.service
[Service]
EnvironmentFile=/etc/myapp/env    # KEY=VALUE を並べたファイル(.env と同じ形式)
ExecStart=/usr/bin/java -jar /opt/myapp/app.jar
```

見比べると全部**同じ構図**であることが分かる: 「外側の起動係(shell / systemd / compose / ECS)が、環境変数を整えてから子プロセスを立てる」。ECS のタスク定義は、systemd の unit ファイルのクラウド版と捉えられる。実務の EC2 運用では `EnvironmentFile` の中身(特にパスワード)を SSM Parameter Store / Secrets Manager から配置するのが定石だが、それは「ファイルを誰が置くか」の話で、注入の仕組み自体は変わらない。

## 「オプションで渡す」は別ルート — 環境変数ではないプロパティソース

「起動オプションで渡す」方法も存在するが、それは**環境変数ではない**。Java には設定値の通り道が複数ある:

```bash
java -DDB_USER=app -jar app.jar          # JVM システムプロパティ(-D は JVM へのオプション)
java -jar app.jar --DB_USER=app          # コマンドライン引数(main の args に入る)
DB_USER=app java -jar app.jar            # 環境変数(これだけが本物の環境変数)
```

Spring Boot はこれらを**プロパティソース**(設定値の供給源)として全部受け取り、優先順位を付けて重ね合わせる:

```
高  コマンドライン引数(--xxx)
 ↑  JVM システムプロパティ(-Dxxx)
 ↑  OS 環境変数
低  application.yml に書いた値(プレースホルダのデフォルト含む)
```

`${DB_USER:app}` のプレースホルダは「環境変数専用」ではなく、**この重なり全体から DB_USER という名前を探す**。だからどのルートで渡しても効く。ただしチーム運用では「値は環境変数で渡す」に統一するのが普通(コンテナでも EC2 でも同じ渡し方になり、`ps` コマンドでパスワードが見えるコマンドライン引数の欠点も避けられる)。

なお発展として、Spring Boot には `SPRING_DATASOURCE_URL` のような**大文字+アンダースコアの環境変数名で yml のキー(`spring.datasource.url`)を直接上書きできる**変換規約(リラックスドバインディング)もある。プレースホルダを書いていないキーも外から差し替えられる、と知っておくと本番トラブル時に役立つ。

## 用語集

- **ビルド時設定 / 実行時設定** — 「アプリを作る工程」への指示(.gradle、Gradle が読む)と「アプリが動くとき」への指示(application.yml、Spring Boot が読む)。後者だけが jar に同梱される
- **settings.gradle** — Gradle が最初に読む「ビルドの入口」。プロジェクト名(= jar のファイル名の前半)を決める
- **rootProject.name / spring.application.name** — ビルド成果物の名前(ビルド時)と、Spring が名乗るアプリ名(実行時)。別物
- **環境変数** — プロセス起動時に親プロセスからコピーして手渡される key=value の表。プロセスごとに独立し、変更の反映には再起動が要る
- **.env ファイル** — KEY=VALUE を並べただけのテキストファイル。それ自体は環境変数ではなく、誰かが読んで変換する必要がある
- **phpdotenv / dotenv** — .env を読んで環境変数相当にするライブラリ(Laravel / Node)。Spring Boot に相当品は同梱されていない
- **env_file(compose)** — ファイルの中身をコンテナの環境変数として丸ごと注入する docker compose の指定
- **タスク定義(ECS)** — コンテナの起動レシピ。本番での環境変数の注入元。systemd unit のクラウド版と捉えられる
- **systemd / unit ファイル** — Linux のサービス起動係とその設定ファイル。`EnvironmentFile=` で環境変数を注入できる
- **プレースホルダ(`${VAR:default}`)** — 設定値の場所に「VAR という名前の値、無ければ default」と書く Spring の記法。Laravel の `env('VAR', 'default')` に相当
- **プロパティソース** — Spring Boot が設定値を探す供給源の総称。コマンドライン引数・システムプロパティ・環境変数・yml などを優先順位付きで重ねる
- **リラックスドバインディング** — `SPRING_DATASOURCE_URL` のような環境変数名で `spring.datasource.url` を上書きできる Spring Boot の名前変換規約

## 関連

- このリポジトリの環境変数の**方針**(.env 一元管理・二役の使い分け・本番 ECS 注入) → [development/README.md](../development/README.md)
- 言語ごとにビルド・補完の仕組みが違う話(今回の「.env を読む文化の違い」と同じ構図) → [build-and-tooling-by-language.md](./build-and-tooling-by-language.md)
- `.env` を読む compose の記述そのもの → リポジトリ直下 `docker-compose.yml`(冒頭コメントと backend / mysql の違い)
- backend 配下の各ファイル(settings.gradle / build.gradle / application.yml 含む)が「何者で誰が作るか」の図鑑 → [backend-project-files.md](./backend-project-files.md)
