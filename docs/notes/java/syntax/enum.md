# enum — クラスが「決まった個数のインスタンス」を作り置きする型

「enum で宣言した `Role` は、インスタンス化した `Role` をフィールドとして持っているということ? enum を `new` して使うことはあるの? `Role.WORKER` で返ってくるのは `new Role("WORKER", 1)` で作ったもの? `WEB` や `WORKER` のフィールドは、`Role` をインスタンス化しないと値を持たないのでは?」という疑問に答える学習メモ。結論を先に言うと:

- **enum はクラスである。** `WEB` / `WORKER` の 1 つ 1 つが `Role` 自身のインスタンスで、`Role` クラスの `public static final` フィールドに入っている(1 章)。
- **フィールドは `static` なので、持ち主はクラス。** インスタンスの中にあるのではない。誰も `new` しなくても、`Role` が最初に使われた時点(クラス初期化)で値が入る。順番はむしろ逆で、**クラス初期化の中でインスタンスが作られる**(2 章)。
- **enum は `new` できない。** コンパイルエラーになり、リフレクションでも JVM が拒否する。インスタンスは宣言した個数から増えない(3 章)。
- **`Role.WORKER` は、作り置きのインスタンスを取り出す式。** 何度書いても同じ 1 個が返るので、`==` で比べてよい(4 章)。

「クラスそのもの」と「インスタンス」の役割の違い(`static` は `new` しなくても使える、など)は、言語の基本として別ノートにまとめた → [class-side-and-instance-side.md](./class-side-and-instance-side.md)

## 0. どこで出てきたか

[AppProperties.java](../../../../backend/src/main/java/com/example/app/config/AppProperties.java) の中に、プロセスの役割を表す enum がある。

```java
public record AppProperties(String baseUrl, Mail mail, String task, Role role, Sqs sqs) {

    public enum Role {
        /** HTTP リクエストを受ける。認証メールの依頼をキューに送る側 */
        WEB,
        /** キューから受け取って処理する。AuthMailQueueConfig が受信ループを立てる */
        WORKER
    }
}
```

`application.yml` の `app.role: ${APP_ROLE:web}` を、Spring Boot が起動時にこの enum に変換する。変換できない綴り(`APP_ROLE=wroker`)は起動時のエラーになる。このリポジトリでの `Role` の役目は、この**綴りの検証**だけである(`Role` の値を読むコードは無い。役割による Bean の出し分けは `@ConditionalOnProperty` が文字列のまま行う)。

## 1. 普通のクラスに書き直すと

### コンパイラが作ったものを見る

enum は、コンパイラが普通のクラスに書き換えている。コンパイル済みの `Role` を `javap -p` で見ると、次のようになる。

```
public final class com.example.app.config.AppProperties$Role extends java.lang.Enum<com.example.app.config.AppProperties$Role> {
  public static final com.example.app.config.AppProperties$Role WEB;
  public static final com.example.app.config.AppProperties$Role WORKER;
  private static final com.example.app.config.AppProperties$Role[] $VALUES;
  public static com.example.app.config.AppProperties$Role[] values();
  public static com.example.app.config.AppProperties$Role valueOf(java.lang.String);
  private com.example.app.config.AppProperties$Role();
  private static com.example.app.config.AppProperties$Role[] $values();
  static {};
}
```

コンストラクタは引数が無いように表示されるが、`javap -s` で見ると実際の型は `(Ljava/lang/String;I)V`、つまり `(String, int)` を受け取っている。名前(`"WEB"`)と宣言順(`0`)である。

### Java のソースに戻すと

上の結果を Java のソースの形に戻すと、だいたい次の意味になる(**説明用。このままではコンパイルできない**。理由は後述)。

```java
public static final class Role extends Enum<Role> {

    // 定数の 1 つ 1 つは、このクラス自身のインスタンス
    public static final Role WEB = new Role("WEB", 0);
    public static final Role WORKER = new Role("WORKER", 1);

    // 全定数を宣言順に並べた配列。values() と valueOf() が使う
    private static final Role[] $VALUES = { WEB, WORKER };

    // private なので、外から new Role(...) はできない
    private Role(String name, int ordinal) {
        super(name, ordinal);   // 名前と宣言順を親クラス Enum に持たせる
    }

    public static Role[] values() {
        return $VALUES.clone();   // 配列の中身を書き換えられないよう、コピーを渡す
    }

    public static Role valueOf(String name) {
        return Enum.valueOf(Role.class, name);   // 無ければ IllegalArgumentException
    }
}
```

読み取れることは次の 5 つ。

| 書き方 | 意味 |
|---|---|
| `public static final Role WEB = new Role(...)` | 定数は、**`Role` クラスが持つ** `static` フィールド。中身は `Role` 自身のインスタンス |
| `private Role(...)` | コンストラクタが `private` なので、インスタンスは宣言した 2 つから増えない |
| `final class` | 継承できない |
| `extends Enum<Role>` | `name()`・`ordinal()`・`toString()`・`compareTo()` などは親クラス `java.lang.Enum` が持つ |
| `AppProperties$Role` | `AppProperties` の中に書いた enum は、自動的に `static` な入れ子クラスになる |

### インスタンスの中身は `name` と `ordinal` だけ

`WEB` と `WORKER` のフィールドは `static` なので、置き場所は**クラス側**である。インスタンスの中には無い。各インスタンスが持っているのは、親クラス `Enum` から受け継いだ 2 つのフィールドだけ。

```
Role クラス(1 つだけ)
├── WEB    ──参照──▶ インスタンス A { name = "WEB",    ordinal = 0 }
├── WORKER ──参照──▶ インスタンス B { name = "WORKER", ordinal = 1 }
└── $VALUES ─参照──▶ 配列 [A, B]
```

`name` と `ordinal` は `static` ではないので、インスタンスごとに別々に持つ。2 回目の `new Role("WORKER", 1)` が、1 回目に作った A の `name` を上書きすることはない。

- **`name`** — 宣言した名前。`name()` や `toString()` が返す
- **`ordinal`** — 宣言した順番。0 から始まる(`WEB` = 0、`WORKER` = 1)

### `extends Enum` は自分では書けない

上のコードを実際にコンパイルすると、エラーになる。

```
error: classes cannot directly extend java.lang.Enum
```

`Enum` を継承できるのは、`enum` キーワードで宣言したものだけである。`enum` を使わずに同じことをするなら、`name` と `ordinal` を自分のフィールドとして持ち、`values()` と `valueOf()` も自分で書く。これは enum が登場する前(Java 1.4 まで)に使われていた **型安全な enum パターン** で、手書きではリフレクション対策やシリアライズ対策までは揃わない(3 章)。

## 2. 定数はいつ、誰が作るのか — 誰も `new` しなくてよい

「`WEB` と `WORKER` のフィールドは、`Role` をインスタンス化しないと値を持たないのでは?」という疑問への答えは **いいえ** である。

`static` フィールドは**クラスに属する**。値が入るのは、そのクラスが最初に使われたとき(クラス初期化)で、`Role` を `new` する必要は無い。そもそも enum は `new` できない(3 章)。インスタンスを作っているのは、クラス初期化の中にある `new Role("WEB", 0)` と `new Role("WORKER", 1)` である。

コンストラクタに出力を仕込んで確かめた。

```java
public class Init {
    enum Role {
        WEB, WORKER;
        Role() { System.out.println("  コンストラクタ: " + name()); }
    }

    public static void main(String[] args) {
        System.out.println("1. main 開始(Role はまだ使っていない)");
        Role r = Role.WORKER;
        System.out.println("2. Role.WORKER を取り出した: " + r);
        System.out.println("3. もう一度: " + (Role.WORKER == r));
    }
}
```

```
1. main 開始(Role はまだ使っていない)
  コンストラクタ: WEB
  コンストラクタ: WORKER
2. Role.WORKER を取り出した: WORKER
3. もう一度: true
```

読み取れることは次の 3 つ。

- `Role` を初めて使った(`Role.WORKER` を書いた)瞬間に、**定数 2 つ分のコンストラクタがまとめて走る**。`WORKER` しか使っていなくても `WEB` も作られる
- 2 回目の `Role.WORKER` では、コンストラクタは走らない。**作り置きを取り出すだけ**
- 取り出したものは毎回同じインスタンスなので、`==` が `true` になる

クラス初期化がいつ走るか、`static {}` の中身を `javap -c` で見るとどうなっているか、「自分の型のフィールドを持つと無限に入れ子にならないか」は別ノートで扱った → [class-vs-instance-initialization.md](./class-vs-instance-initialization.md) の 5 章

## 3. enum は `new` できない

enum の用途は「取りうる値を、宣言した分だけに限る」ことなので、後からインスタンスを増やす方法は塞がれている。

| やり方 | 結果 |
|---|---|
| `new Role()` と書く | コンパイルエラー: `enum classes may not be instantiated` |
| リフレクションで `private` のコンストラクタを呼ぶ | 実行時エラー: `IllegalArgumentException: Cannot reflectively create enum objects` |
| シリアライズしたものを復元する | 新しいインスタンスは作られず、既存の定数が返る |

enum は「`new` して使う」ものではなく、**クラスが作り置きしたインスタンスを、`Role.WEB` のように名前で取り出して使う**ものである。

## 4. 使い方

### 型として使う — 取りうる値をコンパイラに守らせる

```java
void start(Role role) { ... }

start(Role.WORKER);   // OK
start("worker");      // コンパイルエラー(String は Role ではない)
```

`String role` で受け取ると、`"wroker"` も `"admin"` も通ってしまう。enum の型にすると、`WEB` と `WORKER` 以外は渡せない。

### `==` で比べる

```java
if (role == Role.WORKER) { ... }
```

各定数のインスタンスは 1 つずつしかないので、`equals` ではなく `==` で比べてよい。`role` が `null` でも `NullPointerException` にならないので、むしろ `==` のほうが安全である。

### switch で分岐する

```java
String description = switch (role) {
    case WEB -> "HTTP リクエストを受ける";
    case WORKER -> "キューから受け取って処理する";
};
```

switch 式では、`default` を書かなくても全ケースを網羅しているかをコンパイラが確かめる。将来 `BATCH` を足すと、この switch がコンパイルエラーになり、対応漏れに気づける。

### フィールドやメソッドを持たせる

enum は普通のクラスなので、フィールド・コンストラクタ・メソッドを足せる。switch で分岐する代わりに、定数そのものに情報を持たせられる。

```java
public enum Role {
    WEB("HTTP リクエストを受ける", true),
    WORKER("キューから受け取って処理する", false);

    private final String description;
    private final boolean acceptsHttp;

    Role(String description, boolean acceptsHttp) {   // enum のコンストラクタは自動的に private
        this.description = description;
        this.acceptsHttp = acceptsHttp;
    }

    public String description() { return description; }
    public boolean acceptsHttp() { return acceptsHttp; }
}

if (role.acceptsHttp()) { ... }
```

`WEB("...", true)` は、1 章の `new Role("WEB", 0)` に自分の引数を足したものだと考えるとよい。

### 全定数を列挙する・文字列から変換する

```java
Role.values();             // [WEB, WORKER](宣言順)
Role.valueOf("WORKER");    // Role.WORKER
Role.valueOf("worker");    // IllegalArgumentException: No enum constant ...Role.worker
```

`valueOf` は大文字と小文字を区別する。`application.yml` に小文字の `worker` と書けるのは、Spring Boot の緩やかな束縛(relaxed binding)が変換の前に違いを吸収しているからである。

### `name()` と `ordinal()`

```java
Role.WORKER.name();      // "WORKER"
Role.WORKER.ordinal();   // 1
```

`ordinal` は**定数の並び順を変えるだけで値が変わる**。DB やファイルに保存する値には使わず、`name()` を使う。JPA なら `@Enumerated(EnumType.STRING)` を付ける。既定は `EnumType.ORDINAL`(順番を保存)なので、書き忘れると数値で保存される。

### `EnumMap` / `EnumSet` で集める

```java
EnumMap<Role, Integer> poolSize = new EnumMap<>(Role.class);
poolSize.put(Role.WEB, 5);
poolSize.put(Role.WORKER, 2);

EnumSet<Role> all = EnumSet.allOf(Role.class);
```

enum 専用のコレクション。内部で `ordinal` を配列の添字に使うので、`HashMap` / `HashSet` より速く、メモリも少なく済む。

## 5. このリポジトリでの実例

| enum | 使われ方 |
|---|---|
| [`AppProperties.Role`](../../../../backend/src/main/java/com/example/app/config/AppProperties.java) / `AppProperties.Mail.Transport` | 設定の文字列(`worker`、`ses`)を起動時に enum に変換し、**綴りを検証する**。値を読むコードは無い |
| [`AuthTokenPurpose`](../../../../backend/src/main/java/com/example/app/auth/AuthTokenPurpose.java) | `AuthMailWorker` の switch 式で用途ごとに分岐する(`default` 無しで網羅を確かめる)。[`AuthToken`](../../../../backend/src/main/java/com/example/app/auth/AuthToken.java) は `@Enumerated(EnumType.STRING)` で名前を DB に保存する |
| [`AuthMailWorker.Outcome`](../../../../backend/src/main/java/com/example/app/auth/AuthMailWorker.java) | `process` の戻り値で、処理結果(`SENT` / `SKIPPED` / `DUPLICATE`)を表す。`boolean` では 3 通りを表せず、`String` では何が返るか分からない |

`AuthToken` を DB から 1000 行読んでも、`AuthTokenPurpose` のインスタンスは 2 個のままである。各行の `purpose` フィールドは、作り置きの 2 個のどちらかを参照するだけ。

## 6. 発展

このリポジトリではまだ使っていないが、ここまでの「定数はクラス初期化で作られる、そのクラス自身のインスタンス」という理解があれば読める書き方を 3 つ紹介する。

### 定数ごとにメソッドの中身を変える

```java
enum Op {
    PLUS  { int apply(int a, int b) { return a + b; } },
    TIMES { int apply(int a, int b) { return a * b; } };

    abstract int apply(int a, int b);
}

Op.PLUS.apply(2, 3);    // 5
Op.TIMES.apply(2, 3);   // 6
```

`Op` に抽象メソッドを宣言し、定数ごとに `{ }` の中で中身を書く。switch で分岐する代わりに、定数そのものが振る舞いを持つ。新しい定数を足すとき `apply` を書き忘れると、コンパイルエラーになる。

この書き方は、Java の 2 つの仕組みを組み合わせている。

- **抽象メソッド** — `abstract int apply(int a, int b);` のように、名前と引数だけを決めて中身(`{ }`)を書かないメソッド。「継承するクラスは必ずこのメソッドを書くこと」という約束になる。抽象メソッドを持つクラスは `new` できない(中身の無いメソッドを呼ばれると困るため)
- **匿名クラス** — `new Shape() { ... }` のように、`new` と同時にクラスの中身を書いて作る、**名前の無いサブクラス**。宣言と生成が 1 回きりのセットで、そのクラスをもう一度 `new` することはできない

`PLUS { ... }` の `{ }` は、`PLUS` 用の匿名クラスの本体である。1 章と同じく普通のクラスに書き直すと、次の意味になる(説明用。このままではコンパイルできない)。

```java
abstract class Op extends Enum<Op> {

    // フィールドの型は Op。中身は Op を継承した匿名クラスのインスタンス
    public static final Op PLUS = new Op("PLUS", 0) {      // ← 匿名クラス Op$1
        int apply(int a, int b) { return a + b; }          //    抽象メソッド apply を実装する
    };
    public static final Op TIMES = new Op("TIMES", 1) {    // ← 匿名クラス Op$2
        int apply(int a, int b) { return a * b; }
    };

    abstract int apply(int a, int b);                      // ← 抽象メソッド
}
```

```
Op クラス(abstract)
├── PLUS  ──参照──▶ Op$1 のインスタンス { name = "PLUS",  ordinal = 0 }
└── TIMES ──参照──▶ Op$2 のインスタンス { name = "TIMES", ordinal = 1 }

Op$1 extends Op … apply は a + b
Op$2 extends Op … apply は a * b
```

読み取れることは次の 4 つ。

- **`PLUS` と `TIMES` は、`{ }` が無い enum と同じく `Op` の `public static final` フィールド**である。`Op` が持っているのはフィールドで、匿名クラスそのものではない(匿名クラスは `Op$1.class` / `Op$2.class` という別のファイルになる)
- **`{ }` は、フィールドに処理を足しているのではない。** 処理を持つのはクラスで、`{ }` は匿名クラスの本体として `Op` の抽象メソッド `apply` を実装している
- **`Op.PLUS.apply(2, 3)` で `Op$1` の `apply` が動く。** フィールドの型は `Op` だが、オーバーライドしたメソッドは実際のインスタンスのクラスのものが選ばれる(`List<String> list = new ArrayList<>()` で `list.add()` を呼ぶと `ArrayList` の `add` が動くのと同じ)
- **`Op` は `final` ではなく `abstract` なクラスになる。** 普通の enum は自動的に `final class` だが、`Op` は `Op$1` / `Op$2` に継承される必要があるので `final` が付かない。抽象メソッドを持つので `abstract` になる。それでもコンストラクタは `private` なので、外から継承したり `new` したりはできない

`{ }` の中に `Op` で宣言していないメソッドを足しても、外からは呼べない。フィールド `PLUS` の型は `Op` なので、呼べるのは `Op` に宣言されたメソッドだけである。`{ }` でできるのは、実質的に「`Op` で決めたメソッドの中身を、定数ごとに変えること」になる。

```java
enum Op {
    PLUS {
        int apply(int a, int b) { return a + b; }
        void hello() { }                 // Op に無いメソッドを足す
    };

    abstract int apply(int a, int b);
}

Op.PLUS.hello();   // コンパイルエラー: Op に hello() は無い
```

`{ }` を付けた定数は匿名クラスのインスタンスなので、`Op.PLUS.getClass()` は `Op$1` を返す。「この定数がどの enum のものか」を知りたいときは、`getDeclaringClass()` を使う(`Op` が返る)。

### enum に interface を実装させる

```java
interface Describable {
    String description();
}

enum Role implements Describable {
    WEB    { public String description() { return "HTTP リクエストを受ける"; } },
    WORKER { public String description() { return "キューから受け取って処理する"; } };
}

Describable d = Role.WORKER;   // interface の型として扱える
```

enum は他のクラスを継承できない(親はすでに `Enum` で決まっている)。ただし interface は実装できる。enum の定数を、interface を受け取る既存のコードにそのまま渡せる。

### シングルトンを enum で作る

```java
enum Registry {
    INSTANCE;

    private final Map<String, String> entries = new ConcurrentHashMap<>();

    void put(String key, String value) { entries.put(key, value); }
}

Registry.INSTANCE.put("a", "b");
```

定数が 1 つだけの enum は、**インスタンスが 1 つであることを JVM が保証するシングルトン**になる。3 章のとおり、リフレクションでもシリアライズでも 2 個目は作れない。普通のクラスで書くシングルトンより穴が少ないので、『Effective Java』でも推奨されている書き方である。

ただし Spring のアプリでは、1 個だけ使い回したいものは Bean にするのが普通である(Bean の既定のスコープが singleton)。enum のシングルトンは、DI を使わない場面の選択肢と考えるとよい。

## つまずきポイント

- **「`WEB` のフィールドは、`Role` をインスタンス化しないと値を持たない」と考える。** `static` フィールドはクラスに属し、クラス初期化で値が入る。インスタンスはむしろクラス初期化の中で作られる(2 章)
- **enum を `new` しようとする。** できない。使うのは作り置きの定数だけ(3 章)
- **「enum の親クラス」を、定数の持ち主のことだと思う。** Java で「親クラス」は継承元を指す。`Role` の親クラスは `java.lang.Enum`。`WEB` の持ち主は「`WEB` を `static` フィールドとして持つ `Role` クラス」
- **`ordinal()` を DB に保存する。** 定数の並び順を変えるだけで、保存済みデータの意味がずれる。`name()`(`EnumType.STRING`)を使う
- **`@Enumerated` を書き忘れる。** JPA の既定は `ORDINAL`。何も書かないと数値で保存される
- **`valueOf` が大文字と小文字を区別しないと思う。** 区別する。Spring Boot の設定で小文字が通るのは、緩やかな束縛のおかげ
- **定数ごとに `{ }` を書いた enum で `getClass()` を使う。** 名前の無いサブクラス(`Op$1`)が返る。enum の型が欲しいなら `getDeclaringClass()`(6 章)

## 用語集

- **enum(列挙型)** — 取りうる値が決まっているものを表す型。中身は `java.lang.Enum` を継承した `final` クラスで、定数はそのクラス自身のインスタンス
- **enum 定数** — `WEB` / `WORKER` のこと。実体は `public static final` フィールドで、クラス初期化で作られたインスタンスを参照している
- **`name()`** — 定数の宣言名を返す。`toString()` も既定では同じ値を返す
- **`ordinal()`** — 定数の宣言順を返す(0 から)。保存には使わない
- **`values()` / `valueOf(String)`** — コンパイラが自動で作る `static` メソッド。全定数の配列(コピー)/ 名前から定数を探す
- **型安全な enum パターン** — enum が登場する前に、`private` コンストラクタと `static final` フィールドで enum を手書きした書き方
- **`EnumMap` / `EnumSet`** — enum をキーや要素にする専用のコレクション。`ordinal` を添字に使うので速い
- **抽象メソッド** — `abstract` を付け、名前と引数だけ決めて中身を書かないメソッド。継承するクラスに実装を強制する。持つクラスは `abstract class` になり、`new` できない
- **匿名クラス** — `new 親() { ... }` のように、`new` と同時に中身を書いて作る名前の無いサブクラス。enum の定数に `{ }` を付けると、その定数は匿名クラスのインスタンスになる
- **緩やかな束縛(relaxed binding)** — Spring Boot が設定の名前や値の書き方の違い(大文字・小文字、`-` と `_` など)を吸収する仕組み
- **シングルトン** — インスタンスを 1 個だけに保って共有する仕組み。enum の定数 1 つで作るのが Java では最も堅い書き方

## 関連

- クラスそのもの(`static` 側)とインスタンス側の役割と使い分け → [class-side-and-instance-side.md](./class-side-and-instance-side.md)
- enum 定数がクラス初期化で作られる仕組み(`javap -c` の `static {}`、自己参照で無限にならない理由) → [class-vs-instance-initialization.md](./class-vs-instance-initialization.md) の 5 章
- コンストラクタの見分け方(enum のコンストラクタも同じ規則) → [constructor-declaration.md](./constructor-declaration.md)
- interface と implements → [interface-and-implements.md](./interface-and-implements.md)
- 今回の題材のコード → [AppProperties.java](../../../../backend/src/main/java/com/example/app/config/AppProperties.java)
