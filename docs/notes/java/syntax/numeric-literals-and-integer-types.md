# 数値リテラルの型(1 と 1L) — 同じ「1」なのに型が違うとはどういうことか

`new CategoryResponse(1L, "お知らせ")` の `1L` は何なのか、DB には 1, 2, 3 という整数が入っているのになぜ Java では `1L` と書くのか、という疑問をまとめた学習メモ。結論を先に言うと、**`1L` の値は `1` とまったく同じ。`L` は値の一部ではなく「この 1 を long という大きさの箱で扱え」というコンパイラへの指示**。そして、この「リテラルに型を付ける」という発想は **整数型を複数持つ言語に固有のもの**で、PHP には存在せず、JavaScript / TypeScript には BigInt の `1n` という形で後から入った。

## 1L の値は 1 — まず確かめる

```java
System.out.println("1L == 1 -> " + (1L == 1));
```

```
1L == 1 -> true
```

`L` は数字に付く飾りでも、別の数を表す記号でもない。「1個」と書いても「1コ」と書いても数が 1 であることは変わらないのと同じで、`1` も `1L` も数としては 1。違うのは**入れ物のサイズだけ**。

## なぜ書き分けが要るのか — Java の整数型は 4 種類ある

| 型 | サイズ | 表せる最大値 |
|---|---|---|
| `byte` | 1 バイト | 127 |
| `short` | 2 バイト | 32,767 |
| **`int`** | 4 バイト | **2,147,483,647**(約 21 億) |
| **`long`** | 8 バイト | **9,223,372,036,854,775,807**(約 922 京) |

(下 2 つは `Integer.MAX_VALUE` / `Long.MAX_VALUE` を実行して確認した値)

そして Java には **「数字をそのまま書いたら `int` とみなす」というルール**がある。

```java
1     // Java はこれを int(4 バイトの箱)だと解釈する
1L    // L を付けると long(8 バイトの箱)だと解釈する
```

つまり `L` は「デフォルトの `int` ではなく `long` のほうで頼む」という指定。小数の世界で `1` と `1.0` を書き分けるのと同じ発想で、数はどちらも 1 だが型が違う。

> **サフィックスの種類** — `L`(long)のほかに `f`(float)、`d`(double)がある。`3.14` はそのままだと `double` なので、`float f = 3.14f;` のように書く。**小文字の `l` は使わない。** `1l` は数字の `1` と見分けが付かないため、大文字の `L` が慣習。

## L を省くとコンパイルエラーになる

`CategoryResponse` の第 1 引数は `Long` 型(`CategoryResponse.java:5`)。ここに `L` なしの `1` を渡すとコンパイルが通らない。

```java
new CategoryResponse(1, "お知らせ");   // ✗ エラー
new CategoryResponse(1L, "お知らせ");  // ○
```

```
error: incompatible types: int cannot be converted to Long
```

値は同じ 1 なのになぜ弾かれるのか。**箱の種類が違い、Java は箱の詰め替えを 1 段までしか自動でやらない**ため。

```
1   →  int(4バイト)  →  long(8バイト)  →  Long(オブジェクト)
                     ↑ 拡大変換        ↑ ボックス化   ← 2 段は自動適用されない = エラー

1L  →  long(8バイト)  →  Long(オブジェクト)
                      ↑ ボックス化 1 段だけ         ← これは自動でやってくれる = OK
```

`L` を付けるのは、この **1 段目を最初から済ませておくための書き方**。単独ならどちらの変換も許されるのに、連続すると許されない、というのが分かりにくい点。

- `long x = 1;` … ○(拡大変換だけ)
- `Long x = 1L;` … ○(ボックス化だけ)
- `Long x = 1;` … ✗(拡大変換 + ボックス化の 2 段)

## なぜ id は Long なのか — DB のカラム型から決まっている

Java 側の都合で選んでいるのではなく、**テーブル定義から逆算されている**。

```sql
-- V1__create_base_tables.sql
CREATE TABLE categories (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    name          VARCHAR(30) NOT NULL,
    display_order INT         NOT NULL,
```

| MySQL | サイズ | Java |
|---|---|---|
| `INT` | 4 バイト | `int` / `Integer` |
| **`BIGINT`** | **8 バイト** | **`long` / `Long`** |

`id` が `BIGINT` なのは、レコードが 21 億件を超えても破綻しないようにするため(`INT` だと約 21 億で頭打ち)。id に `BIGINT` を使うのは Web アプリの定石。

同じテーブルの `display_order` は `INT` なので、Java 側も `Integer` になっている。**カラム型と Java のフィールド型がきちんと 1 対 1 で対応している**。

```java
// Category.java
private Long id;              // ← BIGINT に対応
private Integer displayOrder; // ← INT に対応
```

`CategoryResponse` の `id` が `Long` なのも元をたどればこの `BIGINT` に行き着く。だからテストで値を作るときも `1L` と書く必要がある。

## 同じ id の旅路 — MySQL → Java → JSON → TypeScript

このリポジトリでは、1 つの id が層をまたぐたびに姿を変えている。

```
① MySQL          id = 1           BIGINT カラムの中の 1
      ↓ JPA が読み出す
② Java(Entity)   Long id = 1L     8 バイトの箱に入った 1
      ↓ CategoryResponse.from() で詰め替え
③ Java(DTO)      Long id = 1L     同じく 8 バイトの箱の 1
      ↓ Jackson が JSON に変換
④ JSON           "id": 1          型の区別が消えて、ただの数値 1
      ↓ フロントが受け取る
⑤ TypeScript     id: number       number 型の 1(frontend/app/types/category.ts)
```

**JSON には `long` も `int` も区別がない。** だから `CategoryControllerTest` の検証側は `.value(1)` と `L` なしで書ける。「Java の中でだけ型がうるさい」と切り分けて理解すると混乱しない。

## 他の言語ではどうか

「リテラルに型を指定する」「書いただけだと int」という発想は Java 固有のものではないが、**言語によって有無がはっきり分かれる**。分かれ目は「その言語が整数型を何種類持っているか」。

### PHP — 整数型は 1 種類。サフィックスは無い

PHP の整数型は **`int` の 1 種類だけ**。`long` も `short` も無いので、**書き分ける必要がなく、リテラルに型を指定する文法も存在しない**。

```php
$id = 1;        // int。これ以外の書き方は無い
echo PHP_INT_MAX;  // 64bit 環境なら 9223372036854775807(Java の long と同じ大きさ)
```

サイズは環境依存で、64bit 環境では 8 バイト。つまり **PHP の `int` は実質 Java の `long` 相当**であり、Java でいう `int`(4 バイト)にあたる型が無い。だから「どちらの箱にするか」という選択自体が発生しない。

注意すべきは範囲を超えたときの挙動で、**PHP は静かに `float` に化ける**。

```php
var_dump(PHP_INT_MAX + 1);  // float(9.2233720368548E+18) ← int ではなくなる
```

Java なら `long` を超えると桁あふれ(オーバーフロー)して負の値に回り込むが、PHP は型そのものが変わる。**どちらも気づきにくいが、方向性が違う**。

### TypeScript / JavaScript — number は 1 種類。ただし BigInt には `n` が付く

JavaScript の数値型は **`number` の 1 種類**で、整数専用の型が無い。実体は **IEEE 754 の倍精度浮動小数点**、つまり Java でいう `double` 相当のものが整数も担当している。

```ts
const id: number = 1   // 整数も小数も同じ number
```

小数で整数を表しているため、**正確に扱える整数には上限がある**。

```
Number.MAX_SAFE_INTEGER = 9007199254740991   (2^53 - 1、約 900 兆)
9007199254740993 => 9007199254740992         ← 超えると静かに丸められる
```

エラーにならず**値が変わる**のが厄介な点。

そして 2020 年に **BigInt** が追加され、ここで初めて **リテラルにサフィックスを付ける文法**が JavaScript に登場した。

```
typeof 1n = bigint | 1n == 1 -> true    ← 値としては 1 と等しい
1n + 1 => TypeError: Cannot mix BigInt and other types
```

**`1n` は Java の `1L` に一番近い存在**。「値は同じ 1 だが型が違う」「型をまたぐ自動変換をしてくれない」という性質までよく似ている。TypeScript でも `number` と `bigint` は別の型として扱われる。

ただし BigInt には JSON との相性問題がある。

```
JSON.stringify({id: 1n}) => TypeError: Do not know how to serialize a BigInt
```

### 3 言語の比較

| | 整数型の種類 | リテラルの型指定 | 既定の型 | 範囲を超えたら |
|---|---|---|---|---|
| **Java** | 4 種類(byte/short/int/long) | **あり**(`1L`) | `int`(4 バイト) | オーバーフローして負に回り込む |
| **PHP** | 1 種類(int) | **なし**(不要) | `int`(64bit 環境で 8 バイト) | `float` に化ける |
| **TypeScript / JS** | 1 種類(number)+ BigInt | **BigInt のみあり**(`1n`) | `number`(倍精度小数) | 2^53 超で静かに丸められる |

**「型指定の文法があるかどうか」は、その言語が同じ「整数」に対して複数の入れ物を用意しているかどうかで決まる。** 入れ物が 1 つしかない言語には、指定する必要も文法も無い。

## このリポジトリでの落とし穴 — BIGINT を number で受けている

上の「旅路」を見ると、`BIGINT`(8 バイト整数)を最終的に TypeScript の `number`(2^53 まで)で受けていることが分かる。

```ts
// frontend/app/types/category.ts
export interface Category {
  id: number      // ← Java 側は Long、MySQL 側は BIGINT
  name: string
}
```

JSON をパースした時点で丸めが起きるため、フロント側で防ぐ手立ては無い。

```
JSON.parse('{"id":9007199254740993}').id => 9007199254740992
```

**いつ壊れるか** — id が `9,007,199,254,740,991`(約 900 兆)を超えたとき。

**現状の評価 — 問題なし。** id は `AUTO_INCREMENT` で 1 ずつ増える学習用アプリなので、この桁に到達することはない。対応は不要。

**実務で実際に踏まれた例** — Twitter は投稿 id が 2^53 を超えたときにこの問題に遭遇し、API のレスポンスに数値の `id` とは別に**文字列版の `id_str` を追加**した。id をランダムな巨大値やタイムスタンプ由来の値で採番する設計(Snowflake ID など)では、**サービス開始直後から到達しうる**ので注意が要る。

## つまずきポイント

- **`1L` と `1` は同じ値。** 別の数ではない。違うのは入れ物のサイズだけ
- **`L` を付けるかどうかは気分ではなく、受け取り側の型で機械的に決まる。** 引数が `Long` なら必須、`int` なら不要
- **単独の変換は OK でも、2 段続くと自動ではやってくれない。** `Long x = 1;` が通らない理由はこれ
- **小文字の `l` は使わない。** `1l` は数字の 1 と紛らわしい
- **JSON になった時点で型の情報は消える。** テストで `.value(1)` と書けるのはそのため
- **PHP と JS は「静かに壊れる」方向。** Java は `L` の付け忘れをコンパイルエラーで教えてくれるが、PHP の float 化も JS の丸めも実行時に無言で起きる
- **BigInt は `JSON.stringify` できない。** BigInt を採用するなら API との境界で必ず変換が要る

## 用語集

- **リテラル** — ソースコードに直接書いた値そのもの。`1`、`"お知らせ"`、`true` など
- **サフィックス** — リテラルの末尾に付けて型を指定する記号。Java の `L`/`f`/`d`、JS の `n`
- **拡大変換(widening)** — 小さい箱から大きい箱への変換。`int` → `long` など。情報が失われないので自動で行われる
- **ボックス化(boxing)** — 基本型(`long`)をオブジェクト型(`Long`)に包む変換。`Long` は `null` になれるが `long` はなれない
- **基本型 / オブジェクト型** — `int`・`long` のように値そのものを持つ型と、`Integer`・`Long` のように値を包んだ箱。DB の未採番を `null` で表したいので、エンティティの id はオブジェクト型
- **オーバーフロー** — 型の表せる範囲を超えて値が回り込む現象。Java の `long` 上限を超えると負の値になる
- **IEEE 754 / 倍精度浮動小数点** — 小数を表す国際規格とその 64bit 版。JavaScript の `number` の正体
- **BigInt** — JavaScript で任意精度の整数を扱う型。リテラルは `1n`。`number` とは混ぜられない
- **AUTO_INCREMENT** — MySQL が id を 1 ずつ自動採番する仕組み

## 関連

- 変数宣言でどこまで型を書くか、`var` と型推論 → [type-declaration-and-var.md](./type-declaration-and-var.md)
- `record` とは何か、`CategoryResponse` の形 → [map-vs-record.md](./map-vs-record.md)
- `.class`(クラスリテラル)というもう一種類のリテラル → [class-literal.md](./class-literal.md)
- TypeScript の型は実行時に消える、`interface` と `type` の違い → [../../typescript/syntax/interface-vs-type.md](../../typescript/syntax/interface-vs-type.md)
- クラスとオブジェクトの関係を 3 言語で比較 → [../../object-and-class-by-language.md](../../object-and-class-by-language.md)
- エンティティと Repository、DB とのマッピング → [../spring/repository-and-entity-vs-laravel-model.md](../spring/repository-and-entity-vs-laravel-model.md)
- `1L` が実際に登場する場所とテストの読み方 → `backend/src/test/java/com/example/app/category/CategoryControllerTest.java` のコメント
