# static と static ファクトリメソッド — 「型に属するメソッド」と `new` の代わりの入口

`MailSenderConfig` の `SesV2Client.create()` を見て「static ファクトリメソッドとは何か」「`SesV2Client` は interface なのに、なぜクラスのようにメソッドを呼べるのか」となった人向けの学習メモ。結論を先に言うと:

- **`static` は「個体ではなく型そのものに属する」という印。** インスタンスメソッドが `new` した実物に紐づくのに対し、static メソッドはクラス名から直接呼べる。その代わり個体のフィールドは読めない
- **static ファクトリメソッドは、`new` の代わりにインスタンスを返す static メソッド。** 名前を付けられる・毎回新しく作らなくてよい・**戻り値を抽象型(interface)にできる**・生成の手順を隠せる、の 4 点が `new` に対する強み
- **Java 8 以降、interface にも static メソッドを「本体つきで」書ける。** だから `SesV2Client.create()` が成立する
- **ただし interface の static メソッドは実装クラスに継承されない**(JLS SE21 §9.2)。呼べるのは **interface 名からだけ**で、`Dog.create()` はコンパイルエラーになる
- **PHP はちょうど逆。** interface に static を宣言できるが**本体は書けず**、呼ぶのは**実装クラス名**。`Animal::create()` は実行時エラー
- **TypeScript の interface は static 側を記述しない。** `implements` はインスタンス側だけのチェックで、static 側を型付けしたいときは `typeof クラス名` を使う

## 0. どこで出てきたか

[MailSenderConfig.java](../../../../backend/src/main/java/com/example/app/config/MailSenderConfig.java) の SES クライアントを作る部分。

```java
@Bean
@ConditionalOnProperty(name = "app.mail.transport", havingValue = "ses")
SesV2Client sesV2Client() {
    return SesV2Client.create();   // ← new ではない。しかも SesV2Client は interface
}
```

`new SesV2Client()` ではなく `SesV2Client.create()`。しかも `SesV2Client` は interface である。この 2 つの「なぜ」を順に解いていく。

## 1. static とは — 「個体」ではなく「型」に属する

Java のメソッドには 2 種類ある。**`static` が付いているかどうか**で、誰のものかが変わる。

```java
class Cat {
    String name;

    String meow() { return name + "「にゃー」"; }   // インスタンスメソッド
    static int legCount() { return 4; }             // static メソッド
}
```

| | インスタンスメソッド | static メソッド |
|---|---|---|
| 誰のものか | `new` で作られた**個体**のもの | **クラス(型)そのもの**のもの |
| 呼ぶのに必要なもの | `new` した実物 | クラス名だけ |
| 個体のフィールドを読めるか | 読める(自分のものだから) | **読めない**(誰の値か決まらない) |

「田中さんの身長を測る」がインスタンスメソッド、「人間の平均寿命を答える」が static メソッド、と考えると近い。前者は本人を連れてこないと答えられないが、後者は誰も連れてこなくても答えられる。

### 呼び方が違う

```java
Cat tama = new Cat();
tama.meow();        // 実物に対して呼ぶ

Cat.legCount();     // クラス名に対して呼ぶ。実物は要らない
```

### static からインスタンスのフィールドは読めない

これは文法として禁止されている。「どの個体の `name` か」が決まらないからである。

```java
class Cat {
    String name = "タマ";
    static String show() {
        return name;   // ← エラー
    }
}
```

```
error: non-static variable name cannot be referenced from a static context
```

同じ理由で、static メソッドの中では **`this` と `super` も使えない**。

```java
static String show() { return this.name; }
```

```
error: non-static variable this cannot be referenced from a static context
```

このような「現在のオブジェクトが決まっていない文脈」を **static コンテキスト** と呼ぶ。JLS SE21 §9.4 も interface の static メソッドについて「static コンテキストを導入し、現在のオブジェクトを参照する構文の使用を制限する。特に `this` と `super` は禁止される」と述べている。

### static フィールドの初期化の話は別ノート

`static final Duration TTL = Duration.ofHours(24)` のような **static フィールドがいつ初期化されるか**(クラス初期化とインスタンス初期化の違い、`static {}` ブロック、コンパイル時定数)は、このノートでは扱わない。→ [class-vs-instance-initialization.md](./class-vs-instance-initialization.md)

## 2. static ファクトリメソッド — `new` の代わりの入口

**「`new` の代わりに、インスタンスを作って返してくれる static メソッド」**のこと。「ファクトリ(工場)」は「インスタンスを製造する」という意味である。

```java
// 普通のやり方
SesV2Client client = new DefaultSesV2Client(config);

// static ファクトリメソッド
SesV2Client client = SesV2Client.create();
```

標準ライブラリにも大量にある。すでに書いたことがあるはずである。

```java
List<String> l = List.of("a", "b");    // static ファクトリ
Optional<String> o = Optional.of("x"); // static ファクトリ
Path p = Path.of("/tmp/a.txt");        // static ファクトリ
Integer i = Integer.valueOf(100);      // static ファクトリ
```

`new` ではなくこちらを使う理由は、主に 4 つある。

### 利点1 — 名前を付けられる

コンストラクタの名前はクラス名で固定なので、作り方が何通りあっても**引数の型でしか区別できない**。static メソッドなら用途を名前で表せる。

```java
// もし new しかなかったら、どちらが何なのか引数を見ないと分からない
new Duration(24);
new Duration(24, TimeUnit.HOURS);

// 実際の Duration は名前で区別している
Duration.ofHours(24);
Duration.ofMinutes(24);
Duration.ofSeconds(24);
```

`AuthTokenService` の `Duration.ofHours(24)` もこれである。

### 利点2 — 毎回新しく作らなくてよい

`new` は必ず新品を作るが、static ファクトリなら「使い回しのインスタンスを返す」「キャッシュから返す」ことができる。`Integer.valueOf` は -128〜127 の範囲をキャッシュしているので、実際に確かめられる。

```java
System.out.println(Integer.valueOf(100) == Integer.valueOf(100));   // true  … 同じ実物
System.out.println(Integer.valueOf(1000) == Integer.valueOf(1000)); // false … 別の実物
```

`==` は「同じ実物か」を見る比較なので、`true` は**同一のインスタンスが返っている**ことを意味する。`new Integer(100)` にはこの最適化ができない(だから非推奨になった)。

### 利点3 — 戻り値を抽象型にできる ← 今回の本題

呼ぶ側には interface だけを見せて、**実際に返しているクラスの名前を隠せる**。

```java
static SesV2Client create() {          // 返すと宣言しているのは interface
    return builder().build();          // 実際に返るのは DefaultSesV2Client
}
```

コンストラクタは「そのクラスのインスタンス」しか返せないので、これはできない。ライブラリ側は実装クラスを後から差し替えても、利用者のコードを壊さずに済む。

### 利点4 — 生成の手順を隠せる

`SesV2Client.create()` の内側では、リージョンの解決・資格情報の解決・HTTP クライアントの選択が走っている。呼ぶ側はその手順を知らなくてよい。

### 名前の慣習

Java には static ファクトリの命名の慣習がある。覚えておくと、知らないライブラリでも入口を見つけやすい。

| 名前 | 意味 | 例 |
|---|---|---|
| `of` | 引数から素直に作る | `List.of("a")` / `Duration.ofHours(24)` |
| `valueOf` | 別の型から変換して作る | `Integer.valueOf("42")` |
| `create` / `newInstance` | 毎回新しく作る | `SesV2Client.create()` |
| `builder` | 設定を足してから作るための**ビルダー**を返す | `SesV2Client.builder()` |
| `copyOf` | 引数の内容をコピーして作る | `List.copyOf(list)` |
| `getInstance` | 唯一のインスタンスなどを返す | `Calendar.getInstance()` |

なお `List.of()` が返すのは**変更不可のリスト**で、`add` すると `UnsupportedOperationException` になる。`new ArrayList<>()` との使い分けが要る。これも「`new` と違って、返すものの性質を自由に決められる」ことの現れである。

## 3. interface に static メソッドが書ける(Java 8 以降)

ここが `SesV2Client.create()` の核心である。

Java 7 までは、interface に書けるのは「中身のないメソッドの宣言」と定数だけだった。Java 8 で 2 つが追加された。

- **default メソッド** — `default` を付けると、interface の中に**実装本体**を書ける。実装クラスに継承される
- **static メソッド** — interface に直接ぶら下がる static メソッド。**実装クラスに継承されない**

`SesV2Client` はこの両方を使っている。実際のソース(`sesv2-2.54.0-sources.jar` の `SesV2Client.java`)がこうなっている。

```java
public interface SesV2Client extends AwsClient {          // 300 行目

    static SesV2Client create() {                          // 12651 行目
        return builder().build();
    }

    static SesV2ClientBuilder builder() {                  // 12658 行目
        return new DefaultSesV2ClientBuilder();
    }
}
```

**interface の中に本体まで書かれている。** だから `SesV2Client.create()` と呼べる。「interface だから中身が無い」は Java 7 までの話で、今は当てはまらない。

### default との違い — 継承されるかどうか

JLS SE21 §9.2 は、interface が継承しないものを列挙している。

> The interface inherits, from the interfaces it extends, all members of those interfaces, except for (i) fields, classes, and interfaces that it hides, (ii) `abstract` methods and default methods that it overrides, (iii) `private` methods, and (iv) **`static` methods**.

つまり **static メソッドは継承されない**。結果として、呼び方は**インターフェース名を書く形ただ 1 つ**になる。

```java
interface Animal {
    String cry();
    static Animal create() { return new Dog(); }
}
class Dog implements Animal {
    public String cry() { return "ワン"; }
}
```

```java
Animal a = Animal.create();   // OK
Animal b = Dog.create();      // エラー
```

```
error: cannot find symbol
        Animal b = Dog.create();
                      ^
  symbol:   method create()
  location: class Dog
```

`Dog` から見ると `create()` は**存在しない**。これはクラスの static メソッドとは違う挙動である(クラスの static は子クラスからも呼べる)。「interface の static メソッドは、その interface 専用の道具箱」と覚えておくとよい。

### オーバーライドもできない

static メソッドは型に固定で紐づくので、そもそも上書きの対象外である。実装クラスで同名の static メソッドを書いても、それは**別の無関係なメソッド**であり、`@Override` を付けるとコンパイラが止める。

```
error: static methods cannot be annotated with @Override
```

### 3 つのメソッドの整理

| | 本体を書けるか | 実装クラスに継承されるか | 呼び方 |
|---|---|---|---|
| 通常の(abstract)メソッド | 書けない | される(実装を強制) | インスタンス経由 |
| `default` メソッド | 書ける | **される** | インスタンス経由 |
| `static` メソッド | 書ける | **されない** | **interface 名から** |

## 4. 実例 — `SesV2Client.create()` を分解する

`javap`(JDK 付属のクラスファイル解析コマンド)で宣言を見ると、interface であることと static メソッドの存在が確認できる。

```
$ javap -cp sesv2-2.54.0.jar software.amazon.awssdk.services.sesv2.SesV2Client

public interface software.amazon.awssdk.services.sesv2.SesV2Client extends ...AwsClient {
  ...
  public static software.amazon.awssdk.services.sesv2.SesV2Client create();
  public static software.amazon.awssdk.services.sesv2.SesV2ClientBuilder builder();
  public static software.amazon.awssdk.regions.ServiceMetadata serviceMetadata();
}
```

### 何が返ってきているのか

呼び出しはこう連なっている。

```
SesV2Client.create()
  → builder()                        // SesV2ClientBuilder を作る
      → new DefaultSesV2ClientBuilder()
  → .build()                         // ビルダーが実物を組み立てる
      → new DefaultSesV2Client(config)
```

最後に返るクラスを `javap` で見ると、こうなっている。

```
final class software.amazon.awssdk.services.sesv2.DefaultSesV2Client
        implements software.amazon.awssdk.services.sesv2.SesV2Client
```

`public` が付いていない。つまり**パッケージプライベート**なので、`com.example.app.config` にいる私たちのコードからは**名前を書くことすらできない**。`DefaultSesV2ClientBuilder` も同じである。

AWS SDK は次の作りを意図的に選んでいる。

- 外に見せる型 → `SesV2Client`(`public interface`)
- 実際に動くクラス → `DefaultSesV2Client`(パッケージプライベート、`final`)
- 両者をつなぐ唯一の入口 → `SesV2Client.create()` / `SesV2Client.builder()`

実装クラスを完全に隠せるので、SDK のバージョンアップで `DefaultSesV2Client` の構造が変わっても、利用者のコードは 1 行も壊れない。**「interface だから `new` できないので `create()` を使う」ではなく、「実装を隠したいから interface + static ファクトリという形を選んでいる」**という順序で理解するとよい。

### `create()` と `builder()` の使い分け

入口は 2 つある。`create()` は `builder().build()` を呼ぶだけの短縮形である。

```java
// 全部おまかせ。リージョン・資格情報は SDK の既定の解決順序で探す
SesV2Client.create();

// 自分で指定したいとき
SesV2Client.builder()
        .region(Region.AP_NORTHEAST_1)
        .credentialsProvider(...)
        .build();
```

`MailSenderConfig` が `create()` で済ませているのは、ECS の Fargate タスクが `AWS_REGION` 環境変数と、タスクロールを引くための資格情報エンドポイントを自動で持っているためである。アプリ側に設定を書く必要がない。

## 5. 他の言語との違い

### PHP — static は書けるが、interface と static の関係が Java と逆

PHP でも static メソッドはあり、`::`(スコープ解決演算子)で呼ぶ。

```php
class Cat {
    public static function legCount(): int { return 4; }
}
echo Cat::legCount();   // 4
```

interface との関係が Java とちょうど逆になっている。**PHP の interface は static メソッドを「宣言」できるが、本体は書けない。**

```php
interface Animal {
    public static function create(): Animal { return new Dog(); }   // ← 本体を書くとエラー
}
```

```
PHP Fatal error:  Interface function Animal::create() cannot contain body
```

本体なしなら宣言できる。その場合は「実装クラスが static メソッドを必ず用意する」という約束になり、**呼ぶのは実装クラス名**である。

```php
interface Animal { public static function create(): Animal; }

class Dog implements Animal {
    public static function create(): Animal { return new Dog(); }
    public function cry(): string { return "ワン"; }
}

echo Dog::create()->cry();   // "ワン" … 実装クラス名から呼ぶ
echo Animal::create();       // ← エラー
```

```
PHP Fatal error:  Uncaught Error: Cannot call abstract method Animal::create()
```

(PHP 8.3.6 で実測)

並べると対比がはっきりする。

| | Java の interface | PHP の interface |
|---|---|---|
| static メソッドの本体 | **書ける** | **書けない**(宣言のみ) |
| 実装クラスに継承されるか | されない | 実装クラスが自分で書く |
| 呼べる名前 | **interface 名のみ** | **実装クラス名のみ** |

Java は「interface が共通の道具を配る」ため、PHP は「実装クラスに static の実装を強制する」ための機能になっている。**目的そのものが違う**ので、片方の感覚で書くと必ず詰まる。

なお Laravel の `User::find(1)` のような書き方は、この static とは別の仕組み(ファサードやモデルの `__callStatic`)なので、混同しないほうがよい。

### TypeScript — interface は static 側を記述しない

TypeScript のクラスにも `static` は書ける。公式ハンドブックはこう説明している。

> Classes may have `static` members. These members aren't associated with a particular instance of the class. They can be accessed through the class constructor object itself.

```typescript
class MyClass {
  static x = 0;
  static printX() { console.log(MyClass.x); }
}
MyClass.printX();
```

Java と違うのは interface の側である。TypeScript の `interface` が記述するのは**インスタンス側の形だけ**で、static 側(クラスそのものが持つメソッド)は対象外である。ハンドブックは `implements` についてこう書いている。

> It's important to understand that an `implements` clause is only a check that the class can be treated as the interface type. It doesn't change the type of the class or its methods *at all*.

つまり `implements` は「この**インスタンス**が interface として扱えるか」の確認にすぎない。static 側に型を付けたいときは、クラスそのものの型を表す `typeof` を使って別に書く。

```typescript
interface Animal { cry(): string; }

class Dog implements Animal {
  static create(): Dog { return new Dog(); }
  cry(): string { return "ワン"; }
}

// implements Animal がチェックしているのは cry() の側だけ。
// static create() は Animal の約束とは無関係に存在している。

type DogClass = typeof Dog;   // クラスそのものの型(create() と new を持つ)
const ctor: DogClass = Dog;   // クラスを値として変数に入れられる
ctor.create().cry();
```

TypeScript ではクラス自体が実行時の値なので、`Dog` をそのまま変数に入れられる。この「クラスが値」という性質は [class-literal.md](./class-literal.md) の 6 章と同じ話である。

### 3 言語まとめ

| | static メソッド | interface に本体つき static を書けるか | 呼ぶ名前 |
|---|---|---|---|
| **Java** | あり | **書ける**(Java 8+) | interface 名のみ |
| **PHP** | あり(`::`) | 書けない(宣言のみ可) | 実装クラス名のみ |
| **TypeScript** | あり | interface は static 側を扱わない | クラス名。型は `typeof` で表す |

## つまずきポイント

- **static メソッドから個体のフィールドを読もうとする。** `non-static variable ... cannot be referenced from a static context` になる。「どの個体か決まっていない」ので当然。`this` / `super` も同じ理由で使えない
- **`Dog.create()` と書いてしまう。** interface の static メソッドは継承されないので `cannot find symbol` になる。**書けるのは `Animal.create()` だけ**。クラスの static は子クラスからも呼べるので、そこと混同しやすい
- **interface の static を `@Override` しようとする。** `static methods cannot be annotated with @Override` で止まる。static はオーバーライドの対象外
- **`SesV2Client` を `new` しようとする。** interface なので `new` できず、実装クラス `DefaultSesV2Client` はパッケージプライベートで名前も書けない。入口は `create()` / `builder()` の 2 つだけ
- **PHP の感覚で「interface に static の中身を書く」。** PHP では `Interface function ... cannot contain body` になる。逆に Java の感覚で `Animal::create()` と書くと `Cannot call abstract method` になる
- **`List.of()` の結果に `add` する。** static ファクトリは「返すものの性質」を自由に決められるので、`new ArrayList<>()` と違って変更不可のリストが返る。`UnsupportedOperationException` になる
- **static ファクトリを「ただの初期化ヘルパー」と誤解する。** 一番の value は**戻り値を interface にして実装を隠せる**こと。`SesV2Client` はこれをやるために interface になっている

## 用語集

- **static メソッド** — 個体ではなくクラス(型)そのものに属するメソッド。`new` なしでクラス名から呼べる。個体のフィールド・`this`・`super` は使えない
- **インスタンスメソッド** — `new` で作った個体に属するメソッド。個体のフィールドを読める
- **ファクトリメソッド** — インスタンスを作って返すメソッド。「工場」の意
- **static ファクトリメソッド** — `new` の代わりに使う static なファクトリメソッド。名前を付けられ、インスタンスを使い回せ、戻り値を抽象型にでき、生成手順を隠せる
- **default メソッド** — Java 8 以降、interface に書ける本体つきのメソッド。**実装クラスに継承される**
- **interface の static メソッド** — Java 8 以降、interface に書ける本体つきの static メソッド。**実装クラスに継承されず**、interface 名からしか呼べない
- **static コンテキスト** — 現在のオブジェクトが決まっていない文脈。`this` / `super` / 非 static フィールドの参照が禁止される
- **ビルダー** — 設定を少しずつ足してから `build()` で完成品を作るパターン。引数が多いときに使う
- **パッケージプライベート** — 修飾子を書かない可視性。同じパッケージからだけ見える。実装クラスを隠すのに使う
- **スコープ解決演算子(`::`)** — PHP で static メソッド・定数にアクセスする演算子。Java の `.` に相当する位置づけ
- **`javap`** — JDK 付属のクラスファイル解析コマンド。クラスか interface か、どんなメソッドがあるかを、ソースが無くても確認できる

## 関連

- interface と implements の基本、`default` メソッド、抽象クラスとの違い → [interface-and-implements.md](./interface-and-implements.md)
- `static` **フィールド**がいつ初期化されるか(クラス初期化とインスタンス初期化、`static {}`、コンパイル時定数) → [class-vs-instance-initialization.md](./class-vs-instance-initialization.md)
- コンストラクタの見分け方(クラス名と同名・戻り値なし) → [constructor-declaration.md](./constructor-declaration.md)
- クラス自体を値として持つ(`Post.class` / TypeScript でクラスが値であること) → [class-literal.md](./class-literal.md)
- 今回の題材のコード → [MailSenderConfig.java](../../../../backend/src/main/java/com/example/app/config/MailSenderConfig.java)
- SES 経路そのものの設計(なぜ SMTP を使わないか、Bean の切り替え) → [../spring/mail-sending-and-transport-switching.md](../spring/mail-sending-and-transport-switching.md)
