<script setup lang="ts">
import type { Post } from '~/types/post'

const { data: categories } = useCategories()
const { fetchTimeline } = usePosts()
const auth = useAuthStore()

const posts = ref<Post[]>([])
const nextCursor = ref<number | null>(null)
const selectedCategoryId = ref<number | null>(null)
const loading = ref(false)
const reachedEnd = ref(false)

async function loadMore() {
  if (loading.value || reachedEnd.value) return
  loading.value = true
  try {
    const timeline = await fetchTimeline({
      cursor: nextCursor.value ?? undefined,
      categoryId: selectedCategoryId.value ?? undefined,
    })
    posts.value.push(...timeline.posts)
    nextCursor.value = timeline.nextCursor
    reachedEnd.value = timeline.nextCursor === null
  } finally {
    loading.value = false
  }
}

/** カテゴリー切り替え時は一覧を最初から取り直す */
async function selectCategory(categoryId: number | null) {
  selectedCategoryId.value = categoryId
  posts.value = []
  nextCursor.value = null
  reachedEnd.value = false
  await loadMore()
}

function onPostCreated(post: Post) {
  // 表示中の絞り込みに合致する場合だけ先頭に差し込む
  if (selectedCategoryId.value === null || post.category.id === selectedCategoryId.value) {
    posts.value.unshift(post)
  }
}

function onPostDeleted(id: number) {
  posts.value = posts.value.filter((post) => post.id !== id)
}

// 無限スクロール: 末尾の番兵が見えたら次のページを読む
const sentinel = ref<HTMLElement | null>(null)
let observer: IntersectionObserver | undefined

onMounted(() => {
  loadMore()
  observer = new IntersectionObserver((entries) => {
    if (entries[0]?.isIntersecting) loadMore()
  })
  if (sentinel.value) observer.observe(sentinel.value)
})

onBeforeUnmount(() => observer?.disconnect())
</script>

<template>
  <div class="timeline">
    <!-- 投稿はログインが必要。タイムラインの閲覧そのものは未ログインでもできる(設計の決定1) -->
    <PostForm
      v-if="auth.isLoggedIn && categories"
      :categories="categories"
      @created="onPostCreated"
    />
    <p v-else-if="auth.resolved && !auth.isLoggedIn" class="timeline-signin">
      <NuxtLink to="/login">ログイン</NuxtLink>
      すると投稿できます
    </p>

    <nav class="category-filter">
      <button
        class="category-chip"
        :class="{ active: selectedCategoryId === null }"
        @click="selectCategory(null)"
      >
        すべて
      </button>
      <button
        v-for="category in categories ?? []"
        :key="category.id"
        class="category-chip"
        :class="{ active: selectedCategoryId === category.id }"
        @click="selectCategory(category.id)"
      >
        {{ category.name }}
      </button>
    </nav>

    <div class="post-list">
      <PostCard v-for="post in posts" :key="post.id" :post="post" @deleted="onPostDeleted" />
    </div>

    <p v-if="loading" class="timeline-status">読み込み中...</p>
    <p v-else-if="reachedEnd && posts.length === 0" class="timeline-status">投稿はまだありません</p>
    <p v-else-if="reachedEnd" class="timeline-status">これ以上投稿はありません</p>

    <div ref="sentinel" class="sentinel" />
  </div>
</template>

<style scoped>
.timeline {
  display: flex;
  flex-direction: column;
  gap: 0.8rem;
}

.category-filter {
  display: flex;
  gap: 0.4rem;
  flex-wrap: wrap;
}

.category-chip {
  border: 1px solid #cbd5e1;
  background: #fff;
  border-radius: 999px;
  padding: 0.2rem 0.8rem;
  font: inherit;
  font-size: 0.85rem;
  cursor: pointer;
  color: #334155;
}

.category-chip.active {
  background: #1d4ed8;
  border-color: #1d4ed8;
  color: #fff;
}

.post-list {
  display: flex;
  flex-direction: column;
  gap: 0.6rem;
}

.timeline-status {
  text-align: center;
  color: #64748b;
  font-size: 0.9rem;
}

.timeline-signin {
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  padding: 0.9rem 1rem;
  margin: 0;
  font-size: 0.9rem;
  color: #334155;
}

.timeline-signin a {
  color: #1d4ed8;
}

.sentinel {
  height: 1px;
}
</style>
