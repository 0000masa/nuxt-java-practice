# Java のパッケージの基礎 — 逆ドメインの命名と「ソースルート」から数える配置

`package com.example.app;` という 1 行に詰まっている 2 つの疑問 — 「なぜ `com` から始まる?」「なぜ `backend/src/main/java` はパッケージ名に含まれない?」 — の答えをまとめた学習メモ。前半が**命名**(名前をどう付けるか)、後半が**配置**(名前とファイルの場所がどう結びつくか)、終盤が **main と test の関係**(なぜテストも同じパッケージ名なのか)の話。

## パッケージとは — クラスの「完全な住所」

パッケージは Java の名前空間で、Laravel の `namespace App\Http\Controllers;` に相当する。「田中さん宛て」では日本中の田中さんに届かないが「東京都○○区の田中さん」なら一人に特定できるのと同じで、`Application` というクラス名単体ではなく `com.example.app.Application` という**完全修飾名**(パッケージ込みの正式名)で世界にただ 1 つのクラスを指せるようにする仕組み。

## 命名 — 所有ドメインを逆順に並べる慣習

`com` も `org` も特別なキーワードではなく、**「自分が持っているインターネットドメインを逆順に並べる」という世界共通の慣習**(逆ドメイン記法)の結果にすぎない:

- `com.example.app` ← ドメイン `example.com` の逆順 + プロジェクト名 `app`
- `org.springframework.boot` ← Spring チームのドメイン `springframework.org` の逆順

### なぜドメインを借りるのか

Java には Packagist / npm レジストリのような「名前の重複を弾く中央登録所」が最初からあったわけではない。世界中の組織が自由にライブラリを作れば `StringUtils` のような名前は確実に衝突する。そこで**既に世界唯一性が保証されている名前 = ドメイン**を接頭辞に借りることで、中央登録所なしで衝突を防いだ。DNS という既存インフラへの相乗りで、分散的に一意性を作る設計。

### なぜ逆順なのか

ドメインは `boot.springframework.org` と**右に行くほど大きな区分**だが、住所や分類は**左から大きい順**(東京都 → ○○区 → ○○町)のほうが整理しやすい。逆順にするとフォルダも `org/ → springframework/ → boot/` と大分類から掘る形になり、同じ組織のコードが 1 フォルダにまとまる。

### 知っておくと混乱しない 3 点

- **`com` と `org` に機能差はない。** 「com は商用、org は非営利」は元ドメインの区分の話で、コンパイラはどちらも同じ扱い。`io.github.〜`(GitHub ユーザー)や `jp.co.〜`(日本企業)も見かける
- **`com.example` は「名無しさん用」。** `example.com` は例示用に予約された誰のものでもないドメイン(RFC 2606)。Spring Initializr のデフォルトで、学習用ならそのままでよい。実プロダクトなら自社ドメインの逆順(例: `jp.co.dreamcareer.app`)が作法
- **`java.*` は例外。** `java.util.List` などは言語本体の標準ライブラリ用に予約された特別な名前空間で、ドメイン由来ではない

この慣習は `build.gradle` にも顔を出す。`group = 'com.example'`(backend/build.gradle)や依存指定 `org.springframework.boot:spring-boot-starter-webmvc` の前半(groupId)も同じ逆ドメイン記法で、「パッケージ名」と「依存のグループ ID」は慣習を共有している。

## 配置 — 一致ルールは「ソースルート」から数える

Java には「パッケージ名とフォルダ階層を一致させる」規則があるが、数え始める基準点は**リポジトリの root ではなく「ソースルート(source root)」**。このプロジェクトのソースルートは `src/main/java/` で、Java が管理するのは**そこから下だけ**:

```
backend/src/main/java/com/example/app/Application.java
└── Gradle と人間の都合 ──┘└── パッケージの領分(Java の世界)──┘
```

マンションに例えると、ソースルートは「敷地の入口」。部屋番号(パッケージ名)は敷地の入口からの相対位置だけを表し、「そのマンションがどの区に建っているか」(`backend/src/main/java`)は含まない。

基準点を `src/main/java` と決めているのは Java 言語ではなく **Gradle の `java` プラグイン**(backend/build.gradle の `id 'java'`)。「規約どおりの構成なら設定を書かなくてよい」という Convention over Configuration(設定より規約)の思想で、Maven が広めた標準レイアウトを Gradle も踏襲している(→ [backend-project-files.md](./backend-project-files.md))。変えたければ `sourceSets` 設定で明示できるが、このプロジェクトは規約どおり。

## コンパイル後の世界では `src/main/java` は消える

「なぜ外側の階層を含めてはいけないか」のより本質的な答え: **`src/main/java` はコンパイル後の世界に存在しない**から。

```
ソース:         backend/src/main/java/com/example/app/Application.java
                        └ ソースルート ┘└ パッケージ由来の階層 ┘
コンパイル結果: backend/build/classes/java/main/com/example/app/Application.class
                        └ 出力先(別の場所)┘└ 同じ階層が再現される ┘
本番 jar の中:  com/example/app/Application.class
```

コンパイルするとパッケージ由来の `com/example/app/` だけが出力先に再現され、`src/main/java` は跡形もなくなる。JVM がクラスを探す基準点(**クラスパス**)は `build/classes/java/main` や jar の中身の root であり、そこから `com/example/app` を辿る。

もし `package backend.src.main.java.com.example.app;` と書いたら、「実行時にもその階層が存在すること」を要求する意味になり、開発機のフォルダ構成と jar の中身が一致せず破綻する。**パッケージ名は「ソースの置き場所」ではなく、開発時・ビルド後・本番で変わらない「クラスの永続的な住所」**であり、変わらないものだけを含める。

## Laravel との対比 — PSR-4 は「基準点の対応表」

Laravel の `namespace App\Http\Controllers;` が `app/Http/Controllers/` に対応するのも、実は同じ「基準点から数える」仕組み。`composer.json` の

```json
"autoload": { "psr-4": { "App\\": "app/" } }
```

が「`App\` の基準点は `app/` フォルダ」という対応表になっている。名前空間はプロジェクト root からではなく PSR-4 で宣言した基準点から数える — Java のソースルートと同じ発想で、違いは宣言のしかただけ:

| | 基準点 | 対応の宣言 |
|---|---|---|
| Laravel(PSR-4) | `composer.json` に明示(`"App\\": "app/"`) | 書く |
| Java(Gradle) | 規約で暗黙(`src/main/java`) | 書かない(`java` プラグインの既定) |

## main と test — 同じ住所を持つ別の敷地

`src/test/java` のテストコード(`ApplicationTests`)も同じ `package com.example.app;` を名乗っている。main と test は別物なのか同じなのか — 答えは「**層によって使い分けられている**」。ファイル・ビルドの層では完全に別物、パッケージの層では同じ仲間になる。

### ビルドの層では別物 — ソースセット

Gradle は `src/main/java` と `src/test/java` を**ソースセット(source set)**という別々の単位で管理する。コンパイル出力も別フォルダに出る(このプロジェクトの実物):

```
src/main/java/com/example/app/Application.java
  → build/classes/java/main/com/example/app/Application.class

src/test/java/com/example/app/ApplicationTests.java
  → build/classes/java/test/com/example/app/ApplicationTests.class
```

2 つの敷地の関係は**一方通行**:

- **test → main は見える。** テストのクラスパスには「main の出力 + テスト用ライブラリ(`build.gradle` の `testImplementation` で宣言した JUnit など)」が積まれる
- **main → test は見えない。** 逆方向は積まれないので、本体からテストクラスは参照できない(本体がテスト用ライブラリにうっかり依存する事故を防ぐ)
- **jar に入るのは main だけ。** テストの .class もテスト用ライブラリも本番へは運ばれない

### パッケージの層では同じ — 同じ住所を名乗ると「家族扱い」

別の敷地なのにわざわざ同じパッケージ名にするのは偶然ではなく意図的な慣習で、狙いは Java の**第 4 の可視性**にある。Java のアクセス修飾子(クラス・メソッド・フィールドの公開範囲を決めるキーワード)は 4 段階:

| 修飾子 | そのメソッド・フィールドに触れる範囲 |
|---|---|
| `public` | どこからでも |
| `protected` | 同一パッケージ + 継承先クラス |
| **何も書かない(= package-private)** | **同一パッケージだけ** |
| `private` | 同じクラスの中だけ |

「何も書かない」が独立した意味(**同一パッケージにだけ見せる**)を持つのが Java の特徴。テストが本体と同じ住所を名乗っていれば、`public` にするほどではないメソッドやフィールドもテストから直接呼べる。テストは別の敷地(test)に住んでいても、**住所が同じだから家族として玄関を通れる**、という関係。おまけとして import も不要になる(`ApplicationTests` が `Application` を import していないのはこのため)。

### PHP(Laravel)には「同一名前空間の特典」がない

では Laravel でテストの名前空間を `Tests\` と本体(`App\`)から分けると、テストから触れなくなるものがあるのか? — **何もない**。PHP のアクセス制御は**クラス単位**で完結していて、名前空間は可視性に一切関与しないから:

| | Java | PHP |
|---|---|---|
| クラス自体の可視性 | `public` / package-private を選べる | 選べない(オートロードできるクラスは常にどこからでも `new` できる) |
| メソッド・プロパティの可視性 | 4 段階(package-private がある) | 3 段階(`public` / `protected` / `private`) |
| パッケージ・名前空間の役割 | 名前の整理 + **可視性の境界** | 名前の整理だけ |

- テストコードは名前空間がどこであれ、本体クラスの **public なメソッド・プロパティには普通に触れる**。`Tests\` と `App\` が別なことによる不利益はない
- **`private` / `protected` なメソッド・プロパティには直接触れない。** これは名前空間が同じでも別でも変わらない(Java でも `private` は同一パッケージからでも触れない — package-private とは別の段)
- **mock はアクセス制御の回避手段ではない。** mock(テスト用の偽物オブジェクト)は「テスト対象が**依存している相手**を差し替える」道具であって、テスト対象の private メソッドの中身に触るためのものではない。PHPUnit / Mockery で mock を作れるのは public / protected なメソッドの振る舞いまで
- どうしても private に触りたいときは **Reflection**(実行時にアクセス制限をこじ開ける仕組み。PHP にも Java にもある)という最終手段があるが、「private を直接テストしたくなったら、public な入口経由でテストするか、クラスを分けるサイン」というのが両言語共通の定石

まとめると: **Java には package-private という中間の段があるから、テストが本体と同じ住所を名乗る意味がある。PHP にはその段がないから、`Tests\` を分けても失うものがない。**

## つまずきポイント

- **VS Code で新規パッケージを作る起点。** エクスプローラーで `src/main/java` を右クリックして作れば正しく `com.example.xxx` になるが、`src` や `main` の下に直接フォルダを掘ると言語サーバーに「宣言されたパッケージと一致しません」と怒られる。エラーの意味はこのメモの一致ルールそのもの
- **`src/main/resources` はもう一つの「ルート」。** `application.yml` や Flyway の `db/migration/` はこちらが基準点で、中身はコンパイルされずそのまま出力・jar に同梱される(→ [flyway-basics.md](./flyway-basics.md))
- **テストのパッケージを本体とずらすと特典が消える。** テストを `com.example.app.tests` に置くとコンパイルは通るが、package-private なメソッド・フィールドに触れなくなる。「同じ住所」は 1 文字でも違えば他人(→ 上の「main と test」の節)
- **`@SpringBootTest` の設定クラス探索も住所頼み。** このアノテーションは `@SpringBootApplication` の付いたクラスを**テスト自身のパッケージから上へ遡って**探す。テストが `com.example.app` にいるから `Application` が自動で見つかる
- **`src/test/resources` というテスト専用のリソースルートもある**(このプロジェクトにはまだ無い)。テスト実行時だけ有効な `application.yml` を置き、本番設定を汚さずテスト用の設定に差し替える、といった使い方が定番

## 用語集

- **パッケージ** — Java の名前空間。Laravel の namespace に相当
- **完全修飾名** — パッケージ込みのクラスの正式名(例: `org.springframework.boot.SpringApplication`)。「住所付きの名前」
- **逆ドメイン記法** — 所有ドメインを逆順にして接頭辞にする命名慣習。世界規模の名前衝突を防ぐ
- **`example.com`** — 例示用に予約された誰のものでもないドメイン。雛形デフォルト `com.example` の由来
- **groupId / group** — Gradle・Maven での「作った組織」の識別子。パッケージ名と逆ドメイン慣習を共有する
- **ソースルート(source root)** — パッケージ階層を数え始める基準点のフォルダ。Gradle の規約では `src/main/java`
- **標準レイアウト** — `src/main/java` / `src/test/java` という Gradle/Maven 共通のフォルダ規約。設定なしで認識される
- **クラスパス** — JVM が実行時にクラスを探す基準点のリスト。`build/classes/java/main` や jar がその一員
- **Convention over Configuration(設定より規約)** — 規約どおりなら設定を書かなくてよいという思想。ソースルートにパス設定が不要な理由
- **sourceSets** — ソースルートの場所を明示的に変えるときの Gradle 設定(このプロジェクトは未使用・規約どおり)
- **PSR-4** — PHP の「名前空間 ↔ フォルダ」対応規約。`composer.json` の対応表が Java のソースルートに相当
- **ソースセット(source set)** — Gradle がソースを管理する単位。main(本体)と test(テスト)は別のソースセットで、出力先も jar への梱包も別
- **アクセス修飾子** — クラス・メソッド・フィールドの公開範囲を決めるキーワード(`public` / `protected` / 無記述 / `private`)
- **package-private** — アクセス修飾子を何も書かないときの可視性。「同一パッケージにだけ見せる」。PHP には無い段
- **フィールド / プロパティ** — クラスが持つ変数のこと。Java では「フィールド」、PHP では「プロパティ」と呼ぶ(同じ概念の呼び名違い)
- **メンバー** — メソッドとフィールドの総称(クラス自体は含まない)。世間の Java 記事で頻出するのでここに載せておくが、このメモでは使わず具体的に書く
- **mock(モック)** — テスト対象が依存する相手を差し替えるテスト用の偽物オブジェクト。アクセス制御の回避手段ではない
- **Reflection** — 実行時にクラスの構造を調べたりアクセス制限をこじ開けたりする仕組み。private のテストに使えるが最終手段

## 関連

- backend のフォルダ・ファイル構成の図鑑(標準レイアウトの位置づけ) → [backend-project-files.md](./backend-project-files.md)
- コンパイル・jar・クラスパスと言語ごとのツーリングの違い → [build-and-tooling-by-language.md](./build-and-tooling-by-language.md)
- Group / Artifact の入力からパッケージ名が決まった経緯 → [spring-initializr.md](./spring-initializr.md)
