# コンポーザブルと `use` 接頭辞 — なぜ `usePosts` は use で始まるのか

`frontend/app/composables/usePosts.ts` を読んで「なぜ関数名が `use` で始まるのか。React が JSX の中で使う関数に `use` を強制するような決まりが Vue にもあるのか」という疑問に答えるメモ。

[vue-vs-react-overview.md](./vue-vs-react-overview.md) §3 の表にある「なぜフックのルール(条件分岐の中で呼ぶな)がないのか」という 1 行を、React 側と Vue 側の両方から深掘りしたもの。

結論を先に言うと:

- **Vue / Nuxt の `use` は慣習であって、フレームワークが強制する構文ルールではない。** `use` を外しても動く。
- **React でも、強制しているのは React 本体ではなく ESLint プラグイン**(`eslint-plugin-react-hooks`)。ただし React には「名前で見分けないと機械的に検査できない、本当に壊れる制約」があり、Vue にはそれがない。
- **Nuxt の自動 import は関数名を見ていない。見ているのは置き場所(ディレクトリ)だけ。** `use` を付けたから自動 import される、のではない。
- ただし **Vue にも「順序」ではなく「タイミング」の制約はある。** `useFetch` / `inject` / ライフサイクル関数は setup の同期実行中にしか呼べない。
- **その制約があるかどうかは名前からは分からない。** このリポジトリの `usePosts` と `useCategories` がまさにその例。

---

## 1. React が `use` を必要とする理由 — 呼び出し「順序」に依存しているから

React のフックは、**呼ばれた順番**だけを頼りに state を対応づけている。

```jsx
function Counter() {
  const [a, setA] = useState(0)   // 1 番目に呼ばれたフック
  const [b, setB] = useState('')  // 2 番目に呼ばれたフック
  // ...
}
```

React は内部で、コンポーネントごとにフックの記録を並び順で保持している(実装は連結リスト。順番に並んだ箱の列だと考えれば足りる)。`useState` は「自分が何番目の呼び出しか」で自分の記録を取りに行く。**引数も変数名も見ていない。**

だから順番がずれると壊れる。

```jsx
function Counter({ cond }) {
  if (cond) {
    const [a] = useState(0)   // cond が false の回はこの呼び出しが消える
  }
  const [b] = useState('')    // 1 番目に繰り上がり、前回 a だった記録を掴む
}
```

これが **フックのルール(Rules of Hooks)** ——「フックはトップレベルで、毎回同じ順序で呼べ」—— の正体。条件分岐・ループ・early return の後で呼んではいけない、という制約はここから来ている。

## 2. 強制しているのは React 本体ではなく lint

ここが誤解しやすい点。**React のランタイムは関数名を検査していない。** `getUserData` という名前の関数の中で `useState` を呼んでも、React は何も言わずに動かす(そして順序がずれれば静かに壊れる)。

ルール違反を実際に見つけているのは、ESLint プラグイン `eslint-plugin-react-hooks` の `rules-of-hooks` ルール。lint は実行せずにソースコードを読むだけなので(**静的解析**)、「この呼び出しはフックか、ただの関数呼び出しか」を判定する手段が要る。関数の中身を全部たどるのは現実的でないため、採用されたのが**名前による判定**:

> `use` で始まり、その次が大文字で続く名前は、フックとみなす。

つまり React において `use` 接頭辞は、**機械が読む識別子**として機能している。`useUserData` を `getUserData` に改名すると、lint は「ただの関数」と判断してルール違反を見逃す。名前が検査の前提条件になっている、という点が Vue との決定的な違い。

## 3. Vue に「順序」のルールがない理由

Vue の `ref()` は、呼ばれるたびに**独立したオブジェクトを新しく作って返すだけ**。何番目の呼び出しかを一切覚えていない。

```js
let count
if (cond) {
  count = ref(0)   // Vue ではこれで壊れない
}
```

加えて、Vue の `<script setup>` は**コンポーネントが生成されるときに 1 回しか実行されない**。何度も再実行されて呼び出し順を突き合わせる、という仕組み自体が存在しない。

この「1 回しか走らない」という性質が `.value` が必要な理由・`useCallback` が不要な理由なども同時に説明する。詳細は [vue-vs-react-overview.md](./vue-vs-react-overview.md) §3 を参照。

結果として、Vue には**名前で見分けなければならない機械的な理由がない**。Vue 公式ドキュメントも "By convention, composable function names start with `use`"(慣習として)という書き方をしていて、要求とは書いていない。

では何のための `use` かというと、**人間へのラベル**。

- これはコンポーネントの setup の中で呼ぶ前提の関数だ
- リアクティブな状態を持っている(かもしれない)
- 単なるユーティリティ関数(`utils/`)ではなく、コンポーネントのロジックの一部だ

を読み手に伝えるための目印。

## 4. Vue にあるのは「タイミング」の制約

順序は自由でも、**いつ呼ぶか**の制約はある。対象は次のような関数。

- `inject()`
- `onMounted()` / `onBeforeUnmount()` などのライフサイクル関数
- Nuxt の `useFetch()` / `useAsyncData()` / `useState()` / `useRoute()` / `useRuntimeConfig()`

これらは **`<script setup>` の同期的な実行中に呼ぶ**必要がある。理由は、Vue と Nuxt が内部に「いま組み立て中のコンポーネントは何か」というグローバルな参照を持っていて、上の関数群がそれを読みに行くから。

```
コンポーネント生成開始
  ↓  「現在のインスタンス」をセット   ← この区間でだけ inject / useFetch が機能する
  <script setup> を同期実行
  ↓  「現在のインスタンス」を解除
生成完了
```

`await` を挟むと、その続きは解除後に実行される。

```js
// 動かない
const something = await fetchSomething()
const route = useRoute()          // 「現在のインスタンス」がもう無い

// 動かない
async function onClick() {
  const { data } = await useFetch('/api/posts')   // イベントハンドラは生成完了後に走る
}
```

Nuxt ではこのとき次のエラーが出る。

```
A composable that requires access to the Nuxt instance was called outside of
a plugin, Nuxt hook, Nuxt middleware, or Vue setup function.
```

イベント起点の書き込み処理では `useFetch` ではなく `$fetch` を使う。`$fetch` はコンポーネントの文脈に依存しないので、どこからでも呼べる。

## 5. 自動 import は命名と無関係 — このリポジトリで検証できる

「`use` を付けないと Nuxt が自動 import してくれないのでは」という誤解があるが、そうではない。証拠がこのリポジトリにある。

```
frontend/app/components/post/Card.vue:2   import type { Post } from '~/types/post'   ← 型だけ import
frontend/app/components/post/Card.vue:37  {{ formatRelativeTime(post.createdAt) }}   ← import なしで使える
```

実体は `frontend/app/utils/formatDate.ts` の `export function formatRelativeTime()`。**`use` は付いていないのに自動 import されている。**

Nuxt が見ているのは置き場所だけ。

| 置き場所 | 自動 import | 名前の条件 |
|---|---|---|
| `app/composables/` 直下 | ○ | なし |
| `app/utils/` 直下 | ○ | なし |
| `app/composables/api/posts.ts`(ネスト) | ×(既定では拾われない) | — |
| `app/types/` | × | — |

自動 import の仕組み(`.nuxt/` への型生成と、ビルド時の import 挿入)は [vue-vs-react-overview.md](./vue-vs-react-overview.md) §2 に書いてある。ディレクトリ規約の一覧は [frontend-structure-best-practices.md](../../development/frontend-structure-best-practices.md)。

## 6. このリポジトリの 2 つの composable は制約の強さが違う

同じ `composables/` にあって同じ `use` で始まるのに、中身の依存関係がまったく違う。**ここが一番実務的に重要な点。**

### `usePosts` — Vue の文脈に依存していない

```ts
// frontend/app/composables/usePosts.ts
export function usePosts() {
  const fetchTimeline = (params) => $fetch<Timeline>('/api/posts', { params })
  const createPost = (body, categoryId) =>
    $fetch<Post>('/api/posts', { method: 'POST', body: { body, categoryId } })
  // ...
  return { fetchTimeline, fetchPost, createPost, deletePost }
}
```

`$fetch` を包んだ関数を返しているだけ。`ref` もライフサイクルも `useFetch` も使っていない。したがって **setup の外から呼んでも、`await` の後から呼んでも、イベントハンドラの中から呼んでも動く。** 技術的には `createPostsApi()` という名前でも成立する。

それでも `use` を付けているのは、`composables/` に置く関数はそう名付ける、という Vue / Nuxt 界隈の共通認識に合わせるため。

### `useCategories` — 本物の制約がある

```ts
// frontend/app/composables/useCategories.ts
export function useCategories() {
  return useFetch<Category[]>('/api/categories', { server: false })
}
```

内側で `useFetch` を呼んでいるので、§4 のタイミング制約をそのまま受け継ぐ。呼び出し側は `<script setup>` のトップレベルで呼んでいる。

```ts
// frontend/app/pages/index.vue:4-5
const { data: categories } = useCategories()   // 制約あり: ここでしか呼べない
const { fetchTimeline } = usePosts()           // 制約なし: どこで呼んでもよい
```

**同じ行に並んでいるが、片方だけが「ここで呼ばなければならない」。** `use` という名前はどちらにも付いていて、どちらなのかを教えてくれない。composable を使うときは、名前ではなく**中身が何に依存しているか**を見て判断する。

---

## 落とし穴

- **`await` の後で Nuxt の `useXxx` を呼ぶ。** 一番よくある事故。データ取得は `<script setup>` の先頭にまとめて書く。
- **イベントハンドラの中で `useFetch` を呼ぶ。** ここは `$fetch` を使う。このリポジトリでは `Form.vue:31` の `createPost` がその形になっている。
- **`use` が付いているから安全、とは限らない。** §6 のとおり。
- **`composables/` のサブディレクトリは自動 import されない。** `composables/index.ts` で re-export するか、`nuxt.config.ts` の `imports.dirs` を設定する。
- **React 19 の `use()` は別物。** Promise や Context を読むための React 組み込み API で、接頭辞としての `use` とは関係がない。
- **`use` を付けないと Vue が壊れる、ということはない。** ただしチームで読むコードなので、慣習からは外れないほうがよい。

## 用語集

- **コンポーザブル(composable)** — Vue で、状態やロジックを再利用できる形にまとめた関数。React のカスタムフックに相当する
- **慣習(convention)** — 言語やフレームワークが強制はしないが、コミュニティで広く共有されている取り決め
- **静的解析** — プログラムを実行せず、ソースコードを読んで問題を検出すること。ESLint がその代表
- **Rules of Hooks** — React のフックをトップレベルで毎回同じ順序で呼ぶルール。呼び出し順で state を管理している仕組み上の必然
- **自動 import(auto-import)** — Nuxt が特定ディレクトリの export を、import 文なしで使えるようにする仕組み
- **`$fetch`** — Nuxt の HTTP クライアント。コンポーネントの文脈に依存せず、どこからでも呼べる
- **`useFetch`** — `$fetch` に SSR / SSG 対応と `data` / `error` / `status` の管理を足したもの。setup の同期実行中にしか呼べない

## 関連

- Vue / Nuxt の全体像(React 経験者向け)→ [vue-vs-react-overview.md](./vue-vs-react-overview.md)
- ディレクトリ規約・命名規則 → [../../development/frontend-structure-best-practices.md](../../development/frontend-structure-best-practices.md)
- 関数を値として扱う(クロージャ / 高階関数)→ [../functions-as-values.md](../functions-as-values.md)
- Vue 公式「コンポーザブル」 https://ja.vuejs.org/guide/reusability/composables
