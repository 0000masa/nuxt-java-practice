<script setup lang="ts">
/**
 * Google ログインの入口(→ CONTEXT.md「Google ログイン」)。
 *
 * <b>素の <a> であることが重要。</b>NuxtLink だとクライアント側ルーティングになって
 * サーバーにリクエストが飛ばず、$fetch だと Google の同意画面を fetch することになって
 * CORS で失敗する。ここはブラウザのページ遷移でなければならない。
 *
 * クリックハンドラは戻り先を控えるだけで、遷移そのものは href に任せている
 * (preventDefault しない)。
 */
const props = withDefaults(
  defineProps<{
    /** ログイン後に戻したいパス。省略時はトップ */
    redirectTo?: string
    label?: string
  }>(),
  { redirectTo: '/', label: 'Google でログイン' },
)

function rememberDestination() {
  rememberPostLoginRedirect(safeInternalPath(props.redirectTo))
}
</script>

<template>
  <a class="auth-google" href="/api/oauth2/authorization/google" @click="rememberDestination">
    {{ label }}
  </a>
</template>
