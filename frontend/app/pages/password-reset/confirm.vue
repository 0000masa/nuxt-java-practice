<script setup lang="ts">
/**
 * パスワード再設定メールのリンクの着地点。新しいパスワードを設定する。
 *
 * 完了するとそのユーザーの全セッションが消えるので、他の端末でログイン中だった場合も
 * 追い出される(→ 設計の決定11)。
 */
const { confirmPasswordReset } = useAuth()
const route = useRoute()

const token = computed(() => (typeof route.query.token === 'string' ? route.query.token : ''))
const newPassword = ref('')
const submitting = ref(false)
const errorMessage = ref('')
const fieldErrors = ref<Record<string, string>>({})
const done = ref(false)

const canSubmit = computed(
  () => token.value.length > 0 && newPassword.value.length > 0 && !submitting.value,
)

async function onSubmit() {
  if (!canSubmit.value) return
  submitting.value = true
  errorMessage.value = ''
  fieldErrors.value = {}
  try {
    await confirmPasswordReset(token.value, newPassword.value)
    done.value = true
  } catch (e) {
    fieldErrors.value = apiFieldErrors(e)
    errorMessage.value =
      Object.keys(fieldErrors.value).length > 0 ? '' : apiErrorMessage(e, '再設定に失敗しました')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section class="auth-card">
    <h1 class="auth-title">新しいパスワードの設定</h1>

    <template v-if="done">
      <p class="auth-notice">
        パスワードを再設定しました。ログイン中だった端末はすべてログアウトされています。
      </p>
      <div class="auth-links">
        <NuxtLink to="/login">ログイン画面へ</NuxtLink>
      </div>
    </template>

    <template v-else-if="token.length === 0">
      <p class="auth-error">リンクにトークンが含まれていません</p>
      <div class="auth-links">
        <NuxtLink to="/password-reset">再設定メールを送り直す</NuxtLink>
      </div>
    </template>

    <template v-else>
      <form class="auth-form" @submit.prevent="onSubmit">
        <p v-if="errorMessage" class="auth-error">{{ errorMessage }}</p>

        <div class="auth-field">
          <label class="auth-label" for="newPassword">新しいパスワード</label>
          <input
            id="newPassword"
            v-model="newPassword"
            class="auth-input"
            type="password"
            autocomplete="new-password"
          />
          <span class="auth-hint">8〜72文字</span>
          <span v-if="fieldErrors.newPassword" class="auth-field-error">
            {{ fieldErrors.newPassword }}
          </span>
        </div>

        <button type="submit" class="auth-submit" :disabled="!canSubmit">設定する</button>
      </form>

      <div class="auth-links">
        <NuxtLink to="/password-reset">再設定メールを送り直す</NuxtLink>
      </div>
    </template>
  </section>
</template>
