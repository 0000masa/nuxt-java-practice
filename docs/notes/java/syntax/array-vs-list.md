# 配列と List — 固定長・可変長と「便利メソッドはどちらにあるか」

`List<Post> fetched` の `List` とは何か、素の配列(`Post[]`)と何が違うのか、をまとめた学習メモ。要点は 2 つ — **①配列は「固定長」、List は「可変長」** ②**Java では便利メソッドが豊富なのは配列ではなく List(コレクション)側**。PHP / TypeScript の「なんでもできる配列」の感覚に一番近いのは、Java では配列ではなく List になる。

## 固定長と可変長 — サイズを後から変えられるか

「固定長」「可変長」は、**要素の個数(サイズ)をあとから変えられるかどうか**の違い。

- **固定長** — 作るときにサイズが決まり、あとから増減できない。「**席数が決まった劇場**」。10 席で作ったら 11 人目は座れない。増やしたければ、より大きい入れ物を新しく作って中身を移し替えるしかない
- **可変長** — 実行中に要素を自由に追加・削除できる。「**椅子を足せる会議室**」

Java での対応:

```java
// 配列 = 固定長。new のときにサイズ(3)を決め、あとから増やせない
Post[] arr = new Post[3];
arr[0] = post;   // 決まった枠への代入はできる
// arr に 4 個目を「追加」する操作は存在しない

// List = 可変長。好きなだけ足せる/減らせる
List<Post> list = new ArrayList<>();
list.add(post);    // 追加
list.remove(0);    // 削除
```

`PostService.getTimeline` で `fetched.subList(0, limit)`(一部を切り出す)や `fetched.size()`(個数を測る)ができたのは、`fetched` が可変長で便利メソッドを持つ `List` だったから。

## Java の素の配列は「低機能」

ここが PHP / TypeScript と大きく違う点。**Java の素の配列(`Post[]`)は非常に低機能**で、配列そのものが持っているのは実質次の 2 つだけ。

- `arr.length` — 要素数(後述するが、これは**メソッドではなくフィールド**)
- `arr[i]` — 添字(インデックス)でのアクセス

`add`(追加)も `map` / `filter`(変換・絞り込み)も `forEach`(繰り返し)も、**配列には用意されていない**。配列でそういう操作をしたければ、`Arrays` という**別の道具クラス**の static メソッド(`Arrays.sort(arr)`、`Arrays.stream(arr)` など)を経由する必要がある。配列は「素材」で、便利な道具は外部に置いてある、というイメージ。

## List(コレクション)は高機能

一方 **`List` には便利メソッドがたくさん付いている**。`add` / `remove` / `get` / `set` / `size` / `contains` / `subList` / `forEach` など。`List` は「要素をまとめて扱う入れ物の一族」= **コレクション**の一員で、最もよく使う実装が `ArrayList`(内部で配列を使いつつ、自動でサイズを広げてくれる)。

実務では、素の配列よりこの `List` を使うことがほとんど。「順序を持つ複数要素を可変長で扱いたい」ときの Java の主役はこちら。

## PHP / TypeScript との比較

| | サイズ | 便利な操作 |
|---|---|---|
| **PHP の配列** | 可変長で柔軟 | `array_map` / `array_filter` など豊富(※メソッドではなく関数) |
| **TypeScript / JS の配列** | 可変長 | `.map` / `.filter` / `.push` などメソッドが最初から付く |
| **Java の配列 `Post[]`** | **固定長** | ほぼ無し(`length` と添字だけ) |
| **Java の `List<Post>`** | 可変長 | `add` / `remove` / `size` / `subList` など豊富 |

PHP / JS では「配列」1 つが柔軟性も便利メソッドも兼ねるが、Java はその役割が **配列(素材・固定長)と List(高機能・可変長)に分かれている**。だから TS の `Post[]` に感覚が近いのは、Java では `List<Post>` のほう。

```java
List<Post> list = ...;  // ≒ TypeScript の Post[] / Array<Post>
Post[] arr = ...;       // 固定長の低レベルな配列。別物
```

## map / filter は List でも「stream 経由」

ひとつ正確に言っておくと、`map` や `filter` は **`List` にも直接のメソッドとしては付いていない**。`.stream()` で「流れ」に変えてから使う。

```java
// JS のように list.map(...) とは直接書けない。stream を挟む
list.stream().map(PostResponse::from).toList();
```

`add` / `size` / `subList` などは `List` に直接付いているが、`map` / `filter` 系の変換は `stream()` 経由、という住み分け。`stream()` は配列側からも `Arrays.stream(arr)` で使えるので、「変換・集計は Stream API に任せる」という点は配列でも List でも共通。

## 個数の数え方が 3 種類ある — 混同注意

要素数や長さの取り方が、入れ物によって書き方が違う。初学者がよく間違えるところ。

```java
arr.length     // 配列 … メソッドではなく「フィールド」。カッコ無し
list.size()    // List … メソッド。カッコあり
str.length()   // String(文字列)… メソッド。カッコあり
```

配列だけ `length`(カッコ無しのフィールド)で、List は `size()`、String は `length()`(どちらもカッコありのメソッド)。`arr.length()` や `list.length` と書くとエラーになるので、3 つセットで覚えておくと安全。

## つまずきポイント

- **「配列に add したい」で詰まる。** 固定長なので配列には `add` が無い。可変長がほしいなら最初から `List`(`ArrayList`)を使う
- **配列の便利操作は `Arrays` クラス経由。** `arr.sort()` ではなく `Arrays.sort(arr)`。「配列自身は低機能、道具は外部クラス」を思い出す
- **`List.of(...)` で作ったリストは変更不可。** `List.of("a","b")` は要素を追加・削除できない不変リストを返す。可変にしたいなら `new ArrayList<>()`
- **`length` と `size()` と `length()` の混同。** 上の 3 種を取り違えるとコンパイルエラー

## 用語集

- **固定長** — サイズを後から変えられない性質。Java の配列(`Post[]`)がこれ
- **可変長** — 要素を自由に追加・削除できる性質。`List`(`ArrayList`)がこれ
- **配列(array)** — `Post[]` のような固定長・低機能の入れ物。`length` と添字アクセスだけを持つ
- **List** — 順序を持つ可変長の入れ物。`add` / `size` / `subList` など豊富なメソッドを持つ
- **コレクション** — 要素をまとめて扱う入れ物の一族(List / Set / Map など)の総称
- **ArrayList** — List の最もよく使う実装。内部で配列を使いつつ自動でサイズを広げる
- **ジェネリクス** — `List<Post>` の `<Post>` の部分。「中身の型」を指定して型安全にする仕組み
- **Arrays** — 配列を操作する static メソッドを集めた道具クラス(`Arrays.sort` / `Arrays.stream` など)
- **Stream API** — `stream().map().filter()...` で要素を流れ作業的に加工する仕組み。map/filter はここにある

## 関連

- 変数宣言の型明示と型推論(`var`)、`List<Post> fetched` の型の書き方 → [type-declaration-and-var.md](./type-declaration-and-var.md)
- コンストラクタの見分け方(クラス名と同名・戻り値なし) → [constructor-declaration.md](./constructor-declaration.md)
