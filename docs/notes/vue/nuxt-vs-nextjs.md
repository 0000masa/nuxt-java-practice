# Nuxt の仕組み — Next.js (App Router) と比べる

ルーティング・レイアウト・設定など、Vue そのものではなく **Nuxt がやっていること**をまとめるメモ。Next.js の App Router 経験を土台に読み替えていく。

データ取得と SSG は分量があるので [data-fetching-and-ssg.md](./data-fetching-and-ssg.md) に分けた。ここはそれ以外。

結論を先に言うと:

- **ファイルベースルーティングの発想は同じ。** ただし Nuxt は `pages/` の**ファイル名**がそのまま URL になる。Next のようにディレクトリを掘って `page.tsx` を置く形ではない。
- **レイアウトは 2 段構え。** `app.vue`(全ページ共通の外枠)と `layouts/`(切り替え可能なレイアウト)に分かれている。Next の `layout.tsx` は前者に近く、Nuxt の `layouts/` に相当するものが Next にはない。
- **ルート情報は props ではなく関数で取りに行く。** `useRoute()` を呼ぶ。
- **`server/` ディレクトリ(Nitro)は使わない。** Next の Route Handlers に当たる機能だが、このリポジトリの API は Spring Boot が担当するため意図的に空にしている。
- **開発時の `/api` 転送は `nuxt.config.ts` の `devProxy` が担っている。** これのおかげで CORS 設定が要らない。

---

## 0. 対応表

| Next.js (App Router) | Nuxt 4 |
|---|---|
| `app/page.tsx` | `app/pages/index.vue` |
| `app/posts/page.tsx` | `app/pages/posts/index.vue` |
| `app/posts/[id]/page.tsx` | `app/pages/posts/[id].vue` |
| `app/[...slug]/page.tsx` | `app/pages/[...slug].vue` |
| `app/layout.tsx`(ルート) | `app/app.vue` |
| `app/posts/layout.tsx`(入れ子) | `app/pages/posts.vue` + `<NuxtPage />` |
| `app/(group)/layout.tsx` | `app/layouts/*.vue` + `definePageMeta({ layout })` |
| `app/loading.tsx` | (なし。自前で書く) |
| `app/error.tsx` | `app/error.vue` |
| `app/not-found.tsx` | `app/error.vue` の中で 404 を分岐 |
| `app/api/*/route.ts` | `server/api/*.ts`(**このリポジトリでは未使用**) |
| `middleware.ts` | `app/middleware/*.ts` |
| `useParams()` | `useRoute().params` |
| `useSearchParams()` | `useRoute().query` |
| `useRouter().push()` | `useRouter().push()` / `navigateTo()` |
| `usePathname()` | `useRoute().path` |
| `<Link href>` | `<NuxtLink to>` |
| `export const metadata` | `useHead()` / `useSeoMeta()` / `nuxt.config.ts` の `app.head` |
| `next.config.js` | `nuxt.config.ts` |
| `next build` | `nuxt build` |
| `output: 'export'` + `next build` | `nuxt generate` |
| React Context / Zustand | `useState()`(Nuxt 組み込み)/ Pinia |

## 1. ファイルベースルーティング

`app/pages/` の下のファイル構成がそのまま URL になる。

```
app/pages/
├── index.vue          → /
└── posts/
    └── [id].vue       → /posts/:id
```

Next の App Router では `app/posts/[id]/page.tsx` のようにディレクトリを掘って `page.tsx` を置いたが、**Nuxt はファイル名がそのまま URL 断片になる**。基本は素直な対応で、迷うのは `posts/index.vue` と `posts.vue` の使い分けくらい(→ 後述)。

| ファイル | URL |
|---|---|
| `pages/index.vue` | `/` |
| `pages/about.vue` | `/about` |
| `pages/posts/index.vue` | `/posts` |
| `pages/posts/[id].vue` | `/posts/:id` |
| `pages/posts/[id]/edit.vue` | `/posts/:id/edit` |
| `pages/[...slug].vue` | 任意の深さにマッチ |
| `pages/users/[[id]].vue` | `/users` と `/users/:id` の両方 |

Nuxt は起動時に `pages/` を走査して、**vue-router のルート定義を自動生成**する。手で `<Route path=... />` を書くことはない。この点は Next と同じ。

**`pages/` が存在しなければルーティング機能自体が無効になる**のも Nuxt の特徴で、単一ページのアプリなら `app.vue` だけで完結できる。

### `posts/index.vue` と `posts.vue`

どちらも `/posts` になるが、**役割が違う**。「子ルートがあるかどうか」で意味が変わる。

**単独で置いたときは同じ。**

```
pages/posts.vue        → /posts
pages/posts/index.vue  → /posts
```

**`pages/posts.vue` と `pages/posts/` ディレクトリが同時にあると、`posts.vue` は親ルート(子の共通の枠)になる。**

```
pages/
├── posts.vue           ← 親。/posts 配下すべての外枠
└── posts/
    ├── index.vue       → /posts        ← posts.vue の中に描画される
    └── [id].vue        → /posts/:id    ← 同じく posts.vue の中に描画される
```

このとき **`posts.vue` には `<NuxtPage />` が必須**。子を描画する差し込み口がなくなるため、書き忘れると子ページが表示されず Nuxt が警告を出す。

```vue
<!-- pages/posts.vue -->
<template>
  <div>
    <h1>投稿</h1>
    <nav><!-- /posts と /posts/:id で共通のタブなど --></nav>
    <NuxtPage />         <!-- ここに index.vue や [id].vue が入る -->
  </div>
</template>
```

つまり **`posts.vue` は「`/posts` というページ」ではなく「`/posts/*` の共通の外枠」を作る仕組み**。単独で置いたときにページとして機能するのは、たまたま子がいないからにすぎない。

Next.js にちょうど対応するものがある。

| Nuxt | Next.js (App Router) |
|---|---|
| `pages/posts/index.vue` | `app/posts/page.tsx` |
| `pages/posts/[id].vue` | `app/posts/[id]/page.tsx` |
| **`pages/posts.vue`** | **`app/posts/layout.tsx`** |
| 親の中の `<NuxtPage />` | `layout.tsx` の中の `{children}` |

**`posts.vue` = 入れ子レイアウト**と読み替えれば、Next で `layout.tsx` を置くかどうかを考えるのと同じ判断になる。

#### どちらを使うか

**基本は `index.vue`。** 理由が 3 つ。

**構成が一貫する。** `/posts` 配下のものが `posts/` ディレクトリに全部収まる。`posts.vue` と `posts/` が並ぶと関係するファイルが 2 か所に分かれる。

**あとから増やすときに構造を変えなくてよい。** `posts.vue` 単独で始めると、`/posts/new` を足したくなった時点で `posts/` ディレクトリができ、そこで `posts.vue` が突然「親ルート」に変質する。`<NuxtPage />` を足さないと動かないのに、変質したこと自体に気づきにくい。

**`<NuxtPage />` の要否を覚えなくて済む。** `index.vue` なら普通のページとして書くだけ。

`posts.vue` を選ぶのは、**`/posts` と `/posts/:id` で共通の枠が欲しいと分かっているとき**だけ。両方の上部に同じタブを出す、サイドバーを共有する、といった場合に限られる。

#### このリポジトリの現状

```
app/pages/
├── index.vue          → /
└── posts/
    └── [id].vue       → /posts/:id
```

`posts/index.vue` も `posts.vue` もないため、**`/posts` は 404**。タイムラインが `/` にあるので、`/posts` という一覧ページを作っていない。

### Next にあって Nuxt にないもの

- **ルートグループ `(group)`** — URL に出さずにディレクトリで分ける機能。Nuxt にはない。代わりに `layouts/` でレイアウトを切り替える。
- **`loading.tsx` / `template.tsx`** — 読み込み中の自動表示。Nuxt では `useFetch` の `status` を見て自分で書く(このリポジトリの「読み込み中...」がそれ)。
- **並列ルート・インターセプトルート** — Nuxt に相当機能はない。

## 2. レイアウトは 2 段構え

### `app.vue` — 全ページ共通の一番外側

```vue
<!-- app/app.vue -->
<template>
  <div>
    <NuxtRouteAnnouncer />
    <NuxtLayout>
      <NuxtPage />
    </NuxtLayout>
  </div>
</template>
```

Next の `app/layout.tsx` に一番近い。ただし `<html>` や `<body>` は書かない(Nuxt が用意する)。

3 つのコンポーネントの役割。

| コンポーネント | 役割 | Next での相当物 |
|---|---|---|
| `<NuxtPage />` | 現在の URL に対応するページを描画する | `layout.tsx` の `{children}` |
| `<NuxtLayout>` | ページが指定したレイアウトを適用する | (相当物なし) |
| `<NuxtRouteAnnouncer />` | ページ遷移をスクリーンリーダーに読み上げさせる | (相当物なし) |

`NuxtRouteAnnouncer` はアクセシビリティ用。SPA ではページ遷移してもブラウザが遷移を通知しないため、視覚に頼らない利用者に「ページが変わった」ことを伝える不可視の要素を挿入する。`nuxi` のテンプレートに最初から入っており、消さずに残してある。

### `layouts/` — 切り替えられる外枠

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

`<slot />` にページの中身が入る([props-and-emits.md](./props-and-emits.md) §3)。

**`default.vue` は何も指定しなければ全ページに自動適用される。** 別のレイアウトを使いたいページは、そのページで宣言する。

```ts
// 例: layouts/auth.vue を使う
definePageMeta({ layout: 'auth' })
```

Next では「レイアウトを外したい」「別のレイアウトにしたい」ときにルートグループでディレクトリを切る必要があったが、**Nuxt はページ側の 1 行で切り替えられる**。認証画面だけヘッダを消す、といった要件が素直に書ける(フェーズ 3 で使うことになる)。

`definePageMeta` も `defineProps` と同じコンパイラマクロで、レイアウト指定のほかに `middleware`(認証ガード)や `layout: false` を書く場所になる。

### 全体の入れ子

```
app.vue
 └─ NuxtLayout        → layouts/default.vue
     └─ <slot />
         └─ NuxtPage  → pages/index.vue
```

### 補足: 親ルートページも外枠になる

「2 段構え」は **Nuxt が「レイアウト」と呼んでいるもの**の話。実際に画面を包む枠は、これに加えてもう 1 つ増えることがある。§1 の親ルートページ(`pages/posts.vue`)がそれで、子ルートがあるとその外枠として振る舞う。

```
app.vue
 └─ NuxtLayout           → layouts/default.vue
     └─ <slot />
         └─ NuxtPage     → pages/posts.vue        ← ページだが外枠として働く
             └─ NuxtPage → pages/posts/[id].vue
```

ただしこれは **`layouts/` とは別の仕組み**(vue-router のネストルート)で、次の点が違う。

| | `layouts/` のレイアウト | 親ルートページ |
|---|---|---|
| 実体 | `layouts/*.vue` | `pages/*.vue`(ページの一種) |
| 差し込み口 | `<slot />` | `<NuxtPage />` |
| 適用範囲 | ページ側が `definePageMeta({ layout })` で選ぶ | **URL の階層で自動的に決まる** |
| 切り替え | できる | できない |

**Nuxt の用語で「レイアウト」と言えるのは `layouts/` の中身だけ**なので、親ルートページを 3 つ目のレイアウトとは呼ばない。ただし**見た目の枠としては 3 重になる**ことは知っておく。使い分けは §1 を参照。

## 3. ルート情報の取り方

Next では `params` が props で渡ってきたが、**Nuxt は関数で取りに行く**。

```ts
// app/pages/posts/[id].vue
const route = useRoute()
const router = useRouter()

post.value = await fetchPost(route.params.id as string)

function onDeleted() {
  router.push('/')
}
```

| 用途 | Next | Nuxt |
|---|---|---|
| 動的セグメント | `params.id`(props)/ `useParams()` | `useRoute().params.id` |
| クエリ文字列 | `useSearchParams()` | `useRoute().query.q` |
| 現在のパス | `usePathname()` | `useRoute().path` |
| 遷移 | `useRouter().push('/')` | `useRouter().push('/')` または `navigateTo('/')` |

`route` はリアクティブなので、`watch(() => route.params.id, ...)` で変化を監視できる。同じページコンポーネントのまま URL だけ変わる遷移(`/posts/1` → `/posts/2`)で必要になる。

**`route.params.id` の型は `string | string[]`。** URL から来る以上つねに文字列で、`[...slug]` の場合は配列になりうるためこの型になっている。このリポジトリでは `as string` でキャストしてから `fetchPost` に渡している。

`navigateTo()` と `router.push()` はどちらでも遷移できるが、`navigateTo()` のほうが Nuxt 推奨。ミドルウェアの中でも使え、SSR 時にはリダイレクトのレスポンスを返すなど文脈に応じて振る舞いを変えてくれる。

## 4. `<NuxtLink>`

```vue
<NuxtLink to="/" class="app-title">投稿アプリ</NuxtLink>
<NuxtLink :to="`/posts/${post.id}`">{{ post.body }}</NuxtLink>
```

Next の `<Link href>` に相当。属性名が `href` ではなく **`to`** である点だけ違う(vue-router の `<RouterLink>` の名残)。

振る舞いも似ている。

- **内部リンクなら SPA 遷移**(ページ全体を再読み込みしない)。外部 URL を渡すと自動的に普通の `<a>` になる。
- **画面に入ったリンク先を先読みする。** Next と同じ。無効化は `:prefetch="false"`。
- **現在のページへのリンクには `router-link-active` / `router-link-exact-active` クラスが自動で付く。** ナビゲーションの現在地表示に使える。

## 5. `nuxt.config.ts`

```ts
export default defineNuxtConfig({
  compatibilityDate: '2025-07-15',
  devtools: { enabled: true },
  css: ['~/assets/css/main.css'],
  app: {
    head: {
      title: '投稿アプリ',
      htmlAttrs: { lang: 'ja' },
    },
  },
  nitro: {
    // 開発時: /api を Spring Boot コンテナに転送(CORS 不要にする)
    devProxy: {
      '/api': {
        target: 'http://backend:8080/api',
        changeOrigin: true,
      },
    },
  },
})
```

| 項目 | 意味 |
|---|---|
| `compatibilityDate` | この日付時点の既定の挙動を使う、という固定。Nuxt / Nitro の破壊的変更から守るための仕組み。Next にはない |
| `devtools` | ブラウザに Nuxt DevTools のパネルを出す(→ 後述) |
| `css` | 全ページに読み込むグローバル CSS。`~` は `frontend/app/` を指すエイリアス |
| `app.head` | 全ページ共通の `<title>` と `<html lang>` |
| `nitro.devProxy` | **開発時だけ** `/api` へのリクエストを転送する |

### Nuxt DevTools

`devtools: { enabled: true }` で有効になるのは、**開発サーバー稼働中にブラウザ画面の下端に出る Nuxt アイコンのボタン**。押すとパネルが開き、フレームワークの内部を覗ける。`Shift + Alt + D`(Mac は `Shift + Option + D`)でも開閉する。

ブラウザの開発者ツール(F12)とは別物で、**アプリの画面に重なって表示される Nuxt 専用の画面**。

| タブ | 見えるもの |
|---|---|
| **Pages** | 全ルートの一覧。どのファイルが対応し、どのレイアウト・ミドルウェアが付いているか。URL を入力してマッチを試せる |
| **Components** | 使っているコンポーネントの一覧と出どころ(自分のコード / Nuxt 組み込み / モジュール由来)。依存関係のグラフも出る |
| **Imports** | 自動インポートされている関数の全一覧と、それぞれの出どころ・使用箇所 |
| **Modules** | 入っている Nuxt モジュールの一覧 |
| **Assets** | `public/` と `assets/` のファイル |
| **Server Routes** | `server/` の API 一覧と、その場で叩けるリクエスト送信フォーム(このリポジトリでは空) |
| **Payload** | `useState` / `useAsyncData` が持っている値 |
| **Hooks / Plugins** | Nuxt のフックやプラグインの実行時間 |
| **Terminals** | 開発サーバーの出力 |

**このリポジトリで一番効くのは `Imports` タブ。** `ref` も `onMounted` も `useRoute()` も `usePosts()` も import せずに使えているが、このタブを開くと**登録されている関数が全部一覧で出て、どのファイル由来かも分かる**。自動インポートの正体がそのまま可視化される([composables.md](./composables.md) §5)。

`Pages` タブは §1 の `posts.vue` / `posts/index.vue` の違いを確かめるのに使える。親ルートになっているかどうかがルート一覧に現れる。

#### Vue DevTools とは別物

紛らわしいが 2 つある。

| | 何を見るもの | 形式 |
|---|---|---|
| **Vue DevTools** | コンポーネントの props / state / リアクティビティ、イベント | ブラウザ拡張 |
| **Nuxt DevTools** | ルート、自動インポート、モジュール、ビルドなど**フレームワーク層** | Nuxt に同梱、画面内に表示 |

Nuxt DevTools の中に Vue DevTools が統合されているので、実質こちらだけ開けば足りる(v4.0 からは Vite DevTools とも統合された)。

#### Next.js に同等のものはない

Next.js の公式ドキュメントに開発時 UI として載っているのは **`devIndicators`** — 画面隅の小さなバッジで、そのルートが静的か動的かを示すのとエラー表示程度のもの。ルート一覧・自動インポート・モジュールを 1 つのパネルで見る機能はない。

| Nuxt DevTools のタブ | Next.js での代替 |
|---|---|
| Components | **React DevTools**(ブラウザ拡張、別途インストール) |
| Pages | なし。`app/` のディレクトリを自分で見る |
| Imports | **そもそも不要**(Next に自動インポートがない) |
| Assets | なし |
| Server Routes + 送信フォーム | なし。Postman などを別に使う |
| Modules | なし(Next にモジュール機構がない) |

**この差は、Nuxt のほうが「暗黙にやっていること」が多いことの裏返し。** 自動インポート、`pages/` からのルート生成、`layouts/` の自動適用、`components/` の自動登録、モジュールによる機能追加 — どれもコードに書かれていないため、コードを読んでも全体像が掴めない。その暗黙部分を見せるツールが必要になる。Next.js は `import` を自分で書き、モジュール機構も持たないので、可視化ツールの必要性も低い。

なお **DevTools は本番ビルドに含まれない**ので、`enabled: true` のままでよい。

### `devProxy` が効いている理由

開発時、フロントは `localhost:3000`、バックエンドは `backend:8080` と別オリジンにいる。ブラウザから直接 `http://backend:8080/api/posts` を叩けば CORS 設定が必要になる。

そこで**フロントのコードは相対パス `/api/posts` を叩き、Nuxt の開発サーバーが受けてバックエンドへ中継する**。ブラウザから見れば同一オリジンへのリクエストなので CORS が発生しない。

```
ブラウザ → http://localhost:3000/api/posts   (同一オリジン)
             ↓ Nuxt の開発サーバーが転送
           http://backend:8080/api/posts
```

Next の `next.config.js` の `rewrites` と同じ発想。

**本番ではこの転送は存在しない。** SSG でビルドした静的ファイルを Spring Boot の `static/` に置くため、HTML も API も同じ Spring Boot が返す。最初から同一オリジンになる。つまり `devProxy` は**開発時だけ本番の同一オリジン構成を再現するための仕掛け**。

```ts
$fetch<Timeline>('/api/posts', { params })   // このコードは開発でも本番でもそのまま
```

コードに絶対 URL を書かず相対パスで統一しているのは、この構成を成立させるため。

## 6. ビルドモード

Nuxt には出力の形が 3 つある。

| コマンド / 設定 | 出力 | 本番に必要なもの | Next での相当物 |
|---|---|---|---|
| `nuxt build` | Nitro サーバー(SSR) | Node.js プロセス | `next build` + `next start` |
| `nuxt generate` | 静的 HTML / JS / CSS | 静的ファイルの配信手段だけ | `output: 'export'` |
| `ssr: false` + `nuxt generate` | 空の HTML + JS(SPA) | 同上 | (近いものはない) |

**このリポジトリは `nuxt generate`。** 出力(`.output/public/`)を Spring Boot の `src/main/resources/static/` に置いて配信する。本番に Node.js を置かないための選択で、理由は [../../tech-stack/README.md](../../tech-stack/README.md) に書かれている。

`nuxt generate` は既定で「ビルド時に各ルートを実際に描画して HTML を書き出す」動作をする。この性質がデータ取得の書き方を縛っていて、そこが [data-fetching-and-ssg.md](./data-fetching-and-ssg.md) の主題になる。

## 7. `<head>` の設定

グローバルな設定は `nuxt.config.ts` の `app.head`。ページごとに変えたいときは `useHead()` を呼ぶ。

```ts
useHead({ title: '投稿詳細' })

// SEO 向けの項目をまとめて書く専用の関数もある
useSeoMeta({
  title: '投稿詳細',
  ogTitle: '投稿詳細',
  description: '投稿の詳細ページ',
})
```

Next の `export const metadata = {...}` に当たる。**Next が宣言的(オブジェクトを export する)なのに対し、Nuxt は関数呼び出し**という違いがある。関数なので、`computed` を渡して動的に変えることもできる。

```ts
useHead({ title: computed(() => post.value?.body.slice(0, 20) ?? '投稿') })
```

ただし SSG + クライアント取得の構成では、**ビルド時に生成される HTML の `<title>` は動的な値を含められない**(その時点でデータがないため)。SNS のリンクプレビューを効かせたいなら、SSR かビルド時取得が必要になる。

## 8. まだ使っていないもの

このリポジトリで今後使う予定のある機能を、対応関係だけ挙げておく。

### `middleware/` — 認証ガード(フェーズ 3)

```ts
// app/middleware/auth.ts
export default defineNuxtRouteMiddleware((to, from) => {
  if (!isLoggedIn()) return navigateTo('/login')
})
```

```ts
// 使うページ側
definePageMeta({ middleware: 'auth' })
```

Next の `middleware.ts` に相当するが、**Next がすべてのリクエストをサーバーで受けるのに対し、Nuxt のルートミドルウェアはページ遷移のたびにクライアント側でも走る**。SSG 構成ではサーバー側が存在しないので、実質クライアント側のガードになる。認証の本体はバックエンドのセッションが握る。

### `useState()` — SSR 対応のグローバル状態(フェーズ 3 以降)

```ts
const user = useState<User | null>('user', () => null)
```

Nuxt 組み込みで、**キーで名前空間を分けた ref をアプリ全体で共有する**もの。React Context に近い立ち位置だが、Provider で包む必要がない。

「単に `ref` をモジュールのトップレベルに置けばよいのでは」と思うところだが、SSR ではサーバープロセスが複数リクエストで共有されるため、モジュールスコープの `ref` は**別のユーザーに漏れる**。`useState` はリクエストごとに切り離される。SSG では問題にならないが、作法として `useState` を使う。

これで足りなくなったら Pinia(Vue の標準的な状態管理ライブラリ、Redux / Zustand の位置)を検討する。

### `plugins/` — 起動時の初期化

`app/plugins/*.ts` に置いたファイルが起動時に自動実行される。`$fetch` の共通エラーハンドラを仕込む、といった用途。Next に相当する仕組みはなく、`layout.tsx` やプロバイダで代替していた部分。

### `server/` — 使わない

Nuxt にはリポジトリ直下の `server/` に API を書ける Nitro という仕組みがあり、Next の Route Handlers(`app/api/*/route.ts`)に相当する。

**このリポジトリでは使わない。** API はすべて Spring Boot が `/api/**` で提供する設計で、`nuxt generate` した時点で Nitro サーバー自体が本番に存在しない。`server/` ディレクトリを作らないのは意図的な判断。

---

## 落とし穴

- **`<NuxtLink>` に `href` を書く。** 属性名は `to`。
- **`route.params.id` を数値として扱う。** 型は `string | string[]`。
- **`pages/` を作らずにルーティングを期待する。** `pages/` が無いとルーティング自体が無効。
- **`posts.vue` と `posts/` を並べて `<NuxtPage />` を書き忘れる。** 同名ファイルとディレクトリが揃うと `posts.vue` は親ルートに変わる。子ページが描画されなくなる → §1。
- **一覧ページを `posts.vue` で作る。** 子が増えたときに親ルートへ変質する。素直に `posts/index.vue` にしておく。
- **`app.vue` から `<NuxtPage />` を消す。** ページが描画されなくなる。
- **`nuxt.config.ts` を変えて反映されない。** 開発サーバーの再起動が要る場合がある。
- **`devProxy` が本番でも効くと思う。** 開発専用。本番は Spring Boot が同一オリジンで返す。
- **`server/` に API を書く。** このリポジトリの設計から外れる。API は Spring Boot 側。
- **`useHead` で SEO が効くと思う。** SSG + クライアント取得では、動的な値はビルド後の HTML に入らない。

## 用語集

- **ファイルベースルーティング** — ファイル配置からルート定義を自動生成する方式。Nuxt / Next 共通
- **vue-router** — Vue の公式ルーターライブラリ。Nuxt が内部で使っている
- **親ルートページ** — `pages/posts.vue` のように、同名ディレクトリの子ルートを `<NuxtPage />` で内包するページ。Next の入れ子 `layout.tsx` に相当する
- **ネストルート** — ルートを親子関係で入れ子にする vue-router の仕組み。親のコンポーネントの中に子が描画される
- **Nitro** — Nuxt のサーバーエンジン。開発サーバー・SSR サーバー・`server/` の API を担う。SSG では出力後に不要になる
- **`compatibilityDate`** — その日付時点の既定挙動に固定する設定。フレームワーク更新による挙動変化を防ぐ
- **`definePageMeta`** — ページにレイアウトやミドルウェアを指定するコンパイラマクロ
- **ルートミドルウェア** — ページ遷移の前に走る処理。認証ガードなどに使う
- **devProxy** — 開発サーバーが特定パスへのリクエストを別のサーバーへ中継する機能。CORS を回避する
- **Nuxt DevTools** — 開発時に画面へ重ねて表示される Nuxt 専用のパネル。ルート・自動インポート・モジュールなど、コードに書かれていない仕組みを可視化する。Vue DevTools(コンポーネントの状態を見るブラウザ拡張)とは別物
- **SPA フォールバック** — 静的配信で存在しないパスへのアクセスを、SPA の入口 HTML に流す設定。SSG + 動的ルートで必要になる(フェーズ 11)

## 関連

- 全体像と対応表 → [vue-vs-react-overview.md](./vue-vs-react-overview.md)
- データ取得と SSG → [data-fetching-and-ssg.md](./data-fetching-and-ssg.md)
- 自動インポートの仕組み → [vue-vs-react-overview.md](./vue-vs-react-overview.md) §2 / [composables.md](./composables.md) §5
- ディレクトリ規約の一覧 → [../../development/frontend-structure-best-practices.md](../../development/frontend-structure-best-practices.md)
- SSG を採用した理由 → [../../tech-stack/README.md](../../tech-stack/README.md)
- 実装フェーズ計画 → [../../development/implementation-progress.md](../../development/implementation-progress.md)
- Nuxt 公式「Routing」 https://nuxt.com/docs/getting-started/routing
- Nuxt DevTools 公式 https://devtools.nuxt.com/
