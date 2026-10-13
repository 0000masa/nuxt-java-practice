# クラス側とインスタンス側 — `new` しなくても使えるもの、`new` しないと使えないもの

「クラスそのものと、インスタンス化したものの役割の違いが分からない。インスタンス化して実体を持たせると値を取り出せるが、インスタンス化しなければ `void start(Role role)` のように型としてしか使えないのでは?」という疑問に答える学習メモ。結論を先に言うと:

- **クラスには 3 つの役割がある。** ①型 ②`static` メンバーの置き場 ③インスタンスの設計図(1 章)。
- **「インスタンス化しなければ型としてしか使えない」は半分正しい。** インスタンス側のもの(`static` の付かないフィールドとメソッド)は `new` しないと使えない。しかしクラス側のもの(`static` が付いたもの)は、`new` しなくても `Role.WEB` や `Math.max(...)` のようにクラス名から使える(2 章)。
- **`static` フィールドは、持ち主のクラスを `new` しなくても値を持っている。** クラスが最初に使われたとき(クラス初期化)に 1 回だけ値が入る。enum の `Role.WEB` はその典型(3 章)。
- **何をクラス側に置くかは、「個体ごとに違うか」で決める。** 個体ごとに違う値や状態はインスタンス側、全体で 1 つの不変の値や個体に依らない処理はクラス側(4 章)。

「クラスは設計図、オブジェクトは実体」という基本(たい焼きの型と、たい焼き)は別ノートで扱った → [../../object-and-class-by-language.md](../../object-and-class-by-language.md)。このノートは、その上で「設計図そのものにも置けるものがある」という `static` の話に絞る。

## 0. どこで出てきたか

enum の `Role` を調べていて、次の 2 つが噛み合わなかった。

- 「クラスは設計図で、`new` して初めて実体ができる」と習った
- それなのに `Role.WEB` は、誰も `new` していないのに値を持っている

この食い違いは、**クラスそのものも、`static` を置ける実体を 1 つ持っている**と分かると解ける。enum そのものの解説は → [enum.md](./enum.md)

## 1. クラスの 3 つの役割

[AuthTokenService.java](../../../../backend/src/main/java/com/example/app/auth/AuthTokenService.java) には、3 つの役割がすべて現れる。

```java
@Service
public class AuthTokenService {

    // ② クラス側:static。クラスに 1 つだけ
    static final Duration EMAIL_VERIFICATION_TTL = Duration.ofHours(24);

    // ③ インスタンス側:static なし。new したものごとに 1 つ
    private final AuthTokenRepository authTokenRepository;

    ...
}

// ① 型:変数や引数の種類として
AuthTokenService service = ...;
```

| 役割 | 何をするか | 使うのに `new` は要るか | 例 |
|---|---|---|---|
| ① 型 | 変数・引数・戻り値の種類を表す。コンパイラが取り違えを検出する | 要らない | `void start(Role role)`、`AuthTokenService service` |
| ② `static` メンバーの置き場 | クラスに 1 つだけあるフィールドとメソッドを持つ | **要らない** | `Role.WEB`、`Role.values()`、`AuthTokenService.EMAIL_VERIFICATION_TTL`、`Math.max(1, 2)` |
| ③ インスタンスの設計図 | `new` したときに、どんなフィールドとメソッドを持つ実体を作るかを決める | **要る** | `role.name()`、`authTokenService.issue(...)` |

「クラスは設計図」という説明は ③ だけを言っている。② があるので、クラスは**設計図であると同時に、`static` のものを入れておく 1 つの入れ物**でもある。

## 2. 何が `new` 無しで使えて、何が使えないか

### クラス側(`static`)— クラス名から使う

```java
Role.WEB                                   // static フィールド
Role.values()                              // static メソッド
Math.max(1, 2)                             // static メソッド
AuthTokenService.EMAIL_VERIFICATION_TTL    // static フィールド
```

クラスに 1 つだけあるので、「どのインスタンスの?」を指定する必要が無い。だからクラス名だけで使える。

### インスタンス側(`static` なし)— インスタンスから使う

```java
Role role = Role.WORKER;
role.name();                               // インスタンスメソッド

authTokenService.issue(user, purpose, ttl); // インスタンスメソッド
```

インスタンスごとに値が違うので、「どのインスタンスの?」が決まらないと使えない。だからインスタンスが要る。

`Role` の場合、インスタンスは自分で `new` するのではなく、クラス側の `Role.WORKER` から取り出す。**インスタンスを手に入れる方法は `new` だけではない**(`static` フィールドから取り出す、`static` ファクトリメソッドから受け取る、Spring に注入してもらう、など)。

### `static` からインスタンス側は見えない

```java
class Cat {
    String name = "タマ";

    static String show() {
        return name;   // コンパイルエラー: non-static variable name cannot be referenced from a static context
    }
}
```

`static` メソッドは特定のインスタンスに属さないので、「どの猫の `name` か」が決まらない。`this` も使えない。逆向き(インスタンスメソッドから `static` を使う)は問題ない。詳しくは → [static-methods-and-factory-methods.md](./static-methods-and-factory-methods.md) の 1 章

## 3. 実体はどこにあるのか

メモリの上では、次のような形になっている。

```
クラス側(クラスごとに 1 つ。クラスが最初に使われたときに用意される)
┌─ Role ────────────────────────────┐
│ static WEB     ──参照──▶ インスタンス A { name = "WEB",    ordinal = 0 }
│ static WORKER  ──参照──▶ インスタンス B { name = "WORKER", ordinal = 1 }
│ static values() / valueOf()       │
└───────────────────────────────────┘

インスタンス側(new されるたびに 1 つ。enum は宣言した個数だけ)
  インスタンス A、インスタンス B
```

- **クラス側の実体**は、クラスごとに 1 つだけある。`static` フィールドの値はここに入る。用意されるのは、そのクラスが最初に使われたとき(クラス初期化)
- **インスタンス側の実体**は、`new` のたびに 1 つ増える。`static` の付かないフィールドの値はここに入る

### 誤解:「`static` フィールドは、持ち主のクラスを `new` しないと値を持たない」

持ち主のクラスを `new` しなくても、値はある。`static` フィールドはクラス側の実体に入るので、インスタンスの有無とは関係ない。

`Math.PI` を使うときに `new Math()` を書かないのと同じである(`Math` はそもそも `new` できない)。`Role.WEB` も、`Role` が最初に使われた時点で値が入る。enum の場合はさらに、**そのクラス初期化の中で `new Role("WEB", 0)` が実行され、インスタンスが作られる**。順番は「インスタンスを作ってからクラスが値を持つ」ではなく、「クラスの初期化の中でインスタンスが作られる」である。実際にコンストラクタに出力を仕込んで確かめた結果は → [enum.md](./enum.md) の 2 章

クラス初期化がいつ・何回走るかは → [class-vs-instance-initialization.md](./class-vs-instance-initialization.md)

### 補足:「クラスそのもの」を値として扱う — `Role.class`

Java では、クラス名をそのまま変数に入れることはできない。クラスそのものを値として扱いたいときは、`Role.class` で `Class` 型のオブジェクトを取り出す(`EnumMap<>(Role.class)` などで使う)。→ [class-literal.md](./class-literal.md)

#### 誤解:「どのクラスも `class` という `static` フィールドを暗黙に持っている」

`Role.class` は `Role.WEB` と同じ「クラス名.何か」の形をしているので、`class` という隠れたフィールドがあるように見える。しかし **`class` はフィールドではない。`.class` は「クラスリテラル」という専用の構文**で、コンパイラが特別に扱う。

| | `Role.WEB` | `Role.class` |
|---|---|---|
| 正体 | `static` フィールドの参照 | クラスリテラル(言語仕様が決めた専用の構文) |
| どこで宣言されているか | `Role` の中 | どこにも宣言されていない |

フィールドでないことは、次の 2 点から分かる。

- **`class` は予約語なので、フィールドの名前に使えない。** `static Class<Role> class = ...;` はコンパイルエラーになる
- **リフレクションでフィールドを一覧しても出てこない。** `Role.class.getDeclaredFields()` で出てくるのは、`WEB`・`WORKER` と、コンパイラが足す `$VALUES` だけで、`class` は無い

中身の `Class` オブジェクトは、**JVM がクラスを読み込む(ロードする)ときに、クラスごとに 1 個作って管理している**。コンパイラは `Role.class` を「`Role` の `Class` オブジェクトを JVM からもらう」というバイトコード(`ldc` 命令)に変換する。つまり、クラスが自分の `Class` オブジェクトをフィールドに持っているわけではない。JVM が管理している 1 個を、`.class` という窓口から受け取っている。

#### `.class` を付けられるもの・付けられないもの

型の名前なら、ほぼ何にでも付けられる。

```java
Role.class          // enum
String.class        // 普通のクラス
Runnable.class      // インターフェース
int.class           // プリミティブ型
int[].class         // 配列型
void.class          // void にもある
```

付けられないのは、ジェネリクスに関わる 2 つだけである。

```java
List<String>.class  // コンパイルエラー。実行時には型引数が消える(型消去)ので、List.class しかない
T.class             // コンパイルエラー。型変数 T の中身は実行時には分からない
```

#### `Role.class` ではクラス初期化が走らない

`Role.WEB` に触れるとクラス初期化が走り、その中で `new Role("WEB", 0)` が実行される(上の誤解の節)。一方、`Role.class` で `Class` オブジェクトを取り出すだけでは、**クラス初期化は走らない**。`static` フィールドに値が入るのは、`Role.WEB` などに初めて触れたときである。この点でも、`Role.class` は `static` フィールドの読み出しとは別の扱いだと分かる。

#### 実際に確かめた結果

backend コンテナ(Java 21)で確かめた。enum のコンストラクタに出力を仕込み、`Role.class` を取り出してから `Role.WEB` に触れる。

```java
public class Main {
    public static void main(String[] args) {
        System.out.println("--- Role.class を取り出す");
        Class<Role> c = Role.class;
        System.out.println("--- getDeclaredFields");
        System.out.println(Arrays.toString(Arrays.stream(c.getDeclaredFields()).map(f -> f.getName()).toArray()));
        System.out.println("--- Role.WEB に触る");
        Role r = Role.WEB;
        System.out.println("--- その他");
        System.out.println(Role.class == Role.class);
        System.out.println(int.class + " / " + int[].class + " / " + void.class);
    }
}
enum Role {
    WEB, WORKER;
    Role() { System.out.println("new Role: " + name()); }
}
```

```
--- Role.class を取り出す
--- getDeclaredFields
[WEB, WORKER, $VALUES]
--- Role.WEB に触る
new Role: WEB
new Role: WORKER
--- その他
true
int / class [I / void
```

- `Role.class` を取り出しても、さらに `getDeclaredFields()` でフィールドを調べても、コンストラクタは呼ばれない。**`Role.WEB` に触れた時点で初めて** 2 個まとめて作られる
- フィールドの一覧は `WEB`・`WORKER`・`$VALUES` で、`class` は無い
- `Role.class == Role.class` は `true`。何度書いても同じ 1 個が返る
- `int[].class` が `class [I` と出るのは、JVM の中での配列型の名前(`[` は配列、`I` は `int`)

コンパイルエラーになる 3 つも確かめた。

```
// static Class<C> class = null;
error: <identifier> expected                  ← class は予約語なので名前に使えない

// Object a = List<String>.class;
error: <identifier> expected                  ← 型引数付きの型には .class を付けられない

// Object b = T.class;
error: cannot select from a type variable    ← 型変数には .class を付けられない
```

`return String.class;` を `javap -c` で逆アセンブルすると、フィールドを読む `getstatic` ではなく `ldc` 命令になっている。

```
0: ldc           #7                  // class java/lang/String
2: areturn
```

`Role.WEB` のような `static` フィールドの読み出しなら `getstatic` になる。`.class` がフィールドではないことは、バイトコードの上でも確かめられる。

## 4. どちらに置くかの決め方

基準は **「インスタンスごとに違うか」** である。

| 置くもの | 置き場所 | 例 |
|---|---|---|
| インスタンスごとに違う値・状態 | インスタンス側 | `User` の `email`、`AuthToken` の `expiresAt` |
| 他の部品への参照(Spring が注入する依存) | インスタンス側 | `AuthTokenService` の `authTokenRepository` |
| 全体で 1 つの不変の値(定数・設定の長さ) | クラス側(`static final`) | `EMAIL_VERIFICATION_TTL`、`WAIT_TIME_SECONDS` |
| インスタンスの状態を使わない処理 | クラス側(`static` メソッド) | `Math.max`、`Duration.ofHours` |
| インスタンスを作って返す入口 | クラス側(`static` ファクトリメソッド) | `SesV2Client.create()`、`Role.valueOf(...)` |
| 決まった個数のインスタンス | クラス側(enum の定数) | `Role.WEB`、`AuthTokenPurpose.PASSWORD_RESET` |

### Spring のアプリでは、インスタンス側が基本

Spring のサービス(`@Service` など)は、Spring が 1 個 `new` してインスタンスとして管理する(Bean)。依存は、コンストラクタでインスタンスのフィールドに注入される。そのため、業務の処理はほとんどが**インスタンスメソッド**になる。

`static` メソッドには注入ができない。依存を使う処理を `static` にすると、テストで差し替えることもできなくなる。このリポジトリでも `static` に置いているのは、ロガー・定数・DTO の `static` ファクトリメソッド(`PostResponse.from(post)`、`ErrorResponse.of(message)` など)・依存を使わない小さな処理だけである。

### `static` に置いてはいけないもの

クラス側は全員で 1 つを共有する。`static` に可変の状態(リクエストごとに変わる値など)を置くと、別のリクエストの値が混ざる。`static` に置いてよいのは、不変のものか、スレッドセーフなものだけ。→ [class-vs-instance-initialization.md](./class-vs-instance-initialization.md) の 6 章

## 5. 他の言語では

`static` という「クラス側」の仕組み自体は、TypeScript にも PHP にもある。違うのは、**クラス側の実体がどれだけ生き続けるか**と、**そもそもクラスを使うか**である。

| | クラス側の書き方 | クラス側の実体が生きる期間 | 定数を置く定番の場所 |
|---|---|---|---|
| Java | `static` | アプリが動いている間(最初に使われてから) | `static final` フィールド、enum |
| TypeScript | `static` | アプリが動いている間(モジュールが読み込まれてから) | クラスより、モジュールの `export const` |
| PHP | `static` / `const`、呼ぶときは `::` | **1 リクエストの間だけ**(既定の動き方) | クラス定数 `const`、enum(PHP 8.1〜) |

- **TypeScript** — `class Role { static WEB = new Role("WEB"); }` と書けば Java と同じ形になる。ただし TS では、クラスそのものも値(コンストラクタ関数というオブジェクト)で、`static` はそのプロパティにすぎない。そもそも定数はクラスに入れず、モジュールの `export const` に置くことが多い
- **PHP** — `Role::WEB` のように `::` でクラス側を使う。既定ではリクエストごとにすべて作り直すので、`static` は「アプリ全体で 1 つ」にはならない

各言語の「一度だけ」の単位の違いは → [class-vs-instance-initialization.md](./class-vs-instance-initialization.md) の 7・8 章、クラスとオブジェクトの立ち位置の違いは → [../../object-and-class-by-language.md](../../object-and-class-by-language.md)

## つまずきポイント

- **「クラスは設計図だから、`new` しないと何も使えない」と考える。** 設計図の役割(③)とは別に、クラスは `static` の置き場(②)でもある。`static` のものは `new` しなくても使える(1・2 章)
- **「`static` フィールドは、持ち主のクラスを `new` しないと値を持たない」と考える。** クラス初期化で値が入る。enum ではインスタンスのほうがクラス初期化の中で作られる(3 章)
- **「インスタンスを手に入れる方法は `new` だけ」と考える。** `Role.WORKER`(`static` フィールド)、`Role.valueOf(...)`・`SesV2Client.create()`(`static` メソッド)、Spring の注入でも手に入る(2 章)
- **「`Role.class` は、どのクラスも暗黙に持っている `class` フィールドの参照」と考える。** フィールドではなく、クラスリテラルという専用の構文。`Class` オブジェクトは JVM がクラスごとに 1 個管理していて、`.class` はそれを受け取る窓口(3 章)
- **`static` メソッドの中でインスタンスのフィールドを読もうとする。** コンパイルエラー。どのインスタンスのものかが決まらない(2 章)
- **Spring のサービスで、依存を使う処理を `static` にする。** 注入もテストでの差し替えもできなくなる(4 章)
- **`static` に可変の状態を置く。** 全員で共有されるので、別のリクエストの値が混ざる(4 章)

## 用語集

- **クラス側(`static`)** — クラスごとに 1 つだけある実体に属するもの。`static` フィールドと `static` メソッド。クラス名から使う
- **インスタンス側** — `new` されたもの 1 つ 1 つに属するもの。`static` の付かないフィールドとメソッド。インスタンスから使う
- **型** — 変数・引数・戻り値に入れてよいものの種類。クラスを宣言すると、その名前の型ができる
- **インスタンス** — クラスから作られた実体。オブジェクトとほぼ同じ意味で、どのクラスから作られたかを強調する言い方
- **インスタンス化** — クラスからインスタンスを作ること。Java では `new` で行う
- **クラス初期化** — クラスが最初に使われたときに 1 回だけ行われる処理。`static` フィールドに値が入る
- **クラスリテラル** — `Role.class` のように、型の名前に `.class` を付けて、その型の `Class` オブジェクトを取り出す構文。フィールドの参照ではない
- **`static` ファクトリメソッド** — `new` の代わりに、インスタンスを作って返す `static` メソッド(`valueOf`、`create`、`of` など)
- **Bean** — Spring が管理するインスタンス。既定では 1 種類につき 1 個だけ作られる

## 関連

- enum の仕組み(定数はクラス側の `static final` フィールド、中身はそのクラス自身のインスタンス) → [enum.md](./enum.md)
- クラスとオブジェクトの違い(設計図と実体)、3 言語での立ち位置 → [../../object-and-class-by-language.md](../../object-and-class-by-language.md)
- クラス初期化とインスタンス初期化(`static` はいつ、何回値が入るか) → [class-vs-instance-initialization.md](./class-vs-instance-initialization.md)
- `static` メソッドと `static` ファクトリメソッド → [static-methods-and-factory-methods.md](./static-methods-and-factory-methods.md)
- コンストラクタ(`new` で何が呼ばれるか) → [constructor-declaration.md](./constructor-declaration.md)
- クラスそのものを値として持つ(`Role.class`) → [class-literal.md](./class-literal.md)
