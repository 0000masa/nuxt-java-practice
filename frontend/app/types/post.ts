import type { Category } from './category'

export interface UserSummary {
  id: number
  username: string
  displayName: string
}

export interface Post {
  id: number
  body: string
  createdAt: string
  user: UserSummary
  category: Category
}

/** タイムライン取得 API のレスポンス。nextCursor が null なら最終ページ */
export interface Timeline {
  posts: Post[]
  nextCursor: number | null
}
