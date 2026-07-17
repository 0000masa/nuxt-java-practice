# build.gradle と Gradle の依存管理

`build.gradle` とは何か、依存ライブラリをどう追加するかについての学習メモ。PHP（composer）/ Node（npm）出身者向け。

## 結論

**`build.gradle` は Node の `package.json`、PHP の `composer.json` に相当する「依存関係とビルド設定の定義ファイル」**です。ただし大きな違いが一つあります: npm や composer には「コマンド一発で依存を追加し、定義ファイルも書き換えてくれる」コマンドがあるのに対し、**Gradle にはそれが（実質）なく、`build.gradle` をエディタで開いて自分で書き足します**。

| | Node | PHP | Java（Gradle） |
|---|---|---|---|
| 定義ファイル | `package.json` | `composer.json` | `build.gradle` |
| 依存を追加するコマンド | `npm install lodash` | `composer require guzzlehttp/guzzle` | **なし（ファイルを手で編集）** |
| 依存を取得するコマンド | `npm install` | `composer install` | `./gradlew build` などの実行時に自動取得 |
| ロックファイル | `package-lock.json` | `composer.lock` | なし（デフォルトでは） |

## 「戸惑うところ」の意味

`npm install <pkg>` に慣れていると、依存を足したいときに「打つべきコマンド」を探してしまいます。しかし Gradle の世界では次の手順が普通です。

1. `build.gradle` を開く
2. `dependencies { ... }` ブロックに 1 行書き足す
3. `./gradlew build`（や IDE のリロード）を実行すると、Gradle が足りないライブラリを自動でダウンロードする

「コマンドがファイルを書き換えてくれる」のではなく「自分がファイルを書き、コマンドはそれを読んで取得する」という順序の逆転が、PHP / Node 出身者が最初に戸惑うポイントです。

## build.gradle の中身（依存関係まわり）

Spring Initializr が生成する `build.gradle` の依存ブロックはこんな形です。

```groovy
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    runtimeOnly 'com.mysql:mysql-connector-j'
    developmentOnly 'org.springframework.boot:spring-boot-devtools'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
}
```

読み方:

- 各行が `スコープ 'グループ名:ライブラリ名'` の形。npm でいう `"lodash": "^4.17.21"` の 1 エントリに相当
- 先頭の単語は**スコープ**（いつ必要か）を表す
  - `implementation` — アプリ本体で使う（npm の `dependencies` に近い）
  - `runtimeOnly` — コンパイル時は不要で実行時だけ必要（例: MySQL ドライバ）
  - `developmentOnly` — 開発時のみ（devtools など）
  - `testImplementation` — テストコードでのみ使う（npm の `devDependencies` に近い）
- **バージョン番号が書かれていない**行が多いのは、Spring Boot が「動作確認済みのバージョンの組み合わせ表（BOM）」を持っていて、そこから自動で選ばれるため。npm のように個々のバージョンを自分で管理しなくてよいのが Spring Boot の楽な点

## 依存を後から追加する例

「Redis 用のライブラリを追加したい」と思ったら:

```groovy
dependencies {
    // ...既存の行...
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'  // ← この 1 行を自分で書き足す
}
```

書き足したら `./gradlew build`（IDE なら Gradle リロード）で取得されます。追加したいライブラリの正確な行は、ライブラリの公式ドキュメントか [Maven Central](https://central.sonatype.com/) で検索してコピーするのが定石です。

## 落とし穴

- **書き足しただけでは何も起きない。** `build.gradle` は定義ファイルにすぎず、`./gradlew build` や IDE のリロードで初めてダウンロードが走る
- **スコープを間違えると動かない/太る。** テスト用ライブラリを `implementation` に書くと本番 Jar に混ざり、逆に本体で使うものを `testImplementation` に書くとコンパイルエラーになる
- **バージョンを自分で書くのは例外的なとき。** Spring Boot 管理下のライブラリ（starter 系や主要ドライバ）はバージョンを書かないのが正しい。書くと BOM の組み合わせ保証から外れることがある

## 用語集

- **build.gradle** — Gradle の依存関係・ビルド設定の定義ファイル。package.json / composer.json 相当
- **dependencies ブロック** — build.gradle 内で依存ライブラリを列挙する場所
- **スコープ（implementation など）** — その依存が「いつ必要か」の区分
- **BOM（Bill of Materials）** — 動作確認済みのライブラリバージョンの組み合わせ表。Spring Boot が提供し、バージョン指定を省略できる理由
- **starter（spring-boot-starter-xxx）** — Spring Boot 公式の「関連ライブラリ一式セット」。1 行足すだけで一機能分の依存がまとめて入る

## 関連

- プロジェクト生成時に依存を指定する方法 → [spring-initializr.md](./spring-initializr.md)
