# backend/ のファイル図鑑 — .java / .gradle / .jar / .bat は何者か

`backend/` に並ぶ雑多なファイル・フォルダを「これは何者か・誰が作るか・開いていいか」の観点で 1 つずつ解説する図鑑。仕組みの深掘りは既存ノート([java-build-and-run.md](./java-build-and-run.md)、[gradle-basics.md](./gradle-basics.md)、[gradle-dependencies.md](./gradle-dependencies.md))へリンクする。

## 一覧表

「誰が作るか」で 4 グループに分かれる。この軸は「git にコミットするか」の判断軸とほぼ一致する。

| ファイル / フォルダ | 正体 | 作った人 | git |
|---|---|---|---|
| `src/**/*.java` | Java のソースコード | 自分 | ✅ |
| `src/main/resources/application.yml` | アプリの設定ファイル | 自分 | ✅ |
| `build.gradle` | ビルドの設計図(依存・プラグイン) | 自分 | ✅ |
| `settings.gradle` | プロジェクト名などの全体設定 | 自分 | ✅ |
| `gradlew` | Gradle Wrapper(Unix 用シェルスクリプト) | Spring Initializr | ✅ |
| `gradlew.bat` | 同上の Windows 版 | Spring Initializr | ✅ |
| `gradle/wrapper/gradle-wrapper.jar` | Wrapper の本体プログラム | Spring Initializr | ✅ |
| `gradle/wrapper/gradle-wrapper.properties` | Wrapper の設定(Gradle のバージョン指定) | Spring Initializr | ✅ |
| `.gitignore` / `.gitattributes` | git への指示書 | Spring Initializr | ✅ |
| `HELP.md` | Initializr が置いていったリンク集 | Spring Initializr | ❌(ignore 済み) |
| `build/`(`.class` など) | ビルドの産物 | Gradle | ❌ |
| `.gradle/`(`.bin` / `.lock`) | ビルドの帳簿・作業メモ | Gradle | ❌ |

## ① 自分が書くファイル

### `.java` — ソースコード

人間が読み書きする Java のプログラム本文。ただしコンピュータはこのままでは実行できず、**コンパイルして `.class` に変換してから**動かす(→ ③、詳細は [java-build-and-run.md](./java-build-and-run.md))。フロントの `.ts` / `.vue` に相当する立ち位置。

`src/main/java/` が本体、`src/test/java/` がテストという配置は Gradle/Maven 界の標準レイアウトで、設定しなくても Gradle がこの場所を探しに来る。パッケージ名(`com.example.app`)がこの階層を含まないのは、一致ルールの基準点がリポジトリ root ではなく「ソースルート」だから → [java-package-basics.md](./java-package-basics.md)。

### `application.yml` — アプリの設定

Spring Boot が起動時に読む設定ファイル。拡張子 `.yml`(YAML)は docker-compose.yml と同じ「インデントで構造を表す設定用の記法」で、言語ではなくただのデータ。このリポジトリでは `${DB_HOST:localhost}` のように**環境変数を参照する穴**を開けておき、実際の値は `.env` から注入している。

### `build.gradle` / `settings.gradle` — ビルドの設計図

拡張子 `.gradle` は「Gradle に読ませる台本」で、中身は Groovy という言語のコード。役割は package.json に近い(依存ライブラリの宣言など → [gradle-dependencies.md](./gradle-dependencies.md))。`settings.gradle` は今のところ `rootProject.name = 'demo'` の 1 行だけで、プロジェクト名を決めている——この名前が後述の成果物 jar のファイル名になる。

## ② 同梱された道具 — Gradle Wrapper 一式

4 ファイルで 1 つの仕組み。「Gradle 本体を自動調達してくれる案内人」で、仕組みの全体は [gradle-basics.md](./gradle-basics.md) に詳しい。ここでは**ファイルとしての正体**だけ:

### `gradlew` と `gradlew.bat` — 同じ台本の Unix 版 / Windows 版

どちらも「wrapper の jar を JVM で起動する」だけの短い起動スクリプト。`gradlew` はシェルスクリプト(Linux / macOS / コンテナ内用)、`.bat` は**Windows のコマンドプロンプト用スクリプト(バッチファイル)**。中身の仕事は同じで、OS ごとにスクリプトの書き方が違うから 2 つある。このリポジトリの backend コンテナは Linux なので使われるのは `gradlew` 側だけだが、Windows で直接開発する人のために `.bat` も同梱されている。

### `gradle-wrapper.jar` — Wrapper の本体

スクリプト 2 つは入口にすぎず、「properties を読む → Gradle 本体をダウンロード → 実行」という実務はこの小さな Java プログラムがやる。**`.jar` については後述**(③)——ここで大事なのは、これは「ビルドの成果物としての jar」ではなく**道具としてコミットされている jar** だということ。

### `gradle-wrapper.properties` — Wrapper への指示書

拡張子 `.properties` は Java 界の伝統的な設定形式で、`キー=値` を並べただけのテキスト。実質的な主役は 1 行:

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.5.1-bin.zip
```

「このプロジェクトは Gradle 9.5.1 を使う」という宣言で、チーム全員のバージョンをここで固定している。Gradle のバージョンを上げるときはこの行を書き換える。

## ③ 機械が生成するもの(git に入れない)

### `build/` — ビルドの産物置き場

`./gradlew bootRun` や `build` が走るたびに作られる出力先。今の中身:

- **`classes/**/*.class`** — `.java` をコンパイルした結果の **JVM バイトコード**。人間用のテキスト(.java)を JVM 用の中間言語に翻訳したもので、バイナリなのでエディタで開いても読めない。`.java` 1 ファイルから原則 1 つ生まれる(→ [java-build-and-run.md](./java-build-and-run.md))
- `resources/main/` — `src/main/resources/` の設定ファイルが出力側へコピーされたもの
- `resolvedMainClassName` — 「起動すべき main クラスはこれ」と Gradle がメモした 1 行テキスト
- `tmp/` — コンパイルの途中経過(差分ビルド用のデータなど)

**`.jar` の正体はここで説明できる。** jar(Java ARchive)は「`.class` 一式 + 設定ファイルを 1 個に固めた zip」で、配布・実行の単位。`./gradlew build` を実行すると `build/libs/demo-0.0.1-SNAPSHOT.jar` が生まれる(`demo` は settings.gradle の名前)——**今はまだ `bootRun` しかしていないので存在しない**。本番 AWS へ持っていくのはこの成果物 jar であり、②の wrapper jar とは役割がまったく違う。

```
.java(自分が書く)──コンパイル──▶ .class(build/ に生成)──梱包──▶ .jar(build/libs/ に生成)
```

全部再生成できるので git には入れない(.gitignore 済み)。`rm -rf build` しても失うものはない。

#### jar を実際に作るコマンド

ホストに Java は入っていないので、JDK を持つ backend コンテナの中で実行する(リポジトリ直下で):

```bash
docker compose exec backend sh ./gradlew build          # コンパイル → テスト → jar 梱包
docker compose exec backend sh ./gradlew build -x test  # テストを飛ばして jar だけ作る
docker compose exec backend sh ./gradlew clean          # 片付け(build/ を丸ごと削除)
```

- `cd backend` は不要。Dockerfile の `WORKDIR /app` により、exec は最初からマウントされた `backend/` の中で実行される。成果物はホストの `backend/build/libs/` からそのまま見える(バインドマウントの恩恵)
- `build` は `bootRun` の「コンパイルして**起動**」に対し、「コンパイル → **テスト** → **jar 梱包**」で終わる別コース。テスト(`@SpringBootTest`)はアプリを丸ごと起動して DB 接続まで本物として動くので、`.env` と mysql が見えるコンテナ内で実行することに意味がある
- jar は 2 つできる: `demo-0.0.1-SNAPSHOT.jar`(依存ライブラリまで全部入りで単体起動できる **Boot jar**。AWS へ持っていくのはこちら)と `demo-0.0.1-SNAPSHOT-plain.jar`(自分のクラスだけの素の jar)
- 「jar は zip」の答え合わせは `unzip -l backend/build/libs/demo-0.0.1-SNAPSHOT.jar` で

### `.gradle/` — Gradle の帳簿(ホームの `~/.gradle` とは別物)

`fileHashes.bin` や `*.lock` が入っているが、これは**差分ビルドのための記録**。「前回ビルド時の各ファイルのハッシュ」を覚えておき、次回「変わっていないファイルはコンパイルし直さない」という高速化に使う。`.bin` は Gradle 専用のバイナリ帳簿、`.lock` は「いま別の Gradle プロセスが書き込み中」を示す鍵ファイルで、どちらも人間が開くものではない。

紛らわしいが、**プロジェクト直下の `.gradle/`(このプロジェクトのビルド帳簿)と、ホームの `~/.gradle`(全プロジェクト共有の依存キャッシュ)は別物**。後者の話は [gradle-basics.md](./gradle-basics.md)。

## ④ git への指示書と置き土産

### `.gitignore` — 「git に入れないもの」リスト

`build/` と `.gradle`(③の生成物)、各種 IDE の設定フォルダ、そして `HELP.md` を除外している。注目は例外指定:

```gitignore
!gradle/wrapper/gradle-wrapper.jar
```

`!` は「これは例外的に**必ず入れる**」の意味。jar は普通「ビルドすれば作れる生成物」なので ignore されがちだが、wrapper の jar だけは道具としてコミットされていないと `./gradlew` 自体が動かない。その事故を防ぐ守りの 1 行。

### `.gitattributes` — 改行コードの取り決め

全 3 行で、**OS ごとの改行コード差(LF / CRLF)による事故**を防いでいる:

```gitattributes
/gradlew text eol=lf      # シェルスクリプトは LF でないと sh が読めない
*.bat    text eol=crlf    # バッチファイルは CRLF が Windows の作法
*.jar    binary           # jar は zip。改行変換されたら壊れる
```

Windows の git は checkout 時にテキストの改行を CRLF へ自動変換することがあり、CRLF になった `gradlew` はコンテナ内の sh が解釈に失敗する(「Windows のファイルシステム事情で `sh ./gradlew` にしている」という [docker-dev-containers.md](./docker-dev-containers.md) の話の親戚)。この 3 行は「gradlew は誰の環境でも LF、.bat は CRLF、jar は触るな」と git に固定させる保険。

### `HELP.md` — Initializr の置き土産(git 管理外)

Spring Initializr が生成した公式ドキュメントへのリンク集。**`.gitignore` に入っているのでコミットされない**(手元のフォルダにだけ存在する)。読んだら消してもよい類のファイル。

## どの言語で書かれているか — コード / 産物 / データの区別

図鑑に出てきた拡張子のうち紛らわしい 4 つは、「言語で書かれたコード」「コードをコンパイルした産物」「ただのデータ」が混ざっている:

| ファイル | 言語 | 分類 |
|---|---|---|
| `.gradle` | **Groovy** | プログラミング言語のコード |
| `.bat` | **バッチ言語**(Windows cmd.exe の命令) | スクリプト言語のコード |
| `.jar` | 言語ではない(中身は Java をコンパイルしたバイトコード) | 成果物のアーカイブ |
| `.properties` | 言語ではない(`キー=値` のテキスト) | ただのデータ形式 |

### .gradle — Groovy という JVM 言語

`build.gradle` の中身は **Groovy** のコード。Groovy は JVM 上で動く動的スクリプト言語で、Java の親戚(Java の構文をゆるくした感じ)。`dependencies { ... }` のような見た目は設定ファイルっぽいが、実際には**メソッド呼び出しとクロージャ**で、Groovy の文法を「設定っぽく読める」ように整えた **DSL**(ドメイン特化言語)。だから if 文やループも普通に書ける。なお Gradle には Kotlin で書く流儀もあり、その場合の拡張子は `.gradle.kts` になる(このプロジェクトは Groovy 版)。

### .bat — Windows のバッチ言語

`gradlew.bat` の中身は実際こうなっている(実物からの抜粋):

```bat
@if "%DEBUG%"=="" @echo off
set DIRNAME=%~dp0
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi
```

**cmd.exe(コマンドプロンプト)が解釈するバッチ言語**で、`%変数%` という参照や `%~dp0`(このスクリプト自身のあるフォルダ)という独特の記法を持つ Windows 専用のスクリプト言語。Unix 側の `gradlew` が**シェルスクリプト(sh の言語)**で書かれているのと対で、「同じ仕事を、それぞれの OS の母国語で書いた 2 通の台本」という関係。

### .jar — 言語ではなく「コンパイル済みコードの入れ物」

`.jar` に「書かれている言語」はない。実物を覗くと:

```
$ unzip -l gradle-wrapper.jar
  META-INF/MANIFEST.MF
  org/gradle/cli/CommandLineOption.class
  org/gradle/cli/CommandLineParser$AfterOptions.class
  ...
```

zip の中に詰まっているのは `.class`(元は **Java** で書かれたソースをコンパイルした JVM バイトコード)。「Java で書かれている」と言いたくなるが、正確には「Java で書かれたコードの、翻訳後のバイナリを固めたもの」で、ソースコードはもう入っていない(変換の系譜は ③ の図)。

ちなみに Groovy や Kotlin も JVM 言語なので、コンパイルすると同じ `.class` になる。jar の中身が「元は何語だったか」は外からは基本分からない——JVM にとってはどれも同じバイトコードだから。

### .properties — 言語ですらないデータ

`キー=値` を 1 行 1 件並べるだけの**データ形式**で、文法も制御構造もない。フロント側で言う `.env` や、application.yml の YAML と同じ「設定を書くための書式」カテゴリ。Java 標準ライブラリに読み込み機能が最初から入っていたため Java 界で伝統的に使われてきた(YAML はその後発の、階層を表せるリッチ版という位置づけ)。

まとめ: **「実行される言語」は Groovy(.gradle)とバッチ(.bat)の 2 つ、`.jar` は Java 由来のバイトコードの梱包、`.properties` はただの設定データ。**

## 用語集

- **Groovy** — JVM 上で動く動的スクリプト言語。build.gradle を書く言語(Java の親戚)
- **DSL(ドメイン特化言語)** — 特定用途向けに「設定っぽく読める」よう整えた言語の使い方。build.gradle は Groovy の DSL
- **JVM 言語** — コンパイルすると同じ .class(バイトコード)になる言語群。Java / Groovy / Kotlin など
- **コンパイル** — 人間用のソースコード(.java)を JVM 用のバイトコード(.class)に翻訳すること
- **バイトコード** — JVM が直接実行できる中間言語。`.class` ファイルの中身
- **jar(Java ARchive)** — .class 一式とリソースを 1 個に固めた zip。配布・実行の単位。「成果物の jar」と「道具としての jar(wrapper)」の二役がある
- **build(タスク)** — コンパイル → テスト → jar 梱包までの一括タスク。bootRun の「起動」の代わりに「梱包」で終わる
- **clean(タスク)** — `build/` を丸ごと削除する片付けタスク
- **`-x <タスク>`** — 指定タスクを除外(exclude)する Gradle のオプション。`-x test` でテストを飛ばす
- **Boot jar / plain jar** — 依存まで全部入りで単体起動できる jar と、自分のクラスだけの素の jar。`build` は両方作る
- **バッチファイル(.bat)** — Windows のコマンドプロンプト用スクリプト。シェルスクリプトの Windows 版
- **.properties** — `キー=値` を並べる Java 界の伝統的な設定ファイル形式
- **標準レイアウト** — `src/main/java` / `src/test/java` という Gradle/Maven 共通のフォルダ規約。設定なしで認識される
- **差分ビルド(インクリメンタルビルド)** — 前回から変わったファイルだけ再コンパイルする高速化。`.gradle/` の帳簿が支える
- **`.lock` ファイル** — 「使用中」を示す鍵。複数プロセスが同じ帳簿に同時に書き込むのを防ぐ
- **LF / CRLF** — 改行コードの流儀(Unix 系 / Windows)。`.gitattributes` でファイル種別ごとに固定できる
- **`!`(gitignore の否定)** — ignore ルールの例外指定。「これだけは必ずコミットする」

## 関連

- `.class`・JVM・コンパイルの仕組み → [java-build-and-run.md](./java-build-and-run.md)
- Gradle Wrapper の動作と `~/.gradle` キャッシュ → [gradle-basics.md](./gradle-basics.md)
- build.gradle の依存宣言の読み方 → [gradle-dependencies.md](./gradle-dependencies.md)
- `sh ./gradlew` と実行権限・改行問題 → [docker-dev-containers.md](./docker-dev-containers.md)
