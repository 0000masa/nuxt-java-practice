# SFC とテンプレート構文 — JSX とどう違うか

`.vue` ファイル(単一ファイルコンポーネント / SFC)の構造と、`<template>` の中で使う `v-if` `v-for` `@click` `:class` `v-model` といった記法をまとめたメモ。React の JSX と対比する。

[vue-vs-react-overview.md](./vue-vs-react-overview.md) を読んだ後、まずコードが読めるようになることを目的とする回。

結論を先に言うと:

- **JSX は JavaScript の式。テンプレートは JavaScript ではない独自の文法。** 見た目は HTML そのままで、属性に `v-` や `:` や `@` を足して制御する。
- **`{{ }}` の中と `"..."` の中には JS の式が書ける。** ただし文(`if` や `for`)は書けない。制御構造は属性側の `v-if` / `v-for` が担当する。
- **テンプレートは HTML ではなくビルド時にコンパイルされる。** 出力は render 関数。このとき「どこが変わりうるか」をコンパイラが解析して印を付けるため、実行時の差分計算が JSX より少なく済む。
- **`<style scoped>` は魔法ではない。** 要素に `data-v-xxxxxxx` 属性を足し、CSS セレクタにも同じ属性条件を足しているだけ。

---

## 1. 3 つのブロック

`.vue` ファイルは 3 つのブロックでできている。順番は自由だが、`<script setup>` → `<template>` → `<style>` の順に書くのが一般的で、このリポジトリもそれで統一している。

```vue
<script setup lang="ts">
// ロジック。コンポーネント生成時に 1 回だけ実行される
</script>

<template>
  <!-- マークアップ。値が変わると必要な箇所だけ再描画される -->
</template>

<style scoped>
/* このコンポーネントにだけ効く CSS */
</style>
```

React と対応させると次のようになる。

| Vue のブロック | React での相当物 |
|---|---|
| `<script setup>` | コンポーネント関数の本体(`return` より前) |
| `<template>` | `return (...)` の中の JSX |
| `<style scoped>` | `Foo.module.css` を別ファイルで作って import |

**`<script setup>` の `setup` は属性で、これがあると書き方が変わる。** `setup` を付けない `<script>` は、`export default { setup() { ... } }` を手書きする古い形になる(ネットの Vue 3 記事にはこの形も出てくる)。このリポジトリでは全ファイル `<script setup>` を使う。

`<script setup>` で宣言した変数・関数は、**そのまま `<template>` から使える**。`return` して公開する手続きは要らない。

```vue
<script setup lang="ts">
const deleting = ref(false)
function onDelete() { /* ... */ }
</script>

<template>
  <!-- deleting も onDelete も、宣言しただけで参照できる -->
  <button :disabled="deleting" @click="onDelete">削除</button>
</template>
```

## 2. `{{ }}` と `:` — 値をどこに差し込むか

JSX は差し込み口が `{ }` 一種類だった。テンプレートでは**テキストとして差し込むか、属性として差し込むか**で書き方が変わる。

```vue
<!-- テキスト: マスタッシュ構文 -->
<span>{{ post.user.displayName }}</span>

<!-- 属性: 頭にコロンを付ける -->
<button :disabled="deleting">削除</button>
<NuxtLink :to="`/posts/${post.id}`">本文</NuxtLink>
```

`:disabled` は `v-bind:disabled` の省略形で、**「この属性の値を文字列としてではなく JS の式として評価しろ」**という指示。コロンを付け忘れると文字列がそのまま入る。

```vue
<button disabled="deleting">   <!-- 文字列 "deleting" が入る。常に disabled になる -->
<button :disabled="deleting">  <!-- 変数 deleting の値が入る -->
```

`{{ }}` と `"..."` の中には**式**なら何でも書ける。

```vue
{{ formatRelativeTime(post.createdAt) }}   <!-- 関数呼び出し -->
{{ remaining < 0 ? '超過' : remaining }}    <!-- 三項演算子 -->
```

書けないのは**文**。`if (...) { }` や `for (...) { }` や変数宣言は入らない。JSX なら `{cond && <p/>}` や `{list.map(...)}` と JS の式で書いていたところを、テンプレートでは次章以降の専用属性で書く。

## 3. 条件分岐 — `v-if` / `v-else` / `v-show`

```vue
<!-- app/components/post/Card.vue -->
<p v-if="linkDisabled" class="post-body">{{ post.body }}</p>
<NuxtLink v-else :to="`/posts/${post.id}`">
  <p class="post-body">{{ post.body }}</p>
</NuxtLink>
```

React なら三項演算子で書いていた部分。`v-else` は `v-if` の**直後の兄弟要素**でなければならない(間に他の要素やコメント以外を挟めない)。3 分岐以上なら `v-else-if` を挟む。

```vue
<!-- app/pages/index.vue -->
<p v-if="loading" class="timeline-status">読み込み中...</p>
<p v-else-if="reachedEnd && posts.length === 0" class="timeline-status">投稿はまだありません</p>
<p v-else class="timeline-status">これ以上投稿はありません</p>
```

似たものに `v-show` がある。**違いは DOM に残るかどうか。**

| | 偽のとき | 用途 |
|---|---|---|
| `v-if` | 要素ごと DOM から消える。真になると作り直される | 表示されないことが多い、生成コストが高い |
| `v-show` | DOM には残り `display: none` になるだけ | 頻繁に切り替わる |

React には `v-show` に当たる短縮記法がなく、`style={{display: cond ? '' : 'none'}}` と手で書いていたところ。

**複数要素をまとめて出し分けたいとき**は `<template>` タグで囲む。React の `<>...</>`(Fragment)に当たる。

```vue
<template v-if="post">
  <h1>{{ post.body }}</h1>
  <time>{{ post.createdAt }}</time>
</template>
```

## 4. 繰り返し — `v-for` と `:key`

```vue
<!-- app/pages/index.vue -->
<PostCard v-for="post in posts" :key="post.id" :post="post" @deleted="onPostDeleted" />
```

React の `posts.map(post => <PostCard key={post.id} post={post} />)` に当たる。違いは 2 点。

- **`map` を書かない。** 繰り返す要素そのものに `v-for` を付ける。
- **`:key` は普通の属性と同じくコロン付き。** 役割は React の `key` と同じで、差分更新のときに要素とデータを対応づけるための識別子。

インデックスも取れる。オブジェクトも回せる。

```vue
<li v-for="(post, index) in posts" :key="post.id">{{ index }}: {{ post.body }}</li>
<li v-for="(value, name) in obj" :key="name">{{ name }}: {{ value }}</li>
<li v-for="n in 5" :key="n">{{ n }}</li>   <!-- 1 から 5 -->
```

**`v-if` と `v-for` を同じ要素に付けてはいけない。** どちらが先に評価されるか分かりにくく、Vue 3 では `v-if` が先に評価されるため `v-for` の変数がまだ存在せずエラーになる。フィルタしたいときは `computed` で絞ったリストを作るか、`<template v-for>` で包んで内側に `v-if` を置く。

このリポジトリでは、フィルタ済みのリストを事前に作る形をとっている。

```vue
<!-- app/pages/index.vue: 空のときも回せるよう ?? [] を挟んでいる -->
<button v-for="category in categories ?? []" :key="category.id">
```

## 5. イベント — `@click` と修飾子

```vue
<button @click="onDelete">削除</button>
<button @click="selectCategory(null)">すべて</button>
```

`@click` は `v-on:click` の省略形。React の `onClick={onDelete}` に当たるが、**`@click="selectCategory(null)"` のように呼び出し式をそのまま書ける**点が違う。React では `onClick={() => selectCategory(null)}` とアロー関数で包む必要があった。

イベントオブジェクトが欲しいときは `$event` を使う。

```vue
<button @click="onClick($event)">
```

テンプレートならではの機能が**修飾子**。ドットでつなげる。

```vue
<!-- app/components/post/Form.vue -->
<form class="post-form" @submit.prevent="onSubmit">
```

`.prevent` は `event.preventDefault()` を呼んでくれる。React なら `onSubmit={(e) => { e.preventDefault(); onSubmit() }}` と書いていた部分。

| 修飾子 | 効果 |
|---|---|
| `.prevent` | `event.preventDefault()` |
| `.stop` | `event.stopPropagation()` |
| `.once` | 1 回だけ発火 |
| `.self` | その要素自身が対象のときだけ発火 |
| `.enter` / `.esc` | キーの指定(`@keyup.enter`) |

## 6. クラスとスタイル — オブジェクトを渡せる

```vue
<!-- app/pages/index.vue -->
<button
  class="category-chip"
  :class="{ active: selectedCategoryId === null }"
  @click="selectCategory(null)"
>
```

`:class` にオブジェクトを渡すと、**値が真のキーだけがクラス名として付く**。この例なら `class="category-chip active"` か `class="category-chip"` になる。

**静的な `class` と `:class` は共存でき、自動でマージされる。** React では `className={`base ${cond ? 'active' : ''}`}` と 1 つの文字列に組み立てる必要があり、`clsx` のようなライブラリを入れるのが定番だったが、Vue は言語機能として持っている。

配列も渡せる。`:style` も同様にオブジェクトを取る。

```vue
<div :class="['a', 'b', { c: isC }]" :style="{ color: textColor, fontSize: size + 'px' }" />
```

## 7. `v-model` — 双方向バインディング

React の制御コンポーネントは `value` と `onChange` の 2 つを書く必要があった。Vue は 1 属性で済む。

```vue
<!-- app/components/post/Form.vue -->
<script setup lang="ts">
const body = ref('')
</script>

<template>
  <textarea v-model="body" rows="3" placeholder="いまどうしてる?" />
</template>
```

React で書くとこうなる。

```tsx
const [body, setBody] = useState('')

return (
  <textarea
    value={body}
    onChange={(e) => setBody(e.target.value)}
    rows={3}
    placeholder="いまどうしてる?"
  />
)
```

`value` と `onChange` の**両方**を書いて初めて入力できる。片方でも欠けると壊れる。

- `value` だけ書くと、React が値を固定するため**何も入力できない読み取り専用**になる(開発時に警告が出る)。
- `onChange` だけ書くと、state と表示がずれる非制御コンポーネントになる。

つまり React では「表示に反映する経路」と「入力を state に戻す経路」を毎回手で配線している。`v-model` はこの往復を 1 つの属性に畳んだもの。**片方だけ書いて壊す、という事故が起こりえない**のが実務上の差。

なお `rows` の書き方も違う。React は JSX なので `rows={3}` と JS の数値を渡すが、Vue のテンプレートは HTML そのままなので `rows="3"` でよい。

`v-model` は次の糖衣構文。

```vue
<textarea :value="body" @input="body = $event.target.value" />
```

React の `value` / `onChange` とそのまま対応している。違うのは `setBody(...)` ではなく `body = ...` と代入していることだけで、これは [reactivity-ref-computed.md](./reactivity-ref-computed.md) で扱う更新方法の差。

**実際に書くのはほぼ `v-model` のほう。** 展開形は仕組みの説明のために示したもので、通常のフォームでこう書くことはない(このリポジトリの `Form.vue` も `textarea` と `select` の両方で `v-model` を使っている)。展開形の出番は「入力値を加工してから代入したい」「`input` 以外のイベントで更新したい」といった、`v-model` では表現できない場合に限られる。

よくある加工は修飾子で済むため、その段階でも展開形は要らないことが多い。

| 修飾子 | 効果 |
|---|---|
| `v-model.trim` | 前後の空白を除いてから代入する |
| `v-model.number` | 数値に変換してから代入する |
| `v-model.lazy` | `input` ではなく `change`(入力確定時)で更新する |

対象の要素によって、内部で使う属性とイベントが自動で切り替わる。

| 要素 | 展開される形 |
|---|---|
| `<input type="text">` / `<textarea>` | `:value` + `@input` |
| `<input type="checkbox">` / `<input type="radio">` | `:checked` + `@change` |
| `<select>` | `:value` + `@change` |

`<select>` では HTML の制約を超えられる点が便利。

```vue
<!-- app/components/post/Form.vue -->
<select v-model="categoryId">
  <option :value="null" disabled>カテゴリーを選択</option>
  <option v-for="category in categories" :key="category.id" :value="category.id">
    {{ category.name }}
  </option>
</select>
```

素の HTML では `<option value>` は文字列しか持てないため、React でも `Number(e.target.value)` と変換するのが定番だった。Vue は `:value` にコロンを付けることで**数値や `null` をそのまま**バインドできる。この `categoryId` は `ref<number | null>(null)` のままで、変換処理が要らない。

コンポーネントに対して `v-model` を使うこともできる。これは props / emits の話なので [props-and-emits.md](./props-and-emits.md) で扱う。

## 8. `<style scoped>` の仕組み

```vue
<style scoped>
.post-card { border: 1px solid #e2e8f0; }
</style>
```

このコンポーネントの中の `.post-card` にだけ効き、他のコンポーネントの `.post-card` には影響しない。仕組みは単純で、ビルド時に次の 2 つを同時に行っている。

1. このコンポーネントが描画する要素に `data-v-7ba5bd90` のような属性を足す
2. CSS セレクタを `.post-card[data-v-7ba5bd90]` に書き換える

つまり CSS Modules のように**クラス名を書き換えるのではなく、属性条件を足している**。デバッグ時にブラウザの開発者ツールで `data-v-` 属性が見えるのはこのため。

覚えておくべき性質が 2 つある。

- **子コンポーネントの中身には効かない。** ただし子コンポーネントの**ルート要素**には親のスコープ属性も付くため、そこだけは効いてしまう。
- **子の内側に効かせたいときは `:deep()` を使う。** `.parent :deep(.child) { ... }`

`scoped` を外すとグローバル CSS になる。このリポジトリでは、全体に効かせたいものは `app/assets/css/main.css` に置き、`nuxt.config.ts` の `css:` で読み込んでいる。

## 9. テンプレートは HTML ではない — コンパイルされる

一番大事な仕組みの話。ブラウザは `<template>` の中身をそのまま解釈しているわけではない。**ビルド時に render 関数へ変換されている。**

```
Card.vue の <template>
      ↓  Vue コンパイラ(@vue/compiler-sfc)がビルド時に変換
render 関数(JS)
      ↓  実行時に呼ばれて仮想 DOM を返す
実 DOM への差分適用
```

JSX も Babel/SWC が `React.createElement` 呼び出しに変換されるので、変換される点は同じ。**違うのは、変換前が JS ではない独自文法であるおかげで、コンパイラが構造を静的に解析できる**という点。

```vue
<article class="post-card">
  <span class="post-author">{{ post.user.displayName }}</span>
  <p class="post-body">{{ post.body }}</p>
</article>
```

このとき Vue のコンパイラは「`<article>` の `class` は絶対に変わらない」「変わりうるのは 2 か所のテキストだけ」と判断できる。そこで変化しない部分は 1 回だけ作って使い回し(静的ホイスティング)、変わりうるノードには「テキストだけ変わる」という印(パッチフラグ)を付ける。実行時の差分計算はその印の付いたノードだけを見る。

JSX は任意の JS 式が混ざりうるため、この種の解析ができない。React が差分計算を軽くするために `memo` / `useMemo` / `useCallback` を書き手に要求するのは、この構造上の違いが背景にある(React Compiler はこれを自動化する試み)。

この「変わりうる箇所だけ更新する」を成立させているもう半分の仕組みが、値の変更検知。それが次の [reactivity-ref-computed.md](./reactivity-ref-computed.md) の話。

---

## 落とし穴

- **属性のコロンを忘れる。** `disabled="deleting"` は文字列 `"deleting"` が入って常に真になる。`:disabled="deleting"` が正しい。
- **`{{ }}` に文を書こうとする。** `{{ if (a) ... }}` は書けない。三項演算子にするか `computed` に出す。
- **`v-if` と `v-for` を同じ要素に付ける。** `computed` で絞るか `<template v-for>` で分ける。
- **`v-else` を `v-if` から離す。** 直後の兄弟要素でなければならない。
- **`:key` を付け忘れる。** React 同様、リストの差分更新がおかしくなる。
- **`scoped` が子コンポーネントの中に効くと思う。** 効かない。`:deep()` が必要。
- **`<script>` と `<script setup>` を混同する。** ネットの記事には `export default { setup() {} }` 形式も多い。このリポジトリは `<script setup>` のみ。

## 用語集

- **SFC(単一ファイルコンポーネント)** — 1 つの `.vue` ファイルにロジック・マークアップ・スタイルをまとめる Vue の形式
- **ディレクティブ** — `v-if` `v-for` `v-model` のように `v-` で始まるテンプレート専用の属性
- **マスタッシュ構文** — `{{ }}`。テキストとして値を差し込む記法
- **修飾子(modifier)** — `@submit.prevent` の `.prevent` のように、ディレクティブの後ろにドットでつなげる指定
- **糖衣構文(シンタックスシュガー)** — より長い書き方の短縮形。`v-model` は `:value` + `@input` の糖衣構文
- **静的ホイスティング** — 変化しない仮想 DOM ノードを 1 回だけ作って使い回すコンパイラ最適化
- **パッチフラグ** — 「このノードはテキストだけが変わる」といった情報をコンパイラがノードに付ける印。実行時の差分計算を絞り込む

## 関連

- 全体像と対応表 → [vue-vs-react-overview.md](./vue-vs-react-overview.md)
- 値が変わったとき何が起きるか → [reactivity-ref-computed.md](./reactivity-ref-computed.md)
- コンポーネント間のデータ受け渡し → [props-and-emits.md](./props-and-emits.md)
- `<style scoped>` と Tailwind の使い分け → [styling-scoped-css-and-tailwind.md](./styling-scoped-css-and-tailwind.md)
- Vue 公式「テンプレート構文」 https://ja.vuejs.org/guide/essentials/template-syntax
