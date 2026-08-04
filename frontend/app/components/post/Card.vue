<script setup lang="ts">
import type { Post } from '~/types/post'

const props = defineProps<{
  post: Post
  /** 詳細ページでは本文へのリンクを無効にする */
  linkDisabled?: boolean
}>()

const emit = defineEmits<{
  deleted: [id: number]
}>()

const { deletePost } = usePosts()
const deleting = ref(false)

async function onDelete() {
  if (!confirm('この投稿を削除しますか?')) return
  deleting.value = true
  try {
    await deletePost(props.post.id)
    emit('deleted', props.post.id)
  } catch {
    alert('削除に失敗しました')
  } finally {
    deleting.value = false
  }
}
</script>

<template>
  <article class="post-card">
    <div class="post-header">
      <span class="post-author">{{ post.user.displayName }}</span>
      <span class="post-username">@{{ post.user.username }}</span>
      <span class="post-category">{{ post.category.name }}</span>
      <time class="post-time">{{ formatRelativeTime(post.createdAt) }}</time>
    </div>
    <p v-if="linkDisabled" class="post-body">{{ post.body }}</p>
    <NuxtLink v-else :to="`/posts/${post.id}`" class="post-body-link">
      <p class="post-body">{{ post.body }}</p>
    </NuxtLink>
    <div class="post-actions">
      <button class="post-delete" :disabled="deleting" @click="onDelete">削除</button>
    </div>
  </article>
</template>

<style scoped>
.post-card {
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  padding: 0.9rem 1rem;
}

.post-header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.85rem;
  flex-wrap: wrap;
}

.post-author {
  font-weight: 700;
}

.post-username,
.post-time {
  color: #64748b;
}

.post-time {
  margin-left: auto;
}

.post-category {
  background: #eff6ff;
  color: #1d4ed8;
  border-radius: 999px;
  padding: 0 0.6rem;
  font-size: 0.75rem;
}

.post-body-link {
  color: inherit;
  text-decoration: none;
}

.post-body {
  margin: 0.5rem 0 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.post-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 0.4rem;
}

.post-delete {
  border: none;
  background: none;
  color: #94a3b8;
  font-size: 0.8rem;
  cursor: pointer;
}

.post-delete:hover {
  color: #dc2626;
}
</style>
