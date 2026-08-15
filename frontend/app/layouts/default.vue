<script setup lang="ts">
const auth = useAuthStore()
const { logout } = useAuth()

async function onLogout() {
  await logout()
  await navigateTo('/')
}
</script>

<template>
  <div class="app">
    <header class="app-header">
      <div class="app-header-inner">
        <NuxtLink to="/" class="app-title">投稿アプリ</NuxtLink>

        <!-- 認証状態が確定するまで何も出さない。SSG では静的 HTML が先に表示され、
             ログイン状態はその後にクライアントで確定するので、確定前に描画すると
             「ログイン」と「ログアウト」の導線が一瞬入れ替わって見える。 -->
        <nav v-if="auth.resolved" class="app-nav">
          <template v-if="auth.user">
            <span class="app-user">{{ auth.user.displayName }}</span>
            <NuxtLink to="/settings/password" class="app-nav-link">パスワード変更</NuxtLink>
            <button type="button" class="app-nav-button" @click="onLogout">ログアウト</button>
          </template>
          <template v-else>
            <NuxtLink to="/login" class="app-nav-link">ログイン</NuxtLink>
            <NuxtLink to="/signup" class="app-nav-button-link">登録</NuxtLink>
          </template>
        </nav>
      </div>
    </header>
    <main class="app-main">
      <slot />
    </main>
  </div>
</template>

<style scoped>
.app-header {
  position: sticky;
  top: 0;
  z-index: 10;
  background: #fff;
  border-bottom: 1px solid #e2e8f0;
}

.app-header-inner {
  max-width: 640px;
  margin: 0 auto;
  padding: 0.75rem 1rem;
  display: flex;
  align-items: center;
  gap: 0.8rem;
}

.app-title {
  font-size: 1.1rem;
  font-weight: 700;
  color: #0f172a;
  text-decoration: none;
}

.app-nav {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 0.7rem;
  font-size: 0.85rem;
}

.app-user {
  color: #334155;
  font-weight: 700;
}

.app-nav-link {
  color: #1d4ed8;
  text-decoration: none;
}

.app-nav-button {
  background: none;
  border: 1px solid #cbd5e1;
  border-radius: 999px;
  padding: 0.2rem 0.8rem;
  font: inherit;
  font-size: 0.85rem;
  color: #334155;
  cursor: pointer;
}

.app-nav-button-link {
  background: #1d4ed8;
  border-radius: 999px;
  padding: 0.2rem 0.9rem;
  color: #fff;
  text-decoration: none;
}

.app-main {
  max-width: 640px;
  margin: 0 auto;
  padding: 1rem;
}
</style>
