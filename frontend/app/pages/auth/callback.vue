<script setup lang="ts">
/**
 * Google ログインの受け皿。
 *
 * backend は認証が成立するとブラウザをこのページへ 302 で戻す。ここに来た = たった今
 * Google から戻ってきた、が確実に言えるので、戻り先の復元処理をこのページに閉じ込められる
 * (プラグインに置くと全ページで走り、鍵の有無だけで判定することになって誤爆する)。
 *
 * 設計 → docs/superpowers/specs/2026-08-15-phase4-google-auth-design.md 決定12
 */
const { fetchMe } = useAuth()

const failed = ref(false)

onMounted(async () => {
  try {
    // フルページロードなので plugins/auth.client.ts も /api/auth/me を呼ぶが、
    // どちらが先に終わるかに依存したくないのでここでも取り直す。
    await fetchMe()
  } catch {
    failed.value = true
    return
  }
  // replace で遷移することで、着地後に「戻る」を押してもこの中継地点に舞い戻らない。
  await navigateTo(takePostLoginRedirect(), { replace: true })
})
</script>

<template>
  <section class="auth-card">
    <p v-if="failed" class="auth-error">
      ログイン状態を確認できませんでした。
      <NuxtLink to="/login">ログイン画面に戻る</NuxtLink>
    </p>
    <p v-else class="auth-notice">ログインしています…</p>
  </section>
</template>
