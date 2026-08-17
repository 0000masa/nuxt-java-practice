// https://nuxt.com/docs/api/configuration/nuxt-config
export default defineNuxtConfig({
  compatibilityDate: '2025-07-15',
  devtools: { enabled: true },
  // ログインユーザーはヘッダ・middleware・各ページが共有する状態なので Pinia に置く
  modules: ['@pinia/nuxt'],
  css: ['~/assets/css/main.css'],
  app: {
    head: {
      title: '投稿アプリ',
      htmlAttrs: { lang: 'ja' },
    },
  },
  nitro: {
    prerender: {
      // ビルド時のクローラは、生成した HTML の <a href> を辿って次に静的化するページを探す。
      // Google ログインのボタンは <a href="/api/oauth2/authorization/google"> なので、
      // そのまま Nuxt のページとして静的化しようとして 404 になる(ビルド時にバックエンドは居ない)。
      // /api 配下は常に Spring Boot が受けるもので Nuxt のルートではないため、まとめて対象外にする。
      ignore: ['/api'],
    },
    // 開発時: /api を Spring Boot コンテナに転送(CORS 不要にする)
    devProxy: {
      '/api': {
        target: 'http://backend:8080/api',
        changeOrigin: true,
      },
    },
  },
})
