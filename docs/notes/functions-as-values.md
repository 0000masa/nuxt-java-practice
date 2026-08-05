# 関数を値として扱う — クロージャ / アロー関数 / ファーストクラス callable

「クロージャ」「アロー関数」「ファーストクラス callable」がよく分からない、という疑問に答える学習メモ。この 3 つはバラバラの機能ではなく、**すべて「関数を値として扱う」という 1 つのテーマの側面**。結論を先に言うと:

- **前提**: JS/TS では**関数は値**。数値や文字列と同じように変数に入れたり、引数で渡したり、戻り値で返したりできる。この土台があって初めて以下 3 つが意味を持つ。
- **クロージャ** — 関数が、**自分の外側の変数を覚えたまま持ち歩く**仕組み。これを使うと「状態を閉じ込める」ことができ、**クラスの代わり**になる。
- **アロー関数** — 関数を短く書く記法。ただし JS では**`this` の扱いも違う**ので、単なる短縮記法ではない。
- **ファーストクラス callable** — すでにある関数を、**呼ばずに「名前で指して値として渡す」**書き方(Java の `PostResponse::from`、PHP 8.1 の `strlen(...)`)。
- **3 言語で自由度が違う。** 特に **Java は捕まえた変数を書き換えられない**ため、JS のようなクロージャ活用ができない。ここが「TS は関数中心、Java はクラス中心」を下支えしている。

## 0. 前提 — 「関数を値として扱う」とは

まず土台。関数を「呼ぶためのもの」だと思っていると、この後の話が入ってこない。**JS/TS では関数は「値」そのもの**で、数値や文字列と同じように扱える。

```typescript
// ① 変数に入れられる
const greet = () => "こんにちは"

// ② 引数として渡せる
const doubled = [1, 2, 3].map((n) => n * 2)

// ③ 戻り値として返せる
function makeGreeter() {
  return () => "やあ"
}
```

このように値として自由に扱える性質を **第一級(ファーストクラス)** と言う。「ファーストクラス callable」という名前もここから来ている。

実際このプロジェクトでも、関数を引数として渡している箇所がある。

```typescript
// frontend/app/pages/index.vue
posts.value = posts.value.filter((post) => post.id !== id)
//                               ^^^^^^^^^^^^^^^^^^^^^^^ 関数を filter に渡している
```

`filter` のように**関数を引数に取る関数**を **高階関数**、渡される側の関数を **コールバック**と呼ぶ。

## 1. クロージャ — 関数が外側の変数を覚えて持ち歩く

**クロージャ**とは、**関数が、自分が作られた場所の外側の変数を覚えたまま持ち歩く仕組み**のこと。「関数が変数を詰めたリュックサックを背負って出かける」イメージ。

```typescript
function makeCounter() {
  let count = 0              // ① 外側のローカル変数
  return () => {             // ② 内側の関数が①を使っている
    count = count + 1
    return count
  }
}

const next = makeCounter()
console.log(next())   // 1
console.log(next())   // 2   ← count が残っている!
console.log(next())   // 3
```

ここで起きていることが肝心。**`makeCounter()` はもう終了しているのに、`count` が消えていない。** 通常ローカル変数は関数が終われば消えるが、**内側の関数がまだ使っているので生き残る**。この「関数 + 覚えている外側の変数」のセットをクロージャと呼ぶ。

そして呼ぶたびに独立したクロージャができる。

```typescript
const a = makeCounter()
const b = makeCounter()
a(); a()      // a の count は 2
b()           // b の count は 1(a とは別物)
```

**「1 つの元から複数の独立した実体を作れる」** — これはクラスとインスタンスの関係とまったく同じ性質。次章でそこを見る。

### このプロジェクトの実例

クロージャは特別な機能ではなく、**コールバックを書けば自然にそうなっている**。`index.vue` の無限スクロール部分がそのまま実例。

```typescript
// frontend/app/pages/index.vue
const sentinel = ref<HTMLElement | null>(null)
let observer: IntersectionObserver | undefined

onMounted(() => {
  loadMore()
  observer = new IntersectionObserver((entries) => {
    if (entries[0]?.isIntersecting) loadMore()   // ← 外側の loadMore を覚えている
  })
  if (sentinel.value) observer.observe(sentinel.value)
})

onBeforeUnmount(() => observer?.disconnect())    // ← 外側の observer を覚えている
```

`IntersectionObserver` に渡したコールバックは、**後でブラウザが勝手に呼ぶ**関数。呼ばれる時点で `onMounted` はすでに終わっているのに、`loadMore` を覚えているから動く。これがクロージャ。

さらに `onBeforeUnmount` のアロー関数は、`onMounted` の中で代入された `observer` を覚えている。**別々のタイミングで実行される 2 つの関数が、同じ 1 つの変数を共有している** — これは後で見るように、Java では書けない書き方。

## 2. クロージャはクラスの代わりになる

1 章のカウンタを、クラスで書き直すと次のようになる。

```typescript
class Counter {
  private count = 0                              // 状態はフィールドに持つ
  next() { this.count += 1; return this.count }
}

const c = new Counter()
c.next()   // 1
c.next()   // 2
```

クロージャ版とクラス版は、**やっていることが同じ**。

| | **クラス版** | **クロージャ版** |
|---|---|---|
| 状態の置き場 | フィールド(`this.count`) | 関数内のローカル変数(`count`) |
| 外から触れるか | `private` で隠す | **そもそも外から名前が見えない**(より強力) |
| 作り方 | `new Counter()` | `makeCounter()` |
| 複数作れるか | ✅ それぞれ独立 | ✅ それぞれ独立 |

つまりクロージャは「**状態を閉じ込めて、決まった操作だけ公開する**」というクラスと同じ目的を果たす。しかも `count` は外から一切アクセスできないので、`private` より強い隠蔽になっている。

操作を複数公開したいなら、**オブジェクトにまとめて返す**。

```typescript
function makeCounter() {
  let count = 0
  return {
    next: () => ++count,
    reset: () => { count = 0 },
    current: () => count,
  }
}

const c = makeCounter()
c.next(); c.next()
c.current()   // 2
```

**これがまさにこのプロジェクトの composable の形。**

```typescript
// frontend/app/composables/usePosts.ts
export function usePosts() {
  const fetchTimeline = (params) => $fetch<Timeline>('/api/posts', { params })
  const fetchPost = (id) => $fetch<Post>(`/api/posts/${id}`)
  // ...
  return { fetchTimeline, fetchPost, createPost, deletePost }   // オブジェクトで返す
}
```

`usePosts()` は「関数の中で部品を作り、オブジェクトにまとめて返す」形になっている。クラスの `PostService` と同じ役割を、クラス無しで果たしている。**TS でクラスが要らない場面が多いのは、この仕組みがあるから**(→ [object-and-class-by-language.md の 7 章](./object-and-class-by-language.md))。

## 3. 外側の変数の捕まえ方 — 3 言語で決定的に違う

ここが重要。**2 章のパターンは JS/TS だから成り立つ**もので、他の言語では同じようにいかない。外側の変数を取り込むことを **キャプチャ** と呼ぶが、その仕様が言語ごとに違う。

### JS / TS — 自動で捕まえ、書き換えもできる

何も書かなくても外側の変数が見え、しかも**書き換えられる**。1 章のカウンタが動くのはこのおかげ。前章で見た `let observer` を別の関数から代入・参照できるのも、この自由さゆえ。

### PHP — `use` で明示的に持ち込む

PHP では、**外側の変数を使うと宣言しない限り見えない**。

```php
$count = 0;

$next = function () use ($count) {   // use で持ち込む。書かないと $count は見えない
    return $count + 1;
};
```

しかも `use ($count)` は**値のコピー**。中で書き換えても外の `$count` は変わらない。書き換えを共有したいなら **参照(`&`)** で渡す。

```php
$count = 0;
$next = function () use (&$count) {   // & を付けると同じ変数を共有する
    return ++$count;
};
echo $next();   // 1
echo $next();   // 2
```

PHP 7.4 で入った**アロー関数 `fn()`** なら、`use` を書かなくても自動でキャプチャされる(ただし値のコピー)。

```php
$next = fn() => $count + 1;   // use 不要
```

### Java — 自動だが「実質 final」しか捕まえられない

Java のラムダは自動でキャプチャするが、**書き換えない変数しか捕まえられない**。

```java
int count = 0;
Runnable r = () -> count++;   // ❌ コンパイルエラー
// 「ラムダ式から参照されるローカル変数は final または実質 final でなければならない」
```

**実質 final(effectively final)** とは、**宣言したあと一度も再代入していないローカル変数**のこと。読むだけなら問題ない。

```java
int limit = 20;                                   // 再代入していない → 実質 final
list.stream().filter(p -> p.getId() < limit);     // ✅ 読むだけならOK
```

なぜこの制約があるのか。Java のラムダは捕まえた変数の**コピー**を持つため、書き換えを許すと「外側の変数とラムダ内の変数、どちらが変わったのか」が曖昧になる。その混乱を避けるため、**そもそも変わらない変数だけ**に限っている。

結果として、**Java では「関数の中に可変の状態を閉じ込める」ができない。** 状態を持ちたければクラスのフィールドにするか、`AtomicInteger` のような専用の入れ物を使う。前章で見た `let observer` を別の関数から代入するような書き方も、Java のローカル変数では不可能。

### まとめ

| | 外側の変数の捕まえ方 | 捕まえた変数の書き換え | クロージャでクラス代替 |
|---|---|---|---|
| **JS / TS** | 自動 | ✅ できる | ✅ **定番の手法** |
| **PHP** | `use ($x)` で明示(`fn()` は自動) | `use (&$x)` にすれば可 | 一応可能だが定番ではない |
| **Java** | 自動 | ❌ **不可**(実質 final のみ) | ❌ **できない** |

**「TS は関数中心、Java はクラス中心」の技術的な下支えがここにある。** Java は言語仕様上、クロージャに状態を持たせられないので、状態を持つならクラスにするしかない。

## 4. アロー関数 — 短く書く記法。ただし `this` の扱いも違う

### まず記法として

```typescript
// 従来の書き方
const double = function (n: number) { return n * 2 }

// アロー関数
const double = (n: number) => n * 2
```

`function` の代わりに `=>` を使う。**本体が式 1 つだけなら `{}` と `return` を省略できる**のが大きな利点。

このプロジェクトでも省略形が使われている。

```typescript
// frontend/app/composables/usePosts.ts
const fetchPost = (id: number | string) => $fetch<Post>(`/api/posts/${id}`)
//                                       ↑ { return ... } を省略している
```

複数行必要なら `{}` を書く(その場合は `return` も必要)。

```typescript
const next = () => {
  count = count + 1
  return count
}
```

### 本題 — アロー関数は自分の `this` を持たない

ここがアロー関数の**本質的な違い**。短さの話だけではない。

まず前提として、**`this` はオブジェクトのメソッドの中で「自分自身」を指すもの**。ところが JS の `this` は厄介で、**「どう呼ばれたか」で中身が変わる**。

```javascript
const timer = {
  count: 0,
  start: function () {
    setInterval(function () {
      this.count++      // ❌ ここの this は timer ではない!
    }, 1000)
  }
}
```

`setInterval` に渡した関数は、後で**単独の関数として**呼ばれる。すると `this` は `timer` ではなくなってしまう(strict モードなら `undefined`、そうでなければグローバルオブジェクト)。結果 `this.count++` は動かない。

昔はこれを 2 通りの「おまじない」で回避していた。

```javascript
start: function () {
  var self = this                 // ① this を別名の変数に退避しておく
  setInterval(function () {
    self.count++                  // self なら確実に timer を指す
  }, 1000)
}
```

```javascript
start: function () {
  setInterval(function () {
    this.count++
  }.bind(this), 1000)             // ② bind で this を固定してから渡す
}
```

`var self = this` と `.bind(this)` は、かつての JS で頻出の定型句だった。**アロー関数はこれを言語の仕組みとして解決した。**

```javascript
start: function () {
  setInterval(() => {
    this.count++      // ✅ 外側(start)の this をそのまま使う
  }, 1000)
}
```

**アロー関数は自分の `this` を作らないので、外側の `this` が透けて見える。** どう呼ばれたかに左右されない。だから `self` も `bind` も不要になった。

つまり **アロー関数は「短く書ける `function`」ではなく、`this` の意味が違う別物**。短さのためだけに導入されたわけではない。

その他の違いも押さえておくとよい。

- **`new` できない**(コンストラクタにならない)
- **`arguments` を持たない**(引数をまとめた特殊な変数が使えない)
- **オブジェクトのメソッドには不向き** — `this` が外側を指してしまうので、メソッドは従来の書き方にする

### このプロジェクトでは `this` が出てこない

実際に frontend を調べると、**`this.` は 1 か所も登場しない**。Vue の **Composition API** は `this` を使わず、`ref` などの変数と関数を組み合わせる設計だからだ。そのため実務上この落とし穴を踏みにくい。

ただし Vue の Options API、クラスを使うコード、古い JS では今も現役の論点なので、「アロー関数は `this` が違う」は覚えておく価値がある。

### PHP / Java のアロー関数・ラムダ

PHP のアロー関数 `fn()`(7.4)は、**1 つの式だけ書ける簡潔版**で、`use` 不要の自動キャプチャが特徴。

```php
$double = fn($n) => $n * 2;
```

Java のラムダ式も同じ系統の記法。このプロジェクトの実コードにも登場する。

```java
// backend/.../post/PostService.java
.orElseThrow(() -> new ResourceNotFoundException("投稿が見つかりません: id=" + id));
//            ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ 引数なしのラムダ式
```

Java のラムダも(JS のアロー関数と同様)**自分の `this` を持たず、外側の `this` を使う**。

## 5. ファーストクラス callable — 既にある関数を「名前で」渡す

やりたいことは単純。**すでに定義済みの関数を、呼ばずに「その関数自体」を渡したい。**

素朴に書くと、呼ぶだけのアロー関数を 1 枚かぶせることになる。

```typescript
[1, 2, 3].map((n) => double(n))   // double を呼ぶだけの関数を作って渡している
```

これは無駄なので、直接渡したい。

```typescript
[1, 2, 3].map(double)   // JS/TS では最初からこう書ける
```

**JS/TS では関数がもともと値なので、名前を書けばそれがそのまま値になる。** 特別な構文は要らない。

### Java — メソッド参照(`::`)

Java では `クラス名::メソッド名` という **メソッド参照** の構文を使う。**このプロジェクトの実コードで使われている。**

```java
// backend/.../post/PostService.java:68
return new TimelineResponse(page.stream().map(PostResponse::from).toList(), nextCursor);
//                                            ^^^^^^^^^^^^^^^^^^
// PostResponse::from は post -> PostResponse.from(post) と同じ意味
```

`CategoryController.java:24` にも `.map(CategoryResponse::from)` がある。ラムダで `post -> PostResponse.from(post)` と書いてもよいが、**引数をそのまま渡すだけなら `::` の方が短く読みやすい**。

### PHP — 8.1 でようやく専用構文が入った

PHP は長らくこれができず、**関数名を文字列で渡す**やり方だった。

```php
array_map('strlen', $words);            // 関数名を文字列で渡す
array_map([$obj, 'method'], $items);    // オブジェクトとメソッド名の配列で渡す
```

文字列なので、**名前を打ち間違えても実行するまで気づけない**(IDE の補完も効かない)。これを解決したのが PHP 8.1 の**ファーストクラス callable 構文**。

```php
array_map(strlen(...), $words);         // strlen(...) で「関数そのもの」を表す
array_map($obj->method(...), $items);   // メソッドも同様
```

`(...)` は「**引数は今渡さない。関数自体を値として取り出す**」という意味の記法。文字列と違い、IDE と静的解析が名前の間違いを検出できる。

### 対応表

| | 既にある関数を値として渡す書き方 | 登場時期 |
|---|---|---|
| **JS / TS** | `map(double)` — 名前を書くだけ | 最初から |
| **Java** | `map(PostResponse::from)` — メソッド参照 | Java 8(2014) |
| **PHP** | `array_map(strlen(...), ...)` | **8.1(2021)**。以前は文字列 `'strlen'` |

`object-and-class-by-language.md` の表で「ファーストクラス callable は PHP 8.1、JS は最初から」と書いたのは、この話。

## 6. 3 つの関係を整理

バラバラに覚えるのではなく、**1 本の幹から枝が分かれている**と捉えるとよい。

```
関数を値として扱える(土台 = 第一級)
├─ その場で関数を作る → アロー関数  (n) => n * 2
│    └─ 外側の変数も一緒に抱える → クロージャ
└─ 既にある関数を渡す → ファーストクラス callable / メソッド参照
```

- **関数が値である**(土台) — これが無ければ以下すべて成り立たない
- **クロージャ** — その関数が「外側の変数」も一緒に抱えている状態。**状態を持てる**のでクラスの代わりになる
- **アロー関数** — その関数を短く書く記法(+ JS では `this` の扱いが違う)
- **ファーストクラス callable** — 既存の関数を名前で指して値として渡す書き方

## つまずきポイント

- **クロージャは「難しい機能」ではない。** コールバックを書いた時点でほぼクロージャになっている。特別な構文は無く、**普通に書くと自然にそうなる**もの。
- **同じクロージャは変数を共有する。** `const next = makeCounter()` の `next` を何度呼んでも同じ `count` が進む。独立させたいなら `makeCounter()` をもう一度呼んで別のクロージャを作る。
- **Java で `count++` をラムダから触ろうとするとエラー。** 実質 final の変数しか捕まえられない。状態を持ちたければフィールドか `AtomicInteger`。
- **PHP は `use` を書かないと外の変数が見えない。** JS の感覚で書くと動かない。`fn()` なら自動キャプチャ。
- **アロー関数は単なる短縮記法ではない。** `this` の扱いが違う。オブジェクトのメソッドをアロー関数で書くと `this` が外側を指してずれる。
- **`map(double)` と `map(double())` は別物。** 後者は「関数を呼んだ結果」を渡してしまう。**関数を値として渡すときは括弧を付けない。**
- **Java のメソッド参照は `::`、呼び出しは `.`。** `PostResponse::from`(関数を渡す)と `PostResponse.from(post)`(今呼ぶ)を混同しないこと。

## 用語集

- **第一級(ファーストクラス)** — 値として自由に扱えること。変数に入れる・引数で渡す・戻り値で返すができる。
- **高階関数** — 関数を引数に取る、または関数を返す関数。`map` / `filter` / `forEach` など。
- **コールバック** — 高階関数に渡される側の関数。「後で呼んでね」と預ける関数。
- **クロージャ** — 関数が、自分の外側の変数を覚えたまま持ち歩く仕組み。状態を閉じ込められるのでクラスの代わりになる。
- **キャプチャ** — クロージャが外側の変数を取り込むこと。取り込み方(自動か明示か、書き換え可否)が言語ごとに違う。
- **実質 final(effectively final)** — 宣言後に一度も再代入していないローカル変数。Java のラムダはこれしか捕まえられない。
- **アロー関数** — `=>` を使う簡潔な関数記法。JS では自分の `this` を持たない点が本質的な違い。PHP では `fn()`(7.4)。
- **`this`** — メソッドの中で自分自身を指すもの。JS では「どう呼ばれたか」で中身が変わるため事故が起きやすい。
- **`bind`** — 関数の `this` を固定して新しい関数を作る JS のメソッド。アロー関数登場前の定番の回避策。
- **メソッド参照(Java)** — `クラス名::メソッド名` で既存メソッドを値として渡す構文。`PostResponse::from` など。
- **ファーストクラス callable 構文(PHP)** — `strlen(...)` のように、関数自体を値として取り出す PHP 8.1 の記法。
- **ラムダ式** — その場で書く名前のない関数。Java の `() -> ...` など。アロー関数とほぼ同じ発想。

## 関連

- クロージャがクラスの代わりになる話の全体像、3 言語のオブジェクトとクラス → [object-and-class-by-language.md](./object-and-class-by-language.md)
- `stream().map()` など Stream API と、map/filter がどこにあるか → [java/syntax/array-vs-list.md](./java/syntax/array-vs-list.md)
- TS の型の付け方(関数の型は `type` が得意) → [typescript/syntax/interface-vs-type.md](./typescript/syntax/interface-vs-type.md)
