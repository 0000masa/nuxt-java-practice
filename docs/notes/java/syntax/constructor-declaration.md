# コンストラクタの見分け方 — Java は「クラス名と同名・戻り値なし」、他言語は?

「なぜ `public PostController(PostService postService) { ... }` がコンストラクタだと分かるのか? PHP の `__construct()` のような決まった名前は要らないのか?」という疑問への答えをまとめた学習メモ。言語によって「どれをコンストラクタとみなすか」の決め方が違い、大きく **クラス名一致型(Java など)** と **固定名型(PHP / JS / Python など)** の 2 系統に分かれる。

## Java のルール — 2 条件を両方満たすとコンストラクタ

Java は、次の **2 つを両方満たすメソッドをコンストラクタとして扱う**。

1. **クラス名と完全に同じ名前**(`PostController` クラスなら `PostController`)
2. **戻り値の型を書かない**(`void` すら書かない)

このプロジェクトの実物で確認する。

```java
// PostController.java
public class PostController {
    private final PostService postService;

    public PostController(PostService postService) {  // 名前=クラス名、戻り値の型なし → コンストラクタ
        this.postService = postService;
    }
}
```

`public` の直後に戻り値の型が無く、名前が `PostController`(クラス名と同じ)なので、Java はこれをコンストラクタと認識し、`new PostController(...)` のときに自動で呼ぶ。

## 最大の落とし穴 — 戻り値の型を書くと「ただのメソッド」になる

Java で初学者がハマる有名なポイント。**うっかり戻り値の型を書くと、名前がクラス名と同じでもコンストラクタではなくなる。**

```java
public PostController(PostService s) { ... }        // ← コンストラクタ
public void PostController(PostService s) { ... }   // ← void を付けた瞬間「ただのメソッド」に化ける
```

しかも下は **コンパイルエラーにならない**(「クラス名と同名のメソッド」として文法上は成立してしまう)。結果、「コンストラクタのつもりが `new` のとき呼ばれず、`this.postService` が代入されないまま(= フィールドは宣言時の空 = `null`)」という気づきにくいバグになる。**「名前が同じ」だけでなく「戻り値の型が無い」がコンストラクタの決め手**、と覚えておく。

## Java の補足 — コンストラクタは複数持てる(オーバーロード)

Java のコンストラクタは、**引数の組み合わせを変えて複数**定義できる(オーバーロード)。このプロジェクトの `Post` エンティティが実例。

```java
// Post.java
protected Post() {                                    // 引数なし。JPA(DB マッピング)用
}
public Post(User user, Category category, String body) {  // 引数あり。実際に投稿を作る用
    this.user = user;
    this.category = category;
    this.body = body;
}
```

どちらも名前は `Post`(クラス名)で戻り値の型が無いので両方コンストラクタ。`new Post(user, category, body)` と呼べば、引数が一致する下が使われる。

## 他言語の決め方 — 「固定名型」が多数派

Java / C++ / C# は「クラス名一致型」だが、**世の中ではむしろ「決まった名前のメソッド」で見分ける言語のほうが多い**。

| 言語 | コンストラクタの決め方 | 系統 |
|---|---|---|
| **Java** / C++ / C# | クラス名と同じ名前のメソッド(戻り値の型なし) | クラス名一致型 |
| **PHP** | `__construct()` という固定名 | 固定名型 |
| **JavaScript** | `class` 内の `constructor` という固定名 | 固定名型 |
| **TypeScript** | JS と同じ `constructor`(+ 独自の短縮記法あり) | 固定名型 |
| **Python** | `__init__`(初期化)/ `__new__`(生成) | 固定名型 |

「クラス名一致型」は、クラスをリネームするとコンストラクタ名も追従して直す必要がある。「固定名型」は名前が常に一定なので、その手間が無い代わりに「名前でクラスとの結びつきが見えにくい」という違いがある。

### PHP — `__construct()`(と歴史的な事情)

PHP は名前を問わず、**`__construct()` という魔法メソッド(magic method)**をコンストラクタとする。クラス名が何であってもこれを書く。

```php
class PostController {
    public function __construct(private PostService $postService) {}
    //                          ↑ PHP 8 の「コンストラクタプロモーション」。
    //                            引数に修飾子を付けるとプロパティ宣言+代入を同時に行う
}
```

歴史的には、PHP も **PHP 4 の頃は Java と同じ「クラス名と同じメソッド = コンストラクタ」方式**だった。PHP 5 で `__construct` が導入されて旧方式は非推奨になり、**PHP 8.0 で旧方式は完全に廃止**された。今 PHP でコンストラクタといえば `__construct` 一択。上の `public function __construct(private PostService $postService)` の書き方(コンストラクタプロモーション)は、後述する TypeScript のパラメータプロパティとそっくりで、どちらも「Java だと別々に書くフィールド宣言＋代入を 1 行に畳む」仕組み。

### JavaScript — `class` の `constructor`、その前は「`new` で呼ぶ関数」

現在の JavaScript(ES6 以降)は、`class` の中の **`constructor` という固定名のメソッド**をコンストラクタとする。

```js
class PostController {
  constructor(postService) {
    this.postService = postService;  // フィールドへの代入は自分で書く(Java と同じ手作業)
  }
}
new PostController(service);
```

ポイントが 2 つ。

- **1 クラスに `constructor` は 1 つだけ**。Java のようなコンストラクタのオーバーロード(複数定義)はできない。引数の個数で処理を変えたいなら中で分岐する
- **ES6 より前はコンストラクタ専用の仕組みが無かった**。当時は「普通の関数を `new` を付けて呼ぶ」とその関数がコンストラクタとして働いた(`function PostController(){ ... }` → `new PostController()`)。つまり JS では **`new` を付けて呼ぶことこそがコンストラクタ扱いの本体**で、`constructor` はその初期化処理を書く場所、という関係

### TypeScript — `constructor` + パラメータプロパティ

TypeScript は JavaScript に型を足した言語なので、コンストラクタの見分け方は **JS と同じく `constructor`**。TS はさらにコンパイルすると JS になる(`constructor` はそのまま残る)。

TS で特筆すべきは **パラメータプロパティ(parameter properties)** という短縮記法。**コンストラクタの引数にアクセス修飾子(`private` / `public` / `readonly` など)を付けると、同名のフィールド宣言と代入を自動で行ってくれる。**

```ts
// 通常の書き方(Java と同じく、フィールド宣言と代入を別々に書く)
class PostController {
  private postService: PostService;
  constructor(postService: PostService) {
    this.postService = postService;
  }
}

// パラメータプロパティを使うと、上とまったく同じ意味を 1 行で書ける
class PostController {
  constructor(private postService: PostService) {}
  //          ↑ private を付けるだけで this.postService フィールドが自動生成・自動代入される
}
```

この下の書き方は、Java の

```java
private final PostService postService;
public PostController(PostService postService) { this.postService = postService; }
```

を丸ごと 1 行に畳んだものに相当する。**Java は「フィールド宣言」と「代入」を必ず自分で書く**が、TypeScript(と PHP 8)は修飾子付き引数でそれを省略できる、という差。DI(依存性注入)でコンストラクタに依存を受け取るコードは 3 言語で発想が同じなので、この対応を知っておくと読み替えが速くなる。

TS のコンストラクタは、型のためのオーバーロード「シグネチャ」を複数書ける(ただし実装は 1 つ)が、Java のような「実装ごと複数のコンストラクタ」とは別物。

## つまずきポイント

- **Java で `void` を付けてコンストラクタが動かない。** 「名前はクラス名と同じなのに `new` で初期化されない」ときは、戻り値の型(特に `void`)を書いていないか疑う
- **JS/TS で `constructor` を複数書けない。** オーバーロードしたくなったら引数で分岐する。Java の感覚のまま複数書くとエラー
- **TS のパラメータプロパティは修飾子が必須。** `constructor(postService: PostService)` のように修飾子を付けないと、ただの引数でありフィールドは作られない。`private` / `public` などを付けて初めて自動生成される
- **PHP 8 でクラス名コンストラクタは使えない。** 古い記事で見かける「クラス名と同名の関数」は PHP 8 では通らない。`__construct` を使う

## 用語集

- **コンストラクタ** — クラスから実体(インスタンス)を作るとき最初に 1 回だけ呼ばれる初期化処理
- **クラス名一致型** — コンストラクタをクラス名と同名で見分ける方式(Java / C++ / C#)
- **固定名型** — 決まった名前で見分ける方式(PHP の `__construct`、JS/TS の `constructor`、Python の `__init__`)
- **オーバーロード** — 同じ名前で引数の組み合わせが違うメソッド/コンストラクタを複数定義すること。Java は可、JS/TS のコンストラクタは不可
- **魔法メソッド(magic method)** — PHP で `__` 始まりの、特定タイミングに自動で呼ばれる予約メソッド(`__construct` など)
- **コンストラクタプロモーション** — PHP 8 の、引数に修飾子を付けてプロパティ宣言＋代入を同時に行う短縮記法
- **パラメータプロパティ** — TypeScript の同種の短縮記法。修飾子付きコンストラクタ引数からフィールドを自動生成する
- **new** — インスタンスを生成しコンストラクタを呼ぶ演算子。JS では「`new` で呼ぶこと」自体がコンストラクタ扱いの本体
- **DI(依存性注入)** — 必要な部品を外から受け取る設計。コンストラクタで受け取る形が Java / TS / PHP で共通

## 関連

- `static` フィールドはいつ初期化されるのか(クラス初期化とインスタンス初期化の違い、TS / PHP との比較) → [class-vs-instance-initialization.md](./class-vs-instance-initialization.md)
- 変数宣言の型明示と型推論(`var`)、フィールドの型必須の話 → [type-declaration-and-var.md](./type-declaration-and-var.md)
- 配列と List(固定長/可変長) → [array-vs-list.md](./array-vs-list.md)
