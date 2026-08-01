// app/utils/ 直下の export は Nuxt が自動 import する。
// 判定に使われるのはディレクトリだけで、関数名は条件に入らない。

/** ISO 日時文字列を「たった今 / n分前 / n時間前 / n日前 / YYYY/M/D」の相対表記にする */
export function formatRelativeTime(isoString: string): string {
  const date = new Date(isoString)
  const diffMs = Date.now() - date.getTime()
  const diffMinutes = Math.floor(diffMs / 60_000)

  if (diffMinutes < 1) return 'たった今'
  if (diffMinutes < 60) return `${diffMinutes}分前`

  const diffHours = Math.floor(diffMinutes / 60)
  if (diffHours < 24) return `${diffHours}時間前`

  const diffDays = Math.floor(diffHours / 24)
  if (diffDays < 7) return `${diffDays}日前`

  return `${date.getFullYear()}/${date.getMonth() + 1}/${date.getDate()}`
}
