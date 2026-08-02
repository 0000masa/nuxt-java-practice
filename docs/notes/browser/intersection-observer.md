# `IntersectionObserver` — 要素が画面に入ったかをブラウザに見張らせる

`app/pages/index.vue` の無限スクロールで使っている `IntersectionObserver` を、API そのものを主語にしてまとめるメモ。Vue のライフサイクル関数の側から見た説明は [lifecycle-and-watch.md](../vue/lifecycle-and-watch.md) §1 にある。

結論を先に言うと:

- **`IntersectionObserver` は Vue の機能でも Nuxt の機能でもない。** ブラウザが提供する Web API のクラスで、素の JavaScript でも React でも同じように使える。だから `import` を書かずに `new` できるし、だから `nuxt generate` のビルド中には存在しない。
- **コールバックは「状態が変わったとき」にしか呼ばれない。** 見えっぱなしのまま何も変わらなければ二度と呼ばれない。無限スクロールで一番踏みやすい罠がここ。
- **`observe()` を呼ぶと、直後に必ず 1 回コールバックが走る。** 見えていなくても `isIntersecting: false` で届く。
- **`disconnect()` が必要なのは、監視対象がある限り observer がブラウザ側で生き続けるから。** JS 側の変数を捨てても回収されず、コールバックが掴んでいる投稿一覧ごとメモリに残る。
- **後始末を忘れたときの見え方が Vue と React で違う。** React は開発中に症状が出るが、Vue は何も起きない。だから Vue のほうが規律で書く必要がある。

---

## 1. 何者か

3 つの層のどこに属するかを押さえると位置づけがはっきりする。

| 層 | 例 | `IntersectionObserver` は |
|---|---|---|
| JavaScript 言語仕様(ECMAScript) | `Array` / `Promise` / `Map` | 違う |
| ブラウザが提供する Web API(DOM) | `fetch` / `URL` / `MutationObserver` | **これ** |
| ライブラリ・フレームワーク | Vue / Nuxt / React | 違う |

ブラウザが `window` に生やしているグローバルなクラスなので、`import` は要らない。TypeScript の型は TypeScript 同梱の DOM 型定義(`lib.dom.d.ts`)から来ているため、`let observer: IntersectionObserver` と書ける。

**この「ブラウザが生やしている」が SSG に直結する。** `nuxt generate` は Node.js 上で走り、そこに `IntersectionObserver` は存在しない。`<script setup>` のトップレベルに `new IntersectionObserver(...)` と書くとビルドが落ちる。`onMounted` の中に置くのは、ブラウザでしか走らない場所を選んでいるということ。

同じ理由で `window` / `document` / `localStorage` も同じ扱いになる。詳しくは [lifecycle-and-watch.md](../vue/lifecycle-and-watch.md) §1。

## 2. なぜ生まれたか

「要素が画面に入ったか」を昔はこう書いていた。

```ts
// 採用されなくなった書き方
window.addEventListener('scroll', () => {
  const rect = sentinel.getBoundingClientRect()
  if (rect.top < window.innerHeight) loadMore()
})
```

問題が 2 つある。

**scroll イベントは 1 回のスクロールで数十回から数百回発火する。** そのたびにコールバックが動く。

**`getBoundingClientRect()` は強制同期レイアウトを起こす。** 位置を正確に答えるために、ブラウザはその時点で保留しているレイアウト計算を全部終わらせてから返す必要がある。スクロール中に毎回これをやると描画が詰まる。

`IntersectionObserver` は、交差判定を**ブラウザのレンダリング処理の一部として行い、状態が変わったフレームでだけコールバックをキューに積む**。スクロールしても状態が変わらなければ、こちらのコードは 1 行も走らない。

## 3. 使い方の構造

```ts
const observer = new IntersectionObserver(callback, options)

observer.observe(element)     // 監視対象を追加(何個でも)
observer.unobserve(element)   // 1 つだけ監視解除
observer.disconnect()         // 全部まとめて監視解除
observer.takeRecords()        // 溜まっている未通知の変化を取り出す(あまり使わない)
```

**`new` と `observe()` は役割が違う。** `new IntersectionObserver(...)` が作るのは「何が起きたら何を呼ぶか」というルールを持ったオブジェクトだけで、**この時点では 1 つも監視していない**。実際に監視が始まるのは `observe(element)` を呼んだ瞬間。「道具を用意する」と「対象を登録する」が 2 段階に分かれている。

**1 つの observer が複数の要素を監視できる。** だからコールバックの第 1 引数が配列になっている。渡ってくるのは「今回状態が変わった要素の分だけ」で、監視中の全要素ではない。

### コンストラクタの第 2 引数(`options`)

| プロパティ | 既定値 | 意味 |
|---|---|---|
| `root` | `null`(= ビューポート) | 「画面に入った」の"画面"にあたる要素。スクロールする `div` の中で判定したいならそれを指定する |
| `rootMargin` | `'0px'` | root の判定範囲を広げる/狭める。CSS の `margin` と同じ書き方。`'200px'` なら**画面に入る 200px 手前**で発火する |
| `threshold` | `0` | 対象の何割が見えたら発火するか。`0` は「1px でも見えたら」、`1.0` は「全部見えたら」。配列で複数指定もできる(`[0, 0.5, 1]`) |

`index.vue` は `options` を渡していないので、**ビューポートに 1px でも入ったら発火**する設定になっている。

### コールバックに渡ってくるもの

```ts
new IntersectionObserver((entries, observer) => {
  // entries: IntersectionObserverEntry[]
  // observer: この observer 自身(コールバックの中から disconnect したいとき用)
})
```

`IntersectionObserverEntry` の主なプロパティ。

| プロパティ | 型 | 意味 |
|---|---|---|
| `isIntersecting` | `boolean` | いま交差しているか |
| `intersectionRatio` | `number` | 何割見えているか(0〜1) |
| `target` | `Element` | 監視対象の要素そのもの。1 つの observer で複数監視しているときはこれで見分ける |
| `boundingClientRect` | `DOMRectReadOnly` | 対象の矩形 |
| `rootBounds` | `DOMRectReadOnly \| null` | root の矩形 |
| `time` | `number` | 変化が記録された時刻 |

### 押さえるべき仕様が 2 つ

**その 1 — `observe()` を呼ぶと、直後に必ず 1 回コールバックが走る。**

仕様では「まだ一度も `IntersectionObserverEntry` をキューに積んでいない対象」を保留リストに入れ、次の更新サイクルで通知する。見えていなければ `isIntersecting: false` で届くだけで、**通知そのものは必ず来る**。

**その 2 — コールバックは「状態が変わったとき」にしか呼ばれない。**

`threshold` の境界を跨いだときにだけ通知される。`threshold: 0` なら「見えていない ↔ 見えている」が切り替わった瞬間だけ。**見えっぱなしのまま何も変わらなければ、二度と呼ばれない。** 無限スクロールで止まるバグはほぼ全部これが原因(→ 落とし穴)。

## 4. このリポジトリでの使われ方

タイムラインの末尾に高さ 1px の要素(番兵)を置き、それが画面に入ったら次のページを読む。

```ts
// app/pages/index.vue
const sentinel = ref<HTMLElement | null>(null)
let observer: IntersectionObserver | undefined

onMounted(() => {
  loadMore()
  observer = new IntersectionObserver((entries) => {
    if (entries[0]?.isIntersecting) loadMore()
  })
  if (sentinel.value) observer.observe(sentinel.value)
})

onBeforeUnmount(() => observer?.disconnect())
```

```vue
<template>
  <div ref="sentinel" class="sentinel" />
</template>
```

```css
.sentinel {
  height: 1px;
}
```

### 読むときのポイント

**`observer` を `ref` にしていない。** 画面表示に使わない値だから。リアクティブにする必要のない値を `ref` で包む理由はない。ただし `onMounted` と `onBeforeUnmount` の両方から触るため、`onMounted` の外側(setup のスコープ)に置く必要がある。

**`entries[0]?.isIntersecting` で済ませている。** 監視対象が番兵 1 つだけなので、配列の先頭を見れば足りる。複数監視するなら `entry.target` で見分けることになる。

**`loadMore()` が二重に走らない理由。** マウント直後は投稿が 0 件なので番兵は画面の中にあり、「その 1」の仕様によって `observe()` の初期観測でも `loadMore()` が呼ばれようとする。つまり `onMounted` の明示的な呼び出しと合わせて 2 回走ろうとする。実際に 2 回リクエストが飛ばないのは、`loadMore()` の先頭に早期 return があるため。

```ts
async function loadMore() {
  if (loading.value || reachedEnd.value) return
  loading.value = true
  // ...
}
```

`loadMore()` は `async` だが、最初の `await` に到達するまでは同期的に走る。つまり `onMounted` の呼び出しが `loading.value = true` を立て終わってから初期観測の通知が届くので、そこで弾かれる。**動くが、タイミングに寄りかかった防御**ではある(→ 落とし穴)。

**ページサイズは 20 件。** `fetchTimeline` に `limit` を渡していないので、バックエンド側の `@RequestParam(defaultValue = "20")` が効く。この 20 という数字が、後述の「1 ページで画面が埋まらないと止まる」問題を実質的に回避している。

### `if (sentinel.value) observer.observe(sentinel.value)` を分解する

`onMounted` の中でいちばん分かりにくい 1 行。**ここが実際に監視を開始している行**で、3 つの部分に分けて読む。

#### `sentinel.value` — 監視したい DOM 要素

```ts
const sentinel = ref<HTMLElement | null>(null)
```

```vue
<div ref="sentinel" class="sentinel" />
```

テンプレートに `ref="sentinel"`(コロンなしの文字列)と書くと、**Vue がマウント時に同名の `ref` へ実際の DOM 要素を入れる**。これがテンプレート ref。値が入るタイミングは決まっている。

| 時点 | `sentinel.value` |
|---|---|
| `<script setup>` の実行中 | `null`(まだ DOM が存在しない) |
| `onMounted` の中 | **`<div class="sentinel">` の実要素** |
| `onBeforeUnmount` の中 | **まだ実要素が入っている** |
| `onUnmounted` 以降 | `null` に戻る |

3 行目と 4 行目の差が、このリポジトリが後始末に `onUnmounted` ではなく `onBeforeUnmount` を選んでいる理由でもある(→ [lifecycle-and-watch.md](../vue/lifecycle-and-watch.md) §1)。

React の `useRef` + `ref={sentinel}` と同じ仕組みで、取り出し方が `.current` ではなく `.value` になっているだけ。

##### 何と何が照合されているのか

照合しているのは**変数名そのものではなく、テンプレートに公開されている名前**。`<script setup>` には「トップレベルで宣言したものは、そのままの名前でテンプレートから使える」という性質があるため、結果として変数名と一致していればよい、ということになる。

```
<div ref="sentinel">          文字列 "sentinel"
                                 ↓ 名前で照合
const sentinel = ref(null)    この束縛の .value に、マウント後の要素が入る
```

**`ref` 属性に書いているのは変数ではなく、変数を指す名前**。`:to="..."` のように式を書く属性とは違うので、コロンが付かない。

この方式には注意点が 3 つある。

**名前が一致しなくてもエラーにならない。**

```vue
<div ref="sentinal" />   <!-- typo -->
```

```ts
const sentinel = ref(null)   // ずっと null のまま
```

警告も出ない。`onMounted` で `sentinel.value` が `null` のままになり、`if (sentinel.value)` が静かに `false` になって**監視が始まらない**。画面にはエラーが出ず、無限スクロールだけが動かない、という壊れ方をする。文字列で名前を照合する方式の弱点。

**子コンポーネントに付けると、DOM 要素ではなくコンポーネントインスタンスが入る。**

```vue
<PostCard ref="card" />   <!-- card.value はコンポーネントインスタンス -->
<div ref="box" />         <!-- box.value は HTMLDivElement -->
```

`observe()` に渡せるのは DOM 要素だけなので、監視したい対象は素の要素でなければならない。`index.vue` が中身のない `div` を置いているのはこのためでもある。

**`v-for` の中に付けると配列が入る。**

```vue
<PostCard v-for="post in posts" :key="post.id" ref="cards" />
```

`cards.value` は要素 1 つではなく配列になる。並び順が DOM の順序と一致する保証はないので、順序に依存する使い方は避ける。

なお `:ref`(コロンあり)にすると式として評価され、関数を渡せば要素が引数で渡ってくる。名前の照合が起きないので typo の余地がない。

```vue
<div :ref="(el) => { sentinel = el }" />
```

記述が冗長になるため通常は使わないが、名前で紐づけたくない場面ではこの形になる。

#### `observer.observe(...)` — 監視対象として登録する

§3 のとおり、`new` した時点ではまだ何も監視していない。

```ts
const observer = new IntersectionObserver(callback)  // ルールを決めた。監視対象はゼロ
observer.observe(sentinel.value)                     // この要素を見張れ、と登録する
```

この行を実行した瞬間から交差の監視が始まり、§3「その 1」の初期観測によって、**次の更新サイクルでコールバックが 1 回届く**。マウント直後は投稿が 0 件で番兵が画面内にあるため、その 1 回目は `isIntersecting: true` で来る。

#### `if (sentinel.value)` — `null` チェック

理由が 2 つある。

**型を絞り込むため(コンパイル時)。** `sentinel` の型は `HTMLElement | null` なので、そのまま渡すと「`null` かもしれない値を `Element` の引数に渡している」と TypeScript が止める。`if` で囲むとそのブロック内では `HTMLElement` に絞り込まれる(型ガード)。

**例外を避けるため(実行時)。** `observe(null)` は無視されるのではなく `TypeError` を投げる。

```
Failed to execute 'observe' on 'IntersectionObserver': parameter 1 is not of type 'Element'.
```

**今のコードでは `null` にはならない。** 番兵はテンプレートに無条件で置かれていて、`onMounted` の時点では必ず要素が入っているため。ただし条件付き表示にした瞬間に `null` があり得るようになる。

```vue
<div v-if="!reachedEnd" ref="sentinel" class="sentinel" />   <!-- こうすると null になりうる -->
```

つまりこの `if` は「起きないケースへの保険」であり、同時に型エラーを消すための必須の記述でもある。

#### まとめ

```ts
onMounted(() => {
  loadMore()                                    // 1 ページ目を取りにいく
  observer = new IntersectionObserver(...)      // 「交差したら loadMore」というルールを作る
  if (sentinel.value)                           // 要素が取れていることを確認して
    observer.observe(sentinel.value)            // 監視対象に登録 = 監視開始
})

onBeforeUnmount(() => observer?.disconnect())   // 登録を全部外す
```

**`observe()` と `disconnect()` が対**になっている。この対応が崩れたときに何が起きるかが §6。

なお Vue 3.5 以降はテンプレート ref に専用の関数がある。`ref(null)` と `ref="名前"` の暗黙の紐づけをやめ、どの名前と対応するかを引数で明示する書き方。

```ts
const sentinel = useTemplateRef('sentinel')
```

上で挙げた「変数名と文字列が一致していないと無言で失敗する」問題に対して、**紐づけをコード上に見えるようにする**のが狙い(引数が文字列である以上、typo が完全になくなるわけではない)。このリポジトリは従来の書き方のままだが、新しく書くならこちらが推奨されている。

## 5. React で書くと何が変わるか

同じものを React で書くとこうなる。

```tsx
const sentinel = useRef<HTMLElement | null>(null)

useEffect(() => {
  loadMore()
  const observer = new IntersectionObserver((entries) => {
    if (entries[0]?.isIntersecting) loadMore()
  })
  if (sentinel.current) observer.observe(sentinel.current)
  return () => observer.disconnect()   // 破棄時 + 依存が変わって再実行される直前に走る
}, [])
```

```tsx
<div ref={sentinel} style={{ height: 1 }} />
```

`IntersectionObserver` を触っている部分は 1 文字も変わらない。ブラウザの API なので当然で、**違いが出るのは「いつ作り、いつ捨てるか」を書く場所と、後始末が実行される保証の強さだけ**。

| | Vue | React |
|---|---|---|
| 生成を書く場所 | `onMounted` | `useEffect` |
| 破棄を書く場所 | **`onBeforeUnmount`(別の関数)** | **`useEffect` の return(同じ関数の中)** |
| `observer` の置き場所 | setup スコープの素の変数(両方から触るため) | `useEffect` の中のローカル変数で完結 |
| DOM 参照 | `ref` + `ref="sentinel"`、中身は `.value` | `useRef` + `ref={sentinel}`、中身は `.current` |
| 依存配列 | ない | `[]` を書かないと毎回作り直される |
| 後始末忘れの検出 | **されない** | **開発時の StrictMode が炙り出す** |

前半 4 行は書き方の違いにすぎない。効いてくるのは最後の 2 行。

なお `useEffect` の `return` が走るのは**アンマウント時だけではない**。依存が変わってエフェクトが再実行されるときも、その直前に走る。上の例が「アンマウント時だけ」に見えるのは依存配列が `[]` だからで、`return` の性質ではない(→ [lifecycle-and-watch.md](../vue/lifecycle-and-watch.md) §1「`useEffect` の return はいつ走るのか」)。この 2 役が、Vue では `onBeforeUnmount` と `onWatcherCleanup` の 2 つに分かれている。

### 後始末が実行される保証の強さ

React 18 以降、開発モードの `StrictMode` は `useEffect` を **マウント → クリーンアップ → マウント** と意図的に 2 回走らせる。目的が「後始末を書き忘れていないか炙り出すこと」そのもの。

`return () => observer.disconnect()` を書き忘れると、**同じ、まだ生きている番兵の要素を 2 つの observer が監視する状態になる**。どちらも発火するので `loadMore()` が 2 回走り、投稿が重複したり余計なページを先読みしたりする。**開発中に症状として見える。**

一方 **Vue の `onMounted` は 1 回しか走らない。** これに相当する仕組みがない。`onBeforeUnmount(() => observer?.disconnect())` の行を消しても、**開発中は何も起きない**。

さらに厄介なことに、Vue では**本番でも画面は壊れない**。コンポーネントがアンマウントされると番兵の要素も DOM から取り除かれ、仕様上その時点で交差面積は 0 として扱われるため、古い observer の `isIntersecting` は `false` のままになる。`loadMore()` は呼ばれない。

つまり **Vue で `disconnect()` を忘れたときに壊れるのは、目に見えない場所だけ**。それが次の節。

## 6. なぜ `disconnect()` が必要なのか

W3C の仕様にこう書かれている。

> An IntersectionObserver will remain alive until **both** of these conditions hold:
> - There are no scripting references to the observer.
> - **The observer is not observing any targets.**

**2 つとも満たされるまで observer は回収されない。** つまり `observe()` したままだと、JS 側の参照を全部捨てても、ブラウザが observer を生かし続ける。

`index.vue` に当てはめると、`observer` は setup スコープのローカル変数なので、コンポーネントが破棄されれば 1 つ目の条件は満たされる。しかし `disconnect()` しなければ 2 つ目が満たされない。そして observer が生きているということは、そこにぶら下がっている参照の連鎖が丸ごと生き続けるということになる。

```
ブラウザ(document)
  └─ observer            ← observe() 中なので回収されない
       └─ コールバック関数  ← observer が掴んでいる
            └─ loadMore   ← クロージャで掴んでいる
                 ├─ posts        ← 無限スクロールで積み上げた投稿の配列
                 ├─ nextCursor
                 ├─ loading / reachedEnd
                 └─ fetchTimeline
  └─ observer の監視対象リスト
       └─ 番兵の要素        ← DOM から外れているのに残る(detached element)
            └─ 親をたどってコンポーネントの DOM ツリー
```

**無限スクロールで 200 件読んだ状態でページを離れると、その 200 件分のオブジェクトがそのまま残る。** タイムラインを開くたびに積み上がっていく。画面は正常に動き続けるので、気づく手立てがない。

`observer.disconnect()` は「2 つ目の条件を満たしにいく操作」だと理解すればよい。監視対象を全部外すことで、observer がブラウザから参照される理由がなくなり、連鎖ごと回収の対象になる。

実際に残っていることは Chrome DevTools の Memory タブで確認できる。Heap snapshot を撮って `Detached` で絞り込むと、DOM から外れているのに回収されていない要素が並ぶ。

### この考え方は他にも効く

覚えるべきは「`IntersectionObserver` は `disconnect` する」ではなく、**「これを掴んでいるのは誰か」を追う癖**のほう。

| 登録するもの | 誰が掴み続けるか | 外し方 |
|---|---|---|
| `IntersectionObserver` | ブラウザ(監視対象がある限り) | `disconnect()` / `unobserve()` |
| `addEventListener` | イベントの発生源になる要素 | `removeEventListener` |
| `setInterval` / `setTimeout` | タイマーを管理するブラウザ | `clearInterval` / `clearTimeout` |
| 進行中の `fetch` | 通信を管理するブラウザ | `AbortController` の `abort()` |
| Vue の `watch` | コンポーネントインスタンス | **自動で止まる**(setup 内で作った場合) |

`watch` だけ自動なのは、Vue がコンポーネントに紐づけて管理しているから。**Vue の管理外にあるものは、全部自分で外す**と考えればよい。

## 落とし穴

**見えっぱなしだと二度と発火しない。** 「その 2」の仕様。1 ページ読んでも画面が埋まらないと、番兵は `isIntersecting: true` のまま変化せず、次のページが永久に読まれない。ユーザーはスクロールもできないので詰む。無限スクロールで最も多いバグ。

このリポジトリで踏まない理由は 2 つある。1 ページ 20 件あれば通常のビューポートは埋まること、そして 20 件未満しか取れなかったときは `reachedEnd` が立ち、そもそも次を読む必要がなくなること。極端に縦長の画面を使うか `limit` を小さくすると顕在化する。対策としては、読み込み後に「まだ番兵が見えているか」を確認して続けて読む、あるいは `rootMargin` を広く取る、といった方法がある。

**後始末を書き忘れる。** §6 のとおり。Vue では画面が壊れないので、テストでも目視でも見つからない。`observe()` を書いたら `disconnect()` を同時に書く、を規律にするしかない。

**`new IntersectionObserver` を setup のトップレベルに書く。** SSG のビルドで `IntersectionObserver is not defined` になる。`onMounted` の中に置く。

**初期観測を忘れて二重に走らせる。** `observe()` は必ず 1 回コールバックを呼ぶ。マウント時に自分でも初回読み込みを呼んでいると、両方走ろうとする。`index.vue` は `loading` フラグで弾いているが、**弾けているのは「初期観測の通知が最初の通信の完了より先に届く」というタイミングに依存している**。通信が極端に速ければ 2 ページ目を先読みしてしまう(実害はないが意図した動きではない)。

**`rootMargin` を指定していない。** 番兵が実際に画面に入ってから通信を始めるので、体感で一瞬の空白が出る。`{ rootMargin: '200px' }` にすると 200px 手前で読み始める。このリポジトリは既定のままにしている。

**読み終わっても observer を止めていない。** `reachedEnd` が立った後も監視は続く。`loadMore()` が早期 return するので実害はないが、`observer.disconnect()` を呼んでもよい場面ではある。

**`ref` の要素が `null` のまま `observe()` する。** `observe(null)` は例外になる。`index.vue` が `if (sentinel.value)` で守っているのはこれ。`v-if` で番兵を出し分けていると実際に `null` になりうる。

**テンプレート ref の名前を間違える。** `ref="sentinal"` のような typo は警告も例外も出ない。`if (sentinel.value)` が静かに `false` になり、**監視が始まらないまま何のエラーも出ない**。無限スクロールが動かないときは、まずここを疑う。

## 用語集

- **Web API** — ブラウザが JavaScript に提供する機能群。`fetch` / `document` / `IntersectionObserver` など。JavaScript の言語仕様には含まれない
- **番兵(sentinel)** — 「ここまで来た」を検出するためだけに置く目印の要素。このリポジトリでは無限スクロールの末尾に置いた 1px の div
- **交差(intersection)** — 監視対象の要素と root の矩形が重なっている状態。`isIntersecting` が表す
- **root** — 交差判定の基準になる領域。既定はビューポート
- **threshold(しきい値)** — 対象の何割が見えたら通知するかの境界。この境界を跨いだときにだけコールバックが呼ばれる
- **初期観測** — `observe()` を呼んだ対象に対して、次の更新サイクルで必ず 1 回通知が届く仕組み
- **テンプレート ref** — テンプレートの `ref="名前"` で DOM 要素を取得する Vue の仕組み。React の `useRef` + `ref={}` に相当
- **型ガード** — `if (x)` などで、その先のブロックの型を TypeScript に絞り込ませる書き方
- **強制同期レイアウト** — 保留中のレイアウト計算をその場で完了させること。`getBoundingClientRect()` などが引き起こし、頻発すると描画が詰まる
- **detached element** — DOM ツリーから取り除かれたのに、JavaScript から参照が残っていて回収されていない要素
- **後始末(cleanup)** — 登録したものを解除する処理。書かないとメモリリークや二重実行の原因になる

## 関連

- Vue のライフサイクル関数から見た説明 → [lifecycle-and-watch.md](../vue/lifecycle-and-watch.md)
- `ref` の仕組みと、リアクティブにしない値の扱い → [reactivity-ref-computed.md](../vue/reactivity-ref-computed.md)
- SSG でブラウザ API が使えない理由 → [data-fetching-and-ssg.md](../vue/data-fetching-and-ssg.md)
- MDN「Intersection Observer API」 https://developer.mozilla.org/ja/docs/Web/API/Intersection_Observer_API
- W3C 仕様(GC の 2 条件はここ) https://w3c.github.io/IntersectionObserver/
- React 公式「StrictMode がエフェクトを 2 回実行する理由」 https://ja.react.dev/reference/react/StrictMode
