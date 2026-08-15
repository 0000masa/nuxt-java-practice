import type { Post, Timeline } from '~/types/post'

/**
 * 投稿 API との通信をここに集約する(ページ・コンポーネントに $fetch を直書きしない)。
 *
 * 素の $fetch ではなく plugins/api.ts が用意した $api を使う。書き込み系(POST / DELETE)は
 * CSRF トークンをヘッダに載せないと 403 になるため、その処理を持つラッパを通す必要がある。
 *
 * $api を取り出す useNuxtApp は setup の同期実行中にしか呼べないので、この composable も
 * setup から呼ぶこと(返した関数はイベントハンドラから呼んでよい)。
 * use 接頭辞は Vue の慣習であって、フレームワークが強制するものではない。
 * → docs/notes/vue/composables.md
 */
export function usePosts() {
  const { $api } = useNuxtApp()

  const fetchTimeline = (params: { cursor?: number; categoryId?: number; limit?: number }) =>
    $api<Timeline>('/api/posts', { params })

  const fetchPost = (id: number | string) => $api<Post>(`/api/posts/${id}`)

  const createPost = (body: string, categoryId: number) =>
    $api<Post>('/api/posts', { method: 'POST', body: { body, categoryId } })

  const deletePost = (id: number) => $api<void>(`/api/posts/${id}`, { method: 'DELETE' })

  return { fetchTimeline, fetchPost, createPost, deletePost }
}
