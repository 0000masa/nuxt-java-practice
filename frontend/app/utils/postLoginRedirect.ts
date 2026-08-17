const STORAGE_KEY = 'postLoginRedirect'

/**
 * 外部サイトへ飛ばされない、自サイト内のパスだけを通す。
 *
 * `//evil.example.com` はプロトコル相対 URL として外部に解決されるので弾く。
 * これを怠ると、細工したリンクを踏ませて自サイト経由で外部へ誘導できてしまう(オープンリダイレクト)。
 */
export function safeInternalPath(value: unknown, fallback = '/'): string {
  if (typeof value !== 'string') return fallback
  return value.startsWith('/') && !value.startsWith('//') ? value : fallback
}

/**
 * Google ログインへ送り出す前に、戻り先を覚えておく。
 *
 * サーバーではなくブラウザに覚えさせるのは、戻り先が<b>クライアント側のルート</b>だから。
 * SSG では保護ページも静的 HTML として 200 で返るため、「利用者がどこへ行きたかったか」を
 * サーバーは一度も見ていない(→ 設計 2026-08-15-phase4-google-auth-design.md 決定11)。
 *
 * sessionStorage はオリジン × タブ単位なので、accounts.google.com を往復しても消えない。
 * 消えるのはタブを閉じたときだけ。
 */
export function rememberPostLoginRedirect(path: string): void {
  if (!import.meta.client) return
  sessionStorage.setItem(STORAGE_KEY, path)
}

/** 覚えておいた戻り先を取り出して消す。無ければトップ。 */
export function takePostLoginRedirect(): string {
  if (!import.meta.client) return '/'
  const stored = sessionStorage.getItem(STORAGE_KEY)
  sessionStorage.removeItem(STORAGE_KEY)
  return safeInternalPath(stored)
}
