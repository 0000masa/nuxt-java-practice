# Java の実行環境とビルドの仕組み — eclipse-temurin、JVM、bootRun

`docker/backend/Dockerfile` のベースイメージ `eclipse-temurin:21-jdk` とは何か、Java の「ビルドしてから動かす」は開発中どうなるのか、開発と本番でビルド・起動のコマンドがどう違うのか、テストコードがなぜ成果物に入らないのか、についての学習メモ。PHP / Node 出身者向け。

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

ただしこの 4 つは独立したコマンドではない。**開発の起動(`bootRun`)はビルドを内包しているが、本番の起動(`java -jar`)は一切ビルドしない**。この非対称は後述する。

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

**`bootRun` は `classes` に依存しているので、コンパイルも自分でやる。** 起動前に `classes` を別途打つ必要はない。`--dry-run`(タスクを実行せず、実行予定のタスクだけを表示するオプション)で確かめられる:

```
$ ./gradlew bootRun --dry-run
:compileJava :processResources :classes :resolveMainClassName :bootRun
```

コンパイルが済んだあと、Gradle が JVM を子プロセスとして起動する。渡されるクラスパスは 3 種類:

```
build/classes/java/main/       ← ばらの .class(フォルダのまま)
build/resources/main/          ← application.yml, static/ など
~/.gradle/caches/.../*.jar     ← 依存ライブラリ(gradle-cache ボリューム)
```

**jar は作らない。** ばらのフォルダと、キャッシュに散らばった依存 jar を直接クラスパスに並べているだけで、`bootRun` しか使っていない限り `backend/build/libs/` には何も生まれない。

devtools は `developmentOnly` スコープなのでこのクラスパスに含まれ、常駐して `build/classes` を監視する。

### 本番のビルド — `./gradlew bootJar`

こちらも `classes` に依存していて、コンパイルしてから 1 個の**実行可能 jar**(`build/libs/app-0.0.1-SNAPSHOT.jar`)に梱包する。依存ライブラリまで同梱されるので、これ 1 つで起動できる(jar の中身の詳細 → [backend-project-files.md](./backend-project-files.md))。

```
$ ./gradlew bootJar --dry-run
:compileJava :processResources :classes :resolveMainClassName :bootJar
```

`docker/app/Dockerfile` が `build` ではなく `bootJar` を使っているのには理由が 2 つある。

- **`build` は `test` タスクに依存している。** ビルドコンテナに MySQL が居ないのでテストが接続エラーで落ち、イメージが作れなくなる(テストは専用の `app_test` データベースを使う → [../test/README.md](../test/README.md))
- **`build` は jar を 2 個作る。** 実行可能 jar に加えて自分のクラスだけの `-plain.jar` も生成されるため、Dockerfile の `COPY .../build/libs/*.jar` がどちらを拾うか曖昧になる。`bootJar` なら 1 個しかできない

### 本番の起動 — `java -jar app.jar`

**`bootJar` は実行しない。というより実行できない。** Gradle は一切登場せず、実行イメージ `21-jre` にはコンパイラも Gradle も入っていない。JVM が jar 内の `MANIFEST.MF` を読み、Spring Boot の loader が同梱の依存ごとクラスパスを組み立てて起動するだけ。

`ENTRYPOINT` が `sh -c "exec java ..."` になっているのは、`exec` で `java` を PID 1 にするため。付けないと `sh` が PID 1 になり、ECS がタスクを止めるときの SIGTERM が `java` に届かない。

### ビルドと起動は開発と本番で非対称

「ビルド」と「起動」を 2×2 に並べたが、**開発側は起動コマンドがビルドを含み、本番側は含まない**。

```
開発: ./gradlew bootRun ──▶ classes(コンパイル)──▶ アプリ起動    1 コマンドで両方やる

本番: ./gradlew bootJar ──▶ classes ──▶ jar 生成                 ステージ2でここまで
      java -jar app.jar ──────────────────▶ アプリ起動           ステージ3。ビルドしない
```

この違いから次のことが言える。

- **開発で `classes` を単独で打つ場面は限られる。** 起動だけなら `bootRun` に任せればよい。`classes` を手で打つのは、**動いているアプリを止めずに①(コンパイル係)を代行したいとき**だけ。だから CLAUDE.md のルールは「編集したら `classes`」であって「restart しろ」ではない
- **本番は「作る」と「動かす」が別のイメージに分かれている。** `docker/app/Dockerfile` のステージ2(`21-jdk`)が jar を作り、ステージ3(`21-jre`)は完成品を受け取って起動するだけ。ステージ3 には Gradle もソースも無いので、そもそもビルドしようがない
- 「起動時にビルドが走らない」ことは本番では利点になる。**コンテナが起動するたびにコンパイルが走ったら、起動が遅くなるうえに、イメージが同じでも実行のたびに結果が変わりうる**。ビルド済みの成果物を配る形にすることで、どのタスクも同じバイトコードを動かすことが保証される

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

## テストコードは成果物に入るのか

`src/test/java/` のテストは、開発・本番どちらの成果物にも入らない。ただし「ビルド時に含まれない」は 2 つに分けて考える必要がある。

| | テストコードは |
|---|---|
| コンパイル | **される**(`./gradlew test` や `build` のとき) |
| jar への梱包 | **されない**(どのコマンドでも) |

### sourceSet による二重の分離

Gradle の `java` プラグインは `main` と `test` という 2 つの **sourceSet**(ソースの区分)を持ち、**出力先が最初から別**になっている。

```
src/main/java  ──compileJava──────▶ build/classes/java/main/   ← bootJar が梱包するのはここ
src/test/java  ──compileTestJava──▶ build/classes/java/test/   ← 梱包対象外
```

分離はもう 1 段ある。`build.gradle` の依存スコープ:

```groovy
implementation     'org.springframework.boot:spring-boot-starter-webmvc'       // 本番にも入る
testImplementation 'org.springframework.boot:spring-boot-starter-webmvc-test'  // テスト時だけ
```

`testImplementation` で宣言したものは `runtimeClasspath` に載らないため、JUnit や Mockito は jar に同梱されない。**テストコード本体とテスト用ライブラリが、別々の仕組みで弾かれている**。

### どのコマンドが test を触るか

| コマンド | test をコンパイルするか |
|---|---|
| `classes` / `bootRun` / `bootJar` | しない |
| `test` / `build` | する |

`--dry-run` で確認できる。`build` だけがテスト側のタスクを含む:

```
$ ./gradlew build --dry-run
:compileJava :processResources :classes :resolveMainClassName :bootJar
:jar :assemble :compileTestJava :processTestResources :testClasses :test :check :build
```

ここに `jar`(= `-plain.jar` を作るタスク)も見えている。前述の「`build` は jar を 2 個作るので Dockerfile では `bootJar` を使う」の裏付けでもある。

### 実測

手元の jar で確認できる。

```
$ unzip -l build/libs/*.jar | grep -ic test                      → 0 件
$ unzip -l build/libs/*.jar | grep -icE "junit|mockito|assertj"  → 0 件
$ unzip -l build/libs/*.jar | grep -c "BOOT-INF/lib/"            → 71 件(すべて本番用)
$ find build/classes/java/test -name "*.class"                   → 存在する(過去の build で生成)
```

`build/classes/java/test/` に `.class` は確かにあるのに、jar には 1 つも入っていない。

このリポジトリの本番ビルドでは、`docker/app/Dockerfile` が `COPY backend/src ./src` で **`src/test/` ごとコンテナに送っている**。それでもステージ2 で走るのは `bootJar` なのでテストはコンパイルすらされず、ステージ3 は jar だけを受け取るので痕跡が残らない。

### 他の言語ではどうか

どれも本番の成果物には入らないが、**「入らない理由」の仕組みが違う**。

- **Vitest / Hono(バンドルする場合)** — esbuild や Vite、Wrangler は**エントリポイントから import を辿って到達できたファイルだけ**を成果物に入れる。`app.test.ts` はアプリ側から import されないので自然に落ちる。`vitest` 自体は `devDependencies` なので `npm ci --omit=dev` でも消える。**分離の根拠が「フォルダの区分」ではなく「import の到達可能性」**である点が Java と違い、アプリ側からテスト用ヘルパーを import すればバンドルに入ってしまう
- **tsc でビルドする場合** — バンドラを使わず `dist/` に出す構成だと、`tsconfig.json` の `exclude` に `**/*.test.ts` を書き忘れると `dist/` にテストのコンパイル結果が混ざる。設定次第で漏れる
- **Laravel / PHP** — そもそもビルド段階が無く、`.php` がそのまま実行される。守っているのは ①`composer install --no-dev` で PHPUnit が `vendor/` に入らない ②`autoload-dev`(`Tests\` 名前空間)が本番のオートローダーに登録されない ③ドキュメントルートが `public/` なので `tests/` を Web から直接叩けない、の 3 点。ただし**リポジトリを丸ごとデプロイすれば `tests/` のファイル自体はサーバーに残る**。消したければ `.gitattributes` の `export-ignore` などで明示的に落とす必要がある
- **このリポジトリの frontend** — 現状テストも vitest も無い(`frontend/package.json` の依存は nuxt / vue / pinia のみ)。追加した場合は 1 番目に該当し、`nuxt generate` の出力 `.output/public/` には入らない

まとめると:

| | 分離の仕組み | 分離される場所 | 漏れる可能性 |
|---|---|---|---|
| Java / Gradle | sourceSet(フォルダの区分)+ 依存スコープ | ビルドツールが構造として保証 | ほぼ無い |
| JS(バンドル) | import の到達可能性 + devDependencies | バンドル時 | import すれば入る |
| JS(tsc) | tsconfig の `exclude` | コンパイル時 | 設定漏れで入る |
| PHP / Laravel | `--no-dev` と `autoload-dev` | インストール・デプロイ時 | ファイル自体は残りうる |

Java が一番厳格なのは、**ビルドツールがテストを「別の sourceSet」という一級の概念として持っている**から。JS と PHP では、テストの分離は規約と設定に支えられている(言語ごとのビルドの違いの続き → [build-and-tooling-by-language.md](./build-and-tooling-by-language.md))。

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
- **sourceSet** — Gradle がソースを `main` / `test` に分ける単位。出力先も依存スコープも別々になる
- **compileTestJava / testClasses** — テスト側のコンパイルタスク。`bootRun` / `bootJar` からは呼ばれない
- **`--dry-run`** — タスクを実行せず、実行される予定のタスクグラフだけを表示する Gradle のオプション。依存関係を調べるのに使える
- **継続ビルド（`--continuous`）** — ソースを監視し、変化のたびに指定タスクを自動再実行する Gradle のモード
- **マルチステージビルド** — 「ビルド用イメージ」と「実行用イメージ」を分ける Dockerfile の書き方

## 関連

- Dockerfile・CMD・マウントの設計 → [docker-dev-containers.md](./docker-dev-containers.md)
- build.gradle と依存管理・スコープ → [gradle-dependencies.md](./gradle-dependencies.md)
- jar の正体（.class を固めた zip）と、成果物 jar / wrapper jar の違い → [backend-project-files.md](./backend-project-files.md)
- ①コンパイル係を誰に任せるかの手法比較と Dev Container 採用理由 → [java-dev-env-comparison.md](./java-dev-env-comparison.md)
- 言語でビルドの要否・補完の仕組みが変わる理由(PHP / Node との比較の続き) → [build-and-tooling-by-language.md](./build-and-tooling-by-language.md)
