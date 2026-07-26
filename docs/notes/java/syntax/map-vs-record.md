# Map と record — キーが固定か、可変か

`GlobalExceptionHandler.handleValidation` に出てくる `Map<String, String> fieldErrors` は、なぜ `Map` なのか。TypeScript の `Record<string, string>` のようなオブジェクト型が Java に無いから仕方なく使っているのか、をまとめた学習メモ。

要点は 3 つ。

1. **`Map` は `Record<string, string>` の代用品ではなく、対応物そのもの**。TS の `Record` も実行時の正体は辞書で、Java でその役をやるのが `Map`
2. **選択の基準はただ 1 つ、「キーの一覧をコンパイル時に列挙できるか」**。できるなら `record` / クラス、できないなら `Map`。この基準は 3 言語すべてで同じ
3. **JSON になった瞬間、その区別は消える**。だから「JSON の形」ではなく「Java 側で型の恩恵を受けられるか」で選ぶ

`record` そのものの説明（何を自動生成するのか、record が無かった頃どれだけ手書きが必要だったか）は [object-and-class-by-language.md](../../object-and-class-by-language.md) にあるので、ここでは繰り返さない。

## 1. なぜこの問いが生まれるのか — PHP なら 1 つで済んでいた

PHP でバリデーションエラーを組み立てるなら、迷う余地がない。連想配列 1 つで終わる。

```php
$fieldErrors = [];
$fieldErrors['body'] = '本文を入力してください';
```

同じものを返す DTO も、PHP なら連想配列のままで通せる。

```php
return ['message' => '入力内容に誤りがあります', 'fieldErrors' => $fieldErrors];
```

ところが Java の同じ場所は、**1 つの型の中で 2 つの道具が使い分けられている**。

```java
// ErrorResponse.java:11
public record ErrorResponse(String message, Map<String, String> fieldErrors) {
```

外側は `record`、中身は `Map`。PHP なら全部 `[]` で済んでいたものが、なぜ 2 つに分かれているのか。これがこのノートの主題。

## 2. TypeScript / JavaScript のオブジェクトは、実行時には辞書

先に TS を見ると理解が早い。TS でこう書いたとき、

```ts
const fieldErrors: Record<string, string> = {}
fieldErrors['body'] = '本文を入力してください'
```

実行時に起きているのは「**オブジェクトという辞書に、`'body'` というキーで値を入れた**」ことだけ。JavaScript のオブジェクトは、後からいくらでもキーを足せる可変の辞書。

`Record<string, string>` は、その辞書に「キーは文字列、値も文字列ですよ」と**型の注釈を貼っているだけ**で、実行時には何の実体も無い（TS の型はコンパイル時に全部消える → [interface-vs-type.md](../../typescript/syntax/interface-vs-type.md)）。

つまり TS では、**キーが固定のオブジェクトも、キーが可変のオブジェクトも、実行時の正体は同じ辞書**。違うのは型の付け方だけ。

```ts
// 実行時はどちらも同じ「辞書」。型の書き方が違うだけ
interface Post { body: string; categoryId: number }   // キー固定
type Errors = Record<string, string>                  // キー可変
```

## 3. Java のオブジェクトは辞書ではない

ここが決定的に違う。Java のクラスや record のインスタンスは、**持つフィールドの名前と個数がコンパイル時に確定して、固定される**。

```java
CreatePostRequest req = new CreatePostRequest("こんにちは", 3L);
req.body();            // 決まったフィールドの読み出しはできる
// req["newKey"] = "何か";   ← こんな構文は存在しない。キーは後から増やせない
```

家に例えると、

- **TS / PHP のオブジェクト** … 後から部屋をいくらでも増築できる家
- **Java の record / クラス** … 設計図の時点で部屋数が決まっている家

増築できる家がほしいなら、Java では最初からそういう入れ物を選ぶ必要がある。それが `Map`。

**だから `Map` は「`Record<string, string>` が無いことの埋め合わせ」ではない。** TS で `Record<string, string>` と書く場面が、Java では `Map<String, String>` になる、というだけの素直な対応関係になっている。

## 4. どちらを使うか — 問いは 1 つだけ

```
「キーの一覧を、今このコードを書いている時点で列挙できるか」
                    │
        できる ─────┴───── できない
           │                  │
     record / クラス          Map
```

3 言語で並べると、判断の中身がまったく同じだと分かる。

| | キー固定（列挙できる） | キー可変（列挙できない） |
|---|---|---|
| **Java** | `record` / クラス | `Map<String, String>` |
| **TypeScript** | `interface` / `type` | `Record<string, string>` |
| **PHP** | クラス（`readonly` プロパティ） | 連想配列 |

**Java だけ特殊なのではなく、TS で書いても同じ判断になる。** 違うのは「PHP は両方を連想配列 1 つで兼ねられてしまうので、判断せずに済んでいた」という点だけ。

### なぜ fieldErrors は「列挙できない」のか

`GlobalExceptionHandler` は**全 Controller 共通**のクラス。ここに飛んでくるバリデーションエラーは、投稿作成のときもあれば、将来ユーザー登録やカテゴリー作成のときもある。キーになるのは「違反した項目名」なので、`body` かもしれないし `categoryId` かもしれないし、まだ存在しない DTO の項目名かもしれない。

証拠はコードに出ている。

```java
// GlobalExceptionHandler.handleValidation
e.getBindingResult().getFieldErrors()
        .forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
```

キーが `"body"` のような**文字列リテラルではなく、`error.getField()` というメソッドの戻り値**になっている。何が返るかは実行してみないと分からない。ここに文字列リテラルを書けないことが、そのまま「record では表現できない」ことを意味する。

### やりがちな失敗 — `Map<String, Object>` を万能の器にする

PHP / Laravel から来ると、連想配列の癖でこう書きたくなる。

```java
// ✗ 何でも入る器。連想配列の感覚をそのまま持ち込んだ形
Map<String, Object> post = new HashMap<>();
post.put("body", "こんにちは");
post.put("categoryId", 3L);
```

これは動くが、Java を使う意味をほぼ捨てている。

- `post.get("bodyy")` と綴りを間違えても**コンパイルエラーにならず、`null` が返るだけ**。気づくのは実行時
- `post.get("body")` の型は `Object` なので、使う側で毎回キャストが要る
- IDE の補完が効かない。「この器に何が入っているか」はコードを読んでも分からない

`record` なら `post.body()` と書けて、綴りミスはコンパイルエラー、型も `String` と分かっている。**キーを列挙できるなら record を選ぶ**のは、この安全網のため。

## 5. Java の Map、最小限だけ

このノートの主題に必要な範囲だけ触れる。

```java
Map<String, String> fieldErrors = new LinkedHashMap<>();
```

**左辺が `Map` で右辺が `LinkedHashMap` なのは、`Map` が仕様（インターフェース）、`LinkedHashMap` が実際の入れ物（実装）だから**（→ [interface-and-implements.md](./interface-and-implements.md)）。実装は 3 つ覚えておけば足りる。

| 実装 | 並び順 |
|---|---|
| `HashMap` | **覚えない**（並びは不定） |
| `LinkedHashMap` | 入れた順を保つ |
| `TreeMap` | キーの昇順に並べ替える |

PHP の連想配列は黙って挿入順を保つので、`HashMap` を使って「なぜ順番がバラバラなのか」と驚くのが最初の関門になる。

**取り出しは `null` に注意。**

```java
fieldErrors.get("body");    // "本文を入力してください"
fieldErrors.get("bodyy");   // null（例外ではない。綴りミスに気づけない）
```

**PHP の `foreach ($arr as $k => $v)` に当たる書き方**は `entrySet()`。

```php
foreach ($fieldErrors as $field => $message) { ... }   // PHP
```

```java
for (var entry : fieldErrors.entrySet()) {             // Java
    entry.getKey();     // 項目名
    entry.getValue();   // メッセージ
}
```

## 6. 名前が紛らわしい — 3 組の衝突

この主題は、言語をまたぐと名前が交差する。ここを混同すると確実に事故る。

### ① Java の `record` ≠ TypeScript の `Record`

**役割が逆にクロスしている。**

| 名前 | 正体 | 相手の言語での対応物 |
|---|---|---|
| Java の `record` | フィールド固定のデータ運搬クラス | ≒ TS の `interface` |
| TS の `Record<K, V>` | キー可変のマップ型 | ≒ Java の `Map` |

`ErrorResponse` が Java の record で、その中の `Map` が TS の `Record` にあたる、という対応を一度整理しておくと混乱しない。

### ② Java の `Map` ≠ TypeScript の `Map`

**TypeScript にも `Map` はあるが、これは `Record<string, string>` とは別物**で、JSON にしたときの挙動が決定的に違う。

```ts
JSON.stringify(new Map([['a', 'b']]))   // → "{}"     ← 中身が消える
JSON.stringify({ a: 'b' })              // → '{"a":"b"}'
```

`JSON.stringify` は Map の中身を見てくれない（列挙可能な自前プロパティを持たないため）。**TS の `Map` は API レスポンスにそのまま載せられない。** Java の `Map` は Jackson が正しく JSON オブジェクトにするので、ここで「Java の Map ＝ TS の Map」と結びつけると壊れる。

TS で「JSON にして返す辞書」がほしいなら、`Map` ではなく素のオブジェクト＋`Record<string, string>` を使う。

### ③ PHP の連想配列は、全部の役を兼ねる

PHP の `[]` は、Java でいう `record` の役も `Map` の役も、さらに `List` の役まで 1 つで担っている。便利な代わりに、**コードを読んでも作者がどのつもりで使ったのか分からない**。Java はその役割を型で分けている、と捉えると移行しやすい。

## 7. JSON にすると区別が消える — ただし方向によって非対称

### 返す方向（Java → JSON）— 区別は消える

`record` で返そうが `Map` で返そうが、Jackson（オブジェクト ↔ JSON 変換ライブラリ）はどちらも JSON オブジェクト `{...}` にする。

```json
{"message":"入力内容に誤りがあります","fieldErrors":{"body":"本文を入力してください"}}
```

`message` は record のフィールド由来、`fieldErrors` の中身は `Map` 由来だが、**JSON を見ただけでは見分けがつかない**。フロントから見れば、どちらもただのオブジェクト。

だから「JSON の形が同じなら Map でいいのでは」と考えたくなるが、それは**返す側の話でしかない**。差が出るのは受け取る方向。

### 受け取る方向（JSON → プログラム）— ここが非対称

このプロジェクトの起動中の API に実際にリクエストを投げて確認した結果。

**(a) Java: JSON → record は、実行時に型が検査される**

`CreatePostRequest.categoryId` は `Long`。ここに文字列を送るとどうなるか。

```
POST /api/posts  {"body":"テスト","categoryId":"abc"}
→ 400
   Cannot deserialize value of type `java.lang.Long` from String "abc"
```

**Java の型宣言は、実行時にも効いている。** Jackson が record のフィールドの型を見て、合わないものを弾いている。コンパイル時だけの飾りではない。

なおこのエラーは Spring の既定のエラー形式で返り、`ErrorResponse`（`message` / `fieldErrors`）の形にはなっていない。`GlobalExceptionHandler` に `HttpMessageNotReadableException` のハンドラが無いため。

**(b) Java: JSON → `Map<String, Object>` なら、何でも入る**

`Map` で受ければ型検査は働かない。`"abc"` も `3` も `null` も、そのまま入って先へ流れる。**record にした時点で無料で付いてくる安全網を、Map を選ぶと自分で捨てることになる。**

ちなみに、DTO に無いキーを送っても既定では無視される（弾かれない）。

```
POST /api/posts  {"body":"","categoryId":1,"unknownKey":"x"}
→ 400 {"message":"入力内容に誤りがあります","fieldErrors":{"body":"本文を入力してください"}}
   ※ unknownKey はエラーにならず、body の検証まで進んでいる
```

**(c) TypeScript: `as` は実行時に何もしない**

対して TS 側は、`Form.vue` でこう受けている。

```ts
// frontend/app/components/post/Form.vue:38
(data?.fieldErrors && Object.values(data.fieldErrors as Record<string, string>)[0]) ||
```

この `as Record<string, string>` は**「そう名乗っている」だけ**で、実行時には何の検査も起きない。実際に数値が入っていても TS は素通しする。

| | 型が効くタイミング |
|---|---|
| Java: JSON → `record` | **実行時**に Jackson が検査して弾く |
| Java: JSON → `Map` | 検査なし（何でも入る） |
| TS: JSON → `as Record<...>` | 検査なし（コンパイル時の自己申告のみ） |

「Java は型がうるさい」の実体は、**コンパイル時だけでなく境界（JSON の出入り口）でも型が仕事をしている**ということ。TS の型は境界で仕事をしない。

## 8. PHP の連想配列の落とし穴 — `{}` と `[]` が入れ替わる

PHP から来た人が JSON API で必ず一度は踏む地雷。**同じ「配列」でも、キーの形によって JSON の種類が変わる。**

実際に PHP 8.3 で確認した結果。

```php
json_encode(['body' => 'こんにちは'])   // → {"body":"こんにちは"}   オブジェクト
json_encode([1, 2])                     // → [1,2]                   配列
json_encode([0 => 'a', 2 => 'b'])       // → {"0":"a","2":"b"}       ← 添字が飛ぶとオブジェクト化
json_encode([])                         // → []                      ← 空だと必ず配列
json_encode(new stdClass())             // → {}                      オブジェクト
```

とりわけ厄介なのが最後の 2 行。**「連想配列を返すつもりだったが、たまたま空だったので `[]` が返り、フロント側で `Object.values()` が壊れる」**という事故が起きる。PHP 側は同じコードなのに、データ次第で JSON の型が変わる。

**Java と TypeScript ではこの事故は起きない。**

```java
Map<String, String> empty = new LinkedHashMap<>();
// → {} （空でもオブジェクト。配列にはならない）
```

`Map` は必ず JSON オブジェクト、`List` は必ず JSON 配列。中身に関係なく型が決まっている。これは「Java の型がうるさい」ことの分かりやすい見返りの 1 つ。

### Laravel なら何を使うか

参考までに、PHP 側でも「キー固定」を型で表現する道具は用意されている。

- **`readonly` クラス**（PHP 8.2 以降。プロパティ単位の `readonly` は 8.1 以降）— Java の `record` に最も近い。コンストラクタで受け取って以降変更できない
  ```php
  final readonly class CreatePostRequest {
      public function __construct(public string $body, public int $categoryId) {}
  }
  // json_encode すると {"body":"...","categoryId":3}
  ```
- **API Resource** — Laravel でレスポンスの形を整える仕組み。「返す器」を 1 箇所に固定するという意味で、このプロジェクトの `PostResponse` / `ErrorResponse` と同じ役割

つまり「連想配列で全部やる」は PHP でも今や推奨ではなく、Java の使い分けは PHP の現在の作法とも地続きになっている。

## 9. このプロジェクトでの実例

| 場所 | 何の例か |
|---|---|
| `ErrorResponse.java:11` | **record と Map の同居**。外側 2 キーは固定なので record、中身のキーは可変なので Map |
| `GlobalExceptionHandler.handleValidation` | `LinkedHashMap` の生成と、`error.getField()` を**キーとして実行時に**詰める処理 |
| `CreatePostRequest.java` | JSON → record。`Long categoryId` の型が実行時に効いている |
| `frontend/app/components/post/Form.vue:38` | Java 側の `Map<String, String>` を TS 側が `Record<string, string>` として受け取っている |

Java の `Map<String, String>` が、JSON を挟んで TS の `Record<string, string>` になっている。**このノートで比較した 2 つの型が、実際に 1 つのデータの両端で対応している**のが確認できる。

## つまずきポイント

- **Java の `record` と TS の `Record` を同じものだと思う。** 役割は逆。Java の record ≒ TS の interface、TS の Record ≒ Java の Map
- **TS の `Map` を API レスポンスに載せる。** `JSON.stringify(new Map(...))` は `{}` になり中身が消える。TS で辞書を JSON にするなら素のオブジェクトを使う
- **`map.get("typo")` の綴りミスに気づけない。** 例外ではなく `null` が返るだけ。record なら `.body()` でコンパイルエラーになる
- **`Map<String, Object>` を万能の器にする。** 型検査も IDE 補完も効かなくなる。キーを列挙できるなら record
- **`LinkedHashMap` は「入れた順」を守るが、「入れる順」までは保証されない。** `handleValidation` に 2 項目同時に違反するリクエストを 5 回投げたところ、`fieldErrors` の並びは `body, categoryId` と `categoryId, body` の**両方が現れた**。`LinkedHashMap` は正しく挿入順を保っているが、その手前の `getFieldErrors()` が返す順序（＝制約が評価される順序）がフィールド宣言順である保証は無い。表示順を確定させたいならフロント側で並べる必要がある
- **`Map<String, String>` の型引数は実行時には消える（型消去）。** `Map.class` はあっても `Map<String, String>.class` は書けない → [class-literal.md](./class-literal.md)
- **PHP の `json_encode` は空配列を `[]` にする。** 連想配列のつもりでも空なら `[]`。Java / TS ではこの現象は起きない

## 用語集

- **Map** — Java で「キー → 値」の対応表を表す型（インターフェース）。TS の `Record<K, V>` / PHP の連想配列に相当
- **HashMap / LinkedHashMap / TreeMap** — Map の代表的な実装。順不同 / 挿入順 / キー昇順
- **record（Java）** — データを運ぶだけのクラスを短く書く仕組み。フィールドは固定・変更不可
- **Record<K, V>（TypeScript）** — キー可変のオブジェクトを表す型。Java の `Map` に相当。Java の record とは別物
- **Map（TypeScript）** — TS 固有の辞書クラス。`JSON.stringify` すると中身が消えるため API レスポンスには使えない
- **連想配列（PHP）** — 文字列キーを持つ配列。Java の record 役も Map 役も List 役も 1 つで兼ねる
- **辞書** — 「キー → 値」で値を引く入れ物の総称。Map / Record / 連想配列はすべてこれ
- **キーが固定 / 可変** — キーの一覧をコンパイル時に列挙できるかどうか。この主題での唯一の判断基準
- **Jackson** — Java のオブジェクトと JSON を相互変換するライブラリ。record も Map も JSON オブジェクトにする
- **型消去** — ジェネリクスの型引数が実行時には失われる Java の仕様
- **readonly クラス（PHP 8.2+）** — 生成後に変更できないクラス。PHP で Java の record に最も近い書き方

## 関連

- `record` そのものの説明、record が無かった頃の手書きの長さ、PHP / TS のクラス観の違い → [object-and-class-by-language.md](../../object-and-class-by-language.md)
- TS の型は実行時に消える・`interface` と `type` の違い → [interface-vs-type.md](../../typescript/syntax/interface-vs-type.md)
- `Map` が仕様で `LinkedHashMap` が実装、という関係 → [interface-and-implements.md](./interface-and-implements.md)
- List と配列の使い分け（コレクションのもう一方の柱） → [array-vs-list.md](./array-vs-list.md)
- 型引数が実行時に消える（型消去）と `.class` → [class-literal.md](./class-literal.md)
- バリデーションが 2 層に分かれている理由 → [validation-layers.md](../../validation-layers.md)
