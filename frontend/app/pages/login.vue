<script setup lang="ts">
const { login, resendVerification } = useAuth()
const auth = useAuthStore()
const route = useRoute()

/**
 * Google ログインが失敗したときに backend が付ける ?error= のコードと、画面に出す文言。
 *
 * URL にメッセージ本文を載せないのは、細工したリンクで任意の文言を表示させられるため。
 * コードはバックエンドの GoogleLoginNotAllowedException と対応している。
 */
const GOOGLE_ERROR_MESSAGES: Record<string, string> = {
  email_unverified:
    'この Google アカウントはメールアドレスの確認が済んでいないため利用できません。メールアドレスとパスワードでログインしてください',
  login_failed: 'Google ログインに失敗しました。時間をおいて試してください',
}

const email = ref('')
const password = ref('')
const submitting = ref(false)
const errorMessage = ref('')
/** メール未確認で弾かれたときだけ、再送の導線を出す */
const showResend = ref(false)
const resending = ref(false)
const resendNotice = ref('')

const canSubmit = computed(
  () => email.value.trim().length > 0 && password.value.length > 0 && !submitting.value,
)

/** ログイン後の戻り先。middleware が付けた ?redirect= があればそこへ */
function destination() {
  return safeInternalPath(route.query.redirect)
}

// Google ログインから戻された失敗の表示。パスワードログインの入力を邪魔しないよう、
// 文言を出すだけで ?error= は消さない(再読み込みしても同じ状態が再現できる)。
// onMountedはvueが用意している関数でコンポーネントが DOM に取り付けられた(マウントされた)ときに呼ばれる関数。
//普段 import を書いていないのは、Nuxt が onMounted などの Vue の API を自動 import しているからです。
// 自動 import は「onMounted という名前で使う」前提で効いているので、別名にしたい場合はその仕組みから外れることになり、
// 自分で import { onMounted as afterMount } from 'vue' と書く必要があります。
onMounted(() => {
  const code = route.query.error
  if (typeof code !== 'string') return
  errorMessage.value = GOOGLE_ERROR_MESSAGES[code] ?? GOOGLE_ERROR_MESSAGES.login_failed!
})

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
  if (resending.value) return
  resending.value = true
  resendNotice.value = ''
  try {
    await resendVerification(email.value.trim())
    // 再送できた時点でログイン失敗の文言は用済み。案内と並べて出すと解決していないように見える
    errorMessage.value = ''
    resendNotice.value = '確認メールを再送しました。メールのリンクを開いてください'
    showResend.value = false
  } catch (e) {
    errorMessage.value = apiErrorMessage(e, '確認メールの再送に失敗しました')
  } finally {
    resending.value = false
  }
}
</script>

<template>
  <section class="auth-card">
    <h1 class="auth-title">ログイン</h1>

    <form class="auth-form" @submit.prevent="onSubmit">
      <p v-if="errorMessage" class="auth-error">
        {{ errorMessage }}
        <button
          v-if="showResend"
          type="button"
          class="auth-inline-button"
          :disabled="resending"
          @click="onResend"
        >
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

    <p class="auth-divider">または</p>

    <AuthGoogleButton :redirect-to="destination()" />

    <div class="auth-links">
      <NuxtLink to="/signup">アカウントを作る</NuxtLink>
      <NuxtLink to="/password-reset">パスワードを忘れた</NuxtLink>
    </div>
  </section>
</template>
