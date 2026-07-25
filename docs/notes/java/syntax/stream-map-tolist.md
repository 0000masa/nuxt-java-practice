# Stream API — なぜ `.stream().map().toList()` と 3 つも書くのか

`PostService` や `CategoryService` に出てくる `.stream().map(...).toList()` は何をしているのか、「レスポンスが配列のときに必要なもの」なのか、をまとめた学習メモ。PHP / Laravel・TypeScript 出身者向け。結論を先に言うと:

- **「配列だから必要」ではない。** 必要なのは「**N 件のものを、N 件の別のものに作り替えたい**」から。同じ `PostService` でも 1 件だけの場所では使っていない。
- **正体は `for` 文 4 行の言い換え。** 特別な仕組みではなく、「空の受け皿を用意 → 1 件ずつ変換 → 詰める」を 1 行で書いたもの。
- **3 つ書くのは Java の `List` に `map` が無いから。** TS は `posts.map(...)` の 1 ステップで済む。Java は「`Stream` に移す → 加工する → `List` に戻す」の行きと帰りが要る。
- **一番近いのは Laravel の Collection。** `collect($posts)->map(...)->all()` と構造がそっくり(ただし遅延評価の有無が違う)。
- **`.map()` だけでは 1 件も処理されない**(遅延評価)し、**`.toList()` が返す List は変更できない**。この 2 つが実際に踏む地雷。

## 1. 「配列だから要る」のではない — 分岐点は「1 件ずつ作り替えるか」

`CategoryService` がやりたいことは、突き詰めるとこれだけ。

```
Repository が返すもの : List<Category>         ← DB 側の姿(displayOrder なども持つ)
API として返したいもの : List<CategoryResponse> ← API 側の姿(id と name だけ)
```

**1 件を変換する道具はすでにある** — `CategoryResponse.from(category)`。困っているのは「**一覧の中身 1 件ずつに、それを適用したい**」という一点だけ。

証拠として、同じ `PostService` の中に**一覧でない場所では使っていない**箇所がある。

```java
// PostService.java:79 / :93 — 1 件なので stream は不要。変換メソッドを直に呼ぶ
return PostResponse.from(post);
```

つまり判断基準は「配列(一覧)かどうか」ではなく「**1 件ずつ作り替える必要があるか**」。一覧を返す場合でも、変換が要らなければ `stream` は要らない。

## 2. 正体は `for` 文 — 4 行の言い換えにすぎない

まったく同じことを、`for` 文で書くとこうなる。

```java
List<CategoryResponse> result = new ArrayList<>();          // ① 空の受け皿を用意
for (Category category : categories) {                      // ② 1 件ずつ取り出す
    result.add(CategoryResponse.from(category));            // ③ 変換して受け皿に入れる
}
return result;                                              // ④ 受け皿を返す
```

`stream().map().toList()` は、**この 4 行とまったく同じこと**を 1 行で書いたもの。魔法ではない。

```java
return categories.stream().map(CategoryResponse::from).toList();
```

違いは「どう書くか」だけ。`for` 文が「**受け皿を作って、回して、詰めろ**」という手順の指示(命令型)なのに対し、`stream` は「**この一覧を、この変換で、List にしろ**」という結果の指示(宣言型)。受け皿の用意や詰める作業といった、毎回同じで間違えやすい部分が消えるのが利点。

## 3. 3 つの役割 — 工場のベルトコンベア

```
List<Category>            .stream()        .map(...)          .toList()
┌──────────────┐       ┌──────────┐   ┌──────────┐   ┌──────────────────┐
│ 部品が入った箱 │ ───→ │ コンベアに │→ │ 加工機    │→ │ 完成品を箱に詰め直す │
│ (Category×10) │       │ 載せる     │   │ 1 個ずつ  │   │ (Response×10)     │
└──────────────┘       └──────────┘   └──────────┘   └──────────────────┘
```

| メソッド | やること | 前後で変わるもの |
|---|---|---|
| **`.stream()`** | `List` の中身を 1 件ずつ流れる形にする。**この時点では何も変換しない**。箱をコンベアに載せるだけ | `List<Category>` → `Stream<Category>`。**元の List は一切変わらない** |
| **`.map(...)`** | 流れてくる各要素に関数を適用し、別のものに置き換える加工ステーション。**件数は変わらない**(10 件入れれば 10 件出る) | `Stream<Category>` → `Stream<CategoryResponse>`(中身の型が変わる) |
| **`.toList()`** | コンベアの終点。流れ終わったものを集めて `List` に戻す | `Stream<CategoryResponse>` → `List<CategoryResponse>` |

`map` は「地図」ではなく数学の**写像**(あるものを別のものに対応付ける)の意味。「`Category` を `CategoryResponse` に対応付ける」と読むと腑に落ちる。

## 4. なぜ Java だけ 3 ステップなのか — TS / PHP との比較

同じ「一覧を変換する」処理を 4 通りで書き比べると、Java だけステップが多い理由が見える。

```ts
// TypeScript(このプロジェクトのフロント側)
const result = categories.map(c => toCategoryResponse(c))
```

```php
// PHP(素)— メソッドではなくグローバル関数
$result = array_map(fn($c) => CategoryResponse::from($c), $categories);
```

```php
// PHP(Laravel の Collection)
$result = collect($categories)->map(fn($c) => CategoryResponse::from($c))->all();
```

```java
// Java
List<CategoryResponse> result = categories.stream().map(CategoryResponse::from).toList();
```

| | `map` はどこにあるか | 変換の起点 | 結果の取り出し |
|---|---|---|---|
| **TypeScript / JS** | **配列のメソッド**(最初から付いている) | 不要 | 不要(そのまま配列) |
| **PHP(素)** | **グローバル関数** `array_map` | 不要 | 不要(そのまま配列) |
| **PHP(Laravel)** | **Collection のメソッド** | `collect($array)` | `->all()` / `->toArray()` |
| **Java** | **Stream のメソッド** | `.stream()` | `.toList()` |

**Java の `List` には `map()` メソッドが無い。** だから一度 `Stream` という「流れを扱う専用の型」に移し替える必要があり、それが `.stream()`。そして `Stream` は `List` ではないので、最後に `.toList()` で戻す必要がある。

```
JS  : 配列 ──.map()──→ 配列                                              (1 ステップ)
PHP : 配列 ──array_map()──→ 配列                                         (1 ステップ)
Java: List ──.stream()──→ Stream ──.map()──→ Stream ──.toList()──→ List  (3 ステップ)
```

「Java の `List` に `map` が無いので、行きと帰りの変換が要る」 — これが 3 つ書く理由。JS が偉いのではなく、Java は「流れを扱う仕組み」を `List` から切り離して別の型にした、という設計判断の違い。切り離したおかげで `filter` などを自由に繋げられる、という見返りがある。

### Laravel の Collection が一番近い

対応関係がほぼそのまま重なる。

| Laravel | Java | 役割 |
|---|---|---|
| `collect($array)` | `.stream()` | 素の入れ物を「チェーンできる形」に移す |
| `->map(...)` | `.map(...)` | 1 件ずつ変換 |
| `->filter(...)` | `.filter(...)` | 絞り込み |
| `->all()` | `.toList()` | 素の入れ物に戻す |

Laravel で `collect()` と `->all()` を書いている人にとって、Java の `.stream()` と `.toList()` は**まったく同じ役割**。「なぜ 2 つ余計に書くのか」は「なぜ `collect()` と `->all()` を書くのか」と同じ話。

ただし**決定的な違いが 2 つ**ある。

- **Laravel の Collection は即座に処理する(eager)が、Java の Stream は終端操作まで待つ(lazy)**(→ 6 章)
- **Collection は何度でも使い回せるが、Stream は 1 回使うと終わり**(→ つまずきポイント)

### PHP の素の関数はクセがある

Java / Laravel と比べたときの PHP の素の関数の扱いにくさも押さえておくと、なぜ Laravel が Collection を用意したかが分かる。

```php
array_map(fn($c) => ..., $categories);   // コールバックが先、配列があと
array_filter($categories, fn($c) => ...); // 配列が先、コールバックがあと ← 引数の順が逆
```

引数の順序が揃っていないため、繋げて書くと入れ子が深くなって読みにくい。

```php
// 絞り込んでから変換すると、内側から外側へ読むことになる
$result = array_map(fn($c) => ..., array_filter($categories, fn($c) => $c->isPublic));
```

Java や Laravel のメソッドチェーンは、これを**書いた順に上から読める**形にしたもの。

```java
categories.stream()
        .filter(category -> category.isPublic())  // 先に絞り込んで
        .map(CategoryResponse::from)              // それから変換する、と上から読める
        .toList();
```

## 5. `CategoryResponse::from` の正体 — メソッド参照

```java
.map(CategoryResponse::from)
```

`::` は「**このメソッドを、関数そのものとして渡す**」という書き方(**メソッド参照**)。次の 2 行は完全に同じ意味。

```java
.map(CategoryResponse::from)                       // メソッド参照(短い)
.map(category -> CategoryResponse.from(category))  // ラムダ式(何をしているか見えやすい)
```

`category -> ...` が**ラムダ式** — その場で書く、名前のない小さな関数。TS のアロー関数 `c => ...`、PHP のアロー関数 `fn($c) => ...` と同じ発想。`.map()` は「1 件をどう変換するか」を関数として受け取るので、そこに渡している。

読むときは、メソッド参照を見たら頭の中でラムダ式に展開すると分かりやすい。

## 6. 遅延評価 — `.map()` だけでは 1 件も処理されない

Java の Stream は**遅延評価**で、`.map()` を書いた時点ではまだ 1 件も処理されない。`.toList()` のような**終端操作**が来て初めてラインの電源が入り、全体が動く。

```java
categories.stream().map(CategoryResponse::from);  // ← 何も起きずに終わる。エラーにもならない
```

`map` / `filter` のような**中間操作**は「加工機を並べる」だけの予約で、`toList` / `forEach` / `count` のような**終端操作**が実際の実行を引き起こす。Laravel の Collection は `->map()` の時点で即座に処理するので、ここは感覚が違う点。

**書いたのに動かない**という不具合はここが原因のことが多い。

## 7. `.toList()` が返す List は変更できない

Java 16 以降の `Stream.toList()` は**変更不可の List** を返す。あとから `add()` すると実行時に `UnsupportedOperationException` で落ちる。

```java
List<CategoryResponse> result = categories.stream().map(CategoryResponse::from).toList();
result.add(new CategoryResponse(999L, "追加分"));  // ← 実行時に例外
```

古い書き方の `.collect(Collectors.toList())` は変更できる `ArrayList` が返るのが通例なので、ここが違う。ネット上の古い記事は `Collectors.toList()` で書かれていることが多く、コピーしてくると挙動が変わる点に注意。

このプロジェクトは Java 21(`backend/build.gradle:12` の `JavaLanguageVersion.of(21)`)なので、短い `.toList()` が使える。API のレスポンスは組み立てたら返すだけなので、変更不可で困らない。

## 8. `map` / `filter` / `forEach` の使い分け

| メソッド | 件数 | 返すもの | 使いどころ |
|---|---|---|---|
| **`map`** | 変わらない(N → N) | 新しい Stream | 型や中身を作り替える |
| **`filter`** | 減る(N → M) | 新しい Stream | 条件に合うものだけ残す |
| **`forEach`** | — | **何も返さない** | 1 件ずつ副作用(出力・登録)を起こす |

`map` は「作り替えて新しい流れを作る」、`forEach` は「1 件ずつ何かをするだけ」。このプロジェクトには `forEach` の実例もある。

```java
// GlobalExceptionHandler.java:40-41 — 新しい List を作るのではなく、既存の Map に詰めるのが目的
e.getBindingResult().getFieldErrors()
        .forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
```

件数を変えたいときは `map` ではなく `filter` を挟む。加工機を増やすイメージで、いくつでも繋げられる。

```java
// 例: 非公開カテゴリーを除いてから変換する(現状このプロジェクトにこのルールは無い)
categories.stream()
        .filter(category -> category.isPublic())   // 減る(N → M)
        .map(CategoryResponse::from)               // 変わらない(M → M)
        .toList();
```

## このプロジェクトでの実例

`backend/src/main/java/` 配下で `stream` を使っているのは **2 箇所だけ**。

- `CategoryService.java:58-60` — 全件を `Category` → `CategoryResponse` に変換
- `PostService.java:68` — 1 ページ分を `Post` → `PostResponse` に変換

  ```java
  return new TimelineResponse(page.stream().map(PostResponse::from).toList(), nextCursor);
  ```

  `TimelineResponse` は `posts`(一覧)と `nextCursor`(1 個の値)を持つので、**一覧の部分だけ** `stream` を通し、`nextCursor` はそのまま渡している。「配列だから必要」ではなく「変換したい一覧にだけ使う」の実例。

使っていない場所(対比):

- `PostService.java:79` / `PostService.java:93` — 1 件なので `PostResponse.from(post)` を直に呼ぶ

## つまずきポイント

- **`.map()` だけ書いて動かない。** 終端操作(`toList` など)が無いと 1 件も処理されない。しかもエラーにならないので気づきにくい(→ 6 章)
- **`.toList()` の結果に `add` して落ちる。** 変更不可の List が返る。可変が要るなら `new ArrayList<>(result)` で包み直す(→ 7 章)
- **Stream は 1 回使うと終わり。** 変数に入れた Stream を 2 回使うと `IllegalStateException: stream has already been operated upon or closed` になる。Laravel の Collection や TS の配列のように使い回せない。必要なら元の `List` から `.stream()` を呼び直す
- **`Collectors.toList()` と `toList()` を混同する。** 古い記事のコピーで可変・不変が変わる。Java 16 以降なら短い `.toList()` でよい
- **`map` で件数を変えようとする。** `map` は必ず N → N。減らしたいなら `filter`
- **元の List が変わると思い込む。** Stream は非破壊で、元の `List` には一切手を触れない。結果は必ず戻り値で受け取る

## 用語集

- **Stream API** — 要素を流れ作業的に加工する Java の仕組み。`map` / `filter` などの変換系はここにある
- **ストリーム(Stream)** — 要素が 1 件ずつ流れていく「流れ」を表す型。`List` のような入れ物ではなく処理の通り道
- **`.stream()`** — `List` などをストリームに載せるメソッド。この時点では何も加工しない
- **`.map()`** — 各要素を別のものに作り替える中間操作。件数は変わらず型が変わる
- **`.toList()`** — ストリームの結果を `List` に集める終端操作。Java 16 以降。**変更不可**の List を返す
- **中間操作** — `map` / `filter` など、繋げるだけで実行されない操作
- **終端操作** — `toList` / `forEach` / `count` など、実際の処理を起動する操作
- **遅延評価** — 終端操作が呼ばれるまで処理を始めない仕組み
- **ラムダ式** — `category -> ...` のような、その場で書く名前のない小さな関数。TS のアロー関数、PHP の `fn()` に相当
- **メソッド参照** — `CategoryResponse::from` のように、既存メソッドを関数として渡す短い書き方
- **写像(map の語源)** — あるものを別のものに対応付けること。「地図」の意味ではない
- **命令型 / 宣言型** — 「どう処理するか」を書くのが命令型(`for` 文)、「何が欲しいか」を書くのが宣言型(Stream)
- **Collection(Laravel)** — PHP の配列をメソッドチェーン可能にする Laravel のクラス。Java の Stream に近いが即時評価で使い回せる

## 関連

- 配列と `List` の違い、`map` が `List` に無いこと自体の位置づけ → [array-vs-list.md](./array-vs-list.md)
- `List<Post>` のような型の書き方と `var` による型推論 → [type-declaration-and-var.md](./type-declaration-and-var.md)
- 変換先の DTO(`CategoryResponse` / `PostResponse`)がなぜ必要か → [../../../api/get-categories.md](../../../api/get-categories.md)
- [Java 21 API — Stream](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/stream/Stream.html)(`toList` の「変更不可」の記述もここ)

## 理解度チェック(自力で考えてみる用)

1. `PostService.java:79` の `return PostResponse.from(post);` を、無理に `stream` を使う形に書き換えようとしたら何が困る? なぜ 1 件のときは `stream` を使わないのか。
2. カテゴリーが DB に 10 件あるとき、`.map(CategoryResponse::from)` は `CategoryResponse.from` を何回呼ぶ? また `.toList()` を消して `.map(...)` で文を終えた場合は何回呼ばれる?
3. Laravel の `collect($posts)->map(...)->all()` と Java の `posts.stream().map(...).toList()` は、それぞれどの部分が対応している? そして「同じに見えて実は違う」点は何か。
