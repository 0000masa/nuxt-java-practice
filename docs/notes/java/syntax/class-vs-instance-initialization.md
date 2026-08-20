# クラス初期化とインスタンス初期化 — 「一度だけ」の単位が言語で違う

「`static final Duration EMAIL_VERIFICATION_TTL = Duration.ofHours(24)` の値は、いつ入るのか? `AuthTokenService` のインスタンスが作られるときか?」という疑問に答える学習メモ。結論を先に言うと:

- **Java の初期化は 2 段階ある。** `static` が付くものは**クラス初期化**(アプリ起動中に 1 回)、付かないものは**インスタンス初期化**(`new` のたび)。TTL(Time To Live = 有効期間の長さ)は前者なので、Bean が何個作られても `Duration.ofHours(24)` は 1 回しか呼ばれない。
- **クラス初期化は「クラスが最初に使われたとき」に遅れて走る。** 起動と同時ではない。トリガは JLS が 4 つだけ定めている(2 章)。
- **`static final int TOKEN_BYTES = 32` だけは扱いが違う。** コンパイル時定数は使用箇所に値が埋め込まれ、クラス初期化を引き起こさない(4 章)。`javap` で見ると `static {}` に現れないので確認できる。
- **TypeScript は「一度だけ」の単位が**クラスではなく**モジュール(ファイル)**。しかも Java と違って遅延せず、`import` された時点で評価される(7 章)。
- **PHP / Laravel は単位が**リクエスト**。** リクエストごとに全部作り直すので、`static` は「アプリ全体で 1 個」の意味にならない。Java の `static final` に相当するのは `const` / `config()` / サービスコンテナの `singleton()`(8 章)。
- **PHP のプロパティ初期化子には関数呼び出しも `new` も書けない**(定数式のみ)。だから `Duration.ofHours(24)` に相当する式をそのまま移植できない(8 章)。

インスタンス初期化(`new` で何が呼ばれるか)そのものは [constructor-declaration.md](./constructor-declaration.md) で扱った。ここではその「もう一段上」にあるクラス初期化との対比を見ていく。

## 1. Java — 初期化は 2 段階ある

同じ「フィールドの初期化」でも、`static` が付くかどうかで走るタイミングと回数が変わる。

| | いつ走るか | 何回走るか | 対象 |
|---|---|---|---|
| **クラス初期化** | クラスが最初に使われたとき | アプリ起動中に **1 回** | `static` フィールド、`static {}` ブロック |
| **インスタンス初期化** | `new` されるたび | **生成した個数だけ** | インスタンスフィールド、コンストラクタ |

このプロジェクトの `AuthTokenService` は、1 つのクラスの中に両方が並んでいるので分かりやすい。

```java
// AuthTokenService.java
@Service
public class AuthTokenService {

    /** メール確認リンクの有効期限。 */
    static final Duration EMAIL_VERIFICATION_TTL = Duration.ofHours(24);   // ← クラス初期化(1 回)

    /** パスワードリセットリンクの有効期限。 */
    static final Duration PASSWORD_RESET_TTL = Duration.ofHours(1);        // ← クラス初期化(1 回)

    // 32 バイト = 256 ビット。
    private static final int TOKEN_BYTES = 32;                             // ← 4 章で扱う特別枠

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();  // ← クラス初期化(1 回)

    private final AuthTokenRepository authTokenRepository;                 // ← インスタンス初期化

    AuthTokenService(AuthTokenRepository authTokenRepository) {             // ← インスタンス初期化
        this.authTokenRepository = authTokenRepository;
    }
}
```

上 4 つは「アプリ全体で共有する固定値」、最後の 1 つは「Spring が注入する依存」。**リクエストごとに作り直す必要がないものが `static`** になっている、という読み方をする。

### 呼び出し側の書き方に差が出る

`AuthService` から使うとき、この違いがそのまま構文に出る。

```java
// AuthService.java の issueVerificationMail
String rawToken = authTokenService.issue(user, AuthTokenPurpose.EMAIL_VERIFICATION,
        AuthTokenService.EMAIL_VERIFICATION_TTL);
//      ↑ クラス名から読む            ↑ インスタンス変数から呼ぶ
```

- `authTokenService.issue(...)` … インスタンスメソッドなので、注入された Bean 経由で呼ぶ
- `AuthTokenService.EMAIL_VERIFICATION_TTL` … `static` なのでクラス名から直接読める

`authTokenService.EMAIL_VERIFICATION_TTL` と書いても文法上は動く(`static` はインスタンス経由でも読める)が、「インスタンスごとの値」という誤解を招くため、**`static` はクラス名から読む**のが作法。

なお `EMAIL_VERIFICATION_TTL` に `private` が付いていないのは、同じパッケージの `AuthService` から読めるようにするため。

## 2. クラス初期化はいつ走るのか — 「最初に使われたとき」

「クラス初期化」は起動と同時ではない。JLS(Java 言語仕様)12.4.1 が、トリガを次の 4 つだけと定めている。この 4 つのどれかが**初めて**起きる直前に、1 回だけ初期化される。

1. そのクラスのインスタンスが作られる(`new`)
2. そのクラスの `static` メソッドが呼ばれる
3. そのクラスの `static` フィールドに代入される
4. そのクラスの `static` フィールドが読まれる。**ただしコンパイル時定数(constant variable)は除く**

4 の但し書きが 4 章の話につながる。

### このプロジェクトでは起動時に走る

Spring Boot は起動時に `@Service` の付いたクラスの Bean を生成する。つまりトリガ 1(インスタンス生成)が起動時に起きるので、`AuthTokenService` のクラス初期化も起動時に完了する。`AuthService` が `AuthTokenService.EMAIL_VERIFICATION_TTL` を読む(トリガ 4)より先に終わっている。

一方、Spring の管理下にない普通のクラス(ユーティリティクラスなど)は、**本当に最初に使われるまで初期化されない**。「起動ログには何も出ないのに、特定の API を叩いた瞬間に初期化エラーが出る」という現象はこれが原因になる。

### `static {}` は宣言順に走る

`static` フィールドの初期化式と `static {}` ブロックは、**ソースに書いた順**で実行される。だから下のように順序を逆にすると `null` を読んでしまう。

```java
static final String UPPER = BASE.toUpperCase();  // ← BASE はまだ null → NullPointerException
static final String BASE = "abc";
```

## 3. コンパイラが生成する `static {}` を見る — `javap`

ソースには `static {}` を書いていないのに、コンパイル結果には存在する。`javap`(JDK 付属のクラスファイル解析コマンド)で確認できる。

```
$ docker compose exec backend javap -p -c \
    build/classes/java/main/com/example/app/auth/AuthTokenService.class
```

`static {}` の部分だけ抜き出すと、こうなっている。

```
  static {};
    Code:
       0: ldc2_w        // long 24l                            ← 引数の 24
       3: invokestatic  // Method java/time/Duration.ofHours   ← Duration.ofHours(24) を呼ぶ
       6: putstatic     // Field EMAIL_VERIFICATION_TTL        ← 結果を static フィールドへ代入
       9: lconst_1      // 1
      10: invokestatic  // Method java/time/Duration.ofHours
      13: putstatic     // Field PASSWORD_RESET_TTL
      16: new           // class java/security/SecureRandom
      23: putstatic     // Field SECURE_RANDOM
      26: invokestatic  // Method java/util/Base64.getUrlEncoder
      32: putstatic     // Field URL_ENCODER
```

読むのに必要な語は 2 つだけ。

- **`putstatic`** … `static` フィールドへの代入
- **`invokestatic`** … `static` メソッドの呼び出し

つまり「ソースに代入が書かれていない」のではなく、**コンパイラが集約した代入がここに置かれ、クラス初期化のときに 1 回だけ実行される**。`final` なので、以後この値は変わらない。

## 4. コンパイル時定数だけは扱いが違う

上の出力をよく見ると、`TOKEN_BYTES` が**現れていない**。

```java
private static final int TOKEN_BYTES = 32;   // static {} に出てこない
```

`static final` かつ「プリミティブ型または `String` で、初期化式が定数式」の変数は **コンパイル時定数(constant variable)** として扱われ、コンパイラが**使用箇所に値を直接埋め込む**。だから代入命令が要らない。

```java
// ソース                          // コンパイル後(概念)
byte[] bytes = new byte[TOKEN_BYTES];   →   byte[] bytes = new byte[32];
```

`Duration.ofHours(24)` はメソッド呼び出しなので定数式ではなく、この扱いはできない。実行しないと結果が分からないため、クラス初期化のときに実際に呼ばれる。

### 効果は「速い」だけではない

2 章のトリガ 4 に「コンパイル時定数は除く」とあったのはこのため。値が埋め込まれているので、**参照側はクラスを読み込む必要すらない**。

その代わり落とし穴がある。**ライブラリ側の定数を変更しても、参照している側を再コンパイルしないと古い値が残る。** 自分のプロジェクト内なら毎回まとめてビルドするので問題にならないが、jar を差し替えるだけの更新では起こり得る。

## 5. 応用 — enum 定数もクラス初期化で作られる

[AuthTokenPurpose](../../../../backend/src/main/java/com/example/app/auth/AuthTokenPurpose.java) の 2 つの定数も、実体は `static final` フィールドである。

```java
public enum AuthTokenPurpose {
    EMAIL_VERIFICATION,
    PASSWORD_RESET
}
```

`javap` で見ると、コンパイラが `public static final` フィールドに展開していることが分かる。

```
public final class com.example.app.auth.AuthTokenPurpose extends java.lang.Enum<...> {
  public static final com.example.app.auth.AuthTokenPurpose EMAIL_VERIFICATION;
  public static final com.example.app.auth.AuthTokenPurpose PASSWORD_RESET;
  private static final com.example.app.auth.AuthTokenPurpose[] $VALUES;
```

そして `static {}` の中で 2 個のインスタンスが生成され、それぞれのフィールドに代入されている(`new` → コンストラクタ → `putstatic` が 2 セット)。**enum 定数は「クラス初期化で作られ、以後変わらないインスタンス」**であり、3 章で見た `SECURE_RANDOM` と構造は同じ。

### 「自分の型のフィールドを持つと無限に入れ子にならないか」への答え

`AuthTokenPurpose` 型のフィールドを `AuthTokenPurpose` クラスが持っているので、一見すると「インスタンスが自分自身を含む」ように見える。ならない理由は 2 つある。

1. **フィールドが `static`** なので、置き場所はクラス側でありインスタンスの中ではない。インスタンスが持つのは継承元 `java.lang.Enum` の `name`(宣言名の文字列)と `ordinal`(宣言順の番号)だけ
2. **フィールドが持つのは参照**であってオブジェクトの複製ではない。参照を持つだけなら入れ子は生じない

無限になるのは「生成の途中でまた生成する」場合だけ。

```java
class Bad  { Bad self = new Bad(); }                // ← StackOverflowError(インスタンス生成のたびに再生成)
class Good { static final Good ONLY = new Good(); } // ← 1 個で終わる(クラス初期化は 1 回だけ)
class Node { Node next; }                           // ← 参照を持つだけ。n.next = n としても平気
```

`Good` が enum 定数と同じ形。**危険なのは自己参照ではなく自己生成**、と覚える。

## 6. `static` に置いていいもの — 不変か、スレッドセーフか

`static` は全リクエストで共有されるので、置くものを選ぶ必要がある。

- `EMAIL_VERIFICATION_TTL`(`Duration`)… **不変(immutable)**。`plus` などは自分を書き換えず新しいオブジェクトを返すので、同時に使っても壊れない
- `SECURE_RANDOM`(`SecureRandom`)… 内部状態を持つが**スレッドセーフに作られている**ので共有できる

逆に「可変で、かつスレッドセーフでないもの」を `static` に置くと、あるリクエストの変更が別のリクエストに漏れる不具合になる(`SimpleDateFormat` を `static` にする事故が有名)。

### 「長さ」を `static` にして「時点」は都度計算する

TTL の設計で注意すべき点がもう 1 つある。`static` にしているのは **`Duration`(長さ)** であって `LocalDateTime`(時点)ではない。

```java
// AuthTokenService.issue
authTokenRepository.save(new AuthToken(user, sha256Hex(rawToken), purpose,
        LocalDateTime.now().plus(validFor)));   // ← 期限は発行のたびに計算する
```

もし `static final LocalDateTime EXPIRES_AT = LocalDateTime.now().plusHours(24)` と書いてしまうと、**アプリ起動時刻から 24 時間後に固定される**。起動 2 日後に発行したトークンは即座に期限切れになる。「クラス初期化は 1 回だけ」という性質が、そのまま不具合になる典型例。

## 7. TypeScript — 「一度だけ」の単位はモジュール

TypeScript / JavaScript にも `static` フィールドと `static` ブロックがある。書き方は Java に近い。

```typescript
class TokenService {
  static readonly EMAIL_VERIFICATION_TTL_MS = 24 * 60 * 60 * 1000  // static フィールド

  static {                      // static 初期化ブロック(ES2022 / TypeScript 4.4 以降)
    // 複雑な初期化をここに書ける。private フィールドにもアクセスできる
  }

  constructor(private repo: TokenRepository) {}   // インスタンス初期化
}
```

ただし Java との違いが 2 つある。

### 違い 1 — 遅延しない

Java はクラスが**最初に使われるまで**初期化を待つ。TypeScript の静的フィールドは**クラス定義が評価された時点**で初期化される。`class` 宣言はモジュールのトップレベルにあるので、そのファイルが `import` された瞬間に走る。

```
Java  … import してもまだ走らない。new / static アクセスで初めて走る(遅延)
TS/JS … import した時点で class 宣言が評価され、static フィールドも初期化される(即時)
```

そのため TS では、`static` の初期化に副作用があると **`import` の順序に依存する**コードになりやすい。

### 違い 2 — そもそもクラスを使わない

JS のモジュールは**一度評価されたらキャッシュされ、二度目以降の `import` では再評価されない**。つまり「アプリ全体で 1 回だけ」を実現する単位は、クラスではなく**モジュール(ファイル)**である。

```typescript
// tokenTtl.ts — トップレベルに書くだけで「1 回だけ」になる
export const EMAIL_VERIFICATION_TTL_MS = 24 * 60 * 60 * 1000
```

Java だと定数を置くだけでもクラスに入れる必要がある(トップレベルに変数を置けない)が、TS はファイルがそのまま入れ物になる。だから TS/JS では `static` フィールドをあまり使わず、**モジュールのトップレベル定数**で済ませるのが普通。

実際このプロジェクトのフロントエンドには、`static` を使ったクラスもモジュールレベルの共有状態も存在しない(状態は composable と `useState` が持つ)。

## 8. PHP / Laravel — 「一度だけ」の単位はリクエスト

PHP は 3 言語の中で最も事情が違う。**リクエストごとにプログラム全体を読み込み直し、終わったら全部捨てる**(shared-nothing 方式)。

```
Java  … JVM のプロセスが生き続ける。static は起動から終了までずっと同じ
TS/JS … Node のプロセスが生き続ける。モジュールは 1 回だけ評価される
PHP   … リクエストごとに作り直す。static は「そのリクエストの間だけ 1 個」
```

つまり `public static $ttl = 86400;` と書いても、それは「アプリ全体で 1 個」ではなく「**このリクエストの間だけ 1 個**」でしかない。Java の `static final` を移植したつもりでも意味が変わる。

### 制約 1 — 初期化子に定数式しか書けない

PHP のプロパティ初期化子(`static` かどうかに関係なく)には、**定数式しか書けない**。公式マニュアルは「this initialization must be a constant value」と述べており、関数呼び出しや `new` は書けない。

```php
class TokenService {
    public static int $ttlSeconds = 24 * 60 * 60;      // OK(定数式)
    public static array $purposes = ['email', 'reset']; // OK(定数の配列)

    public static $ttl = self::makeTtl();               // 不可(メソッド呼び出し)
    public static $random = new SecureRandom();         // 不可(new)
}
```

PHP 8.1 で `new` を書ける場所が広がった(引数のデフォルト値、Attribute の引数、関数内の `static` 変数、グローバル定数)が、RFC は **「New expressions continue to not be supported in (static and non-static) property initializers and class constant initializers」** と明記しており、プロパティ初期化子は対象外のまま。

したがって Java の

```java
static final Duration EMAIL_VERIFICATION_TTL = Duration.ofHours(24);
```

を PHP にそのまま移すことはできない。定数式で書ける形(秒数の整数など)に落とすか、後述の遅延初期化を自分で書くことになる。

### 制約 2 — `static` 初期化ブロックが無い

PHP には Java の `static {}` に相当する構文が無い。複雑な初期化が必要なら、`static` メソッドの中で「まだ無ければ作る」形を自分で書く。

```php
class TokenService {
    private static ?SecureRandom $random = null;   // 定数(null)で宣言し

    private static function random(): SecureRandom {
        return self::$random ??= new SecureRandom();  // 初回アクセス時に作る
    }
}
```

なお **評価が遅延する点は Java と同じ**で、PHP の `static` プロパティとクラス定数の初期化子は「クラスが最初に使われたとき」に評価される(前掲 RFC が lazy evaluation と説明している)。違うのは「書ける式の範囲」と「寿命」の 2 点。

### 予備知識 — singleton(シングルトン)とは

**「そのクラスのインスタンスを 1 個だけに保ち、全員で同じものを使い回す」仕組みの呼び名。** 歴史的に 2 つの別物がこの名前で呼ばれているので、分けて覚える。

**① GoF のシングルトンパターン(設計パターン)** — クラス自身が「1 個しか作れない」ように自分を縛る書き方。コンストラクタを `private` にして外からの `new` を禁止し、`getInstance()` でただ 1 つのインスタンスを返す。

```java
public class Config {
    private static final Config INSTANCE = new Config();   // クラス初期化で 1 個だけ作る
    private Config() {}                                     // 外から new できない
    public static Config getInstance() { return INSTANCE; }
}
```

中身は本ノートの 3〜5 章そのもので、`static final` フィールド＋クラス初期化で実現している。ただし現在は「どこからでも触れるグローバル変数になり、テストで差し替えられない」という理由で避けられることが多い。

**② DI コンテナの singleton スコープ(フレームワークの機能)** — クラス自身は普通のクラスのまま。**コンテナ(依存を組み立てて配る役)の側が「1 個だけ作って配る」と決める。** Spring も Laravel もこちら。クラスに制約が無いので、テストでは普通に `new` して差し替えられる。

Spring の公式ドキュメントは両者の違いを明示している。GoF は「ClassLoader ごとに 1 個」だが、**Spring の singleton は「コンテナごと・Bean 定義ごとに 1 個」**(原文: "The scope of the Spring singleton is best described as being per-container and per-bean")。だから同じクラスを 2 つの Bean として定義すれば 2 個作れる。

そして **Spring の既定のスコープは singleton**。`@Service` や `@RestController` を付けたクラスは、何も指定しなくても 1 個しか作られない。

### Laravel では何を使うか

Laravel は「アプリ全体で 1 個」を `static` ではなく**フレームワークの仕組み**で表現する。Java の `static final` に相当するものは、用途によって 3 つに分かれる。

| やりたいこと | Java | Laravel |
|---|---|---|
| 固定値をコードに埋める | `static final` / `enum` | クラス定数 `const TTL_HOURS = 24;` |
| 環境ごとに変えたい値 | `@ConfigurationProperties` など | `config('auth.ttl')`(`config/*.php` + `.env`) |
| インスタンスを 1 個だけ共有 | `static` フィールド / DI コンテナの singleton Bean | サービスコンテナ `$this->app->singleton(...)` |

```php
// AppServiceProvider::register()
$this->app->singleton(TokenService::class, fn ($app) => new TokenService($app->make(TokenRepository::class)));
```

Laravel の `singleton()` は「一度解決したら、以後はコンテナが同じインスタンスを返す」という意味(公式: "Once a singleton binding is resolved, the same object instance will be returned on subsequent calls into the container")。注意が必要なのは、**その「以後」がどこまで続くかは `singleton` 自身ではなくコンテナの寿命で決まる**点。

- **通常の PHP-FPM 運用** … リクエストごとにコンテナごと作り直されるので、結果として「1 リクエストの間 1 個」になる
- **Octane** … コンテナがメモリに残り続けるので、**`singleton` はリクエストを跨いで生き残る**

つまり `singleton` は「1 リクエスト 1 個」を保証する機能ではない。リクエスト(やキューのジョブ)ごとに確実に作り直したい場合のために、Laravel には別途 **`scoped()`** がある(公式: scoped で登録したインスタンスは "flushed whenever the Laravel application starts a new lifecycle")。`singleton` と `scoped` の違いは、Octane やキューワーカーを使ったときに初めて表に出る。

Spring も同じ役割をコンテナが担うが、Spring のコンテナはアプリの寿命ずっと生きるので、`static` フィールドと singleton Bean の寿命が結果的に一致する。この一致が、Java では「固定値は `static final`、依存は DI」という素直な住み分けを可能にしている。逆に PHP は既定でリクエストごとに全部消えるため、`static` に頼らずコンテナに任せる作法が自然になった。

### 3 言語まとめ

| | 「一度だけ」の単位 | 初期化のタイミング | 初期化子に書ける式 | 専用の初期化ブロック |
|---|---|---|---|---|
| **Java** | クラス(JVM の寿命) | クラスが最初に使われたとき(遅延) | 任意の式(メソッド呼び出し可) | `static {}` |
| **TypeScript** | モジュール(プロセスの寿命) | クラス定義の評価時 = `import` 時(即時) | 任意の式 | `static {}`(ES2022 / TS 4.4) |
| **PHP / Laravel** | **リクエスト**(既定の PHP-FPM 運用時) | クラスが最初に使われたとき(遅延) | **定数式のみ**(関数呼び出し・`new` 不可) | なし(`static` メソッドで代用) |

## 9. このプロジェクトでの実例

- **`AuthTokenService`** … `EMAIL_VERIFICATION_TTL` / `PASSWORD_RESET_TTL` / `SECURE_RANDOM` / `URL_ENCODER` がクラス初期化、`authTokenRepository` がインスタンス初期化。`TOKEN_BYTES` はコンパイル時定数
- **`AuthTokenPurpose`** … enum 定数 2 つがクラス初期化で生成される(5 章)
- **`AuthService`** … `AuthTokenService.EMAIL_VERIFICATION_TTL` をクラス名から読み、`authTokenService.issue(...)` をインスタンス経由で呼ぶ(1 章)
- **`AuthToken`** … `purpose` はインスタンスフィールドだが、`AuthTokenPurpose` の既存インスタンスを参照するだけ。DB から 1000 行読んでも enum のインスタンスは 2 個のまま

## つまずきポイント

- **`static` に「時点」を置く。** `LocalDateTime.now()` を `static` フィールドに入れると起動時刻で固定される。`static` にしていいのは「長さ」や「設定値」まで(6 章)
- **`static` に可変オブジェクトを置く。** リクエスト間で状態が漏れる。不変かスレッドセーフかを確認してから置く
- **`static` フィールドの宣言順に依存する。** 上から順に実行されるので、後で宣言したフィールドを先に参照すると `null` になる(2 章)
- **クラス初期化中の例外は `ExceptionInInitializerError` になる。** 「起動はしたのに、ある API を叩いた瞬間に落ちる」という追いにくい症状になりやすい。`static` の初期化に重い処理や失敗し得る処理を書かない
- **`javap` の出力に無いフィールドを「初期化されていない」と誤読する。** コンパイル時定数はインライン展開されているだけ(4 章)
- **TS で `static` の初期化に副作用を書く。** `import` した時点で走るため、import 順に依存する挙動になる(7 章)
- **PHP のプロパティ初期化子に関数呼び出しを書いてパースエラー。** 定数式しか書けない。遅延初期化は `??=` を使った `static` メソッドで書く(8 章)
- **Laravel の `singleton` を「1 リクエスト 1 個」と思い込む。** それはコンテナが毎リクエスト作り直される場合の結果に過ぎない。リクエスト単位を保証したいなら `scoped` を使う(8 章)
- **Laravel Octane を使うと PHP でも `static` がリクエストを跨いで生き残る。** 公式ドキュメントも「adding data to a statically maintained array will result in a memory leak」と警告している。「単位はリクエスト」という前提が変わるので、`static` に溜め込むコードは Octane 化した瞬間にメモリリークになる

## 用語集

- **クラス初期化** — クラスが最初に使われるときに 1 回だけ行われる処理。`static` フィールドの初期化と `static {}` の実行
- **インスタンス初期化** — `new` のたびに行われる処理。インスタンスフィールドの初期化とコンストラクタの実行
- **静的初期化子(`static {}`)** — クラス初期化のときに実行されるブロック。Java / TS(ES2022)にあり、PHP には無い
- **コンパイル時定数(constant variable)** — `static final` かつプリミティブ / `String` で初期化式が定数式のもの。使用箇所に値が埋め込まれ、クラス初期化を引き起こさない
- **定数式** — 実行しなくても値が決まる式。PHP のプロパティ初期化子はこれに限られる
- **遅延評価(lazy evaluation)** — 必要になるまで評価を先延ばしにすること。Java と PHP のクラスレベルの初期化はこの方式
- **不変(immutable)** — 生成後に内部状態が変わらない性質。共有しても壊れない
- **TTL(Time To Live)** — 「どれだけの間有効か」を表す**長さ**。発行時刻に TTL を足したものが期限(`expires_at`)という**時点**になる。長さと時点を混同すると 6 章の事故になる
- **shared-nothing** — リクエストごとに状態を作り直し、何も共有しない方式。PHP の既定の動き方
- **`javap`** — JDK 付属のコマンド。クラスファイルの構造やバイトコードを表示する
- **`putstatic` / `invokestatic`** — `static` フィールドへの代入 / `static` メソッドの呼び出しを行うバイトコード命令
- **`ExceptionInInitializerError`** — クラス初期化中に例外が起きたときに投げられるエラー
- **シングルトン(singleton)** — インスタンスを 1 個だけに保って共有する仕組み。GoF の設計パターン(クラス自身が縛る)と、DI コンテナのスコープ(コンテナが決める)の 2 つの意味がある
- **GoF(Gang of Four)** — 書籍『デザインパターン』の著者 4 人。シングルトンパターンの出典
- **Bean** — Spring のコンテナが管理するオブジェクト
- **Bean スコープ** — Spring が Bean を何個作りどれだけ生かすかの設定。既定は singleton(コンテナごと・Bean 定義ごとに 1 個)
- **サービスコンテナ** — Laravel の DI 機構。依存を組み立てて配る。`bind()` / `singleton()` / `scoped()` で「何個作るか」を指定する
- **`scoped()`(Laravel)** — リクエストやジョブのライフサイクルごとに 1 個。新しいライフサイクルが始まると破棄される
- **Laravel Octane** — Laravel をメモリに常駐させて動かす仕組み。リクエスト単位という前提が崩れる

## 関連

- コンストラクタの見分け方(`new` で何が呼ばれるか = インスタンス初期化の入口) → [constructor-declaration.md](./constructor-declaration.md)
- `static` **メソッド**の話(static ファクトリメソッド、interface の static メソッド) → [static-methods-and-factory-methods.md](./static-methods-and-factory-methods.md)
- オブジェクトとクラスの違い、3 言語での立ち位置 → [../../object-and-class-by-language.md](../../object-and-class-by-language.md)
- クラス自体を値として持つ(`Post.class` / `Post::class`) → [class-literal.md](./class-literal.md)
- 数値リテラルと整数型(`TOKEN_BYTES` のような定数の型の話) → [numeric-literals-and-integer-types.md](./numeric-literals-and-integer-types.md)
- 今回の題材のコード → [AuthTokenService.java](../../../../backend/src/main/java/com/example/app/auth/AuthTokenService.java) と [AuthTokenPurpose.java](../../../../backend/src/main/java/com/example/app/auth/AuthTokenPurpose.java)
