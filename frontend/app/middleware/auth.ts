/**
 * ログイン必須ページのガード。使う側は definePageMeta({ middleware: 'auth' }) と書く。
 *
 * <p><b>これは UX のためのものでしかない。</b>SSG なのでページの静的 HTML 自体は誰でも取得でき、
 * 実際の防御は API 側の 401 だけが担う。
 */
export default defineNuxtRouteMiddleware((to) => {
  // プリレンダ(nuxt generate)時はブラウザの Cookie が存在せず、必ず未ログイン判定になる。
  // ここで抜けないと、全ページが「/login へのリダイレクト」として静的化されてしまう。
  if (import.meta.server) return

  const auth = useAuthStore()
  if (auth.isLoggedIn) return

  // 元いたページを渡しておき、ログイン後に戻す
  return navigateTo(`/login?redirect=${encodeURIComponent(to.fullPath)}`)
})
