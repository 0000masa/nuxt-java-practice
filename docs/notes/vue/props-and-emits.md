# props と emits — コンポーネント間のデータの受け渡し

親から子へ値を渡す `defineProps`、子から親へ通知する `defineEmits`、そして React の `children` に当たる `<slot>` をまとめるメモ。

題材は `app/pages/index.vue`(親)と `app/components/post/Card.vue` / `Form.vue`(子)の関係、それに `app/layouts/default.vue` の `<slot />`。

結論を先に言うと:

- **`defineProps` / `defineEmits` は関数ではなくコンパイラマクロ。** import しないのはそのため。`<script setup>` のトップレベルにしか書けず、ビルド時に消える。
- **親から子は props、子から親は emits と、方向で仕組みが分かれている。** React は両方向とも props(値とコールバック関数)だった。
- **props は書き換えられない。** React と同じ一方向データフローだが、Vue は実行時に警告を出して止めにくる。
- **`<slot>` は `children` に相当するが、名前付きスロットと引数付きスロットまで言語機能として持っている。** React の render props に当たるものが構文になっている。

---

## 0. 対応表

| React | Vue | 補足 |
|---|---|---|
| `function Foo({ a, b }: Props)` | `defineProps<{ a: string; b?: number }>()` | 型で宣言する |
| デフォルト値 `{ a = 1 }` | 分割代入に `= 1`(Vue 3.5+)/ `withDefaults` | 後述 |
| `<Foo a="x" />` | `<Foo a="x" />` | 文字列はそのまま |
| `<Foo a={expr} />` | `<Foo :a="expr" />` | コロンが要る |
| `<Foo flag />`(真偽値の省略) | `<Foo flag />` | 同じ書き方が使える |
| `onDeleted={fn}`(コールバック props) | `@deleted="fn"` + `defineEmits` | 別の仕組みになる |
| `children` | `<slot />` | |
| 複数の子を名前で受け取る(props に JSX) | 名前付きスロット `<slot name="x" />` | |
| render props | 引数付きスロット | |
| `useImperativeHandle` | `defineExpose` | ほぼ使わない |

## 1. `defineProps` — 親から子へ

```ts
// app/components/post/Card.vue
const props = defineProps<{
  post: Post
  /** 詳細ページでは本文へのリンクを無効にする */
  linkDisabled?: boolean
}>()
```

TypeScript の型引数だけで宣言する。**この型情報からビルド時に実行時の検証コードが生成される**ため、型注釈でありながら実行時にも意味を持つ。ここが普通の TS と違うところ。

### import していない理由

`defineProps` は import していない。[vue-vs-react-overview.md](./vue-vs-react-overview.md) §2 で説明した自動インポートとも別で、**これは `<script setup>` 専用のコンパイラマクロ**。関数として存在しているわけではなく、ビルド時にコンパイラが解釈して別のコードに置き換える。

そのため次の制約がある。

- **`<script setup>` のトップレベルにしか書けない。** 関数の中や条件分岐の中には書けない。
- **変数を渡せない。** 型引数はコンパイル時に解析されるため、`defineProps<SomeGenericType<T>>()` のような動的な指定はできない場合がある。
- **1 ファイルに 1 回だけ。**

同じ仲間が `defineEmits` / `defineModel` / `defineExpose` / `defineOptions`。

### テンプレートでは `props.` が要らない

```vue
<script setup lang="ts">
const props = defineProps<{ post: Post; linkDisabled?: boolean }>()

async function onDelete() {
  await deletePost(props.post.id)   // スクリプトでは props. が要る
}
</script>

<template>
  <p v-if="linkDisabled">{{ post.body }}</p>   <!-- テンプレートでは要らない -->
</template>
```

ref の `.value` と同じ非対称。**戻り値を変数に受けなくても、テンプレートからは名前で参照できる。** 実際 `Form.vue` は受けていない。

```ts
// app/components/post/Form.vue
defineProps<{
  categories: Category[]
}>()
```

スクリプト側で使わないなら、この形でよい。

### デフォルト値

Vue 3.5 以降は、分割代入にそのまま書ける。

```ts
const { post, linkDisabled = false } = defineProps<{
  post: Post
  linkDisabled?: boolean
}>()
```

React とまったく同じ見た目になる。**ここで分割代入してもリアクティビティは失われない。** 通常のオブジェクトの分割代入なら値が固定されてしまうが、コンパイラが `linkDisabled` への参照を `props.linkDisabled` に書き戻すため、親が値を変えれば追従する([reactivity-ref-computed.md](./reactivity-ref-computed.md) §6 で「分割代入は切れる」と書いたが、props はコンパイラの支援がある例外)。

Vue 3.4 以前は `withDefaults` を使っていた。ネットの記事にはこちらも多い。

```ts
const props = withDefaults(defineProps<{ linkDisabled?: boolean }>(), {
  linkDisabled: false,
})
```

このリポジトリはどちらも使わず、`?:` のまま(未指定なら `undefined`)で扱っている。`v-if="linkDisabled"` のように真偽値として見るだけなら、`undefined` は偽なので困らない。

### 渡す側

```vue
<!-- app/pages/index.vue -->
<PostCard v-for="post in posts" :key="post.id" :post="post" @deleted="onPostDeleted" />
<PostForm v-if="categories" :categories="categories" @created="onPostCreated" />
```

```vue
<!-- app/pages/posts/[id].vue -->
<PostCard v-if="post" :post="post" link-disabled @deleted="onDeleted" />
```

`link-disabled` に注目。**コロンも値も付いていない。** これは 2 つのことが同時に起きている。

1. **ケバブケースで書ける。** 宣言は `linkDisabled`(キャメルケース)だが、テンプレートでは `link-disabled` と書くのが HTML の慣習に沿う。Vue が変換して対応づける。`:linkDisabled` とキャメルケースのまま書いても動く。
2. **真偽値の省略記法。** 値を書かないと空文字が渡るが、props の型が `boolean` と分かっているため Vue が `true` に変換する。React の `<PostCard linkDisabled />` と同じ感覚で書ける。

### props は書き換えられない

```ts
props.post.body = '書き換え'   // 開発時に警告が出る
```

React と同じ一方向データフロー。子が親のデータを直接いじると、変更の出どころが追えなくなるため禁止されている。

**値を加工したいなら `computed`、子の中だけで持ちたいなら初期値として `ref` にコピーする。**

```ts
const upperName = computed(() => props.post.user.displayName.toUpperCase())
```

ただし **props に渡ってきたオブジェクトの中身は、実際には書き換えられてしまう**(Vue が禁止しているのは props 自体への再代入と、開発時の検出範囲まで)。規約として書き換えない、と理解しておく。

## 2. `defineEmits` — 子から親へ

React ではコールバック関数を props で渡していた。Vue は**イベント**という別の仕組みを使う。

```ts
// app/components/post/Card.vue
const emit = defineEmits<{
  deleted: [id: number]
}>()

async function onDelete() {
  await deletePost(props.post.id)
  emit('deleted', props.post.id)
}
```

```vue
<!-- 親: app/pages/index.vue -->
<PostCard :post="post" @deleted="onPostDeleted" />
```

```ts
function onPostDeleted(id: number) {
  posts.value = posts.value.filter((post) => post.id !== id)
}
```

React なら次のように書いていた部分。

```tsx
type Props = { post: Post; onDeleted: (id: number) => void }
// 子: props.onDeleted(post.id)
// 親: <PostCard post={post} onDeleted={onPostDeleted} />
```

### 型の書き方

```ts
const emit = defineEmits<{
  deleted: [id: number]        // イベント名: [引数の型, ...]
  created: [post: Post]
  changed: []                  // 引数なし
}>()
```

**キーがイベント名、値が引数の型を並べたタプル**という構文。関数の型を並べる古い書き方(`(e: 'deleted', id: number): void`)もあるが、こちらのほうが読みやすい。

### props のコールバックと何が違うのか

やれることはほぼ同じだが、設計上の違いがいくつかある。

| | コールバック props(React 流) | emits(Vue 流) |
|---|---|---|
| 戻り値 | 受け取れる | 受け取れない(常に `undefined`) |
| 未指定のとき | `props.onDeleted?.()` と分岐が要る | 誰も聞いていなければ何も起きない |
| 親側の書き方 | `onDeleted={fn}` | `@deleted="fn"` / `@deleted="fn($event)"` |
| DOM イベントとの見た目 | 区別される | 同じ `@` で統一される |

`@click` と `@deleted` が同じ書き方になるのが Vue の狙い。**「そのタグに何かが起きた」を一貫して `@` で書く。**

なお Vue でもコールバックを props で渡すことは可能で、「戻り値が欲しい」「必ず呼ぶ必要がある」場面ではそちらを選ぶこともある。ただし通常は emits を使う。

### `PostForm` の例

作成された投稿を親に渡す形。

```ts
// app/components/post/Form.vue
const emit = defineEmits<{
  created: [post: Post]
}>()

async function onSubmit() {
  const post = await createPost(body.value, categoryId.value)
  body.value = ''
  emit('created', post)
}
```

```ts
// app/pages/index.vue
function onPostCreated(post: Post) {
  // 表示中の絞り込みに合致する場合だけ先頭に差し込む
  if (selectedCategoryId.value === null || post.category.id === selectedCategoryId.value) {
    posts.value.unshift(post)
  }
}
```

**API 通信は子が行い、一覧の状態は親が持つ**という分担。子は「投稿ができた」とだけ伝え、それを一覧のどこに差し込むか(あるいは差し込まないか)は親が決めている。

### 通信をどちらに置くか

前項の分担は emits を使ったから決まったものではない。**通信を親に置く形も同じくらい妥当**で、React ではむしろそちらのほうが多く見かける。emits か props かとは独立した設計判断なので、分けて考える。

**A. 親が通信と状態更新をまとめて持つ**

```tsx
// 親(React)
async function handleDelete(id: number) {
  await deletePost(id)
  setPosts(posts => posts.filter(p => p.id !== id))
}

<PostCard post={post} onDelete={handleDelete} />
```

```tsx
// 子 — 押されたことを伝えるだけ
<button onClick={() => onDelete(post.id)}>削除</button>
```

**B. 子が通信し、親は結果を受けて状態を更新する**(このリポジトリ)

判断の軸は 1 つ。

> **その状態を必要としているのは誰か。**

`posts` 配列は親しか持っていないので、更新するのは必ず親。ここは A も B も変わらない。分かれるのは**通信そのものと、通信に付随する状態**の置き場所だけ。

#### このリポジトリが B を選んでいる理由

`PostCard` が **2 か所から使われていて、削除後の振る舞いだけが違う**ため。

```ts
// pages/index.vue — 一覧から取り除く
function onPostDeleted(id: number) {
  posts.value = posts.value.filter((post) => post.id !== id)
}
```

```ts
// pages/posts/[id].vue — トップへ戻る
function onDeleted() {
  router.push('/')
}
```

一方で**削除の手順そのものは 2 か所で同一**。

```ts
// components/post/Card.vue
if (!confirm('この投稿を削除しますか?')) return
deleting.value = true
try {
  await deletePost(props.post.id)
} catch {
  alert('削除に失敗しました')
} finally {
  deleting.value = false
}
```

A の形にすると、この確認ダイアログ・API 呼び出し・`deleting` フラグ・エラー処理を**両方の親に書くことになる**。「共通なのは削除の手順、違うのは削除後の行き先」なので、**手順を子に、行き先を親に**置いた、という分割になっている。

もう 1 つは `deleting` の置き場所。

```vue
<button :disabled="deleting" @click="onDelete">削除</button>
```

これは**そのボタンだけが必要とする状態**で、親は知る必要がない。A にすると親が「いまどの id が削除中か」を `Set<number>` などで持ち、それを子へ渡し直すことになる。`ref` 1 つで済んでいたものが、状態 + props + 更新処理の 3 点セットになる。

#### A のほうが向いている場面

| 状況 | 理由 |
|---|---|
| 子を**表示専用の部品**にしたい | API に依存しなくなり、テストが書きやすくなる |
| **楽観的更新**をしたい | 先に一覧から消して、失敗したら戻す。配列を持つ親にしか書けない |
| 削除の前後に**親固有の処理**が挟まる | 分析イベントの送信、複数選択の解除など |
| 同じ操作を**複数の場所から**起動する | 一覧のボタンとメニューの両方から削除する、など |

いまの `PostCard` は `usePosts()` を import しているので、**厳密には表示専用の部品ではない**。「投稿カード」という単位に削除の責務まで持たせた、という判断が入っている。カードを別の文脈で使い回す必要が出てきたら、A に寄せ直す余地がある。

#### 補足: ライブラリを入れるとこの二択が消える

TanStack Query や SWR を使うと、一覧の状態を親のローカル state で持たなくなるため、「誰が配列を更新するか」という問題自体がなくなる。

```tsx
const { mutate } = useMutation({
  mutationFn: deletePost,
  onSuccess: () => queryClient.invalidateQueries({ queryKey: ['posts'] }),
})
```

子が直接呼んでも、キャッシュ経由で一覧が更新される。このリポジトリは学習のためライブラリを入れず `ref` + `$fetch` で組んでいるので、その分「どちらに置くか」を自分で決める必要が出ている。

## 3. `<slot>` — `children` に相当するもの

```vue
<!-- app/layouts/default.vue -->
<template>
  <div class="app">
    <header class="app-header">
      <NuxtLink to="/" class="app-title">投稿アプリ</NuxtLink>
    </header>
    <main class="app-main">
      <slot />
    </main>
  </div>
</template>
```

React の `{children}` と同じ。`<slot />` の位置に、外側から渡された中身が入る。

```vue
<MyBox>
  <p>ここが slot に入る</p>
</MyBox>
```

### 名前付きスロット

複数の差し込み口を作れる。React では `<Card header={<h1/>} body={<p/>} />` のように JSX を props で渡していた部分。

```vue
<!-- 子 -->
<template>
  <article>
    <header><slot name="header" /></header>
    <slot />                             <!-- 名前なし = default -->
    <footer><slot name="footer">フッタ既定値</slot></footer>
  </article>
</template>
```

```vue
<!-- 親 -->
<MyCard>
  <template #header><h1>タイトル</h1></template>
  <p>本文</p>
  <!-- #footer を書かなければ「フッタ既定値」が表示される -->
</MyCard>
```

`#header` は `v-slot:header` の省略形。**`<slot>` の中に書いた内容がフォールバック**になる点も便利で、React では `children ?? <Default />` と自分で書いていた部分。

### 引数付きスロット

**子が持っているデータを、親が書くマークアップに渡せる。** React の render props に当たる。

```vue
<!-- 子 -->
<li v-for="item in items" :key="item.id">
  <slot :item="item" :index="index" />
</li>
```

```vue
<!-- 親 -->
<MyList :items="posts">
  <template #default="{ item }">
    <strong>{{ item.body }}</strong>
  </template>
</MyList>
```

React の `<MyList items={posts} render={(item) => <strong>{item.body}</strong>} />` と同じことを、関数を渡さずに書ける。

このリポジトリではまだ `default.vue` の `<slot />` しか使っていないが、**フェーズ 8 の検索ラボ**のように「枠は共通、中身だけ差し替える」形が出てくると使いどころになる。

## 4. コンポーネントの `v-model`(今後使う)

`v-model` は自作コンポーネントにも付けられる。Vue 3.4 以降は `defineModel()` で書く。

```ts
// 子
const model = defineModel<string>()
// model は ref のように扱える。書き換えると親にも反映される
```

```vue
<!-- 親 -->
<MyInput v-model="keyword" />
```

内部的には `modelValue` という props と `update:modelValue` というイベントの組み合わせで、`defineModel` はその定型を隠している。React に相当物はなく、`value` + `onChange` を自分で組み立てていた部分。

このリポジトリでは、フォームが `PostForm` の中で完結しているためまだ出番がない。**フェーズ 8 の検索条件フォーム**を部品に分けるときが最初の候補になる。

---

## 落とし穴

- **props を渡すときのコロンを忘れる。** `:post="post"` が正しい。`post="post"` は文字列 `"post"` が渡る。
- **スクリプトで `props.` を忘れる。** テンプレートでは不要、スクリプトでは必要。
- **props を書き換える。** 一方向データフローに反する。`computed` で加工するか、`ref` にコピーする。
- **emits を宣言せずに `emit()` を呼ぶ。** 型が効かないうえ、宣言していないイベント名は素の DOM 属性として子のルート要素に漏れる。
- **イベント名を `onDeleted` にする。** emits のイベント名は `deleted`。`on` を付けるのは親側の `@` が担当する。
- **`defineProps` を関数の中に書く。** コンパイラマクロなのでトップレベルにしか置けない。
- **キャメルケースとケバブケースの混在に戸惑う。** 宣言はキャメル、テンプレートはケバブが慣習。どちらでも動く。
- **emits を使うと通信も子に置くものだと思う。** 別の話。通信を親に置いて子は「押された」とだけ伝える形も等しく妥当 → §2「通信をどちらに置くか」。

## 用語集

- **props** — 親から子へ渡すデータ。子からは読み取り専用
- **emits** — 子から親へ通知するイベント。`defineEmits` で宣言し `emit()` で発火する
- **コンパイラマクロ** — `defineProps` のように、実行時の関数ではなくビルド時にコンパイラが解釈して置き換える記述。import しない
- **一方向データフロー** — データは親から子へ流れ、子は直接書き換えない、という設計原則。React と共通
- **スロット(slot)** — 親が書いたマークアップを子の指定位置に差し込む仕組み。React の `children`
- **名前付きスロット** — 差し込み口に名前を付けて複数用意したもの
- **引数付きスロット(スコープ付きスロット)** — 子が持つデータを親のマークアップに渡せるスロット。React の render props
- **`defineModel`** — 自作コンポーネントを `v-model` に対応させるためのマクロ

## 関連

- 全体像と対応表 → [vue-vs-react-overview.md](./vue-vs-react-overview.md)
- テンプレートでの `:` と `@` の意味 → [sfc-and-template-syntax.md](./sfc-and-template-syntax.md)
- 分割代入とリアクティビティ → [reactivity-ref-computed.md](./reactivity-ref-computed.md)
- コンポーネントの命名規則(`components/post/Card.vue` → `<PostCard>`)→ [../../development/frontend-structure-best-practices.md](../../development/frontend-structure-best-practices.md)
- Vue 公式「props」 https://ja.vuejs.org/guide/components/props
- Vue 公式「スロット」 https://ja.vuejs.org/guide/components/slots
