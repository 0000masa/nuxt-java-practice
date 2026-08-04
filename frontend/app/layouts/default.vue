<template>
  <div class="app">
    <header class="app-header">
      <div class="app-header-inner">
        <NuxtLink to="/" class="app-title">投稿アプリ</NuxtLink>
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
}

.app-title {
  font-size: 1.1rem;
  font-weight: 700;
  color: #0f172a;
  text-decoration: none;
}

.app-main {
  max-width: 640px;
  margin: 0 auto;
  padding: 1rem;
}
</style>
