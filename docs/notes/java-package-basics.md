# Java のパッケージの基礎 — 逆ドメインの命名と「ソースルート」から数える配置

`package com.example.app;` という 1 行に詰まっている 2 つの疑問 — 「なぜ `com` から始まる?」「なぜ `backend/src/main/java` はパッケージ名に含まれない?」 — の答えをまとめた学習メモ。前半が**命名**(名前をどう付けるか)、後半が**配置**(名前とファイルの場所がどう結びつくか)の話。

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

## つまずきポイント

- **VS Code で新規パッケージを作る起点。** エクスプローラーで `src/main/java` を右クリックして作れば正しく `com.example.xxx` になるが、`src` や `main` の下に直接フォルダを掘ると言語サーバーに「宣言されたパッケージと一致しません」と怒られる。エラーの意味はこのメモの一致ルールそのもの
- **`src/main/resources` はもう一つの「ルート」。** `application.yml` や Flyway の `db/migration/` はこちらが基準点で、中身はコンパイルされずそのまま出力・jar に同梱される(→ [flyway-basics.md](./flyway-basics.md))
- **`src/test/java` は別のソースルート。** `ApplicationTests` のパッケージも同じ `com.example.app` — main と test は「同じ住所を持つ別の敷地」。だからテストから本体のクラスが import なしで見える(同一パッケージ扱い)

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

## 関連

- backend のフォルダ・ファイル構成の図鑑(標準レイアウトの位置づけ) → [backend-project-files.md](./backend-project-files.md)
- コンパイル・jar・クラスパスと言語ごとのツーリングの違い → [build-and-tooling-by-language.md](./build-and-tooling-by-language.md)
- Group / Artifact の入力からパッケージ名が決まった経緯 → [spring-initializr.md](./spring-initializr.md)
