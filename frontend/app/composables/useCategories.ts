import type { Category } from '~/types/category'

/**
 * カテゴリー一覧の取得。
 * SSG(nuxt generate)ではビルド時にバックエンドが居ないため、
 * server: false でクライアント側でのみ取得する(このアプリの API 取得は全てこの方針)。
 *
 * useFetch を呼ぶため、setup の同期実行中にしか呼べない。
 * await の後やイベントハンドラの中から呼ぶと動かない。
 * 同じ use 接頭辞でも usePosts にはこの制約がない。名前は制約を保証しない。
 * → docs/notes/vue/composables.md
 */
export function useCategories() {
  return useFetch<Category[]>('/api/categories', { server: false })
}
