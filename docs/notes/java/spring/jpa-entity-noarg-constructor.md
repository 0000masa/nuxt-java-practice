# JPA エンティティの「引数なしコンストラクタ」— なぜ空の `protected Post()` が要るのか

`Post.java` にある、中身が空で誰も呼んでいないように見えるコンストラクタ:

```java
// JPA 専用の引数なしコンストラクタ。
protected Post() {
    // JPA 用
}
```

「これは何のためにあるのか」「消したらダメなのか」「Java の書き方? それとも Spring Boot 特有?」に答える学習メモ。結論から言うと **Spring Boot 特有ではなく、JPA(ORM)を使う Java エンティティ共通のお約束**。理由は「JPA の要求」「Java の言語仕様」「アクセス制御の設計」の 3 つが重なっている。

対象ファイル: [Post.java](../../../../backend/src/main/java/com/example/app/post/Post.java)

## まず結論(3 行)

1. **Hibernate が DB の行をオブジェクトに変換するとき、まず「空の器」を引数なしコンストラクタで作る**から必要。
2. **Java は「コンストラクタを 1 つでも書くと、自動生成される引数なしコンストラクタが消える」**ため、`Post(User, Category, String)` を書いた時点で手で書き足す必要が出る。
3. **`protected` なのは「アプリからは使わせず、JPA には使わせる」ため。** JPA 仕様上 `public` か `protected` に限られ、その中で最も狭いのが `protected`。

## なぜ引数なしコンストラクタが要るのか — 3 つの理由

### 理由① JPA(Hibernate)が「空の器」を作るために要求する

Hibernate は、DB から取ってきた 1 行を Post オブジェクトに変換するとき、**リフレクション**(実行時にクラスの構造を名前で動的に調べて操作する Java の仕組み)を使って `new Post()` 相当を呼び、**まず中身が空のインスタンスを作ってから、フィールドに値を 1 つずつ差し込む**。この「空の器を作る」ステップで使うのが引数なしコンストラクタ。

なぜ引数付きの `Post(User, Category, String)` ではダメなのか? → Hibernate は「投稿者やカテゴリに何を渡せばいいか」を知らない。だから **引数ゼロで作れる入口**を要求する。「とりあえず空箱をくれ、中身は自分で入れるから」という発想。これが無いと取得時・起動時にエラーになる。

> JPA 仕様(Jakarta Persistence)の要求そのもの。Hibernate 以外の JPA 実装(EclipseLink 等)でも、他フレームワークで JPA を使っても同じく必要。**Spring Boot は Hibernate を組み込んでいるだけ**で、この要求の出どころではない。

### 理由② Java では「コンストラクタを書くと、デフォルトの引数なしが消える」

ここが見落としやすい Java の言語仕様。**コンストラクタを 1 つも書かなければ、コンパイラが引数なしコンストラクタ(デフォルトコンストラクタ)を自動で用意**してくれる。ところが **自分でコンストラクタを 1 つでも書くと、この自動生成は消える**。

`Post` は「投稿者・カテゴリ・本文を渡す」`Post(User, Category, String)` を定義している。その瞬間、自動のデフォルトコンストラクタは無くなり、理由①で必要な引数なしコンストラクタが存在しなくなる。**だから明示的に手で書き足している**。

```java
public Post(User user, Category category, String body) { ... }  // これを書いた瞬間、
                                                                // 自動の引数なしコンストラクタが消える
protected Post() { }                                            // → JPA のために手で復活させる
```

言い換えると、空の `protected Post()` は「引数付きコンストラクタを書いたせいで消えた引数なしコンストラクタを、JPA のために復活させている」行。(Java のコンストラクタ判定・オーバーロードの一般論 → [constructor-declaration.md](../syntax/constructor-declaration.md))

### 理由③ なぜ `public` でも `private` でもなく `protected` なのか

`protected` は「同じパッケージ + 継承したクラスからだけ見える」アクセス修飾子。

- **`public` にしない理由**: `public` だと、アプリのどこからでも `new Post()` で**投稿者も本文も無い"中身が空の不正な Post"**を作れてしまう。それを防ぎ、正規の入口(引数付きコンストラクタ)だけを使わせたい。
- **`private` にしない理由**: JPA 仕様は「引数なしコンストラクタは **`public` か `protected`**」と定めている。`private` は不可(Hibernate がリフレクションで触れられる必要があるうえ、プロキシ生成のため継承先からも見える必要がある)。

その結果、**仕様で許される中で最も公開範囲が狭い `protected`** が選ばれる。「Hibernate には見せるが、アプリからはうっかり使えなくする」絶妙な設定。(可視性の各段階 → [java-package-basics.md](../../java-package-basics.md) の package-private の節)

## Hibernate が行をオブジェクトに変換する流れ(実体化)

引数なしコンストラクタが実際に呼ばれる瞬間を、`postRepository.findById(1)` を例に追う:

```
1. postRepository.findById(1) → SELECT * FROM posts WHERE id = 1
2. DB が posts の 1 行を返す
3. Hibernate:「まず空の Post を作ろう」→ リフレクションで protected Post() を呼ぶ  ← ここ
4. 返ってきた行の各値(id / user_id / body / created_at)を、空の Post のフィールドに差し込む
5. 値の詰まった Post が呼び出し側(Service)に返る
```

ポイントは **「空の器を先に用意 → あとから中身を詰める」** という 2 段構え。だから「全項目そろって初めて作れる」引数付きコンストラクタでは都合が悪く、引数ゼロの入口が要る。この「DB の行 → オブジェクト」の変換を **実体化(materialization / hydration)** と呼ぶ。実体化を担うのが Hibernate(= JPA 実装)であることは [repository-and-entity-vs-laravel-model.md](./repository-and-entity-vs-laravel-model.md) の 3 層(JPA / Hibernate / Spring Data JPA)とつながる。

## Java の書き方? Spring Boot 特有?

質問「これは Java ではなく Spring Boot 特有?」への答えは **「Spring Boot 特有ではない。JPA/Hibernate + Java 言語仕様の合わせ技」**。

| 要素 | どこ由来か |
|---|---|
| 「引数なしコンストラクタが**必須**」という要求 | **JPA(仕様)/ Hibernate(実装)** ← Spring Boot ではない |
| 「コンストラクタを書くと**デフォルトが消える**」ルール | **素の Java** の言語仕様 |
| 「`public` / `protected` に限る」制約 | **JPA(仕様)** |

Spring Boot 抜きで Hibernate を単体で使っても、同じ空コンストラクタは必要。**「ORM(JPA)を使う Java エンティティのお約束」**と覚えるのが正確。

## つまずきポイント

- **「使われていないから」と消さない。** アプリコードからは呼ばれないが、Hibernate が実体化で使う。消すと取得時・起動時にエラー(`No default constructor for entity` のようなメッセージ)。
- **`public` に緩めない。** 動きはするが、中身の無い不正な `Post` をアプリから作れるようになり、設計の防御が崩れる。
- **`private` にはできない。** JPA 仕様違反で、Hibernate が実体化・プロキシ生成できずエラーになる。
- **引数付きコンストラクタを消すと話が変わる。** もし `Post(User, Category, String)` を消せば、Java がデフォルトの引数なしコンストラクタを自動生成するので、`protected Post()` を書かなくても JPA は動く(ただしその場合「必須項目そろって初めて作れる」という設計上の利点は失われる)。
- **`record` はエンティティに使えない。** 「引数なしコンストラクタが作れない」「フィールドが不変」などの理由で、JPA エンティティに Java の `record` は基本使えない(DTO には使える。このプロジェクトの `CreatePostRequest` 等は record)。

## 用語集

- **引数なし(no-arg)コンストラクタ** — 引数を取らないコンストラクタ。JPA エンティティに必須
- **デフォルトコンストラクタ** — コンストラクタを 1 つも書かないとき Java が自動生成する引数なしコンストラクタ。別のコンストラクタを書くと消える
- **リフレクション** — 実行時にクラスの構造(コンストラクタ・フィールド等)を名前で動的に調べ・操作する Java の仕組み。Hibernate が実体化に使う
- **実体化(materialization / hydration)** — DB の行を読み、オブジェクトを作ってフィールドに値を詰める処理。JPA 実装(Hibernate)が担う
- **`protected`** — 同じパッケージ + 継承先クラスからだけ見えるアクセス修飾子。JPA 用コンストラクタで多用される
- **プロキシ** — Hibernate が遅延読み込み(LAZY)のために作る、エンティティを継承した"影武者"オブジェクト。継承するため元クラスの引数なしコンストラクタが見える必要がある
- **JPA / Hibernate** — Java の ORM 標準仕様(JPA)と代表的な実装(Hibernate)。この空コンストラクタを要求する張本人

## 関連

- Java のコンストラクタの見分け方・オーバーロード(なぜ複数コンストラクタを持てるか) → [constructor-declaration.md](../syntax/constructor-declaration.md)
- Entity と Repository の役割分担・JPA/Hibernate/Spring Data JPA の 3 層 → [repository-and-entity-vs-laravel-model.md](./repository-and-entity-vs-laravel-model.md)
- アクセス修飾子(`public`/`protected`/package-private/`private`)の違い → [java-package-basics.md](../../java-package-basics.md)
- `Post` エンティティ全体の読み方 → [Post.java](../../../../backend/src/main/java/com/example/app/post/Post.java) のコメント
