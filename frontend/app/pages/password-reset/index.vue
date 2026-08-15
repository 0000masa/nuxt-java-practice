<script setup lang="ts">
/** パスワードリセットの申請。ログインしていない状態で使う */
const { requestPasswordReset } = useAuth()

const email = ref('')
const submitting = ref(false)
const errorMessage = ref('')
const done = ref(false)

const canSubmit = computed(() => email.value.trim().length > 0 && !submitting.value)

async function onSubmit() {
  if (!canSubmit.value) return
  submitting.value = true
  errorMessage.value = ''
  try {
    await requestPasswordReset(email.value.trim())
    done.value = true
  } catch (e) {
    errorMessage.value = apiErrorMessage(e, '再設定メールの送信に失敗しました')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section class="auth-card">
    <h1 class="auth-title">パスワードの再設定</h1>

    <template v-if="done">
      <p class="auth-notice">
        {{ email }} に再設定用のメールを送りました。リンクは1時間で無効になります。
      </p>
      <div class="auth-links">
        <NuxtLink to="/login">ログイン画面へ</NuxtLink>
      </div>
    </template>

    <template v-else>
      <form class="auth-form" @submit.prevent="onSubmit">
        <p v-if="errorMessage" class="auth-error">{{ errorMessage }}</p>

        <div class="auth-field">
          <label class="auth-label" for="email">メールアドレス</label>
          <input id="email" v-model="email" class="auth-input" type="email" autocomplete="email" />
          <span class="auth-hint">登録したメールアドレスに再設定用のリンクを送ります</span>
        </div>

        <button type="submit" class="auth-submit" :disabled="!canSubmit">再設定メールを送る</button>
      </form>

      <div class="auth-links">
        <NuxtLink to="/login">ログイン画面へ戻る</NuxtLink>
      </div>
    </template>
  </section>
</template>
