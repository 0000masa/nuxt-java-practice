/**
 * アプリ起動時に 1 回だけログイン状態を復元する。
 *
 * SSG なのでサーバー側にセッションを見る機会がなく、ブラウザで動き出してから
 * /api/auth/me を叩いて状態を得るしかない。
 *
 * このリクエストの副産物として XSRF-TOKEN Cookie も発行される。だから
 * 「ログイン前なので CSRF トークンが無くログインできない」という問題が起きない
 * (→ 設計の決定14)。
 *
 * ファイル名が api.ts より後ろ(アルファベット順)なのは意図的。plugins は名前順に実行されるので、
 * $api を提供する api.ts が先に走る必要がある。
 */
export default defineNuxtPlugin(async () => {
  try {
    await useAuth().fetchMe()
  } catch {
    // バックエンドが落ちている場合など。未ログインとして扱い、画面は表示させる。
    useAuthStore().set(null)
  }
})
