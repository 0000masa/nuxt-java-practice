/**
 * API 通信の共通ラッパ($api)。全ての API 呼び出しはこれを通す。
 *
 * 役割は 2 つ。
 * 1. CSRF トークンをヘッダに載せる
 * 2. セッション切れ(401)を 1 箇所で扱う
 *
 * → docs/adr/0002-session-cookie-over-jwt.md
 */
export default defineNuxtPlugin(() => {
  const api = $fetch.create({
    //メソッド短縮記法 onRequest: ({ options }) => { ... }と同じ意味
    onRequest({ options }) {
      const method = String(options.method ?? 'GET').toUpperCase()
      // GET / HEAD は状態を変えないので CSRF トークンは不要(サーバー側も要求しない)
      if (method === 'GET' || method === 'HEAD') return

      const token = readCsrfToken()
      if (!token) return
      const headers = new Headers(options.headers)
      headers.set('X-XSRF-TOKEN', token)
      options.headers = headers
    },

    async onResponseError({ request, response }) {
      if (response.status !== 401 || !import.meta.client) return

      // 認証系エンドポイントの 401 は「ログイン失敗」「メール未確認」など、
      // その画面が文言を出して扱うべきもの。ここでリダイレクトすると邪魔になる。
      if (String(request).startsWith('/api/auth/')) return

      // それ以外の 401 はセッションが切れたということ。状態を捨ててログイン画面へ送る。
      useAuthStore().set(null)
      const current = window.location.pathname + window.location.search
      // onResponseError は追加処理を行うためのフックで、戻り値は無視される
      // ($api の呼び出し元にはこの後もエラーが投げられる)。
      // navigateTo の戻り値もルートミドルウェア以外では意味を持たないので return しない。
      await navigateTo(`/login?redirect=${encodeURIComponent(current)}`)
    },
  })

  return { provide: { api } }
})

/**
 * XSRF-TOKEN Cookie を読む。
 *
 * リクエストごとに読み直しているのが重要。Spring Security はログイン成功時と
 * ログアウト成功時にトークンを作り直すので、起動時に 1 回読んで保持すると古い値を送ってしまう。
 */
function readCsrfToken(): string | undefined {
  if (!import.meta.client) return undefined
  const raw = document.cookie
    .split('; ')
    .find((row) => row.startsWith('XSRF-TOKEN='))
    ?.slice('XSRF-TOKEN='.length)
  return raw ? decodeURIComponent(raw) : undefined
}
