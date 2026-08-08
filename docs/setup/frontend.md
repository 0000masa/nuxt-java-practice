# Nuxt 環境構築手順

フロントエンドの初期構築手順。プロジェクトは `frontend/` に作成する。

> 補足: 現行の `nuxi`(3.37 時点)が生成するのは **Nuxt 4** ベースのプロジェクト。本リポジトリのドキュメントで「Nuxt 3」と書いていた箇所は Nuxt 4 に読み替える(SSG や devProxy などこのリポジトリの方針はどちらでも同じ)。

> Vue / Nuxt が初めてなら、コードの読み方は [docs/notes/vue/](../notes/vue/vue-vs-react-overview.md) にまとめてある(React / Next.js との比較つき)。

## 前提ツール

> PC 自体のセットアップ(WSL2 / Docker Desktop / VS Code / git / GitHub 接続)から始める場合は → [docs/setup/new-machine.md](./new-machine.md)

| ツール | バージョン | 備考 |
|---|---|---|
| Node.js | 22 LTS | ローカルで直接動かす場合。コンテナ内開発なら不要 |
| npm | Node 同梱 | パッケージマネージャーは npm を使う |

## プロジェクト作成

通常のターミナル(対話式)で実行する:

```bash
cd frontend
rm -f .gitkeep        # 空フォルダ維持用のファイル。プロジェクトを作るのでもう不要
npx nuxi@latest init .
```

※ 旧手順にあった `--package-manager npm` はフラグ名が誤り(正しくは `--packageManager`)。対話で選ぶ方が確実なので、フラグなしで実行して以下の質問に答える。

### 対話で聞かれる項目と選択

バージョンによって文言や順序は多少変わるが、聞かれる内容は次のとおり。

| 質問 | 意味 | 選択 |
|---|---|---|
| テンプレート選択(content / minimal / module / ui / v5-nightly) | ひな形の種類。詳細は [docs/notes/nuxi-templates.md](../notes/nuxi-templates.md) | **minimal**(最小構成のアプリ。このリポジトリは API + 画面のシンプルな構成なので余計なものが入らないこれを選ぶ) |
| パッケージマネージャー(npm / pnpm / yarn / bun / deno) | 依存ライブラリの管理ツール | **npm**(このリポジトリの方針) |
| 依存関係をインストールするか | 生成直後に `npm install` 相当を実行するか | **Yes**(No にした場合はあとで `npm install` を手動実行) |
| git リポジトリを初期化するか(Initialize git repository?) | `frontend/` 内に新しく `.git` を作るか | **No**(リポジトリ直下で既に git 管理しており、入れ子の git を作ると管理が壊れるため) |
| 公式モジュールの追加(聞かれる場合のみ) | ESLint 等の公式モジュールを最初から入れるか | **何も選ばず Enter**(必要になったら後から追加できる) |

### 非対話(CI やスクリプト)で実行する場合

対話に答えられない環境では、すべてフラグで指定する:

```bash
npx nuxi@latest init . -t minimal --packageManager npm --no-gitInit -f
```

- `-t minimal` — テンプレート指定
- `--no-gitInit` — git 初期化しない
- `-f` — 既存ファイル(`.gitkeep` など)があっても続行

## SSG モードの設定

本プロジェクトは SSG(`nuxt generate`)で静的ファイルを生成し、Spring Boot の static/ から配信する方針(理由は [docs/tech-stack/README.md](../tech-stack/README.md) を参照)。

`nuxt.config.ts` に開発用プロキシとあわせて設定する:

```ts
export default defineNuxtConfig({
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

- API 呼び出しは相対パス(`/api/...`)で書く。開発では devProxy が、本番では同一オリジン配信が解決するため、環境ごとの API URL 切り替えが不要になる
- SSG のため、ページで DB 由来の動的データを使う場合はクライアント側フェッチ(`useFetch` の client オプション等)で取得する

## 開発サーバーの起動

```bash
npm run dev      # http://localhost:3000
```

docker-compose 環境では nuxt コンテナがこれを実行する(HMR 付き)。

## 本番用ビルド(SSG)

```bash
npm run generate
```

- 出力先: `.output/public/`
- この中身を Spring Boot の `src/main/resources/static/` にコピーして配信する(CI ではイメージビルド時にコピーする)

## 動作確認

1. `npm run dev` で `http://localhost:3000` にトップページが表示されること
2. `npm run generate` が成功し、`.output/public/index.html` に**中身入りの HTML**(空でない)が生成されること
