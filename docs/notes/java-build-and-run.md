# Java の実行環境とビルドの仕組み — eclipse-temurin、JVM、bootRun

`docker/backend/Dockerfile` のベースイメージ `eclipse-temurin:21-jdk` とは何か、Java の「ビルドしてから動かす」は開発中どうなるのか、そして開発と本番でビルド・起動のコマンドがどう違うのか、についての学習メモ。PHP / Node 出身者向け。

## eclipse-temurin とは — 「Java 公式」が一枚岩ではない話

Java の世界は npm や PHP と事情が違い、**同じ設計図（OpenJDK のソースコード）から複数の団体がそれぞれ「ビルド済み JDK」を配布**しています。Oracle 製、Amazon 製（Corretto）、Microsoft 製、そして Eclipse Adoptium 製の **Temurin**。どれも同じ互換性テストに合格した「中身は実質同じ」JDK で、パン屋に例えると「同じレシピで焼いた別の店のパン」です。

Temurin が定番になっている理由:

- **無料でライセンスの心配がない**（Oracle 製は商用利用の条件が何度も変わって皆が疲れた歴史がある）
- **Docker Hub の「Docker Official Images」認定**を受けている。かつての `openjdk` 公式イメージは 2022 年に非推奨（deprecated）になり、公式ドキュメントが乗り換え先として eclipse-temurin などを案内している

つまり「Oracle 製の公式イメージ」ではないが、「Docker 公式イメージであり、Java コンテナの事実上の標準」です。

### タグ `21-jdk` の意味

- **21** — Java 21（LTS = 長期サポート版）
- **jdk** — コンパイラ（javac）込みの**開発キット**。実行専用の縮小版 **JRE**（`21-jre`）と区別される

開発コンテナはコンテナ内でコンパイルするので JDK 版が必須。本番用 Dockerfile を作るときは「ビルドは JDK、実行イメージは JRE（小さい）」と分ける**マルチステージビルド**が定石になります。

## 「ビルドしてから各 OS で動かす」の正体

```
PHP / Node:  ソースコード ──そのまま──→ 実行系が直接解釈
Java:        ソースコード(.java) ──コンパイル──→ バイトコード(.class) ──→ JVM が実行
```

コンパイル結果は OS ごとの実行ファイルではなく**バイトコード**という中間形式です。OS ごとの差は **JVM（Java 仮想マシン）**が吸収します。「各 OS の実行環境で動かす」とは「OS ごとに用意された JVM の上で、同じバイトコードを動かす」という意味です（Write once, run anywhere）。

## 開発中もコードを変えるたびにビルドが必要？

**必要です。そして保存しただけでは何も起きません。** 「ホストで保存 → バインドマウントでコンテナ内のソースも即変わる」までは自動ですが、そこから先が Nuxt とは違います。

### bootRun は「監視員」ではなく「一発実行のコマンド」

`npm run dev`（nuxt dev）と見た目が似ているので同じ性質だと思いやすいのですが、性格が違います。

| | `nuxt dev` | `./gradlew bootRun` |
|---|---|---|
| 起動時 | dev サーバーを立てる | 差分コンパイル → アプリ起動 |
| 起動後にファイルを保存すると | **監視していて自動反映**（ホットリロードが本業） | **何も起きない**（Gradle の仕事は起動時に終わっていて、動いているのは Spring Boot アプリだけ） |

レストランに例えると、bootRun は「注文を受けて料理して配膳したら仕事終了のコック」、nuxt dev は「席に張り付いていておかわりに即応える給仕」です。bootRun の差分コンパイル（変更されたファイルだけ再コンパイル）が走るのは**コマンドを実行したその瞬間に一回だけ**です。

### 「保存 → 反映」に必要な 2 段階

変更が動いているアプリに反映されるまでの経路を分解するとこうなります。

```
① ソース(.java)の変更を検知して コンパイル する係   ← この構成では不在！
        ↓ .class(コンパイル済みファイル)が変わる
② .class の変化を検知して アプリを再起動する係       ← spring-boot-devtools(居る)
```

`backend/build.gradle` に入っている `spring-boot-devtools`（`developmentOnly` スコープ)が見張っているのは**ソースではなくコンパイル結果（.class）**です。つまり後半②の係は常駐しているのに、前半①の「保存を検知してコンパイルする係」がコンテナ + bootRun だけの構成にはいません。バインドマウントが運んでくるのはソースであって、コンパイル結果ではないからです。

### 反映させる方法は 2 つ

1. **素朴な方法: コンテナを再起動する**（`docker compose restart backend`）。bootRun がやり直され、そのとき差分コンパイルが走る
2. **①の係を追加で雇う方法: 継続ビルドをもう 1 プロセス動かす。** 別ターミナルで `./gradlew compileJava --continuous`（`--continuous` = ソースを監視して変わるたびにタスクを再実行するモード）を実行しておくと、これが .class を更新し続け、devtools がそれを拾ってアプリを高速再起動する。IntelliJ などの IDE の「保存時自動コンパイル」を使う場合は、**IDE が①の係を務めている**という構図

まとめ: 「ビルドは必要。マウントが運ぶのはソースだけ。反映には『コンパイルする係』を誰かが務める必要があり、それが再起動か、継続ビルドか、IDE か、の違い」。

### このリポジトリの採用構成 — ①を Dev Container 内の VS Code が務める

このリポジトリは **Dev Container 方式**を採用した。VS Code で backend コンテナの中に入って開発すると(`.devcontainer/devcontainer.json`)、コンテナ内の Java 拡張が保存時に自動コンパイルして①を務め、devtools(②)がそれを拾って再起動する。CMD は `sh ./gradlew bootRun` のままなので、VS Code を開いていないときは方法 1(restart)に自然に戻るだけで壊れない。候補に挙がった他の手法(継続ビルド常駐・ホスト IDE など)との比較と選定理由は [java-dev-env-comparison.md](./java-dev-env-comparison.md) を参照。

#### ①が働くのは「Dev Container の VS Code で保存したとき」だけ

ここでいう自動コンパイルのトリガーは **VS Code エディタ上の保存操作**であって、ワークスペースのファイル変更検知ではない。そのため**ホスト側のエディタや Claude がファイルを書き換えた場合(バインドマウントでコンテナ内のソースは更新される)でも、①は働かない**。

- Dev Container を起動したまま、ホスト側から `Application.java` を書き換えて 40 秒待っても `build/classes/java/main/.../Application.class` の更新時刻は変わらなかった(新しい .class はどこにも書き出されない)
- 変更通知自体はコンテナまで届いている。コンテナ内で Java の `WatchService`(Linux では inotify)にソースディレクトリを監視させると、ホスト側の編集で `ENTRY_MODIFY` が発火する。届かないのではなく、言語サーバーが保存操作以外ではビルドを起動しない

.class が更新されないので devtools(②)も拾うものがなく、アプリは再起動しない。ホスト側から編集したときは `docker compose exec backend sh ./gradlew classes`(または `docker compose restart backend`)で①を代行する必要がある。CLAUDE.md にある「Claude が backend の Java を編集したら `gradlew classes` を実行する」ルールはこのため。

## `bootRun` タスクと Gradle Wrapper

- **`./gradlew`（Gradle Wrapper）** — Gradle 本体をインストールしていなくても、初回に正しいバージョンを自動ダウンロードして実行するスクリプト。ダウンロードした本体は docker-compose の `gradle-cache` ボリュームに保存され、コンテナを作り直しても再ダウンロードされない（Gradle そのものと キャッシュの置き場所の詳細 → [gradle-basics.md](./gradle-basics.md)）
- **`bootRun`** — Spring Boot の Gradle プラグインが提供するタスク。「コンパイル → 組み込み Tomcat ごとアプリを起動」まで一気にやる。ポート 8080 で待ち受け（EXPOSE 8080 / compose の `"8080:8080"`）
- CMD の先頭に `sh` が付いている理由（実行権限の保険）は [docker-dev-containers.md](./docker-dev-containers.md) を参照

## 開発と本番 — 4 つのコマンドの違い

ビルドと起動には、開発と本番でそれぞれ別のコマンドを使う。組み合わせは 4 つ。

| | 開発 | 本番 |
|---|---|---|
| **ビルド** | `./gradlew classes` | `./gradlew bootJar` |
| **起動** | `./gradlew bootRun` | `java -jar app.jar` |

このリポジトリでの実際の打ち方:

```bash
# 開発(リポジトリ直下から。コンテナ内で実行される)
docker compose exec backend sh ./gradlew classes   # 反映だけ。アプリは止めない
docker compose up -d backend                       # 起動(CMD が bootRun)

# 本番(docker/app/Dockerfile の中で走る)
sh ./gradlew bootJar        # ステージ2
java -jar /app/app.jar      # ステージ3(ENTRYPOINT)
```

### 開発のビルド — `./gradlew classes`

コンパイルとリソースのコピーだけを行い、起動はしない。中身は 2 つのタスクの束:

```
compileJava      src/main/java/**/*.java  → build/classes/java/main/
processResources src/main/resources/**    → build/resources/main/
```

`classes` はこの 2 つをまとめた集約タスクで、それ自体は何もしない。**`src/main/resources/` の中身は .java ではないのでコンパイルされず、そのままコピーされるだけ**という点に注意(`application.yml` や `static/` がこれにあたる)。

差分コンパイルなので、変更したファイルだけが再コンパイルされる。上の①(コンパイル係)を手で代行するのがこのコマンドで、`.class` さえ更新すれば②の devtools が拾って再起動するため、**アプリを止める必要はない**。

### 開発の起動 — `./gradlew bootRun`

`classes` を実行してから、Gradle が JVM を子プロセスとして起動する。渡されるクラスパスは 3 種類:

```
build/classes/java/main/       ← ばらの .class(フォルダのまま)
build/resources/main/          ← application.yml, static/ など
~/.gradle/caches/.../*.jar     ← 依存ライブラリ(gradle-cache ボリューム)
```

**jar は作らない。** ばらのフォルダと、キャッシュに散らばった依存 jar を直接クラスパスに並べているだけで、`bootRun` しか使っていない限り `backend/build/libs/` には何も生まれない。

devtools は `developmentOnly` スコープなのでこのクラスパスに含まれ、常駐して `build/classes` を監視する。

### 本番のビルド — `./gradlew bootJar`

`classes` の結果を 1 個の**実行可能 jar**(`build/libs/app-0.0.1-SNAPSHOT.jar`)に梱包する。依存ライブラリまで同梱されるので、これ 1 つで起動できる(jar の中身の詳細 → [backend-project-files.md](./backend-project-files.md))。

`docker/app/Dockerfile` が `build` ではなく `bootJar` を使っているのには理由が 2 つある。

- **`build` は `test` タスクに依存している。** ビルドコンテナに MySQL が居ないのでテストが接続エラーで落ち、イメージが作れなくなる(テストは専用の `app_test` データベースを使う → [../test/README.md](../test/README.md))
- **`build` は jar を 2 個作る。** 実行可能 jar に加えて自分のクラスだけの `-plain.jar` も生成されるため、Dockerfile の `COPY .../build/libs/*.jar` がどちらを拾うか曖昧になる。`bootJar` なら 1 個しかできない

### 本番の起動 — `java -jar app.jar`

Gradle は一切登場しない。実行イメージも `21-jre` でコンパイラが無い。JVM が jar 内の `MANIFEST.MF` を読み、Spring Boot の loader が同梱の依存ごとクラスパスを組み立てて起動する。

`ENTRYPOINT` が `sh -c "exec java ..."` になっているのは、`exec` で `java` を PID 1 にするため。付けないと `sh` が PID 1 になり、ECS がタスクを止めるときの SIGTERM が `java` に届かない。

### 違いのまとめ

| 観点 | 開発 | 本番 |
|---|---|---|
| JVM を起動するのは | Gradle(子プロセスとして) | 直接 |
| クラスパスの形 | ばらのフォルダ + キャッシュ内の jar | jar 1 個の内部 |
| `classpath:/static/` の実体 | `build/resources/main/static/` | jar 内の `BOOT-INF/classes/static/` |
| 依存ライブラリの置き場 | `~/.gradle/caches/`(gradle-cache ボリューム) | jar に同梱 |
| devtools | 有効(自動再起動) | 含まれない |
| 必要なもの | JDK + Gradle + ソース | JRE + jar だけ |
| コード変更の反映 | `classes` → devtools が再起動 | イメージ再ビルド |

開発は道具一式を持ち歩き、本番は成果物だけを持っていく、という違い。`docker/app/Dockerfile` のステージ 3 が `21-jre` で、ソースも Gradle も残っていないのがその現れ。

### 注意点

- **`backend/src/main/resources/static/` は開発では空。** Nuxt の SSG 出力が入るのは `docker/app/Dockerfile` の `COPY --from=frontend` の瞬間だけ。そのため `backend/src/main/java/com/example/app/config/StaticResourceConfig.java` の静的リソース配信設定が実際に働くのは本番 jar でだけで、開発時のフロントは nuxt コンテナ(devProxy 経由)が担当する
- **`build.gradle` を変えたときは `classes` では足りない。** 依存のクラスパスは起動時に固定されるので `docker compose restart backend` が必要(→ [java-dev-env-comparison.md](./java-dev-env-comparison.md))

## 落とし穴

- **初回の `docker compose up` はかなり待たされる。** Gradle 本体のダウンロード → 依存ライブラリ全取得 → 全ファイルコンパイル → 起動、と続くため。2 回目以降は gradle-cache が効いて速くなる。「壊れた?」と思う前にログを見る
- **devtools は本番に入らない。** `developmentOnly` スコープのおかげで `./gradlew bootJar`（`build`）で作る本番用 jar には含まれない。スコープ（[gradle-dependencies.md](./gradle-dependencies.md) 参照）が「開発用の仕掛けを本番に紛れさせない」仕事をしている例
- **`21-jre` にすると開発コンテナは動かない。** コンパイラがないので bootRun のコンパイル段階で失敗する。JRE が活きるのは実行だけを行う本番イメージ

## 用語集

- **OpenJDK** — Java のオープンソース実装。各社がこれをビルドして配布する
- **Temurin / Eclipse Adoptium** — OpenJDK のビルド配布プロジェクト。旧 AdoptOpenJDK。事実上の標準
- **JDK / JRE** — 開発キット（コンパイラ込み）/ 実行環境のみ
- **LTS** — 長期サポート版。Java 21 はこれ
- **バイトコード** — コンパイル結果の中間形式。OS 非依存で、JVM が実行する
- **JVM（Java 仮想マシン）** — バイトコードを各 OS 上で実行する層。「どこでも動く」の実現装置
- **差分コンパイル** — 変更されたファイルだけを再コンパイルする仕組み。毎回のビルドを速くする
- **spring-boot-devtools** — クラス（.class）の変化を検知してアプリを高速再起動する開発ツール。ソースは見ていない
- **bootRun** — コンパイル + Spring Boot アプリ起動を行う Gradle タスク。実行時に一回だけ働き、ファイル監視はしない
- **compileJava** — コンパイルだけを行う Gradle タスク（bootRun はこれ + 起動を含む上位タスク）
- **processResources** — `src/main/resources` の中身をコンパイルせず `build/resources/main` へコピーする Gradle タスク
- **classes** — compileJava + processResources をまとめた集約タスク。「ビルドだけして起動はしない」がこれ
- **bootJar** — 依存ライブラリごと 1 個に固めた実行可能 jar を作る Spring Boot の Gradle タスク
- **実行可能 jar（fat jar）** — 依存ライブラリまで同梱していて `java -jar` 単体で起動できる jar。Spring Boot が作るのはこれ
- **継続ビルド（`--continuous`）** — ソースを監視し、変化のたびに指定タスクを自動再実行する Gradle のモード
- **マルチステージビルド** — 「ビルド用イメージ」と「実行用イメージ」を分ける Dockerfile の書き方

## 関連

- Dockerfile・CMD・マウントの設計 → [docker-dev-containers.md](./docker-dev-containers.md)
- build.gradle と依存管理・スコープ → [gradle-dependencies.md](./gradle-dependencies.md)
- jar の正体（.class を固めた zip）と、成果物 jar / wrapper jar の違い → [backend-project-files.md](./backend-project-files.md)
- ①コンパイル係を誰に任せるかの手法比較と Dev Container 採用理由 → [java-dev-env-comparison.md](./java-dev-env-comparison.md)
- 言語でビルドの要否・補完の仕組みが変わる理由(PHP / Node との比較の続き) → [build-and-tooling-by-language.md](./build-and-tooling-by-language.md)
