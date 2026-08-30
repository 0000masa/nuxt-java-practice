<script setup lang="ts">
/**
 * 確認メールのリンクの着地点。
 *
 * メールの URL がバックエンドではなくこのページを指しているのは、成功・期限切れ・使用済みの
 * 表示をフロントで統一できるようにするため(→ 設計の決定5)。
 * トークンの消費そのものは POST /api/auth/verify-email が行う。GET リンクにしていないのは、
 * メールソフトのリンク先読みで勝手に消費される事故を避けるため。
 */
const { verifyEmail, resendVerification } = useAuth()
const { $linkQuery } = useNuxtApp()

type State = 'verifying' | 'done' | 'failed'
const state = ref<State>('verifying')
const errorMessage = ref('')

/** 期限切れ / 使用済みからの復帰用。メールアドレスを入れて再送させる */
const resendEmail = ref('')
const resendNotice = ref('')

onMounted(async () => {
  // useRoute().query ではなく、開かれた URL のクエリを読む。プリレンダ済みのページでは
  // マウント時点の route.query も window.location も空になっているため
  // → plugins/link-query.client.ts
  const token = $linkQuery('token')
  if (token.length === 0) {
    state.value = 'failed'
    errorMessage.value = 'リンクにトークンが含まれていません'
    return
  }
  try {
    await verifyEmail(token)
    state.value = 'done'
  } catch (e) {
    state.value = 'failed'
    errorMessage.value = apiErrorMessage(e, 'メールアドレスの確認に失敗しました')
  }
})

async function onResend() {
  resendNotice.value = ''
  errorMessage.value = ''
  try {
    await resendVerification(resendEmail.value.trim())
    resendNotice.value = '確認メールを再送しました。新しいメールのリンクを開いてください'
  } catch (e) {
    errorMessage.value = apiErrorMessage(e, '確認メールの再送に失敗しました')
  }
}
</script>

<template>
  <section class="auth-card">
    <h1 class="auth-title">メールアドレスの確認</h1>

    <p v-if="state === 'verifying'">確認しています...</p>

    <template v-else-if="state === 'done'">
      <p class="auth-notice">確認が完了しました。ログインできます。</p>
      <div class="auth-links">
        <NuxtLink to="/login">ログイン画面へ</NuxtLink>
      </div>
    </template>

    <template v-else>
      <p v-if="errorMessage" class="auth-error">{{ errorMessage }}</p>
      <p v-if="resendNotice" class="auth-notice">{{ resendNotice }}</p>

      <form class="auth-form" @submit.prevent="onResend">
        <div class="auth-field">
          <label class="auth-label" for="resendEmail">確認メールを再送する</label>
          <input
            id="resendEmail"
            v-model="resendEmail"
            class="auth-input"
            type="email"
            autocomplete="email"
          />
          <span class="auth-hint">登録したメールアドレスを入力してください</span>
        </div>
        <button type="submit" class="auth-submit" :disabled="resendEmail.trim().length === 0">
          再送する
        </button>
      </form>

      <div class="auth-links">
        <NuxtLink to="/login">ログイン画面へ</NuxtLink>
      </div>
    </template>
  </section>
</template>
