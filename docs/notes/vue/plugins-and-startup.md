# プラグインと起動処理 — Next に相当するものはあるか

`frontend/app/plugins/auth.client.ts` を読んで「`defineNuxtPlugin` とは何か。ファイル名の `.client` は何を変えているのか。Next にも同じような仕組みはあるのか」という疑問に答えるメモ。

[nuxt-vs-nextjs.md](./nuxt-vs-nextjs.md) がルーティング・レイアウト・ビルドモードを扱うのに対し、こちらは**アプリが起動してから画面が使えるようになるまでの間に何が走るか**を扱う。[data-fetching-and-ssg.md](./data-fetching-and-ssg.md) §4 が「`nuxt generate` のビルド時に何が起きるか」なので、その続き(ブラウザで起きること)にあたる。

結論を先に言うと:

- **プラグインは「アプリ起動時に 1 回だけ走る初期化コード」。** `app/plugins/` に置くだけで自動登録される。用途は 2 つで、**道具を配る**(`provide`)か、**処理を走らせる**か。
- **実行順序はファイル名のアルファベット順。** このリポジトリの `api.ts` → `auth.client.ts` はこの順序に依存していて、リネームすると壊れる。
- **`.client` は動作を変える接尾辞。** SSG のプリレンダ時に走らせないために必要で、付け忘れるとビルドが不安定になる。
- **Nuxt は非同期プラグインの完了を待ってからアプリをマウントする。** だから「起動時に `await` で認証状態を取り切る」が書ける。
- **Next にも対応物はある。** ただし `instrumentation-client.ts` は**非同期処理を待たない**ので、`auth.client.ts` と同じことは書けない。「Next には相当する仕組みがない」わけでも「同じものがある」わけでもない。

---

## 0. 対応表

| やりたいこと | Nuxt | Next.js (App Router) |
|---|---|---|
| アプリ全体で使う道具を配る | `plugins/xxx.ts` の `provide` → `useNuxtApp().$xxx` | ルート `layout.tsx` の Context Provider / モジュールを `import` |
| ブラウザで起動時に 1 回走らせる | `plugins/xxx.client.ts` | `instrumentation-client.ts`(v15.3 以降) |
| サーバー起動時に 1 回走らせる | `plugins/xxx.server.ts` | `instrumentation.ts` の `register()` |
| 実行順序の制御 | ファイル名順 / `01.` 接頭辞 / `dependsOn` | Provider の入れ子の深さ |
| 非同期処理を待つか | **待つ**(既定は直列実行) | **待たない**(fire-and-forget) |
| ページ遷移のたびに走らせる | `middleware/`(→ [nuxt-vs-nextjs.md](./nuxt-vs-nextjs.md)) | `middleware.ts` / `onRouterTransitionStart` |

最後から 2 行目が一番大きい差で、§6 で扱う。

## 1. アプリが起動してから画面が使えるまで

Nuxt はクライアント側で次の順に処理する(Nuxt Lifecycle)。

```
1. プラグインを実行          ← app/plugins/*
2. ルートの検証
3. ルートミドルウェアを実行   ← app/middleware/*
4. Vue アプリのマウント・ハイドレーション
5. onMounted などの Vue ライフサイクル
```

このリポジトリで実際に起きることを当てはめると次のようになる。

```
静的 HTML が表示される               ← ログイン状態は不明。ヘッダの nav は空
  ↓ JS がロードされる
plugins/api.ts        $api を用意する(通信はまだ発生しない)
  ↓
plugins/auth.client.ts  await /api/auth/me → store.set(user) → resolved = true
  ↓
middleware/auth.ts    auth.isLoggedIn を見てガードする
  ↓
Vue アプリのマウント・ハイドレーション  ← ヘッダが「ログアウト」を出す
```

ここから 2 つのことが分かる。

**(a) ルートミドルウェアはログイン状態を信用してよい。** `middleware/auth.ts:13` は `auth.isLoggedIn` を見るだけで `resolved` を確認していない。これは手抜きではなく、**プラグインがミドルウェアより先に完了することが Nuxt のライフサイクルで保証されている**から成り立っている。

**(b) それでも `resolved` は必要になる。** 一見すると、マウント時点で状態は確定済みなのだから `stores/auth.ts:20` の `resolved` は要らないように見える。しかし SSG では **1 の前に静的 HTML が表示されている**。その HTML はビルド時に生成されたもので、`.client` プラグインが走っていない = `resolved` が `false` の状態で描画されている。

```html
<!-- layouts/default.vue:20 の v-if="auth.resolved" が false のまま出力される -->
<nav> ... </nav>   ← この要素自体が静的 HTML に入らない
```

もし `resolved` を見ずに `auth.user` の有無だけで分岐したら、**静的 HTML には必ず「ログイン」「登録」が焼き込まれる**。ログイン済みのユーザーには、一瞬それが見えてから「ログアウト」に入れ替わる。`resolved` が守っているのは**マウント後ではなくプリレンダ出力のほう**だと理解すると腑に落ちる。

## 2. `defineNuxtPlugin` とは何か

```ts
// frontend/app/plugins/auth.client.ts:14
export default defineNuxtPlugin(async () => {
  // ...
})
```

`defineNuxtPlugin()` は、**渡した関数を「Nuxt プラグイン」として印を付けて包む関数**。実行時にしていることはほとんどなく、目的は 2 つ。

- **型が効く。** 引数として受け取れる `nuxtApp` の型が付き、`provide` の戻り値も型として拾われる
- **ビルド時に Nuxt が解析できる。** §4 の `dependsOn` などのオプションが解釈される

`defineNuxtRouteMiddleware` / `definePageMeta` / `defineStore` と同じ発想で、**このコードがどういう役割かをフレームワークに宣言する**ためのもの。React 側に対応物がないのは、Next がファイルの役割を「ファイル名」だけで決めているから(`layout.tsx` / `page.tsx` / `middleware.ts`)。

Nuxt はプラグインファイルの**デフォルトエクスポートを 1 つだけ**読む。名前付きエクスポートは無視される。

引数を使う場合はこう書く(このリポジトリでは使っていない)。

```ts
export default defineNuxtPlugin((nuxtApp) => {
  nuxtApp.hook('app:mounted', () => { /* マウント後に走る */ })
})
```

## 3. プラグインの 2 つの用途

このリポジトリのプラグインは 2 つあり、**役割が正反対**になっている。プラグインの用途がちょうど 1 つずつ現れている。

### (a) 道具を配る — `provide` と `$api`

```ts
// frontend/app/plugins/api.ts:38
return { provide: { api } }
```

プラグインが `provide` を含むオブジェクトを返すと、その中身が **`$` を頭に付けた名前**で `useNuxtApp()` から取り出せるようになる。`api` で提供すれば `$api`。

```ts
// frontend/app/composables/useAuth.ts:10
const { $api } = useNuxtApp()
```

`$` はプラグイン由来の共有物であることを示す Nuxt の規則で、自動で付く。**import 文がどこにも無いのにアプリ全体から使える**のはこの仕組みによる。必要なものを自分で作らず外から受け取る形なので、**DI(依存性の注入)** にあたる。

`$api` の中身は `$fetch` のラッパで、リクエストのたびに 2 つのことをする。

- CSRF トークンをヘッダに載せる(`api.ts:17-21`)。中身 → [../browser/csrf.md](../browser/csrf.md)
- セッション切れ(401)をまとめて扱う(`api.ts:24-35`)

だから**素の `$fetch` を直接呼んではいけない**。1 箇所でも直書きすると、そこだけ CSRF トークンが付かず 403 になる。この方針は [usePosts.ts:4-7](../../../frontend/app/composables/usePosts.ts) のコメントにも書かれている。

### (b) 処理を走らせる — `auth.client.ts`

```ts
// frontend/app/plugins/auth.client.ts:14-21
export default defineNuxtPlugin(async () => {
  try {
    await useAuth().fetchMe()
  } catch {
    useAuthStore().set(null)
  }
})
```

SSG ではサーバー側にセッションを見る機会がないので、**ブラウザで動き出してから `/api/auth/me` を叩いてログイン状態を復元する**しかない。それをここでやっている。

この API を起動時に必ず 1 回叩く構成には副産物がある。**このレスポンスと一緒に `XSRF-TOKEN` Cookie が発行される**ため、「ログイン前なので CSRF トークンが無く、ログインリクエストが 403 になる」という問題が起きない(spec 決定 14)。

`catch` が担っているのは、エラーの握りつぶしではなく**結論を出すこと**。`resolved` が `true` になるのは `stores/auth.ts:27` の `set()` が呼ばれたときだけなので、ここで `set(null)` を書かないと、バックエンドが落ちているときに `resolved` が永久に `false` のままになる。`layouts/default.vue:20` は `v-if="auth.resolved"` なので、**ヘッダの導線が何も表示されないアプリ**になる。エラーも出ず、ただ何も起きない。

### なぜ状態(Pinia)ではなくプラグインに置くのか

「起動時に 1 回」の処理をストアやレイアウトに書くと、ページ遷移のたびに走る。プラグインは**アプリの起動につき 1 回**という保証があるので、初期化はここに置く。逆に「ページ遷移のたび」に必要なガードは `middleware/` に置く。

## 4. 実行順序 — ファイル名順と `dependsOn`

**プラグインはファイル名のアルファベット順に登録される。** 番号を前置して制御することもできる(`01.api.ts` / `02.auth.client.ts`。文字列としてソートされるので `1.` ではなく `01.` と 0 を付ける)。

このリポジトリには依存が 1 本ある。

```
plugins/api.ts          $api を provide する          ← 先に走る必要がある
plugins/auth.client.ts  useAuth() 経由で $api を使う
```

`a-p-i` < `a-u-t-h` なので、たまたま正しい順序になっている。`auth.client.ts:11-12` に「ファイル名が api.ts より後ろなのは意図的」と書いてあるのは、**コードを読んでも分からない情報**だから。`session.client.ts` のようにリネームした瞬間に、`$api` が未定義で落ちる。

### 非同期プラグインは待たれる

**既定では Nuxt はプラグインを直列に実行する。** `async` なプラグインは、その完了まで後続のプラグインを待たせる。`parallel: true` を指定すると待たれなくなる。

`auth.client.ts` が `async` であることには意味がある。`/api/auth/me` の往復が終わるまで次に進まないので、**ミドルウェアが動く時点でもマウントされる時点でも、ログイン状態は確定済み**になる(§1)。

### 明示したい場合はオブジェクト形式

ファイル名に頼らず依存関係を書く形もある。

```ts
// plugins/api.ts
export default defineNuxtPlugin({
  name: 'api',
  setup() { /* ... */ return { provide: { api } } },
})

// plugins/auth.client.ts
export default defineNuxtPlugin({
  name: 'auth',
  dependsOn: ['api'],          // name で指定する。ファイル名ではない
  async setup() { /* ... */ },
})
```

指定できる主なもの。

| プロパティ | 意味 |
|---|---|
| `name` | プラグインの識別名。`dependsOn` から参照される |
| `dependsOn` | 先に完了させたいプラグインの `name` の配列 |
| `enforce` | `'pre'` / `'post'`。ファイル名順より優先して前後に寄せる |
| `parallel` | `true` にすると完了を待たずに次へ進む |
| `setup()` | プラグイン本体。`async` にできる |
| `hooks` | Nuxt のランタイムフックをまとめて登録する |

**このリポジトリでは採用していない。** プラグインが 2 つで依存が 1 本の現状では、`name` の追加分だけ記述が増えて守るものが少ない。プラグインが増えて順序が追えなくなったら移行する。

## 5. `.client` / `.server` — SSG との関係

ファイル名の接尾辞で実行場所が変わる。

| ファイル名 | 実行される場所 |
|---|---|
| `api.ts` | サーバー(プリレンダ)とブラウザの両方 |
| `auth.client.ts` | **ブラウザのみ** |
| `xxx.server.ts` | サーバーのみ。SSG では**ビルド時**のみ |

`auth.client.ts` に `.client` が要る理由は SSG にある。`npm run generate` は Node.js 上で全ページを描画して HTML を書き出すので、`.client` が無いと**ビルドマシンの上で `/api/auth/me` を叩こうとする**。

- ビルドマシンにユーザーの Cookie は無い。聞くまでもなく未ログインしか返らない
- ビルド時にバックエンドが起動している保証もない。CI で `npm run generate` するだけなら接続できず、毎回 `catch` に落ちる

これは `middleware/auth.ts:10` の `if (import.meta.server) return` と同じ判断で、あちらはコード内の分岐、こちらはファイル名で表現している。**SSG では「ブラウザ前提の処理をビルド時に走らせない」が繰り返し出てくる論点**になる。

なお `.server` プラグインは、SSG では本番リクエスト時ではなく**ビルド時にしか走らない**。`nuxt generate` した時点で Nitro サーバーは本番に存在しない(→ [nuxt-vs-nextjs.md](./nuxt-vs-nextjs.md) §6)。

## 6. Next.js に相当するものはあるか

用途ごとに答えが違う。

### (a) 道具を配る → Provider、または素の `import`

Next に `provide` / `useNuxtApp` にあたる仕組みは無い。ルート `layout.tsx` を Context Provider で包むのが定石になる。

```tsx
// app/layout.tsx
export default function RootLayout({ children }) {
  return (
    <html lang="ja">
      <body>
        <QueryProvider>
          <AuthProvider>{children}</AuthProvider>
        </QueryProvider>
      </body>
    </html>
  )
}
```

順序を表現する方法が根本的に違う。**Nuxt はファイル名(または `dependsOn`)の一次元、Next は入れ子の深さ**。上の例では `QueryProvider` が外側なので先に用意される。

そもそも Next では、クライアント専用のものならモジュールのトップレベルに置いて `import` するだけで足りる場面が多い。

```ts
// lib/api.ts
export const api = createApiClient()   // import した全員が同じインスタンスを共有する
```

**Nuxt がこれをやらないのは SSR のため。** サーバープロセスは複数リクエストで共有されるので、モジュールスコープに状態を置くと**別のユーザーに漏れる**。`provide` / `useNuxtApp` はリクエストごとに切り離された入れ物を用意する仕組みで、Nuxt 組み込みの `useState` が必要な理由(→ [nuxt-vs-nextjs.md](./nuxt-vs-nextjs.md) §8)と同じ根っこにある。SSG しかしないこのリポジトリでは実害の出ない話だが、作法としてこちらに従っている。

### (b) サーバー起動時に 1 回 → `instrumentation.ts`

```ts
// instrumentation.ts(プロジェクトルート)
export function register() {
  registerOTel('next-app')
}
```

> This function will be called **once** when a new Next.js server instance is initiated, and must complete before the server is ready to handle requests.

**サーバーインスタンスの起動につき 1 回**で、完了までリクエストを受け付けない。Nuxt の `.server` プラグインに近いが、Nuxt 側が「リクエストごとのアプリ生成のたび」に走るのに対し、こちらは**プロセス起動時に 1 回だけ**。粒度が違う。用途も OpenTelemetry の初期化などに寄っている。

### (c) ブラウザ起動時に 1 回 → `instrumentation-client.ts`

Next 15.3 で入った。タイミングは Nuxt のクライアントプラグインとほぼ同じ。

> 1. **After** the HTML document is loaded
> 2. **Before** React hydration begins
> 3. **Before** user interactions are possible

ただし**決定的な違いがある**。

> Only synchronous, top-level code is guaranteed to complete before hydration. Asynchronous work started here (a `Promise`, `import()`, or top-level `await`) is not awaited and may resolve after hydration has begun, so treat it as **fire-and-forget**.

**Next 側は非同期処理を待たない。** つまり `auth.client.ts` と同じもの——「`await` で `/api/auth/me` を取り切ってからアプリを動かす」——は `instrumentation-client.ts` では書けない。ハイドレーションが先に始まってしまう。

| | Nuxt `.client` プラグイン | Next `instrumentation-client.ts` |
|---|---|---|
| 走るタイミング | ハイドレーション前 | ハイドレーション前(同じ) |
| 非同期を待つか | **待つ**(`parallel: true` で待たなくできる) | **待たない**(常に fire-and-forget) |
| 主な用途 | 初期化全般。状態の復元にも使える | 監視・計測・ポリフィル |

Next で「起動時に認証状態を確定させてから描画する」をやるなら、選択肢は次のようになる。

- **サーバー側で取る** — RSC でリクエスト時に Cookie を読んでレンダリングする。App Router で最も素直な方法だが、**サーバーが要る**ので SSG のこのリポジトリでは選べない
- **Provider + Suspense** — クライアントで取り、解決するまで `fallback` を出す
- **`useEffect` で取る** — 取得中の状態を各コンポーネントが自分で扱う。このリポジトリの `resolved` に近い形

つまり**「Next には相当する仕組みが無い」も「同じものがある」も正しくない**。ファイル規約としては存在するが、非同期を待つかどうかが違うので、置き換えるとアプリの起動の形が変わる。

---

## 落とし穴

- **プラグインのファイル名を変える。** 実行順序が変わる。`api.ts` より前に来る名前にすると `$api` が未定義になる → §4
- **`.client` を付け忘れる。** `nuxt generate` のビルド時にもブラウザ前提の処理が走る。ビルドマシンからバックエンドに繋がらず毎回失敗する → §5
- **`.server` プラグインが本番リクエスト時に走ると思う。** SSG ではビルド時にしか走らない → §5
- **`parallel: true` を安易に付ける。** 後続が完了を待たなくなる。`auth.client.ts` に付けると、ログイン状態が確定する前にミドルウェアとマウントが進む → §4
- **`catch` で何もしない。** `set()` が呼ばれないと `resolved` が `false` のままになり、ヘッダの導線が永久に出ない → §3(b)
- **プラグインの中でも `await` の後に `useNuxtApp()` 系を呼ぶ。** `auth.client.ts` が `useAuth()` を `await` の**前**に呼んでいるのは偶然ではない → [composables.md](./composables.md) §4
- **`$fetch` を直書きする。** CSRF トークンが載らず、書き込み系が 403 になる。必ず `$api` を通す → §3(a)
- **`dependsOn` にファイル名を書く。** 指定するのは `name` プロパティの値 → §4
- **番号前置を `1.` `2.` と書く。** 文字列としてソートされるので `10.` が `2.` より前に来る。`01.` と 0 を付ける → §4
- **`resolved` を余計なものだと思う。** マウント時には確定済みだが、守っているのはその前の**プリレンダ出力** → §1

## 用語集

- **プラグイン** — アプリの起動時に 1 回だけ走る初期化コード。`app/plugins/` に置くと自動登録される
- **`defineNuxtPlugin`** — 渡した関数を Nuxt プラグインとして宣言する関数。型付けとビルド時の解析のために要る
- **`provide`** — プラグインが返すオブジェクトのキー。中身が `useNuxtApp()` から取り出せるようになる
- **`$` 接頭辞** — `provide` された道具に自動で付く印。プラグイン由来の共有物であることを示す
- **DI(依存性の注入)** — 必要なものを自分で作らず外から受け取る設計。差し替えとテストが利く
- **`dependsOn`** — 先に完了させたいプラグインを `name` で指定するオプション。ファイル名順より確実
- **`parallel`** — `true` にすると、そのプラグインの完了を待たずに後続へ進む
- **`enforce`** — `'pre'` / `'post'` で、ファイル名順より優先して実行位置を前後に寄せる
- **`.client` / `.server`** — 実行場所を限定するファイル名の接尾辞。付けなければ両方で走る
- **プリレンダ** — ビルド時にサーバー上でページを描画して HTML を書き出すこと。`.client` は走らない
- **ハイドレーション** — 出力済みの HTML に、ブラウザ側の JS がイベントリスナを取り付けて操作可能にする処理
- **`instrumentation.ts`** — Next のサーバー起動時に 1 回走るファイル。`register()` をエクスポートする
- **`instrumentation-client.ts`** — Next 15.3 以降。ハイドレーション前にブラウザで走る。**非同期処理は待たれない**
- **fire-and-forget** — 開始するだけで完了を待たない実行の仕方

## 関連

- `useNuxtApp` を呼べるタイミングの制約 → [composables.md](./composables.md) §4
- ルーティング・レイアウト・ビルドモード → [nuxt-vs-nextjs.md](./nuxt-vs-nextjs.md)
- `nuxt generate` のビルド時に何が起きるか → [data-fetching-and-ssg.md](./data-fetching-and-ssg.md) §4
- クロージャ(プラグインが `$api` を抱え込む仕組み)→ [../functions-as-values.md](../functions-as-values.md)
- CSRF トークンの中身 → [../browser/csrf.md](../browser/csrf.md)
- 認証まわりの設計判断(決定 10: Pinia / 決定 14: `GET /api/auth/me` は公開)→ [../../superpowers/specs/2026-08-05-phase3-auth-design.md](../../superpowers/specs/2026-08-05-phase3-auth-design.md)
- ディレクトリ規約・状態をどこに置くか → [../../development/frontend-structure-best-practices.md](../../development/frontend-structure-best-practices.md)
- Nuxt 公式「Plugins」 https://nuxt.com/docs/guide/directory-structure/plugins
- Nuxt 公式「Nuxt Lifecycle」 https://nuxt.com/docs/guide/concepts/nuxt-lifecycle
- Next.js 公式「instrumentation-client.js」 https://nextjs.org/docs/app/api-reference/file-conventions/instrumentation-client
- Next.js 公式「How to set up instrumentation」 https://nextjs.org/docs/app/guides/instrumentation
