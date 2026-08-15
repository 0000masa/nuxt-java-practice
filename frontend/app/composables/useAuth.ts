import type { MeResponse, SignupPayload } from '~/types/auth'

/**
 * 認証 API との通信をここに集約する。状態は stores/auth.ts が持つ。
 *
 * setup の同期実行中に呼ぶこと(useNuxtApp を使うため)。返した関数はイベントハンドラから呼んでよい。
 * → docs/notes/vue/composables.md
 */
export function useAuth() {
  const { $api } = useNuxtApp()
  const store = useAuthStore()

  /** 現在のログイン状態を取り直してストアに反映する。未ログインなら null が入る */
  const fetchMe = async () => {
    const { user } = await $api<MeResponse>('/api/auth/me')
    store.set(user)
    return user
  }

  /**
   * ログイン。ボディが JSON ではなく form-urlencoded なのは、Spring Security 標準の
   * formLogin に乗せているため。この 1 箇所だけ他の API と送り方が違う。
   * 統一したくなっても JSON に変えないこと(→ docs/adr/0002-session-cookie-over-jwt.md)。
   */
  const login = async (email: string, password: string) => {
    const { user } = await $api<MeResponse>('/api/auth/login', {
      method: 'POST',
      body: new URLSearchParams({ email, password }),
    })
    store.set(user)
    return user
  }

  const logout = async () => {
    await $api('/api/auth/logout', { method: 'POST' })
    store.set(null)
  }

  const signup = (payload: SignupPayload) =>
    $api<void>('/api/auth/signup', { method: 'POST', body: payload })

  const verifyEmail = (token: string) =>
    $api<void>('/api/auth/verify-email', { method: 'POST', body: { token } })

  const resendVerification = (email: string) =>
    $api<void>('/api/auth/verification/resend', { method: 'POST', body: { email } })

  const requestPasswordReset = (email: string) =>
    $api<void>('/api/auth/password-reset/request', { method: 'POST', body: { email } })

  const confirmPasswordReset = (token: string, newPassword: string) =>
    $api<void>('/api/auth/password-reset/confirm', { method: 'POST', body: { token, newPassword } })

  const changePassword = (currentPassword: string, newPassword: string) =>
    $api<void>('/api/auth/password', { method: 'PUT', body: { currentPassword, newPassword } })

  return {
    fetchMe,
    login,
    logout,
    signup,
    verifyEmail,
    resendVerification,
    requestPasswordReset,
    confirmPasswordReset,
    changePassword,
  }
}
