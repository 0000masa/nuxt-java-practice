import type { Post, Timeline } from '~/types/post'

/**
 * 投稿 API との通信をここに集約する(ページ・コンポーネントに $fetch を直書きしない)。
 *
 * 現状の中身は $fetch だけで、ref も useFetch もライフサイクルも使っていない。
 * そのため setup の外(await の後、イベントハンドラの中)から呼んでも動く。
 * ここに ref / useFetch を追加すると、この前提は崩れる。
 * use 接頭辞は Vue の慣習であって、フレームワークが強制するものではない。
 * → docs/notes/vue/composables.md
 */
export function usePosts() {
  const fetchTimeline = (params: { cursor?: number; categoryId?: number; limit?: number }) =>
    $fetch<Timeline>('/api/posts', { params })

  const fetchPost = (id: number | string) => $fetch<Post>(`/api/posts/${id}`)

  const createPost = (body: string, categoryId: number) =>
    $fetch<Post>('/api/posts', { method: 'POST', body: { body, categoryId } })

  const deletePost = (id: number) => $fetch<void>(`/api/posts/${id}`, { method: 'DELETE' })

  return { fetchTimeline, fetchPost, createPost, deletePost }
}
