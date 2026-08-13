<script setup lang="ts">
const { login, resendVerification } = useAuth()
const auth = useAuthStore()
const route = useRoute()

const email = ref('')
const password = ref('')
const submitting = ref(false)
const errorMessage = ref('')
/** メール未確認で弾かれたときだけ、再送の導線を出す */
const showResend = ref(false)
const resendNotice = ref('')

const canSubmit = computed(
  () => email.value.trim().length > 0 && password.value.length > 0 && !submitting.value,
)

/** ログイン後の戻り先。middleware が付けた ?redirect= があればそこへ */
function destination() {
  const redirect = route.query.redirect
  return typeof redirect === 'string' && redirect.startsWith('/') ? redirect : '/'
}

// 既にログインしているなら留まる意味がない
watchEffect(() => {
  if (auth.resolved && auth.isLoggedIn) navigateTo(destination(), { replace: true })
})

async function onSubmit() {
  if (!canSubmit.value) return
  submitting.value = true
  errorMessage.value = ''
  showResend.value = false
  resendNotice.value = ''
  try {
    await login(email.value.trim(), password.value)
    await navigateTo(destination(), { replace: true })
  } catch (e) {
    errorMessage.value = apiErrorMessage(e, 'ログインに失敗しました')
    // バックエンドはメール未確認のときだけこの文言を返す(→ AuthResponseWriter.onLoginFailure)
    showResend.value = errorMessage.value.includes('確認が完了していません')
  } finally {
    submitting.value = false
  }
}

async function onResend() {
  resendNotice.value = ''
  try {
    await resendVerification(email.value.trim())
    resendNotice.value = '確認メールを再送しました。メールのリンクを開いてください'
    showResend.value = false
  } catch (e) {
    errorMessage.value = apiErrorMessage(e, '確認メールの再送に失敗しました')
  }
}
</script>

<template>
  <section class="auth-card">
    <h1 class="auth-title">ログイン</h1>

    <form class="auth-form" @submit.prevent="onSubmit">
      <p v-if="errorMessage" class="auth-error">
        {{ errorMessage }}
        <button v-if="showResend" type="button" class="auth-inline-button" @click="onResend">
          確認メールを再送する
        </button>
      </p>
      <p v-if="resendNotice" class="auth-notice">{{ resendNotice }}</p>

      <div class="auth-field">
        <label class="auth-label" for="email">メールアドレス</label>
        <input id="email" v-model="email" class="auth-input" type="email" autocomplete="email" />
      </div>

      <div class="auth-field">
        <label class="auth-label" for="password">パスワード</label>
        <input
          id="password"
          v-model="password"
          class="auth-input"
          type="password"
          autocomplete="current-password"
        />
      </div>

      <button type="submit" class="auth-submit" :disabled="!canSubmit">ログイン</button>
    </form>

    <div class="auth-links">
      <NuxtLink to="/signup">アカウントを作る</NuxtLink>
      <NuxtLink to="/password-reset">パスワードを忘れた</NuxtLink>
    </div>
  </section>
</template>
