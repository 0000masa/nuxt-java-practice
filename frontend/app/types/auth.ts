/** ログイン中のユーザー。GET /api/auth/me が返す user の中身 */
export interface CurrentUser {
  id: number
  username: string
  displayName: string
  email: string
}

/**
 * GET /api/auth/me のレスポンス。
 * 未ログインでも 200 が返り、user が null になる(エラーではなく正常な答えとして扱う)。
 */
export interface MeResponse {
  user: CurrentUser | null
}

/** 会員登録のリクエストボディ */
export interface SignupPayload {
  username: string
  displayName: string
  email: string
  password: string
}
