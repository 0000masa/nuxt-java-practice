# クラスリテラル(`.class`) — クラスそのものを値として渡す記法

`@ExceptionHandler(ResourceNotFoundException.class)` の末尾に付いている `.class` は何なのか、をまとめた学習メモ。結論を先に言うと:

- **`.class` は「クラスそのもの」を1個の値として取り出す記法。** クラス名の文字列ではない
- **返ってくるのは `Class<T>` 型のオブジェクト。** Java では「設計図」もまた1個のオブジェクトとして扱える
- **`new` では代用できない。** 意味の面(まだ存在しない「種類」を指したい)でも、文法の面(アノテーションの引数には実行時の計算を書けない)でも不可能
- **PHP の `Post::class` は見た目が同じで中身が別物。** あちらは文字列を返す。ここが最大の罠
- **TypeScript には相当する記法が無い。** クラス自体が最初から値なので、わざわざ取り出す必要がない

## 1. どこに出てくるか — このプロジェクトの実コード

`.class` はこのリポジトリの backend に3種類の顔で登場する。

```java
// ① アノテーションの引数として(GlobalExceptionHandler)
@ExceptionHandler(ResourceNotFoundException.class)
public ErrorResponse handleNotFound(ResourceNotFoundException e) { ... }

// ② 普通のメソッドの引数として(Application)
SpringApplication.run(Application.class, args);

// ③ テスト用アノテーションの引数として(PostControllerTest)
@WebMvcTest(PostController.class)
```

②が分かりやすい。`SpringApplication.run` はただのメソッドで、その第1引数に `Application.class` を渡している。つまり **`.class` はアノテーション専用の特殊な書き方ではなく、普通に「値」として持ち回れるもの**だということ。Spring はこれを受け取って「このクラスが置かれているパッケージを起点に、配下のクラスを探しに行く」という判断に使っている。

## 2. `Post.class` は何を返すのか — 「設計図」もまたオブジェクト

[object-and-class-by-language.md](../../object-and-class-by-language.md) で、クラスは**設計図**(たい焼きの型)、オブジェクトはそこから作られた**実体**(たい焼き)だと整理した。`.class` はここに一段のひねりを加える。

**Java では、その設計図そのものも1個のオブジェクトとして手に持てる。** それが `Class` 型のオブジェクトで、`.class` はそれを取り出す入り口。

```java
// 左辺の型に注目。Class<ResourceNotFoundException> という型の変数に代入できる
Class<ResourceNotFoundException> type = ResourceNotFoundException.class;

System.out.println(type.getName());        // com.example.app.common.exception.ResourceNotFoundException
System.out.println(type.getSimpleName());  // ResourceNotFoundException
```

つまり `.class` を付けた瞬間、それは「型の名前」ではなく **`Class<T>` 型の値**になる。だから変数に入れられるし、メソッドの引数として渡せるし、`List<Class<?>>` に詰めることもできる。

この `Class` オブジェクトは **JVM が1クラスにつき1個だけ持っている**。何度 `Post.class` と書いても、返ってくるのは常に同じ1個のオブジェクト。だから `==` で比較しても正しく一致する(この後の §4 で使う)。

## 3. なぜ `new` ではダメなのか — 理由は2つある

`@ExceptionHandler(new ResourceNotFoundException("..."))` と書けないのはなぜか。理由が2段階あり、どちらか片方でも致命的。

### 理由1 — 意味が合わない(こちらが本質)

指定したいのは「**まだ起きていない例外の“種類”**」であって、特定の1件の例外ではない。「404 を返すのは、これから飛んでくるかもしれない ResourceNotFoundException すべて」と言いたい。

実体(インスタンス)は「起きてしまった1件」しか表せないので、そもそも種類を指すのに使えない。**「種類」を値として名指しできるのが `.class` の存在理由**。

### 理由2 — 文法として書けない

アノテーションの引数には **コンパイル時に確定している値** しか書けない。書ける型は次の6種類に限定されている。

| アノテーションの引数に書ける型 | 例 |
|---|---|
| プリミティブ型 | `@Min(1)`、`@Size(max = 280)` |
| `String` | `@NotBlank(message = "本文を入力してください")` |
| **`Class`** | **`@ExceptionHandler(ResourceNotFoundException.class)`** |
| enum(列挙型) | `@ResponseStatus(HttpStatus.NOT_FOUND)` |
| 別のアノテーション | `@Valid` を内側に持つ形など |
| 上記の1次元配列 | `@ExceptionHandler({A.class, B.class})` |

`new ...` は「実行時に実体を作る」計算なので、コンパイル時には値が決まらない。**原理的にこの一覧に入れない。** 逆に言えば、`Class` がわざわざこの一覧に入っているのは、「型を指定したい」という需要が非常に多いから。

## 4. `.class` と `getClass()` の違い

似た顔をした2つ。**`.class` はコンパイル時に書いた型、`getClass()` は実行時の実体の型**を返す。

| | 書き方 | 何に付ける | いつ決まる |
|---|---|---|---|
| **クラスリテラル** | `Post.class` | **クラス名**に付ける | コンパイル時。ソースに書いた型そのもの |
| **`getClass()`** | `post.getClass()` | **オブジェクト**に付ける | 実行時。その変数に実際に入っている実体の型 |

普通は一致する。

```java
ResourceNotFoundException e = new ResourceNotFoundException("投稿が見つかりません");
e.getClass() == ResourceNotFoundException.class;  // true
```

差が出るのは**継承が絡むとき**。変数の型と、中に入っている実体の型がずれる場合がある。

```java
// 変数の型は親(RuntimeException)、中身は子(ResourceNotFoundException)
RuntimeException e = new ResourceNotFoundException("投稿が見つかりません");

e.getClass();                 // → ResourceNotFoundException.class(実体の型)
// RuntimeException.class     // ← ソースに書いた型。実体とは別物
```

Spring が例外を捌けるのはこの `getClass()` 側の性質のおかげ。変数の型が何であれ、飛んできた例外の**実体**の型を見て対応表を引ける。

## 5. Spring はこれを何に使っているか

`GlobalExceptionHandler` で起きていることを `.class` の視点で追うと、こうなる。

1. **起動時** — Spring がクラスを走査し、`@ExceptionHandler` に書かれた `Class` オブジェクトを回収して「例外の型 → 呼ぶメソッド」の対応表を作る
2. **実行時** — 例外が飛んでくると、その実体の型と対応表の `Class` を突き合わせ、合致したメソッドを呼ぶ

ここで効いているのが「**`Class` オブジェクトはクラスにつき1個**」という性質。だから対応表のキーとして使える。そしてこの「クラスやメソッドを実行時に名前で見つけて呼び出す仕組み」を **リフレクション** と呼ぶ。`.class` はそのリフレクションへの入口にあたる。

## 6. 他の言語との違い

### PHP — 同じ見た目で、返るものが違う

PHP にも `Post::class` という**そっくりな記法**がある。Laravel を書いていれば毎日見るはず。

```php
// Laravel — リレーション定義
public function user() {
    return $this->belongsTo(User::class);
}
```

だが **PHP の `::class` が返すのは「完全修飾クラス名の文字列」**。オブジェクトではない。

```php
echo User::class;      // "App\Models\User" ← ただの文字列
var_dump(User::class); // string(14) "App\Models\User"
```

| | Java `Post.class` | PHP `Post::class` |
|---|---|---|
| 返り値 | `Class<Post>` オブジェクト | `string`(完全修飾クラス名) |
| そこからできること | メソッド一覧の取得、インスタンス生成、型判定 | まず文字列からクラスを解決する必要がある |
| クラス名の文字列が欲しいとき | `Post.class.getName()` | そのまま使える |

**なぜ PHP は文字列で足りるのか。** PHP は文字列からクラスを直接扱える言語だから。`$class = User::class; new $class;` がそのまま動くし、オートロードが文字列を受け取ってファイルを探す。文字列とクラスの距離が近い。

Java はそうなっていない。文字列からクラスに戻すには `Class.forName("com.example...")` という明示的な変換が要り、しかも綴りを間違えても**コンパイルは通ってしまい実行時に落ちる**。`.class` で最初から `Class` オブジェクトとして持てば、綴り間違いはコンパイルエラーになるし、IDE のリネームも追従する。**Java が文字列ではなく `Class` を選んでいるのは、この型安全のため**。

なお PHP 8 のアトリビュート(`#[Route('/posts')]` など)は Java のアノテーションに近い仕組みだが、そこに渡すクラス指定もやはり `::class` の文字列。

### TypeScript — クラス自体が最初から値なので、記法が要らない

TypeScript(JavaScript)には `.class` に相当するものが**無い**。必要ないから。

```typescript
class Post {}

const t = Post;        // クラス名を書くだけで、それが値
console.log(t.name);   // "Post"
new t();               // 変数経由でインスタンス化もできる
```

JS ではクラスは実体としてはただの関数オブジェクトで、最初から値として存在している。だから「クラスを値として取り出す」という操作自体が発生しない。DI を持つフレームワーク(NestJS など)で `@Inject(UserService)` のようにクラスを直接書けるのはこのため。

一方で TypeScript には Java と逆の制約がある。**`interface` や型注釈は実行時に完全に消える**ため、`interface Post` を値として渡すことはできない。実行時にも残したければ `class` で書くしかない。

| | Java | PHP | TypeScript |
|---|---|---|---|
| クラスを値として指す書き方 | `Post.class` | `Post::class` | `Post`(そのまま) |
| 得られるもの | `Class<Post>` オブジェクト | クラス名の文字列 | クラス(関数)そのもの |
| 型情報は実行時に残るか | クラスは残る(型引数は消える) | クラスは残る | **クラスは残るが `interface`/型注釈は消える** |

## つまずきポイント

- **`.class` は「クラス名の文字列」ではない。** PHP 経験者が最も引っかかる点。文字列が欲しいなら `Post.class.getName()`(完全修飾名)か `getSimpleName()`(短い名前)を呼ぶ
- **`new` と `.class` は用途が排他。** 「実体が欲しい」なら `new`、「種類を指したい」なら `.class`。混ぜられない
- **`List<String>.class` とは書けない。** 書けるのは `List.class` まで。ジェネリクスの型引数は実行時には消えてしまう(型消去)ため、`Class` オブジェクトも型引数を区別して持てない。`List<String>` と `List<Post>` は実行時には同じ `List.class`
- **プリミティブ型にも `.class` がある。** `int.class`、`void.class` も書ける。オブジェクトではない型なのに `Class` オブジェクトだけは存在する、という例外的な扱い
- **`.class` で書かれたメソッドは「参照 0 件」に見える。** 呼び出しているのは Spring がリフレクション経由で行うため、ソース上に呼び出し行が存在しない。詳しくは `GlobalExceptionHandler.java` 冒頭のコメントを参照

## 用語集

- **クラスリテラル** — `Post.class` の書き方。クラスそのものを `Class<T>` 型の値として取り出す
- **`Class<T>`** — クラスの情報(名前・メソッド一覧・親クラスなど)を持つオブジェクトの型。JVM が1クラスにつき1個だけ持つ
- **リフレクション** — 実行時にクラスやメソッドを名前で見つけて呼び出す仕組み。`Class` オブジェクトがその入口
- **完全修飾クラス名(FQCN)** — パッケージ名まで含めた正式名称。`com.example.app.post.Post` のような形
- **型消去** — ジェネリクスの型引数がコンパイル後に消える Java の仕様。`List<String>.class` が書けない理由
- **アノテーションの引数の制限** — コンパイル時に確定する値(プリミティブ / String / Class / enum / アノテーション / それらの配列)しか書けないというルール

## 関連

- ダイヤモンド演算子 `<>` と型引数の省略 → [diamond-operator.md](./diamond-operator.md)
- クラスとオブジェクトの関係、3言語での立ち位置の違い → [../../object-and-class-by-language.md](../../object-and-class-by-language.md)
- 例外ハンドリングの全体像と他フレームワークとの比較 → [../spring/exception-handling-vs-other-frameworks.md](../spring/exception-handling-vs-other-frameworks.md)
- パッケージと完全修飾名 → [../../java-package-basics.md](../../java-package-basics.md)
