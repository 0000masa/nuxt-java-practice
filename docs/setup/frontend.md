# Nuxt 環境構築手順

フロントエンド(Nuxt 3)の初期構築手順。プロジェクトは `frontend/` に作成する。

## 前提ツール

| ツール | バージョン | 備考 |
|---|---|---|
| Node.js | 22 LTS | ローカルで直接動かす場合。コンテナ内開発なら不要 |
| npm | Node 同梱 | パッケージマネージャーは npm を使う |

## プロジェクト作成

```bash
cd frontend
npx nuxi@latest init . --package-manager npm
npm install
```

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
