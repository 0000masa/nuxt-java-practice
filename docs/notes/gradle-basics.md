# Gradle の基礎 — Wrapper、./gradlew、キャッシュの置き場所

Gradle という道具そのものについての学習メモ。`docker/backend/Dockerfile` の `CMD ["sh", "./gradlew", "bootRun"]` と、`docker-compose.yml` の `gradle-cache:/root/.gradle` の読み解き。PHP / Node 出身者向け。

## Gradle とは — 「npm 一式」の Java 版

PHP / Node の世界では複数ツールに分かれている役割が、Java では Gradle 1 つに束ねられています。

| 役割 | Node | PHP（Composer） | Java（Gradle） |
|---|---|---|---|
| 依存ライブラリの管理 | npm + package.json | Composer + composer.json | Gradle + build.gradle |
| タスクの実行（dev 起動、テスト等） | npm scripts | composer scripts（Laravel 実務では artisan が主役） | Gradle タスク（`bootRun`、`test`...） |
| 成果物のビルド | vite / webpack など | **なし**（PHP はコンパイル不要でそのまま実行） | Gradle 自身（コンパイル〜Jar 作成） |
| 依存の保存先 | node_modules（プロジェクト内） | vendor（プロジェクト内） | `~/.gradle/caches`（ホーム。全プロジェクト共有） |
| ツール自身の保存先 | **Node イメージに同梱**（`/usr/local` 内。nuxt コンテナで実測） | イメージに**別途インストール**するのが定番（PHP 本体に同梱されないため）。Dockerfile で `COPY --from=composer:2 /usr/bin/composer /usr/local/bin/composer` と入れ、`/usr/local/bin/composer` に置くのが典型（実体は PHAR という単一ファイル。PHP 版 jar のような自己完結アーカイブ） | **イメージに無い**。Wrapper が `~/.gradle/wrapper/dists` へ自動ダウンロード（→ [Wrapper の章](#gradle-wrapper--本体を自動調達する案内人)） |

PHP 列の「ずれ」は、そのまま Java を理解するヒントになります。PHP はコンパイル不要なので、依存を入れる Composer とフレームワークの便利コマンド（artisan）があれば回りますが、Java は「コンパイル → jar に梱包」というビルド工程が**必須**——だからビルドツールが主役に座り、依存管理もタスク実行も 1 つに束ねる構図になります。保存先が Java だけプロジェクト外（ホーム共有）なのも思想の違いで、詳しくは後述の[「置き場所の思想」](#node_modules--vendor-との違い--置き場所の思想)を参照。

感覚としては `./gradlew bootRun` ≒ `npm run dev` ≒ `php artisan serve`、`./gradlew build` ≒ `npm run build` です。なお Java 界にはもう 1 つ **Maven** という老舗ビルドツールがあり、Gradle か Maven かの二択が npm か yarn かの選択に似た立ち位置です。Spring Initializr の最初の選択肢（`type=gradle-project`）はまさにこれを選んでいました（→ [spring-initializr.md](./spring-initializr.md)）。

## そもそも「パッケージマネージャー」とは — Gradle はそれを内蔵したビルドツール

「Node のパッケージマネージャー = npm、PHP = Composer、Java = Gradle」と並べたくなるが、実は最後だけ半分しか正しくない。まず言葉の定義から。

### パッケージマネージャーの 4 つの仕事

パッケージマネージャーは「**他人が書いたコード（ライブラリ）を安全に借りてくる係**」で、仕事は 4 つ:

1. **入手** — 中央の倉庫（**レジストリ**。npm レジストリ / Packagist / Maven Central）から探してダウンロードする
2. **バージョン解決** — 「ライブラリ A は B の 2.x が必要、C は B の 3.x が必要」のような衝突を調停し、使う版を 1 セットに確定する
3. **推移的依存の回収** — 借りたライブラリが**さらに借りている**ものを芋づる式に全部揃える（1 個 install したら大量に入ってくるのはこれ）
4. **再現性の保証** — 確定した組み合わせを記録し（package-lock.json / composer.lock）、誰のマシンでも同じ構成を再現する

npm と Composer は**この 4 つが本業**のツール。apt や brew も同じ概念の OS 版で、管理対象が「言語のライブラリ」か「OS のソフトウェア」かの違いしかない。Gradle もこの 4 仕事を全部やる（4 の記録方法だけ毛色が違い、lockfile は任意機能で、基本は build.gradle の宣言と BOM でバージョンを確定する → [gradle-dependencies.md](./gradle-dependencies.md)）。

### では Gradle の「本業」は何か — 7 つの仕事の検証

Gradle は依存管理を**一機能として内蔵**しているだけで、本業はビルド全体の指揮。冒頭の疑問「コンパイルやテストや jar 作成までできるのか?」への答えは**全部 Yes**だが、担い手が 3 種類に分かれる:

| やりたいこと | Gradle では | 担い手 | Node で言うと |
|---|---|---|---|
| 依存ライブラリの取得 | `dependencies { }` の宣言から自動解決 | **標準**（コア機能） | npm 自身の本業 |
| Java コードのコンパイル | `compileJava` タスク | **プラグイン**（`java`） | tsc（別ツール） |
| テストの実行 | `test` タスク（JUnit を起動） | **プラグイン**（`java`） | vitest / jest（別ツール） |
| JAR / WAR の作成 | `jar` / `bootJar` / `war` タスク | **プラグイン**（`java` / Spring Boot / `war`） | vite build（別ツール） |
| コード生成 | アノテーションプロセッサ、OpenAPI Generator 等 | **プラグイン**（後付け） | openapi-generator 等（別ツール） |
| デプロイ処理 | 成果物の公開は `maven-publish`、サーバー配備は自作かプラグイン | **プラグイン or 自作** | npm publish / デプロイは CI 等の別道具 |
| 独自タスクの実行 | `tasks.register('...')` を build.gradle に書く | **自作** | npm scripts |

- **標準（コア）** — Gradle 本体が持つのは実は「依存解決」と「タスク実行エンジン」だけ
- **プラグイン** — タスクの詰め合わせを後付けする仕組み。このリポジトリの build.gradle の `plugins { }` がまさにそれで、`id 'java'` が compileJava / test / jar を、`id 'org.springframework.boot'` が **bootRun / bootJar** を足している。つまり毎日打っている `./gradlew bootRun` は「Spring Boot プラグインが追加したタスク」
- **自作** — build.gradle は Groovy のコード（→ [backend-project-files.md](./backend-project-files.md)）なので、タスクを直接書ける。既存の `tasks.named('test') { useJUnitPlatform() }` も「プラグインが足した test タスクをコードで触っている」実例

### npm scripts との本質的な違い — 起動台か、統合エンジンか

「npm も scripts に書けば何でも実行できるのでは?」はそのとおりで、**起動できるかどうかは違いにならない**。違いは実行のされ方:

- **npm scripts は起動台。** npm は tsc や vitest の中身を知らず、ただコマンドを叩くだけ。ツール同士も互いを知らない
- **Gradle のタスクは 1 つのエンジンに統合されている。** タスク間の依存関係（**タスクグラフ**）を Gradle が知っているので、`./gradlew build` 一発で「コンパイル → テスト → jar 梱包」が正しい順序で走る。さらに各タスクの入力・出力を追跡していて、**変わっていないタスクはスキップする**（実行ログに出る `UP-TO-DATE` の正体）

まとめ: **npm / Composer は「パッケージマネージャー（+ 起動台）」、Gradle は「タスク実行エンジンがパッケージマネージャーも内蔵したもの」**。冒頭の表で同じ行に並べられるのは、Gradle がでかい図体の一部で npm の仕事もこなしているからで、同格のツールだからではない。

## Gradle Wrapper — 本体を自動調達する「案内人」

### そもそもなぜ「Gradle のインストール」が必要なのか

Node.js をインストールすると npm が付いてくるのは、**Node の公式インストーラが npm を同梱している**からです。ランタイムとパッケージマネージャーのセット販売という、実は例外的な親切です。

Java の JDK に入っているのは `java`（JVM）と `javac`（コンパイラ）などの基本工具だけで、**ビルドツールは入っていません**。理由は歴史と勢力図:

- Java（1995 年）誕生時にビルドツールはまだ存在せず、Ant（2000）→ Maven（2004）→ Gradle（2008〜）と**後から民間で発展した**
- Maven と Gradle が競合として並立しており、JDK がどちらかを標準採用する形になっていない

つまり Gradle は「JVM の上で動く、ただの別ソフト」であり、素朴には brew や SDKMAN 等で自分でインストールして `gradle` コマンドを使える状態にする必要があります。実は **PHP がまさに同じ構図**（PHP 本体に Composer は同梱されず、別途インストールする）。Node が例外的にセットなだけで、「言語ランタイムと道具は別配布」のほうが普通です。

### Wrapper の仕組み — 4 ファイルの受付係

Wrapper は一言でいうと「**正しいバージョンの Gradle を、必要になった瞬間に自動で用意してから、本題のタスクに取り次ぐ受付係**」です。実体はリポジトリにコミットされた 4 ファイル（`gradlew` / `gradlew.bat` / `gradle-wrapper.jar` / `gradle-wrapper.properties` → [backend-project-files.md](./backend-project-files.md)）で、役割分担は:

1. `gradlew`（シェルスクリプト）が JDK の `java` を探し、`gradle-wrapper.jar` を起動する
2. jar が `gradle-wrapper.properties` の `distributionUrl`（= このプロジェクトは Gradle 9.5.1 を使え）を読む
3. `~/.gradle/wrapper/dists/` にそのバージョンがあるか確認。**なければダウンロードして展開**
4. その Gradle 本体に `bootRun` などの指定タスクを渡して実行させる

これで解決するのは「インストールの手間」だけではなく、**バージョンのばらつき**という第二の敵も同時に倒しています:

- **インストール不要** — backend コンテナに Gradle を入れていないのに動くのはこのため（イメージは JDK だけ）
- **バージョン統一** — 手動インストールだと人によって Gradle 8 だったり 9 だったりしてビルド結果が揺れる。Wrapper は properties に書かれた版を**プロジェクト側が強制**するので、チーム・CI・コンテナ全員が同じ版で揃う

Node で例えるなら「リポジトリに小さな nvm + 実行スクリプトが同梱されている」状態。より正確な対応物は **Corepack**（package.json の `packageManager` 欄で yarn / pnpm の版を固定し自動調達する仕組み）で、「全員に同じ道具の同じ版を使わせたい」という要求は言語を問わず存在する、ということです。

### いつ実行されるのか — 毎回走るが、ダウンロードは初回だけ

上の手順は「セットアップ時に一度だけ」ではなく、**`./gradlew` を打つたびに毎回**走ります。このリポジトリでは `docker compose up` のたびに CMD の `sh ./gradlew bootRun` が実行されるので、**コンテナ起動のたび**です。ただし各ステップの重さが違います:

```
docker compose up（毎回）
 └─ sh ./gradlew bootRun
     ├─ properties を読む ……………………… 毎回（一瞬）
     ├─ ~/.gradle/wrapper/dists を確認 …… 毎回（一瞬）
     ├─ Gradle 本体をダウンロード ………… ★無いときだけ（数十秒）
     └─ Gradle を起動して bootRun ………… 毎回
```

「無いとき」が具体的にいつかというと、このリポジトリでは 3 パターン:

- **本当の初回**（`gradle-cache` ボリュームが空の状態での最初の起動）
- **`docker compose down -v` などでボリュームを消した後**（キャッシュごと消えるので再ダウンロード）
- **gradle-wrapper.properties のバージョンを書き換えた後**（新しい版が手元に無いので取りに行く）

逆に言えば、2 回目以降の `compose up` でダウンロードが走らないのは、named volume `gradle-cache:/root/.gradle` が `wrapper/dists/` ごと保存しているからです。「初回起動が遅い理由」（→ [java-build-and-run.md](./java-build-and-run.md)）と、この初回ダウンロードは同じ現象の別側面です。

なお「自動で用意」といっても **OS へのインストールは最後まで起きません**。`~/.gradle/wrapper/dists/` へのダウンロード & キャッシュであり、PATH 登録はされないので `gradle` コマンドは永遠に打てるようにならない——**常に `./gradlew` という受付係を経由**します。

### Wrapper はどこから来たのか — Spring Initializr と `gradle wrapper` タスク

あの 4 ファイル、自分で書いた覚えがないのにコミットされています。出どころは **Spring Initializr**（[start.spring.io](https://start.spring.io)）——Spring チームが運営する **Spring Boot プロジェクト専用の雛形生成サービス**で、「依存は Web と JPA、ビルドは Gradle、Java 21」と注文すると build.gradle・ソースの骨格・**Wrapper 一式**入りの zip をくれます（このリポジトリの backend もこれ → [spring-initializr.md](./spring-initializr.md)）。混同しやすい切り分け:

- **Spring Initializr** = Spring 固有（Spring Boot の雛形しか作れない）
- **同梱されてきた Wrapper** = Gradle 標準の機能（Spring とは無関係。どんな Gradle プロジェクトにもある）

配達員（Initializr）は Spring の社員だが、荷物に入っていた道具（Wrapper）は Gradle 社の汎用品、という関係です。Node / PHP での該当物:

| | 雛形生成 | 実行例 |
|---|---|---|
| Spring Boot | Spring Initializr | start.spring.io で選んで zip を受け取る |
| Node（Nuxt） | create 系スキャフォールダ | `npm create nuxt@latest`（frontend もこれ → [nuxi-templates.md](./nuxi-templates.md)） |
| PHP（Laravel） | composer create-project / Laravel installer | `composer create-project laravel/laravel` |

役割は完全に同じで、違いは配達方法だけ（Initializr は **Web サービス**、Node / PHP はローカル CLI）。

Wrapper 自体は Gradle 標準の **`wrapper` タスク**でいつでも生成できます。`gradle wrapper --gradle-version 9.5.1` と打つと、指定版を指す properties 込みで**あの 4 ファイルが生成される**——Initializr は裏でこれ相当の処理をして zip に同梱していたわけです。既に Wrapper があるプロジェクトなら `./gradlew wrapper --gradle-version 10.0` で**受付係自身に後任を用意させる**（自己更新）こともできます。

ここに鶏と卵の関係があります。`gradle wrapper` は Gradle のタスクなので、実行には**インストール済みの Gradle 本体**（+ JVM で動くので JDK）が必要。「Wrapper を作るには一度は本物の Gradle が要る」わけです。実務での解決経路は 3 つ:

1. **雛形サービスに作らせる** — Initializr が代わりに生成（このリポジトリの経路。手元に Gradle 不要）
2. **一時的にインストールして生成** — brew や SDKMAN で入れて `gradle wrapper` を打ち、以後は `./gradlew` しか使わない
3. **既存の Wrapper に更新させ続ける** — 最初の一度以外 Gradle 本体は不要

つまりチーム開発では「`gradle` コマンドが要るのはプロジェクトを最初に作る 1 人だけ、Initializr を使えばその 1 人すら不要」という状態になります。

### Gradle のバージョンは誰が決めるのか — Dockerfile ではなく properties の 1 行

決定権はフレームワークでも Dockerfile でもなく、**リポジトリにコミットされたこの 1 行**にあります:

```properties
# backend/gradle/wrapper/gradle-wrapper.properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.5.1-bin.zip
```

Initializr は雛形生成時に「その時点の推奨版」を**初期値として書いてくれただけ**で、以後この行の持ち主はプロジェクト側です。Spring Boot 本体は実行時にこの決定に関与しません。このリポジトリの「バージョンの決めごと」の置き場所を並べると:

| 決めごと | 書いてある場所 | 誰の都合か |
|---|---|---|
| JDK のバージョン（21） | `docker/backend/Dockerfile` の `eclipse-temurin:21-jdk` | 実行環境の都合 |
| Gradle のバージョン（9.5.1） | `gradle-wrapper.properties` | **プロジェクトの都合** |
| 依存ライブラリのバージョン | `build.gradle` | プロジェクトの都合 |

properties 側に置く利点は、**コンテナの外でも同じように効く**こと。コンテナ・ホスト直接・CI のどこで `./gradlew` を打っても、リポジトリの properties が唯一の正なので全員同じ版で揃います。Dockerfile に書くと「コンテナで動かしたときだけ有効な決め事」になってしまう。

対照的な設計として、`FROM gradle:9.5-jdk21` のような **Gradle 入り公式イメージ**で Dockerfile 側が版を決める流儀も実在します。ただし実務の主流・公式推奨は Wrapper 側です。[Gradle 公式の Docker ガイド](https://docs.gradle.org/current/userguide/docker.html)自身が「[gradle イメージ](https://hub.docker.com/_/gradle)はちょっとした実験や、ソースを checkout しない CI 向け。**production build には使うべきでない**。Wrapper のあるプロジェクトは素の JDK イメージ（eclipse-temurin 等）+ `./gradlew` で十分」と明言しています。理由は、イメージに焼かれた Gradle と properties の指す Gradle の**二重管理 → 版ずれを構造的に防げる**こと、イメージを小さく保てること。このリポジトリの構成（JDK だけのイメージ + `sh ./gradlew`）は、まさにこの公式推奨形です。

## `./gradlew` はどこを指しているか

`./gradlew` はディレクトリではなく**ファイルへのパス**です。`.` = 「現在のディレクトリ」なので、「**今いるディレクトリにある gradlew というファイル**」の意味。シェルは裸の名前（`gradlew`）だと PATH という登録済みの場所しか探さないため、「そこにあるこのファイルだよ」と `./` で明示します。

backend コンテナでは `WORKDIR /app` なので `./gradlew` = `/app/gradlew`。そして `/app` にはホストの `backend/` がバインドマウントされているので、**実体はリポジトリの `backend/gradlew`** です。（CMD で `sh ./gradlew` と sh 経由にしている理由は [docker-dev-containers.md](./docker-dev-containers.md) を参照）

## `~/.gradle` キャッシュ — 何が、どこに貯まるのか

Gradle は作業に必要なものを**ユーザーのホームディレクトリ配下の `.gradle`** に貯めます。backend コンテナの実物（named volume `gradle-cache` の中身）を覗いた構造がこうです:

```
/root/.gradle（コンテナ内の ~/.gradle）
├── wrapper/dists/gradle-9.5.1-bin/<ハッシュ>/  ← 【倉庫A】Gradle 本体。Wrapper（受付係）の管轄
├── caches/
│   ├── modules-2/files-2.1/                    ← 【倉庫B】借りてきた jar 全部。Gradle の管轄
│   └── 9.5.1/  jars-9/  journal-1/             ← Gradle の内部帳簿（コンパイル済みスクリプト等）
└── daemon/  native/  notifications/            ← 動作ログなどの雑務フォルダ
```

- **倉庫 A（`wrapper/dists/`）= Gradle 本体の置き場。** Wrapper がダウンロードした zip の展開物
- **倉庫 B（`caches/modules-2/`）= パッケージの置き場。** Maven Central 等から取得した jar

### 倉庫 B に入るのは build.gradle に書いた分だけではない（実測）

build.gradle の `dependencies { }` は 9 行ですが、倉庫 B には**実測で 94 グループ**のライブラリが入っていました。内訳は 3 種類:

1. **推移的依存** — `spring-boot-starter-webmvc` が連れてくる Tomcat・Jackson・ログライブラリなどの芋づる分
2. **テストツール** — `org.junit` も実在。**JUnit は Gradle の付属品ではなく**、`testImplementation` / `testRuntimeOnly` が連れてくる「ただの依存パッケージ」なので他のライブラリと同格で並ぶ（Gradle の `test` タスクは JUnit を起動する係であって、JUnit そのものは Gradle の中に入っていない）
3. **プラグイン** — `plugins { id 'org.springframework.boot' }` の実体 `spring-boot-gradle-plugin` も実在。プラグインも「プラグイン専用レジストリ（**Gradle Plugin Portal**）から借りてきた jar」なので同じ倉庫行き

つまり倉庫 B の入場資格は「**ネットから借りてきた jar であること**」で、用途（アプリ用・テスト用・ビルド用）は問いません。唯一の例外が Gradle 本体——「借りてきた道具」ではなく「倉庫番自身」なので、雇い主の Wrapper が倉庫 A に住まわせている、という整理です。

パッケージの実パスはこんな形です:

```
.../files-2.1/com.mysql/mysql-connector-j/9.7.0/4e6e3e...98cba/mysql-connector-j-9.7.0.jar
      グループ ↑        名前 ↑            版 ↑   SHA1 ハッシュ ↑
```

バージョンがフォルダ名に入っているので**複数バージョンが並んで共存**できます（全プロジェクト共有が安全に成立する理由）。SHA1 ハッシュの階層はダウンロード物の改ざん・破損検出用で、人間が手で触る前提の構造ではありません。

### 第 3 の倉庫 `~/.gradle/jdks` — コンパイラは Gradle の持ち物ではない

Java コンパイラ（javac）は Gradle に入っておらず、**JDK のものを借りて**使います。build.gradle の `toolchain { languageVersion = 21 }` は「JDK 21 のコンパイラを使え」という指定で、このリポジトリではコンテナイメージ（`eclipse-temurin:21-jdk`）の JDK がそのまま使われます。もし手元に合う版の JDK が無い環境だと、Gradle は **JDK 自体を自動ダウンロード**することがあり、その置き場が第 3 の倉庫 `~/.gradle/jdks/` です（このリポジトリでは出番が無いので存在しません）。

### コンテナ内では「コンテナの ~/.gradle」に配置される

その理解で正しいです。backend コンテナの実行ユーザーは root で、root のホームディレクトリは `/root`。つまりコンテナ内の `~/.gradle` = `/root/.gradle` であり、`docker-compose.yml` の `gradle-cache:/root/.gradle` は**まさにその場所に named volume を差し込んでいます**。ホスト側に対応する `~/.gradle` は存在せず（ホストで Gradle を動かさない限り作られない）、named volume の実データは Docker が管理する領域に保存されます。

### node_modules / vendor との違い — 「置き場所の思想」

役割（ダウンロードした依存の置き場）は近いですが、思想が違います。

| | node_modules / vendor | ~/.gradle のキャッシュ |
|---|---|---|
| 置き場所 | **プロジェクトの中**（プロジェクトごとにコピーを持つ） | **ユーザーのホーム**（全プロジェクトで共有） |
| 中身 | 依存ライブラリのみ | 依存ライブラリ + **Gradle 本体** |
| プロジェクト側の参照 | プロジェクト内のフォルダを直接読む | ビルド時にキャッシュ内の jar をパスで参照 |

Java プロジェクトには node_modules に相当する「プロジェクト内の依存フォルダ」が**存在しません**。10 個のプロジェクトが同じライブラリを使うなら、キャッシュに 1 部だけ置いてみんなで参照します（pnpm の共有ストア方式に近い考え方）。

## なぜバインドマウントではなく named volume なのか

frontend の node_modules と比べると設計の違いが見えます。

**node_modules が「バインドマウントで扱われている」のは、選んだ結果というより巻き込まれた結果**です。node_modules はプロジェクトの中（`frontend/node_modules`）にあるので、`./frontend:/app` でプロジェクトごとマウントすれば自動的に共有されます。しかもホスト側でも npm や IDE（型補完など）がそのフォルダを必要とするので、ホストに実体があることに意味があります。

一方 `~/.gradle` は**プロジェクトの外**にあるので、`./backend:/app` のマウントには含まれません。そして何もしなければコンテナのファイルシステムは使い捨てなので、コンテナを作り直すたびに Gradle 本体も依存も全部ダウンロードし直しになります。永続化の手段は 2 つ:

1. **ホストのフォルダを割り当てる（バインドマウント）** — 例: `~/.gradle-docker:/root/.gradle`
2. **named volume を割り当てる** — このリポジトリの選択

named volume が選ばれる理由:

- **ホスト側に実体を見せる必要がない。** Gradle はコンテナ内でしか動かさないので、ホストの誰もこのキャッシュを読まない（node_modules との決定的な違い）
- **ホストを汚さない。** バインドマウントだと root 所有のファイル群がホストのホームに現れ、パスの取り決めも必要になる
- **「消えては困るが、消えても再生成できる」性質に合う。** named volume は `docker compose down` で残り、`down -v` で意図的に捨てられる。キャッシュの扱いとしてちょうどいい

つまり「**ホストと共有したいものはバインドマウント、コンテナだけの永続データは named volume**」という使い分けで、mysql-data / minio-data と同じ側に分類された、ということです。

### では `./backend:/` にすれば `.gradle` もマウント圏内に入る?

「`/root/.gradle` がマウントの外にあるなら、`./backend:/` とコンテナのルートごとマウントすれば圏内に入るのでは?」という発想は筋が良いのですが、**不可能です**。理由はマウントの性質にあります。

マウントの動きは「フォルダの中身をコピーして混ぜる」ではなく、**「その場所の上にホストのフォルダを重ね掛けする」**です。重ねた下にあったものは消えはしませんが**見えなくなります**（布をかぶせるイメージ。**マスキング**と呼ばれる性質）。コンテナの `/` にはイメージの中身そのもの——OS のファイル一式（`/bin/sh` など）、JDK、`/root`——が広がっているので、仮に `/` へ重ねられたら:

```
/ に backend/ を重ね掛け
→ /bin も /usr(JDK)も /root も全部 backend の中身で覆い隠される
→ sh が見つからない、Java も無い → CMD の実行すら不可能
```

`.gradle` を圏内に入れるどころか、**gradlew を動かす道具一式を自分で隠してしまう**わけです。この事故が起きないよう、Docker はマウント先 `/` の指定自体をエラーにしています。

そもそも `/` ごと包む必要はなく、マウントは**任意のパスに個別に差し込める**ので狙い撃ちすればよい（前述の手段 1 `./.gradle-cache:/root/.gradle` がそれ）。もう 1 つ、**キャッシュの側を引っ越させる**別解もあります:

```yaml
    environment:
      GRADLE_USER_HOME: /app/.gradle-home   # ~/.gradle の代わりにここを使え、という指示
```

これでキャッシュが `/app` の中 = 既存マウントの圏内に生まれます。「node_modules がプロジェクトの中にあるから巻き込まれてマウントされる」構図を Gradle で人工的に再現するやり方です。（どの方法も技術的には可能で、それでも named volume が選ばれる理由は前述のとおり）

なおマスキングは部分マウントでも起きます。イメージ内に `/app` へ COPY 済みのファイルがある状態で `./backend:/app` をマウントすると COPY した中身は隠れる——「Dockerfile で作ったはずのファイルが無い!」の定番原因です。`/` は拒否されますが `/usr` などの重要ディレクトリは拒否されないので、マウント先は「そこに何が既にあるか」を意識して選ぶこと。

## 実際のプロジェクトではどうしているか — 「ホストに見せる」の実務調査

.gradle / node_modules / vendor をホストから見えるようにするかは、エコシステムごとに定番が違います。

| | ホストから見える? | 実務でよくある形 |
|---|---|---|
| **vendor（Laravel）** | **見えるのが主流** | Laravel 公式の Sail がプロジェクト丸ごとバインドマウント（vendor 込み） |
| **node_modules** | **両方の流派がある** | コンテナ内開発の定番は「匿名ボリュームで隠す」。ホストでも npm を使う構成（このリポジトリ）も普通 |
| **~/.gradle** | **見えないのが主流** | named volume（このリポジトリと同じ）が定番。ホストでも Gradle を使う人だけ共有マウントする流派がある |

- **node_modules** — コンテナ内開発では[匿名ボリュームで隠すのが定番の推奨](https://medium.com/@duckdevv/docker-node-modules-management-why-anonymous-volume-is-the-right-answer-247fbc14c481)（`.:/app` + `/app/node_modules`）。理由はホストとコンテナの OS 差による native バイナリの不整合、Docker Desktop（Mac/Windows）での性能、権限問題。ただし隠すと**ホストの IDE が型定義を読めなくなる**ので、ホストでも npm install する構成も同じくらい見かける
- **vendor（Laravel）** — [公式ツールの Sail](https://laravel.com/docs/13.x/sail) はプロジェクト丸ごとバインドマウントで vendor も**見える**。PHP の IDE は vendor 内のクラス定義を読んで補完するため、見えることに実益がある。一方 Mac/Windows では [vendor をコンテナ内ボリュームに移す高速化 Tips](https://dev.to/tylerlwsmith/speed-up-laravel-in-docker-by-moving-vendor-directory-19b9) が知られる程度に性能コストもある
- **~/.gradle** — [Gradle 公式ドキュメントの Docker ガイド](https://docs.gradle.org/current/userguide/docker.html)自体が named volume 方式を案内。変種として[ホストの `~/.gradle` を共有マウントする流派](https://www.endoflineblog.com/optimizing-development-with-docker)もある（ホストでも Gradle / IDE ビルドを使う人には二重ダウンロードが消えて合理的）。Java の IDE は node_modules のようなプロジェクト内フォルダではなく**ホスト側の ~/.gradle と自前のインデックス**で補完を賄うため、コンテナのキャッシュを見せるメリットがない

分かれ目はほぼ一点: **「ホスト側の道具（IDE やパッケージマネージャ）がそのフォルダを読むか?」**

- 読む（vendor、node_modules と TypeScript 補完）→ 見せる価値がある。性能が問題になったら初めて隠す工夫をする
- 読まない（~/.gradle）→ 隠す（named volume）が素直

このリポジトリの現状（node_modules はホスト実体・.gradle は named volume）は、この基準に照らして両方とも実務の主流パターンに乗っています。

## 落とし穴

- **`docker compose down -v` を気軽に打たない。** gradle-cache は再ダウンロードで済むが、**mysql-data も一緒に消えて DB の中身が飛ぶ**
- **`backend/.gradle`（プロジェクト直下）は別物。** ビルドの途中状態を置くプロジェクト固有の作業フォルダで、共有キャッシュ（~/.gradle）とは役割が違う。git には入れない
- **ホストでも Gradle を動かすとキャッシュは二重になる。** ホストの `~/.gradle` とコンテナの named volume は別の場所なので、それぞれ初回ダウンロードが走る

## 用語集

- **Gradle** — Java のビルドツール。依存管理 + タスク実行 + ビルドを 1 つで担う（npm 一式の Java 版）
- **パッケージマネージャー** — ライブラリの入手・バージョン解決・推移的依存の回収・再現性の保証を担う道具。npm / Composer が該当し、Gradle はこれを一機能として内蔵する
- **レジストリ** — パッケージの中央倉庫。npm レジストリ / Packagist / Maven Central
- **推移的依存** — 借りたライブラリがさらに借りているライブラリ。芋づる式に全部揃える必要がある
- **プラグイン（Gradle）** — タスクの詰め合わせを後付けする拡張。`java` が compileJava / test / jar を、Spring Boot プラグインが bootRun / bootJar を足す
- **タスクグラフ** — タスク間の依存関係の地図。「正しい順序で一発実行」と「UP-TO-DATE スキップ」の土台
- **Maven** — Gradle と並ぶもう 1 つの定番ビルドツール。二択の関係
- **Gradle Wrapper（gradlew）** — プロジェクト指定バージョンの Gradle 本体を自動ダウンロードして実行する同梱スクリプト
- **Spring Initializr** — Spring チーム運営の Spring Boot 専用雛形生成サービス（start.spring.io）。Wrapper 一式も同梱してくれる。Node の `npm create` 系、PHP の `composer create-project` に相当
- **`wrapper` タスク** — Wrapper の 4 ファイルを生成・更新する Gradle 標準タスク。`./gradlew wrapper --gradle-version X` で自己更新できる
- **Corepack** — Node 側の類似機構。package.json の `packageManager` 欄で yarn / pnpm の版を固定し自動調達する
- **SDKMAN** — JDK や Gradle など Java 界の道具を入れるバージョンマネージャ（brew の Java 特化版のような立ち位置）
- **gradle-wrapper.properties** — Wrapper が読む「使うべき Gradle バージョン」の設定ファイル
- **`./`** — 「現在のディレクトリ」を表すパス表記。PATH 検索ではなく場所の明示
- **~/.gradle** — ユーザー単位の Gradle キャッシュ置き場（本体 + 依存 jar）。全プロジェクト共有
- **wrapper/dists（倉庫 A）** — Wrapper がダウンロードした Gradle 本体の置き場
- **caches/modules-2（倉庫 B）** — 借りてきた jar 全部の置き場。依存・テストライブラリ・プラグインを区別しない
- **Gradle Plugin Portal** — プラグイン専用のレジストリ（Maven Central のプラグイン版）
- **ツールチェーン（toolchain）** — 「この版の JDK でコンパイルせよ」という指定。コンパイラは Gradle ではなく JDK の持ち物
- **~/.gradle/jdks（第 3 の倉庫）** — ツールチェーンが JDK を自動ダウンロードしたときの置き場
- **named volume** — Docker が管理する永続保存領域。コンテナを作り直しても残り、`down -v` で削除
- **バインドマウント** — ホストのフォルダをコンテナに見せる方式。実体はホスト側にある
- **マウントの重ね掛け（マスキング）** — マウント先に元からあったファイルは削除されず「見えなくなる」だけ、という性質
- **GRADLE_USER_HOME** — `~/.gradle` の場所を変更する環境変数

## 関連

- CMD の読み解き、バインドマウントと named volume の使い分けの実例 → [docker-dev-containers.md](./docker-dev-containers.md)
- bootRun タスクの中身、初回起動が遅い理由 → [java-build-and-run.md](./java-build-and-run.md)
- build.gradle の書き方と依存管理 → [gradle-dependencies.md](./gradle-dependencies.md)
