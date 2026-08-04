<script setup lang="ts">
import type { Post } from '~/types/post'

const route = useRoute()
const router = useRouter()
const { fetchPost } = usePosts()

const post = ref<Post | null>(null)
const notFound = ref(false)

// SSG では全投稿の HTML を事前生成できないため、詳細はクライアント側で取得して描画する
onMounted(async () => {
  try {
    post.value = await fetchPost(route.params.id as string)
  } catch {
    notFound.value = true
  }
})

function onDeleted() {
  router.push('/')
}
</script>

<template>
  <div class="post-detail">
    <NuxtLink to="/" class="back-link">← タイムラインへ戻る</NuxtLink>

    <PostCard v-if="post" :post="post" link-disabled @deleted="onDeleted" />
    <p v-else-if="notFound" class="detail-status">投稿が見つかりませんでした</p>
    <p v-else class="detail-status">読み込み中...</p>
  </div>
</template>

<style scoped>
.post-detail {
  display: flex;
  flex-direction: column;
  gap: 0.8rem;
}

.back-link {
  color: #1d4ed8;
  text-decoration: none;
  font-size: 0.9rem;
}

.detail-status {
  text-align: center;
  color: #64748b;
  font-size: 0.9rem;
}
</style>
