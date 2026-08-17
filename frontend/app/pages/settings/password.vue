<script setup lang="ts">
/**
 * ログイン中のパスワード変更。
 *
 * 完了すると、この端末以外のセッションが消える(操作した本人は追い出されない)。
 * リセット(/password-reset)とは別の操作 → CONTEXT.md「パスワード変更」
 */
definePageMeta({ middleware: 'auth' })

const { changePassword } = useAuth()
const auth = useAuthStore()

/**
 * Google ログインだけで作られたユーザーはパスワードを持たない。
 * 照合する「現在のパスワード」が存在せず必ず失敗するので、フォーム自体を出さない。
 * 設定したい場合はパスワードリセットの経路を使う(→ docs/api/request-password-reset.md)。
 */
const hasPassword = computed(() => auth.user?.hasPassword !== false)

const currentPassword = ref('')
const newPassword = ref('')
const submitting = ref(false)
const errorMessage = ref('')
const fieldErrors = ref<Record<string, string>>({})
const done = ref(false)

const canSubmit = computed(
  () => currentPassword.value.length > 0 && newPassword.value.length > 0 && !submitting.value,
)

async function onSubmit() {
  if (!canSubmit.value) return
  submitting.value = true
  errorMessage.value = ''
  fieldErrors.value = {}
  done.value = false
  try {
    await changePassword(currentPassword.value, newPassword.value)
    done.value = true
    currentPassword.value = ''
    newPassword.value = ''
  } catch (e) {
    fieldErrors.value = apiFieldErrors(e)
    errorMessage.value =
      Object.keys(fieldErrors.value).length > 0 ? '' : apiErrorMessage(e, '変更に失敗しました')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section class="auth-card">
    <h1 class="auth-title">パスワードの変更</h1>

    <p v-if="!hasPassword" class="auth-notice">
      このアカウントにはパスワードが設定されていません(Google ログインで作られたアカウントです)。
      パスワードを設定するには
      <NuxtLink to="/password-reset">パスワードの再設定</NuxtLink>
      から手続きしてください。
    </p>

    <form v-else class="auth-form" @submit.prevent="onSubmit">
      <p v-if="errorMessage" class="auth-error">{{ errorMessage }}</p>
      <p v-if="done" class="auth-notice">
        パスワードを変更しました。この端末以外はログアウトされています。
      </p>

      <div class="auth-field">
        <label class="auth-label" for="currentPassword">現在のパスワード</label>
        <input
          id="currentPassword"
          v-model="currentPassword"
          class="auth-input"
          type="password"
          autocomplete="current-password"
        />
        <span v-if="fieldErrors.currentPassword" class="auth-field-error">
          {{ fieldErrors.currentPassword }}
        </span>
      </div>

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

      <button type="submit" class="auth-submit" :disabled="!canSubmit">変更する</button>
    </form>

    <div class="auth-links">
      <NuxtLink to="/">タイムラインへ戻る</NuxtLink>
    </div>
  </section>
</template>
