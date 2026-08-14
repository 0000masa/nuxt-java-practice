import { defineStore } from 'pinia'
import type { CurrentUser } from '~/types/auth'

/**
 * ログインユーザーの共有状態。
 *
 * <p>ここには「状態」だけを置き、API 通信は composables/useAuth.ts に置く。
 * 状態を読みたいだけのコンポーネント(ヘッダなど)が通信の関心を持たなくて済む。
 * defineStoreは関数を作る関数であるのでuseAuthStoreは関数。useXxxStore は 
 * Pinia 公式ドキュメントが推奨している命名規則であって、フレームワークが強制するものではない。
 */
export const useAuthStore = defineStore('auth', () => {
  const user = ref<CurrentUser | null>(null)

  /**
   * 起動時の /api/auth/me が終わったか。
   *
   * SSG なので静的 HTML が先に表示され、ログイン状態はその後に確定する。
   * この間に「ログイン」と「ログアウト」の導線が入れ替わって見えるのを防ぐため、
   * 確定するまでヘッダは何も出さない。
   */
  const resolved = ref(false)

  const isLoggedIn = computed(() => user.value !== null)

  /** /api/auth/me やログインの結果を反映する。null なら未ログイン */
  function set  (next: CurrentUser | null) {
    user.value = next
    resolved.value = true
  }
  //useAuthStore() が返すのは { user, resolved, ... } という生のオブジェクトではなく、Pinia が包み直したものです。
  // この包みが ref を自動で開けてくれる（アンラップと言います）ので、外からは .value なしで読み書きできます。
  return { user, resolved, isLoggedIn, set }
})
