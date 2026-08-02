# ライフサイクルと `watch` — `useEffect` に当たるもの

React の `useEffect` に相当する機能を Vue で探すと、**用途ごとに別の関数へ分かれている**ことに気づく。マウント時なら `onMounted`、値の変化に反応するなら `watch`、依存を自動収集したいなら `watchEffect`。この対応関係と、それぞれの仕組みをまとめるメモ。

題材は `app/pages/index.vue` の無限スクロール(IntersectionObserver の登録と破棄)と、`app/pages/posts/[id].vue` の投稿取得。

結論を先に言うと:

- **`useEffect` が 1 つで担っていた仕事を、Vue は 3 つ以上に分けている。** 依存配列の中身によって使う関数が変わる、と考えると対応づけやすい。
- **依存配列は存在しない。** `watch` は監視対象を第 1 引数で明示し、`watchEffect` は実行時に自動収集する。
- **`onMounted` はブラウザでしか走らない。** サーバー側の描画(SSR / SSG のビルド)では実行されない。このリポジトリが `[id].vue` で `onMounted` を使っているのはこの性質を利用している。
- **後始末の書き方が違う。** `useEffect` の return **1 つ**が「次の実行の直前」と「アンマウント時」の 2 役を兼ねていたが、Vue はこれを `onWatcherCleanup()` と `onBeforeUnmount()` の **2 つ**に分けている。
- **「値が変わったから何かする」より「イベントが起きたから何かする」を優先する。** React の "You Might Not Need an Effect" と同じ考え方で、このリポジトリのカテゴリー切り替えがその実例になっている。

---

## 0. 対応表

| React | Vue | 実行タイミング |
|---|---|---|
| `useEffect(fn, [])` | `onMounted(fn)` | DOM に載った直後、1 回だけ |
| `useEffect(fn, [x])` | `watch(x, fn)` | `x` が変わったとき(初回は走らない) |
| `useEffect(fn, [x])` で初回も走らせたい | `watch(x, fn, { immediate: true })` | 初回 + `x` が変わったとき |
| `useEffect(fn)`(依存配列なし) | `watchEffect(fn)` | 初回 + 中で読んだ値が変わったとき |
| `useEffect(fn, [])` の return | `onBeforeUnmount(fn)` | 破棄の直前(再実行がないため、ここだけ) |
| `useEffect(fn, [x])` の return | `watch` 内の `onWatcherCleanup(fn)` | 次の実行の直前 + 破棄時 |
| `useLayoutEffect` | `onMounted`(既定で DOM 更新後) | — |

## 1. `onMounted` / `onBeforeUnmount`

このリポジトリの実例。無限スクロールのために、リストの末尾に置いた 1px の要素(番兵)が画面に入ったら次のページを読む。

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

React で書くとこうなる。

```tsx
const sentinel = useRef<HTMLElement | null>(null)

useEffect(() => {
  loadMore()
  const observer = new IntersectionObserver((entries) => {
    if (entries[0]?.isIntersecting) loadMore()
  })
  if (sentinel.current) observer.observe(sentinel.current)
  return () => observer.disconnect()    // 破棄時 + 依存が変わって再実行される直前に走る
}, [])
```

### `useEffect` の return はいつ走るのか

対応関係を見る前に、ここを誤解しやすいので先に潰しておく。**`return` で返した関数は「アンマウント時だけ」に走るのではない。**

> `useEffect` の cleanup は、**「次にそのエフェクトが実行される直前」と「アンマウント時」の 2 つのタイミング**で呼ばれる。

依存配列に値が入っている場合を見ると分かる。

```tsx
useEffect(() => {
  const id = setInterval(tick, delay)
  return () => clearInterval(id)
}, [delay])
```

`delay` が `1000` から `2000` に変わったときの順序。

| # | 起きること |
|---|---|
| 1 | `delay` が変わって再レンダー |
| 2 | **前回の cleanup が走る** → `clearInterval(古い id)` |
| 3 | エフェクト本体が走る → `setInterval(tick, 2000)` |
| 4 | 以降 `delay` が変わるたび 2〜3 の繰り返し |
| 5 | アンマウント時に**最後の cleanup** が走る |

**「片付けてから作り直す」を毎回やる**のが `useEffect` の設計で、登録と解除を同じ関数の中に書かせるのはそのため。片方だけ書き換えるとズレるので、対で書く構造になっている。

上の `IntersectionObserver` の例が「アンマウント時だけ」に見えるのは、**依存配列が `[]` だから**。依存が二度と変わらない = エフェクトが再実行されない = 「次の実行の直前」が永遠に来ない。**一般ルールの特殊ケース**であって、`return` の性質そのものではない。

(開発時の `StrictMode` だけは例外で、`[]` でも マウント → cleanup → マウント と 2 回走らせる。後始末の書き忘れを炙り出すための仕掛け → [intersection-observer.md](../browser/intersection-observer.md) §5)

**この 2 役が、Vue では 2 つの別々の関数に分かれる。**

| React の `return` の役割 | いつ | Vue の対応物 |
|---|---|---|
| 次の実行の直前に片付ける | 依存が変わったとき | `watch` 内の `onWatcherCleanup()`(→ §2) |
| 最後に片付ける | アンマウント時 | `onBeforeUnmount()` |

`onMounted` は再実行されないので、Vue には「次の実行の直前」という概念自体がない。その役割は `watch` 側に移っている。§0 の対応表が 2 行に分かれているのはこのため。

### Vue と React の対応関係

対応関係で押さえるところは 3 つ。

**DOM 参照も `ref` を使う。** React は state 用の `useState` と DOM 参照用の `useRef` が別だったが、Vue はどちらも `ref`。テンプレート側で `ref="sentinel"`(コロンなしの文字列)と書くと、同名の ref にその要素が入る。中身は `.current` ではなく `.value` で取る。

**後始末は別の関数に書く。** `useEffect` は「登録と解除を 1 か所にまとめられる」のが利点だったが、Vue は `onMounted` と `onBeforeUnmount` に分かれる。そのため上のコードでは `observer` を外側の変数に置いて両方から触れるようにしている。この変数を `ref` にしていないのは、**画面表示に使わない値だから**。リアクティブにする必要がない値を `ref` で包む理由はない。

**`onMounted` はブラウザでしか走らない。** サーバー側の描画では DOM が存在しないため、`onMounted` と `onBeforeUnmount` は呼ばれない。`window` や `document` を触る処理を安全に置ける場所、という位置づけになる。`IntersectionObserver` もブラウザにしかないクラスなので、`new` はここでしかできない。

なお `IntersectionObserver` そのもの(オプション、コールバックが呼ばれる条件、`disconnect()` が必要な理由)は [intersection-observer.md](../browser/intersection-observer.md) にまとめてある。

`[id].vue` はこの性質を積極的に使っている。

```ts
// app/pages/posts/[id].vue
// SSG では全投稿の HTML を事前生成できないため、詳細はクライアント側で取得して描画する
onMounted(async () => {
  try {
    post.value = await fetchPost(route.params.id as string)
  } catch {
    notFound.value = true
  }
})
```

`nuxt generate` のビルド時にはバックエンドが起動していないので、ここが走ってしまうと失敗する。`onMounted` に置くことで「ブラウザで開いたときにだけ取得する」を保証している。この判断の詳細と、`useAsyncData` を使う代替案は [data-fetching-and-ssg.md](./data-fetching-and-ssg.md) で扱う。

### ライフサイクル関数の一覧

| 関数 | タイミング | サーバー側 |
|---|---|---|
| `onBeforeMount` | DOM に載る直前 | 走らない |
| `onMounted` | DOM に載った直後 | 走らない |
| `onBeforeUpdate` | 再描画による DOM 更新の直前 | 走らない |
| `onUpdated` | 再描画による DOM 更新の直後 | 走らない |
| `onBeforeUnmount` | 破棄の直前 | 走らない |
| `onUnmounted` | 破棄の直後 | 走らない |
| `onErrorCaptured` | 子孫でエラーが起きたとき | 走る |

後始末は `onBeforeUnmount` と `onUnmounted` のどちらでも書けるが、**DOM や子コンポーネントにまだ触れる `onBeforeUnmount` のほうが安全**で、このリポジトリもそちらを使っている。テンプレート ref も、`onBeforeUnmount` の時点ではまだ要素が入っていて、`onUnmounted` 以降は `null` に戻る。

### コンポーネント破棄はいつ起きるか

「破棄(アンマウント)」とは、**Vue がそのコンポーネントのインスタンスを捨て、対応する DOM 要素を取り除くこと**。`onBeforeUnmount` に書いた後始末が実際に走るのはこの瞬間なので、「いつ起きるのか」を具体的に押さえておく。

| 何が起きたか | 破棄されるか | このリポジトリの例 |
|---|---|---|
| `<NuxtLink>` / `navigateTo()` によるページ遷移 | **される** | 投稿本文をクリックして `/posts/:id` へ移動 → `index.vue` が破棄 |
| `v-if` が `false` になる | **される** | — |
| `v-for` のリストから項目が消える | **される** | 投稿を削除して `PostCard` が消える |
| 親コンポーネントが破棄される | **される**(子孫まで連鎖) | ページが変わればその中の `PostCard` も全部 |
| 開発中のホットリロード(HMR) | **される** | ファイルを保存したとき |
| `<KeepAlive>` に包まれている | **されない** | このリポジトリでは未使用 |
| **ブラウザのリロード(F5)** | **されない**(後述) | |
| **タブを閉じる / 別ドメインへ移動** | **されない**(後述) | |

**このリポジトリで実際に走っている場面**はこれ。

```vue
<!-- components/post/Card.vue -->
<NuxtLink v-else :to="`/posts/${post.id}`" class="post-body-link">
```

タイムラインで投稿本文をクリックすると `/posts/:id` へ遷移し、そこで `index.vue` が破棄されて `onBeforeUnmount(() => observer?.disconnect())` が走る。詳細ページから「← タイムラインへ戻る」で戻ると、**`index.vue` は同じものが復元されるのではなく、新しく作り直される**。だから積み上げた投稿もスクロール位置も初期化され、`onMounted` がもう一度走って新しい observer が作られる。

`<KeepAlive>` で包んだ場合だけ例外で、コンポーネントは破棄されずに退避される。この場合 `onBeforeUnmount` / `onMounted` は呼ばれず、代わりに `onDeactivated` / `onActivated` が呼ばれる。「戻ったときにスクロール位置と投稿を保ちたい」となったら選択肢に入るが、そのときは observer の後始末も `onDeactivated` 側へ移すことになる。

#### ブラウザがページを捨てるとき

上の表の下 2 行が誤解しやすい。**リロード・タブを閉じる・別ドメインへ移動といった操作では、`onBeforeUnmount` は呼ばれない。**

理由は、**JavaScript のランタイムごと消えるから**。Vue が「これから破棄します」と通知する余地がない。SPA 内のページ遷移は「JS は生きたまま、コンポーネントだけ差し替わる」のに対し、ブラウザのページ遷移は「舞台ごと片付ける」動きになる。

**そしてそれで問題ない。** メモリも DOM も observer も、まとめて破棄されるため。`disconnect()` が必要なのは、**JS が生き続けたままコンポーネントだけ入れ替わる SPA 遷移**のほうだけ、ということになる。

ではブラウザ離脱時に何かしたい場合は? **それは Vue のライフサイクルの外**で、ブラウザのイベントとして別に用意されている。

| イベント | 用途 |
|---|---|
| `beforeunload` | 未保存の変更があるときに、離脱の確認ダイアログを出す |
| `pagehide` | ページが非表示になるとき。`beforeunload` より発火が確実 |
| `visibilitychange` | タブが裏に回ったときにも発火する。**自動保存にはこれが最も確実** |

`beforeunload` で確認ダイアログを出す場合はこう書く。

```ts
function confirmLeave(event: BeforeUnloadEvent) {
  event.preventDefault()
  event.returnValue = true   // 古いブラウザ向けの互換指定
}

onMounted(() => window.addEventListener('beforeunload', confirmLeave))
onBeforeUnmount(() => window.removeEventListener('beforeunload', confirmLeave))
```

**`addEventListener` した以上、`onBeforeUnmount` で `removeEventListener` する必要がある。** ここでも「登録したものは自分で外す」が効いてくる。

`beforeunload` には注意点が 3 つある。

**メッセージは指定できない。** かつては任意の文言を出せたが、悪用されたため廃止された。いまはブラウザ固定の文言しか出ない。

**ユーザーがページを一度も操作していないと、ダイアログは出ない。** クリックやキー入力などの操作(sticky activation)が必要という仕様。「操作していない = 失われる入力もない」という理屈で、ダイアログを出す正当な理由がないとみなされる。

**Firefox では、`beforeunload` のリスナがあるだけでそのページが bfcache に載らなくなる。** bfcache はブラウザバックで前のページを瞬時に復元する仕組みで、これが効かなくなると戻る操作が目に見えて遅くなる。**常時登録するのではなく、未保存の変更があるときだけ登録して、なくなったら外す**のが推奨されている。

自動保存が目的なら `beforeunload` ではなく `visibilitychange` を使うほうがよい。モバイルではタブを閉じても `beforeunload` や `pagehide` が発火しないことがあるが、`visibilitychange`(非表示になったとき)は確実に来る。

このリポジトリでは投稿フォームに下書き保存がないので、いまのところ出番はない。

### 呼ぶ場所の制約

ライフサイクル関数は **`<script setup>` の同期実行中に呼ぶ**必要がある。Vue が内部に持つ「いま組み立て中のコンポーネント」への参照を読みに行くため、`await` を挟んだ後や、イベントハンドラの中からは登録できない。

```ts
// 動かない
const data = await fetchSomething()
onMounted(() => { ... })     // 「組み立て中」の状態がもう解除されている
```

同じ制約が Nuxt の `useFetch` などにもかかる。詳しくは [composables.md](./composables.md) §4。

なお **`onMounted` の第 1 引数を `async` にするのは問題ない**。上の `[id].vue` がその形で、`onMounted` 自体は同期的に呼ばれているため制約に触れない。

## 2. `watch` — 値の変化に反応する

`useEffect(fn, [x])` に相当する。第 1 引数に監視対象、第 2 引数に処理を書く。

```ts
const keyword = ref('')

watch(keyword, (newValue, oldValue) => {
  console.log(`${oldValue} から ${newValue} に変わった`)
})
```

`useEffect` との違いは 4 つ。

**依存を配列ではなく第 1 引数で指定する。** 複数なら配列で渡す。

```ts
watch([keyword, categoryId], ([newKeyword, newCategoryId]) => { ... })
```

**変わる前の値が取れる。** `useEffect` では自前で `useRef` に前回値を保持する必要があった部分が、そのまま引数で渡ってくる。

**初回は走らない。** `useEffect` は必ず初回にも走ったが、`watch` は変化があったときだけ。初回も走らせたいなら `{ immediate: true }` を付ける。

**オブジェクトの中身の変化は既定では追わない。** `ref` にオブジェクトを入れて、そのプロパティだけを書き換えても発火しない。追いたいなら `{ deep: true }`。ただし全プロパティを走査するので、大きなオブジェクトでは重くなる。

```ts
watch(user, fn, { deep: true })
```

**ref そのものではなく「値」を監視したいときは関数で渡す。** これはよく間違える。

```ts
watch(user.name, fn)        // 動かない。user.name はただの文字列
watch(() => user.name, fn)  // 正しい。getter を渡す
```

主なオプション。

| オプション | 効果 |
|---|---|
| `immediate: true` | 初回も実行する |
| `deep: true` | オブジェクトの内部の変化も監視する |
| `once: true` | 1 回だけ実行して停止する |
| `flush: 'post'` | DOM 更新後に実行する(既定は更新前) |

### 後始末

再実行のたびに後始末したいときは、コールバックの中で `onWatcherCleanup()` を呼ぶ。

```ts
watch(keyword, (value) => {
  const controller = new AbortController()
  fetch(`/api/search?q=${value}`, { signal: controller.signal })

  onWatcherCleanup(() => controller.abort())   // 次の実行の直前と、破棄時に呼ばれる
})
```

`useEffect` の return が持つ 2 役のうち、**「次の実行の直前に片付ける」ほうがこれ**(→ §1「`useEffect` の return はいつ走るのか」)。`onWatcherCleanup` は Vue 3.5 で追加された関数で、それ以前はコールバックの第 3 引数 `onCleanup` を使っていた(現在も使える)。

**`<script setup>` の中で作った `watch` は、コンポーネント破棄時に自動で停止する。** 手動で止めたいときは戻り値の関数を呼ぶ。

```ts
const stop = watch(keyword, fn)
stop()
```

## 3. `watchEffect` — 依存を自動収集する

依存配列なしの `useEffect(fn)` に近い。**即座に 1 回実行され、その実行中に読まれたリアクティブな値が依存として登録される。**

```ts
watchEffect(() => {
  console.log(`${keyword.value} / ${categoryId.value}`)
})
// keyword か categoryId が変わるたびに再実行される
```

`computed` と仕組みは同じ(どちらもエフェクト)で、**値を返すのが `computed`、副作用を起こすのが `watchEffect`** という役割の違い。

`watch` との使い分けは次のとおり。

| | `watch` | `watchEffect` |
|---|---|---|
| 依存の指定 | 明示 | 自動収集 |
| 初回実行 | しない(`immediate` で変えられる) | する |
| 変更前の値 | 取れる | 取れない |
| 向いている場面 | 特定の値の変化に反応する | 複数の値から副作用を組み立てる |

**依存が明示されているほうが読みやすい**ため、迷ったら `watch` を選ぶ。`watchEffect` は依存が暗黙になるぶん、意図せぬ再実行の原因が追いにくい。

## 4. そもそも監視しない — このリポジトリの選択

ここまで書いておいてなんだが、**このリポジトリには `watch` も `watchEffect` も 1 つもない。** これは書き漏らしではなく、設計としてそうなっている。

カテゴリーの絞り込みを見てほしい。「選択中のカテゴリーが変わったら一覧を取り直す」という、いかにも `watch` を使いたくなる要件。

```ts
// app/pages/index.vue
async function selectCategory(categoryId: number | null) {
  selectedCategoryId.value = categoryId
  posts.value = []
  nextCursor.value = null
  reachedEnd.value = false
  await loadMore()
}
```

```vue
<button @click="selectCategory(category.id)">{{ category.name }}</button>
```

`watch(selectedCategoryId, reload)` と書く代わりに、**ボタンが押されたときに直接呼んでいる**。

`watch` で書いた場合と比べると差が分かる。

```ts
// 採用していない書き方
watch(selectedCategoryId, async () => {
  posts.value = []
  nextCursor.value = null
  reachedEnd.value = false
  await loadMore()
})
```

- 状態の変更(`selectedCategoryId.value = x`)と、その結果起きること(再取得)が**別の場所に離れる**。コードを読むとき、代入箇所から再取得へ辿り着けない。
- コードのどこからでも `selectedCategoryId` に代入すると再取得が走る。意図しない発火の余地が生まれる。
- 「同じカテゴリーを 2 回押しても発火しない」といった暗黙の挙動が入り込む(`watch` は値が変わらなければ走らない)。

React の公式ドキュメントに "You Might Not Need an Effect" という章があり、**イベントに反応する処理はイベントハンドラに書け**と説いている。Vue でも考え方は同じで、`watch` を使うのは次のような「イベントに紐づけられない」場面に限られる。

- ルートのクエリパラメータが外部から変わったとき(ブラウザバックなど)
- 別のコンポーネントが持つ状態の変化に反応するとき
- 入力に対するデバウンス付きの自動検索

このリポジトリでは**フェーズ 8 の検索ラボ**で最初に `watch` の出番が来る見込み。それまでは「イベントハンドラで足りるならそれで書く」でよい。

---

## 落とし穴

- **`watch` に `.value` を渡す。** `watch(user.name, fn)` は動かない。`watch(() => user.name, fn)`。
- **`watch` が初回に走ると思う。** 走らない。`{ immediate: true }` が要る。
- **オブジェクトの中身の変化を追えると思う。** `{ deep: true }` が要る。
- **`useEffect` の return がアンマウント時だけ走ると思う。** 依存が変わって再実行されるたび、その直前にも走る。「破棄時だけ」に見えるのは依存配列が `[]` のときの特殊ケース → §1。
- **`await` の後でライフサイクル関数を呼ぶ。** 登録されない。`<script setup>` のトップレベルで呼ぶ。
- **`onMounted` がサーバーでも走ると思う。** 走らない。逆に言えば、`window` / `document` / `localStorage` を触ってよい場所。
- **ブラウザを閉じたら `onBeforeUnmount` が走ると思う。** 走らない。JS のランタイムごと消えるため Vue は通知できない。走るのは SPA 内のページ遷移や `v-if` による破棄のとき。ブラウザ離脱時に何かしたいなら `beforeunload` / `visibilitychange`。
- **後始末を書き忘れる。** `IntersectionObserver` / `addEventListener` / `setInterval` は必ず `onBeforeUnmount` で解除する。React の `StrictMode` のような「後始末忘れを炙り出す仕組み」が Vue にはないため、書き忘れても開発中は何も起きない → [intersection-observer.md](../browser/intersection-observer.md) §6。
- **何でも `watch` にする。** §4 のとおり、イベントハンドラで書けるならそちらが読みやすい。
- **リアクティブでなくてよい値を `ref` にする。** `index.vue` の `observer` のように、画面表示に関係しない値は素の変数でよい。

## 用語集

- **ライフサイクル関数(ライフサイクルフック)** — コンポーネントの生成・更新・破棄の各段階で呼ばれる関数。`onMounted` など
- **マウント** — コンポーネントが実 DOM に載ること。**アンマウント**はその逆で、DOM から取り除かれること
- **SPA 内遷移** — JavaScript のランタイムを維持したまま、表示するコンポーネントだけを差し替えるページ移動。`<NuxtLink>` や `navigateTo()` がこれ。ブラウザのリロードとは別物
- **bfcache(back/forward cache)** — ブラウザバック・フォワードで前のページを瞬時に復元するためにブラウザが保持する仕組み
- **sticky activation** — ユーザーがそのページを一度でも操作したという状態。確認ダイアログなど、一部の機能はこれを満たさないと動かない
- **番兵(sentinel)** — 「ここまで来た」を検出するためだけに置く目印の要素。このリポジトリでは無限スクロールの末尾に置いた 1px の div
- **`watch`** — 指定した値の変化に反応して処理を実行する関数
- **`watchEffect`** — 中で読んだ値を自動で依存として登録し、変化するたび再実行する関数
- **後始末(cleanup)** — 登録したイベントリスナやタイマーを解除する処理。書かないとメモリリークや二重実行の原因になる
- **テンプレート ref** — テンプレートの `ref="名前"` で DOM 要素を取得する仕組み。React の `useRef` + `ref={}` に相当

## 関連

- 全体像と対応表 → [vue-vs-react-overview.md](./vue-vs-react-overview.md)
- 題材にした `IntersectionObserver` 自体の解説 → [intersection-observer.md](../browser/intersection-observer.md)
- `ref` / `computed` の仕組み → [reactivity-ref-computed.md](./reactivity-ref-computed.md)
- 呼ぶ場所の制約(setup の同期実行中)→ [composables.md](./composables.md)
- `[id].vue` が `onMounted` で取得している理由 → [data-fetching-and-ssg.md](./data-fetching-and-ssg.md)
- Vue 公式「ウォッチャー」 https://ja.vuejs.org/guide/essentials/watchers
- Vue 公式「ライフサイクルフック」 https://ja.vuejs.org/guide/essentials/lifecycle
- MDN「beforeunload イベント」(ダイアログの制約と bfcache) https://developer.mozilla.org/ja/docs/Web/API/Window/beforeunload_event
- React 公式「You Might Not Need an Effect」 https://ja.react.dev/learn/you-might-not-need-an-effect
