# Vue / Nuxt の全体像 — React / Next.js 経験者向け

React と Next.js(App Router)は書けるが、Vue と Nuxt は触ったことがない、という前提で書いた入口のメモ。このリポジトリの `frontend/` の実コードをそのまま題材にする。

結論を先に言うと、**違いのほとんどは 1 つの根っこから出ている**。

- **React のコンポーネントは「毎回実行される関数」**。state が変われば関数本体を丸ごと再実行し、返ってきた要素ツリーの差分を取って DOM に反映する。
- **Vue のコンポーネントは「1 回だけ実行されるセットアップ + 再実行されるテンプレート」**。`<script setup>` の中身はコンポーネントが生まれるとき 1 回しか走らない。値が変わったときに走り直すのは**テンプレートだけ**、しかも変更された値を使っている箇所だけ。
- ここから、`.value` が必要な理由、依存配列が存在しない理由、フックのルール(条件分岐の中で呼ぶな)が存在しない理由、`useMemo` / `useCallback` がほぼ不要な理由が、全部まとめて説明できる。
- **Nuxt には React Server Components に当たる仕組みがない。** 代わりに「この取得処理をサーバーで走らせるか、ブラウザで走らせるか」を関数のオプションで指定する。このリポジトリは SSG(`nuxt generate`)なので**全部ブラウザ側**にしている。

## 0. 対応表 — まずここだけ見れば読める

### 言語・コンポーネント層(Vue)

| React | Vue | 補足 |
|---|---|---|
| `function Foo() { ... }` + JSX | `.vue` ファイル(SFC)の `<script setup>` + `<template>` | ロジックとマークアップが別ブロックに分かれる |
| `useState(0)` → `[count, setCount]` | `ref(0)` → `count.value` | setter 関数はない。`.value` に代入する |
| `useMemo(() => ..., [deps])` | `computed(() => ...)` | 依存配列は書かない。自動で検出される |
| `useEffect(() => {...}, [])` | `onMounted(() => {...})` | マウント時 1 回だけ |
| `useEffect(() => {...}, [x])` | `watch(x, () => {...})` | 値の変化に反応する |
| `useEffect` の return(後始末) | `onBeforeUnmount` / `watch` の `onCleanup` | → [lifecycle-and-watch.md](./lifecycle-and-watch.md) |
| `useCallback` | (不要) | セットアップが 1 回しか走らないので関数が作り直されない |
| `useRef(null)` + `ref={el}` | `ref(null)` + `ref="名前"` | DOM 参照も同じ `ref` を使う |
| カスタムフック `useFoo()` | コンポーザブル `useFoo()` | **ただの関数**。フックのルールがない → [composables.md](./composables.md) |
| `props.foo` | `defineProps<{ foo: string }>()` | → [props-and-emits.md](./props-and-emits.md) |
| コールバック props `onDeleted` | `defineEmits` + `emit('deleted', ...)` | イベントを「上に飛ばす」形 |
| `children` | `<slot />` | |
| `{cond && <p/>}` | `<p v-if="cond" />` | |
| `list.map(x => <li key={x.id}/>)` | `<li v-for="x in list" :key="x.id" />` | |
| `onClick={fn}` | `@click="fn"` | |
| `className={...}` | `:class="{ active: isActive }"` | オブジェクトを渡せる |
| CSS Modules | `<style scoped>` | 同じファイル内に書く |
| 制御コンポーネント(`value` + `onChange`) | `v-model="body"` | 双方向バインディングが 1 属性で済む |

### フレームワーク層(Nuxt)

| Next.js (App Router) | Nuxt 4 | 補足 |
|---|---|---|
| `app/page.tsx` | `app/pages/index.vue` | ファイルベースルーティングは同じ発想 |
| `app/posts/[id]/page.tsx` | `app/pages/posts/[id].vue` | ディレクトリではなくファイル名に `[id]` |
| `app/layout.tsx` | `app/layouts/default.vue` + `app/app.vue` | 2 段構えになっている |
| `params.id`(props で渡ってくる) | `useRoute().params.id` | 関数で取りに行く |
| `<Link href="/">` | `<NuxtLink to="/">` | |
| Server Component(デフォルト) | **存在しない** | → [data-fetching-and-ssg.md](./data-fetching-and-ssg.md) |
| `'use client'` | 不要(全部クライアント側のコンポーネント) | |
| `fetch()` を Server Component 内で await | `useFetch()` / `useAsyncData()` | |
| クライアントからの API 呼び出し | `$fetch()` | |
| `next.config.js` | `nuxt.config.ts` | |
| `output: 'export'` | `nuxt generate` | このリポジトリはこれ |
| import が必要 | **import 不要**(自動インポート) | 次章 |

## 1. まず 1 ファイル読んでみる

`app/components/post/Card.vue` の骨格。CSS を省いて構造だけ抜き出す。

```vue
<script setup lang="ts">
import type { Post } from '~/types/post'

const props = defineProps<{
  post: Post
  linkDisabled?: boolean
}>()

const emit = defineEmits<{
  deleted: [id: number]
}>()

const { deletePost } = usePosts()
const deleting = ref(false)

async function onDelete() {
  if (!confirm('この投稿を削除しますか?')) return
  deleting.value = true
  try {
    await deletePost(props.post.id)
    emit('deleted', props.post.id)
  } catch {
    alert('削除に失敗しました')
  } finally {
    deleting.value = false
  }
}
</script>

<template>
  <article class="post-card">
    <span class="post-author">{{ post.user.displayName }}</span>
    <time>{{ formatRelativeTime(post.createdAt) }}</time>
    <p v-if="linkDisabled" class="post-body">{{ post.body }}</p>
    <NuxtLink v-else :to="`/posts/${post.id}`">
      <p class="post-body">{{ post.body }}</p>
    </NuxtLink>
    <button class="post-delete" :disabled="deleting" @click="onDelete">削除</button>
  </article>
</template>

<style scoped>
.post-card { border: 1px solid #e2e8f0; }
</style>
```

対応する React を書くとこうなる。

```tsx
export function PostCard({ post, linkDisabled, onDeleted }: Props) {
  const { deletePost } = usePosts()
  const [deleting, setDeleting] = useState(false)

  async function onDelete() {
    if (!confirm('この投稿を削除しますか?')) return
    setDeleting(true)
    try {
      await deletePost(post.id)
      onDeleted(post.id)
    } catch {
      alert('削除に失敗しました')
    } finally {
      setDeleting(false)
    }
  }

  return (
    <article className={styles.postCard}>
      <span>{post.user.displayName}</span>
      <time>{formatRelativeTime(post.createdAt)}</time>
      {linkDisabled
        ? <p>{post.body}</p>
        : <Link href={`/posts/${post.id}`}><p>{post.body}</p></Link>}
      <button disabled={deleting} onClick={onDelete}>削除</button>
    </article>
  )
}
```

やっていることは同じ。目立つ差は 4 つ。

1. **`return` がない。** マークアップは `<template>` ブロックに書く。関数の戻り値ではない。
2. **`deleting.value = true` と書く。** setter 関数はなく、代入すると再描画される。
3. **`{ }` ではなく `{{ }}`、属性は `:` を付ける。** `:disabled="deleting"` の `:` は「この属性値を JS 式として評価しろ」という印。`v-bind:disabled` の省略形。
4. **`useState` も `usePosts` も `formatRelativeTime` も import していない。** 次章の話。

## 2. なぜ import なしで動くのか

React では `useState` も自作フックも必ず import する。Nuxt では書かない。魔法ではなく、**ビルド時にコード変換とファイル生成をしている**。

Nuxt は起動時に決まったディレクトリを走査して、`frontend/.nuxt/` の下に定義ファイルを吐く。実物を見ると分かりやすい。

```typescript
// frontend/.nuxt/types/imports.d.ts (自動生成。編集しない)
declare global {
  const computed: typeof import('vue').computed
  const ref: typeof import('vue').ref
  const useRoute: typeof import('nuxt/dist/app/composables/router').useRoute
  // ... 数百行
}
```

```typescript
// frontend/.nuxt/components.d.ts (自動生成。編集しない)
export const PostCard: typeof import("../app/components/post/Card.vue")['default']
export const PostForm: typeof import("../app/components/post/Form.vue")['default']
export const NuxtLink: typeof import("../node_modules/nuxt/dist/app/components/nuxt-link")['default']
```

`components/post/Card.vue` が `PostCard` という名前になっているのは、ディレクトリ名 + ファイル名を連結する規約による。この命名規則の一覧は [frontend-structure-best-practices.md](../../development/frontend-structure-best-practices.md) にまとまっている。

重要なのは、これが**型定義だけ**だという点。実行されるコードのほうは、Vite のプラグイン(unimport)がビルド時に各ファイルの先頭へ `import { ref } from 'vue'` 相当の行を挿し込んでいる。つまり最終的なバンドルには普通の import が存在する。**書かなくてよいだけで、無くなったわけではない。**

副作用が 2 つある。

- **`.nuxt/` が無いと型が効かない。** クローン直後に VS Code で `ref` が赤くなるのは、まだ生成されていないだけ。`npm install`(`postinstall` で `nuxt prepare` が走る)か `npm run dev` を一度実行すれば解消する。
- **自動インポートの対象はディレクトリのトップレベルだけ。** `composables/usePosts.ts` は自動だが、`composables/api/usePosts.ts` のようにネストすると拾われない。`types/post.ts` のような**型**も対象外なので、実コードでも `import type { Post } from '~/types/post'` は明示的に書いている。`~` は `frontend/app/` を指すエイリアス。

## 3. 一番大きい違い — 「1 回だけ走る」ということ

ここが本丸。冒頭に書いた根っこの話を、具体例で確認する。

React では、コンポーネント関数は**再描画のたびに丸ごと再実行される**。

```tsx
function Counter() {
  const [count, setCount] = useState(0)
  console.log('実行された')            // ボタンを押すたびに出る
  const doubled = count * 2            // 毎回計算し直される
  const handler = () => setCount(c => c + 1)  // 毎回別の関数オブジェクトが作られる
  return <button onClick={handler}>{doubled}</button>
}
```

Vue では、`<script setup>` の中身は**コンポーネントが生成されるとき 1 回だけ**実行される。

```vue
<script setup lang="ts">
const count = ref(0)
console.log('実行された')              // 最初の 1 回だけ出る
const doubled = computed(() => count.value * 2)
function increment() { count.value++ } // 1 回しか作られない
</script>

<template>
  <button @click="increment">{{ doubled }}</button>
</template>
```

ボタンを押すと `count.value` が変わり、**それを使っているテンプレートの箇所だけ**が更新される。`<script setup>` は二度と走らない。

この 1 点から、React 経験者が疑問に思うことがまとめて説明できる。

| 疑問 | 答え |
|---|---|
| なぜ `count.value` で `.value` が要るのか | セットアップが 1 回しか走らない以上、`count` がただの数値だと値の変化を伝える手段がない。オブジェクトに包んで、`.value` への読み書きを検知できるようにしている |
| なぜ `useCallback` が要らないのか | `increment` は 1 回しか作られない。毎回作り直される問題自体が起きない |
| なぜ `computed` に依存配列が要らないのか | `computed` の中で `count.value` を**読んだ瞬間に**依存として記録される。書き手が宣言する必要がない |
| なぜフックのルール(条件分岐の中で呼ぶな)がないのか | React は呼び出し**順序**で state を対応づけているためルールが要る。Vue の `ref()` は呼ぶたびに独立したオブジェクトを返すだけなので、どこで何回呼んでも構わない → [composables.md](./composables.md) |
| なぜ `useEffect` が無いのか | 「再実行のたびに副作用を同期させる」という問題設定がそもそも無い。マウント時にやりたいなら `onMounted`、値の変化に反応したいなら `watch`、と用途ごとに別の関数になっている |

裏返すと、**Vue で React の癖のまま書くと落とし穴にはまる場所**もここに集中する。詳しくは [reactivity-ref-computed.md](./reactivity-ref-computed.md) で扱う。

## 4. Nuxt 側の一番大きい違い — RSC がない

Next.js の App Router は「コンポーネントがサーバーで動くかブラウザで動くか」をコンポーネント単位で分ける設計になっている。Nuxt にはこの分け方がない(`.server.vue` という実験的機能はあるが、通常は使わない)。

Nuxt では**全部のコンポーネントがブラウザで動くコンポーネント**で、サーバーとブラウザの区別は「データ取得をどちらで走らせるか」というオプションで表現する。

```typescript
// app/composables/useCategories.ts
export function useCategories() {
  return useFetch<Category[]>('/api/categories', { server: false })
  //                                              ^^^^^^^^^^^^^ ブラウザでだけ取得する
}
```

このリポジトリは `nuxt generate`(SSG)でビルドし、出力を Spring Boot の `static/` に置いて配信する構成なので、**ビルド時点でバックエンドが起動していない**。そのため API 取得は全部ブラウザ側にしている。この判断と代替案は [data-fetching-and-ssg.md](./data-fetching-and-ssg.md) で扱う。

## 5. このリポジトリのファイルと担当

```
frontend/
├── nuxt.config.ts              ビルド設定・devProxy(/api → backend:8080)
└── app/
    ├── app.vue                 ルートコンポーネント。<NuxtLayout><NuxtPage /></NuxtLayout>
    ├── layouts/default.vue     共通レイアウト(ヘッダ + <slot />)
    ├── pages/
    │   ├── index.vue           タイムライン。無限スクロール
    │   └── posts/[id].vue      投稿詳細
    ├── components/post/
    │   ├── Card.vue            → <PostCard>
    │   └── Form.vue            → <PostForm>
    ├── composables/            API 通信の集約(usePosts / useCategories)
    ├── utils/formatDate.ts     相対時刻の整形(自動インポートされる)
    └── types/                  型定義(自動インポートされない)
```

## 読む順番

1. **[sfc-and-template-syntax.md](./sfc-and-template-syntax.md)** — `.vue` ファイルの構造とテンプレート構文。まずコードが読めるようになる
2. **[reactivity-ref-computed.md](./reactivity-ref-computed.md)** — `ref` / `computed` の仕組み
3. **[composables.md](./composables.md)** — コンポーザブル(= カスタムフック)と `use` 接頭辞。なぜ Vue にはフックのルールが無いのか
4. **[lifecycle-and-watch.md](./lifecycle-and-watch.md)** — `onMounted` / `watch`。React の `useEffect` に当たる部分
5. **[props-and-emits.md](./props-and-emits.md)** — コンポーネント間のデータの受け渡し
6. **[nuxt-vs-nextjs.md](./nuxt-vs-nextjs.md)** — ルーティング・レイアウト・設定
7. **[data-fetching-and-ssg.md](./data-fetching-and-ssg.md)** — `useFetch` / `$fetch` と SSG 前提の取り方

## 関連

- スタイルの書き方(`<style scoped>` と Tailwind)→ [styling-scoped-css-and-tailwind.md](./styling-scoped-css-and-tailwind.md)
- ディレクトリ規約・命名規則の一覧 → [../../development/frontend-structure-best-practices.md](../../development/frontend-structure-best-practices.md)
- SSG を採用した理由 → [../../tech-stack/README.md](../../tech-stack/README.md)
- Nuxt の環境構築手順 → [../../setup/frontend.md](../../setup/frontend.md)
