/**
 * バックエンドのエラーレスポンス(ErrorResponse)の形。
 * fieldErrors はリクエストボディのバリデーションエラーのときだけ入り、それ以外は null。
 * → docs/api/README.md
 */
interface ErrorResponseBody {
  message?: string
  fieldErrors?: Record<string, string> | null
}

function body(error: unknown): ErrorResponseBody {
  // $fetch(ofetch)は 4xx / 5xx で例外を投げ、レスポンスボディを data に入れる
  return (error as { data?: ErrorResponseBody })?.data ?? {}
}

/** 画面上部に出す 1 行のメッセージ。項目別エラーがあればその最初の 1 件を優先する */
export function apiErrorMessage(error: unknown, fallback: string): string {
  const data = body(error)
  const firstFieldError = data.fieldErrors && Object.values(data.fieldErrors)[0]
  return firstFieldError || data.message || fallback
}

/** 入力欄の下に出す「項目名 → メッセージ」。無ければ空 */
export function apiFieldErrors(error: unknown): Record<string, string> {
  return body(error).fieldErrors ?? {}
}

/** HTTP ステータスコード。通信そのものが失敗した場合は undefined */
export function apiStatus(error: unknown): number | undefined {
  return (error as { status?: number })?.status
}
