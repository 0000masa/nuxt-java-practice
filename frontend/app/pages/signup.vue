<script setup lang="ts">
const { signup } = useAuth()

const form = reactive({
  username: '',
  displayName: '',
  email: '',
  password: '',
})
const submitting = ref(false)
const errorMessage = ref('')
const fieldErrors = ref<Record<string, string>>({})
/** 登録が完了したか。画面遷移せず、この画面の表示を切り替える */
const done = ref(false)

const canSubmit = computed(
  () =>
    form.username.trim().length > 0 &&
    form.displayName.trim().length > 0 &&
    form.email.trim().length > 0 &&
    form.password.length > 0 &&
    !submitting.value,
)

async function onSubmit() {
  if (!canSubmit.value) return
  submitting.value = true
  errorMessage.value = ''
  fieldErrors.value = {}
  try {
    await signup({
      username: form.username.trim(),
      displayName: form.displayName.trim(),
      email: form.email.trim(),
      password: form.password,
    })
    done.value = true
  } catch (e) {
    fieldErrors.value = apiFieldErrors(e)
    // 項目別エラーがある場合は入力欄の下に出るので、上部には出さない
    errorMessage.value =
      Object.keys(fieldErrors.value).length > 0 ? '' : apiErrorMessage(e, '登録に失敗しました')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section class="auth-card">
    <h1 class="auth-title">会員登録</h1>

    <template v-if="done">
      <p class="auth-notice">
        {{ form.email }} に確認メールを送りました。メールのリンクを開くとログインできるようになります。
      </p>
      <div class="auth-links">
        <NuxtLink to="/login">ログイン画面へ</NuxtLink>
      </div>
    </template>

    <template v-else>
      <form class="auth-form" @submit.prevent="onSubmit">
        <p v-if="errorMessage" class="auth-error">{{ errorMessage }}</p>

        <div class="auth-field">
          <label class="auth-label" for="username">ユーザー名</label>
          <input id="username" v-model="form.username" class="auth-input" autocomplete="username" />
          <span class="auth-hint">英数字と _ のみ、3〜30文字。プロフィールの @xxx になります</span>
          <span v-if="fieldErrors.username" class="auth-field-error">{{ fieldErrors.username }}</span>
        </div>

        <div class="auth-field">
          <label class="auth-label" for="displayName">表示名</label>
          <input id="displayName" v-model="form.displayName" class="auth-input" autocomplete="nickname" />
          <span class="auth-hint">投稿に表示される名前。日本語も使えます</span>
          <span v-if="fieldErrors.displayName" class="auth-field-error">{{ fieldErrors.displayName }}</span>
        </div>

        <div class="auth-field">
          <label class="auth-label" for="email">メールアドレス</label>
          <input id="email" v-model="form.email" class="auth-input" type="email" autocomplete="email" />
          <span class="auth-hint">ログインに使います</span>
          <span v-if="fieldErrors.email" class="auth-field-error">{{ fieldErrors.email }}</span>
        </div>

        <div class="auth-field">
          <label class="auth-label" for="password">パスワード</label>
          <input
            id="password"
            v-model="form.password"
            class="auth-input"
            type="password"
            autocomplete="new-password"
          />
          <span class="auth-hint">8〜72文字</span>
          <span v-if="fieldErrors.password" class="auth-field-error">{{ fieldErrors.password }}</span>
        </div>

        <button type="submit" class="auth-submit" :disabled="!canSubmit">登録する</button>
      </form>

      <div class="auth-links">
        <NuxtLink to="/login">すでにアカウントを持っている</NuxtLink>
      </div>
    </template>
  </section>
</template>
