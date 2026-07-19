# Nuxt(SSG)プロジェクト構成のベストプラクティス

`nuxt generate`(SSG)で配信し、出力を Spring Boot の `static/` に置く本プロジェクトの frontend 構成の参考資料。
Web 調査(Nuxt 公式ドキュメント + コミュニティ記事)の結果をまとめたもの。実際の構成を決める際はこの資料を参照する。

前提: API はすべて `/api/**`(バックエンドの Spring Boot が担当)、フロントは相対パス `/api` を devProxy 経由で叩く。

> **注記(2026-07-19)**: 本リポジトリの frontend は **Nuxt 4**(`nuxt: ^4.4.8`)。本書は Nuxt 3 時点の調査だが、考え方はそのまま通用する。主な違いは、アプリ側コード(`pages/` `components/` `composables/` `layouts/` `middleware/` `plugins/` `utils/` `stores/` `types/` など)を **`app/` ディレクトリ配下**に置くこと。`public/` `server/` `shared/` `nuxt.config.ts` はルート直下のまま。

---

## 1. Nuxt 3 の標準ディレクトリ構成と役割

Nuxt はディレクトリ名に規約を持たせ、多くを**自動インポート**する(手動 import 不要)。

| ディレクトリ | 役割 | 自動インポート |
|---|---|---|
| `pages/` | ファイルベースルーティング。`.vue` ファイル/フォルダ構造がそのまま URL になる。`pages/index.vue`→`/`、`pages/users/[id].vue`→`/users/:id` | ルート自動生成 |
| `components/` | 再利用 Vue コンポーネント。テンプレート内でタグとして直接使える | ○(コンポーネント名) |
| `composables/` | Composition API による再利用ロジック(`useXxx`) | ○(**トップレベルのみ**) |
| `layouts/` | ページを包む共通レイアウト(ヘッダ/フッタ等)。`default.vue` が既定 | ○ |
| `middleware/` | ルート遷移前に実行する処理(認証ガード等) | ○(名前指定で参照) |
| `plugins/` | アプリ生成時に実行。Vue プラグイン登録・グローバル設定 | ○(ファイル配置で自動登録) |
| `utils/` | 純粋関数などの共通ユーティリティ。components/composables/pages から使える | ○ |
| `assets/` | Vite が**ビルド処理する**アセット(SCSS、最適化対象画像など) | - |
| `public/` | **ビルド処理せず**そのまま配信される静的ファイル(favicon、robots.txt 等) | - |
| `server/` | Nitro のサーバ API・ルート・ミドルウェア(`server/api/**` 等) | サーバ側で○ |
| `shared/` | Vue アプリと Nitro サーバの**両方**から使えるコード(型・定数など) | ○(Nuxt 3.14+) |
| `modules/` | プロジェクトローカルの Nuxt モジュール | 自動登録 |
| `layers/` | 再利用可能なコード群(components/composables/config をまとめて共有) | 自動登録 |

主要ファイル: `app.vue`(ルートコンポーネント)、`error.vue`(エラーページ)、`app.config.ts`(リアクティブな実行時設定)、`nuxt.config.ts`(ビルド/フレームワーク設定)。

**自動インポートの仕組み**: Nuxt が `components/` `composables/` `utils/` を走査し、`.nuxt/` に型定義とインポート文を生成する。VS Code で型が出ないときは `nuxt prepare` / `nuxt dev` / `nuxt build` のいずれかを一度走らせると解消する。

---

## 2. `components/` の整理方法

### 自動命名(ネスト時のプレフィックス)

ファイルパス + ファイル名からコンポーネント名が決まり、**重複するパスセグメントは除去**される。

- `components/Button.vue` → `<Button />`
- `components/form/Input.vue` → `<FormInput />`
- `components/base/foo/Button.vue` → `<BaseFooButton />`
- `components/user/profile/Card.vue` → `<UserProfileCard />`

> パスプレフィックスを付けたくない場合は `nuxt.config.ts` の `components: [{ path: '~/components', pathPrefix: false }]` で無効化できるが、**名前衝突を防ぐためプレフィックスは有効のまま推奨**。

### 機能別 vs 種類別

- 小〜中規模: **種類別**(`components/base/`、`components/form/`、`components/layout/`)で十分
- 中〜大規模: **機能(ドメイン)別**が保守しやすい(`components/user/`、`components/post/` に、そのドメインのカードやフォームをまとめる)。UI 部品だけ `components/ui/`(または `base/`)に共通化する折衷案が実践的

### 命名規則

- **PascalCase**(複数単語)を推奨。`components/UserCard.vue`。単一単語コンポーネントは避ける(HTML 標準要素との衝突回避)
- Vue 公式のオーダー: Base コンポーネントは `Base` / `App` / `V` 等の共通プレフィックスを付ける

### 特殊サフィックス/プレフィックス

- `Xxx.client.vue` → **クライアントのみ**でレンダリング(ブラウザ API 依存の部品)。SSG で特に有用
- `Xxx.server.vue` → サーバのみ(実験的、`componentIslands` 必要。SSG では通常使わない)
- `<LazyXxx />` → `Lazy` プレフィックスで**遅延ロード**(必要になるまでコード読み込みを遅らせる)

---

## 3. `composables/` の設計指針

### 自動インポートの範囲に注意

Nuxt は **`composables/` の直下(トップレベル)だけ**を走査する。サブディレクトリは既定で無視されるので、次のどちらかが必要:

1. `composables/index.ts` で re-export する(推奨・シンプル)
2. `nuxt.config.ts` の `imports.dirs` に `'~/composables/**'` を追加する

### 命名規則

`use` プレフィックス + camelCase。`composables/useAuth.ts` が `useAuth()` を export。

### API 呼び出しロジックの置き場所

**「API 通信は composables に集約する」のが定石**。ページ/コンポーネントに `$fetch` を直書きせず、`useUsers()` のようなドメイン別 composable にまとめる。

推奨パターン: `useFetch` の戻り値の形(`data` / `error` / `status` / `refresh`)をラップして返し、呼び出し側の一貫性とペイロードのシリアライズを保つ。

```ts
// composables/useUsers.ts
export function useUsers() {
  // 一覧: 画面初期表示のデータ取得は useFetch/useAsyncData
  const list = () => useFetch('/api/users')

  // 作成: イベント起点(ボタン押下)の書き込みは $fetch
  const create = (body: UserInput) =>
    $fetch('/api/users', { method: 'POST', body })

  return { list, create }
}
```

### `useFetch` / `useAsyncData` / `$fetch` の使い分け(最重要)

| | 用途 | SSR/プリレンダ安全 |
|---|---|---|
| `useFetch` | **画面初期表示**のデータ取得(setup 内で URL を渡すだけ) | ○(サーバで1回だけ取得しペイロードでクライアントへ受け渡し) |
| `useAsyncData` | カスタムな非同期ロジックを包む(複数リクエスト合成、外部クエリ層、SDK 呼び出し等) | ○ |
| `$fetch` | **イベント起点の単発通信**(フォーム送信、削除ボタン等) | ×(単独で SSR に使うと二重取得=ハイドレーション不整合。初期取得には使わない) |

原則: **「setup 中の初期データ = `useFetch` / `useAsyncData`」「ユーザー操作後の通信 = `$fetch`」**。`useAsyncData` の中で `$fetch` を呼ぶのは正しい使い方。

---

## 4. TypeScript 型定義の置き場所

- 慣習として **`types/` ディレクトリ**(プロジェクト直下)にドメイン型・インターフェイスを置く。`types/user.ts` に `export interface User {}` など
- 型は自動インポート対象外なので、`types/` からの明示 import が明快
- グローバル拡張(`.d.ts` によるモジュール拡張・型宣言)はルートまたは `types/` に配置。カスタムディレクトリなら `tsconfig.json` の `typeRoots` / `compilerOptions` 調整が要る場合あり
- API のリクエスト/レスポンス型は、その API を呼ぶ composable の近く(`composables/` 内 or `types/api/`)に置くと保守しやすい
- 型を頻繁に使い回すなら `nuxt.config.ts` の `imports` に型ディレクトリを追加して自動インポート対象にする手もある

> 補足: **Nuxt 4** ではコンテキスト別に配置(app 用は `app/`、server 用は `server/`、両方共有は `shared/`)する方針。本プロジェクトは Nuxt 4 なので、ドメイン型は `app/types/` に集約する。

---

## 5. 中規模 SPA/SSG での実践的な構成例(認証・API・Pinia 込み)

```
frontend/
├── assets/
│   └── css/
│       └── main.css
├── components/
│   ├── base/                # 汎用UI(BaseButton.vue, BaseModal.vue…)
│   ├── layout/              # AppHeader.vue, AppFooter.vue
│   └── user/                # ドメイン別(UserCard.vue, UserForm.vue…)
├── composables/
│   ├── useAuth.ts           # 認証状態・ログイン/ログアウト
│   ├── useUsers.ts          # /api/users 通信の集約
│   └── useApi.ts            # $fetch 共通ラッパ(baseURL・エラー処理)
├── layouts/
│   ├── default.vue
│   └── auth.vue             # ログイン画面用など
├── middleware/
│   └── auth.ts              # 未認証リダイレクト
├── pages/
│   ├── index.vue
│   ├── login.vue
│   └── users/
│       ├── index.vue
│       └── [id].vue
├── plugins/
│   └── api.ts               # $fetch インスタンス初期化など
├── stores/                  # Pinia
│   ├── auth.ts
│   └── user.ts
├── types/
│   ├── user.ts
│   └── api.ts
├── utils/
│   └── formatDate.ts
├── public/
│   └── favicon.ico
├── app.vue
├── error.vue
├── nuxt.config.ts
└── tsconfig.json
```

### 状態管理(Pinia)と composables の役割分担

- **Pinia ストア(`stores/`)**: アプリ全体で共有するグローバル状態(ログインユーザー、権限など)。`@pinia/nuxt` モジュール導入で `stores/` を自動インポート
- **composables**: 画面/機能スコープの再利用ロジック・API 通信ラッパ。状態を持ちすぎず「ロジックの束」に
- 目安: **「複数画面で共有する状態 → Pinia」「取得・変換ロジックや局所状態 → composable」**

### 大規模化への備え

ドメインが増えるなら `layers/`(または modules)でドメイン単位に components/composables/pages をまとめて分割する方式が有効。中規模の段階では上記のフラット構成で十分。

---

## 6. SSG モード特有の注意点(このプロジェクトで最重要)

### `server/` ディレクトリ・サーバ API は SSG では使えない

**`nuxt generate`(= `nuxt build --prerender`)で生成すると出力にサーバが含まれないため、`server/api/**` などのサーバエンドポイントは動かない。** 静的 HTML + `payload.json` が `.output/public/` に吐かれるだけ。

→ 本プロジェクトは **API を Spring Boot(`/api/**`)が担当**し、Nuxt の `server/` は使わない構成なので、この制約と方針が完全に一致する。Nuxt 側で `server/api` を作らないこと。動的データは Spring Boot の `/api` を `useFetch('/api/...')` で叩く。

### `ssr: false`(SPA モード)と `nuxt generate`(SSG)は別物

- `nuxt generate`(既定 `ssr: true`): ビルド時に各ルートを**プリレンダ**して静的 HTML を生成。SEO に有利
- `ssr: false`: `index.html` + JS バンドルのみの純クライアント SPA。プリレンダの SEO 恩恵を失う。SSG を選ぶなら `ssr: true` のままにする
- ブラウザ API 依存でサーバレンダ不可の部分は `<ClientOnly>` や `*.client.vue` で囲う

### 動的ルートのプリレンダ

`pages/users/[id].vue` のような動的ルートを静的化するには、ビルド時にパス一覧を取得して Nitro に渡す:

- `nuxt.config.ts` の `nitro.prerender.routes` に列挙、または
- `prerender:routes` フック(推奨)で API からパスを取得し `ctx.routes` に追加
- 事前に全パスを列挙できない場合は SPA フォールバック(`nitro.prerender.crawlLinks` + fallback)を検討

### 初期データ取得

プリレンダ時、`useFetch` / `useAsyncData` は**ビルド時にサーバ側で実行**され、結果が payload に埋め込まれる。ただし本構成では取得先が別サーバ(Spring Boot)なので、**「ビルド時にバックエンドが起動している必要があるデータ」はビルドタイミングに注意**。ユーザーごとに変わる/ビルド時に確定できないデータは、クライアント側取得(`<ClientOnly>` やマウント後の `$fetch`)にする判断が必要。

---

## 出典

- Nuxt 公式: [Directory Structure](https://nuxt.com/docs/3.x/directory-structure)
- Nuxt 公式: [components](https://nuxt.com/docs/3.x/directory-structure/components)
- Nuxt 公式: [composables](https://nuxt.com/docs/3.x/directory-structure/composables)
- Nuxt 公式: [Data Fetching(useFetch/useAsyncData/$fetch)](https://nuxt.com/docs/3.x/getting-started/data-fetching)
- Nuxt 公式: [Prerendering](https://nuxt.com/docs/3.x/getting-started/prerendering)
- Nuxt 公式: [Deployment(Static Hosting)](https://nuxt.com/docs/3.x/getting-started/deployment)
- Nuxt 公式: [Rendering Modes](https://nuxt.com/docs/3.x/guide/concepts/rendering)
- Nuxt 公式: [TypeScript](https://nuxt.com/docs/3.x/guide/concepts/typescript)
- Nuxt 公式(参考・Nuxt 4 の app/ 構成): [Directory Structure v4](https://nuxt.com/docs/4.x/directory-structure)
- Vue School: [Understanding the Directory Structure in Nuxt 3](https://vueschool.io/articles/vuejs-tutorials/understanding-the-directory-structure-in-nuxt-3/)
- DEV Community: [Using Modules and Pinia to structure Nuxt 3 app](https://dev.to/jacobandrewsky/using-modules-and-pinia-to-structure-nuxt-3-app-5963)
- Medium(Sultonkhon Oblokulov): [Folder structure for Nuxt 3](https://medium.com/@sultondev/folder-structure-for-nuxt-3-478d147452ba)
- DEV Community(Rafael Magalhaes): [Generating Dynamic Routes for SSG with Nuxt 3](https://dev.to/rafaelmagalhaes/generating-dynamic-routes-for-static-site-generation-with-nuxt-3-1epi)
