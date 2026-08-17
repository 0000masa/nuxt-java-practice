/** ログイン中のユーザー。GET /api/auth/me が返す user の中身 */
export interface CurrentUser {
  id: number
  username: string
  displayName: string
  email: string
  /**
   * パスワードが設定されているか。Google ログインだけで作られたユーザーは false になる。
   * false のときパスワード変更フォームは出さない(現在のパスワードが存在せず、必ず失敗するため)。
   */
  hasPassword: boolean
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
