<script setup lang="ts">
import type { Category } from '~/types/category'
import type { Post } from '~/types/post'

defineProps<{
  categories: Category[]
}>()

const emit = defineEmits<{
  created: [post: Post]
}>()

const MAX_LENGTH = 280

const { createPost } = usePosts()
const body = ref('')
const categoryId = ref<number | null>(null)
const submitting = ref(false)
const errorMessage = ref('')

const remaining = computed(() => MAX_LENGTH - body.value.length)
const canSubmit = computed(
  () => body.value.trim().length > 0 && remaining.value >= 0 && categoryId.value !== null && !submitting.value,
)

async function onSubmit() {
  if (!canSubmit.value || categoryId.value === null) return
  submitting.value = true
  errorMessage.value = ''
  try {
    const post = await createPost(body.value, categoryId.value)
    body.value = ''
    emit('created', post)
  } catch (e: any) {
    // バックエンドの ErrorResponse(message / fieldErrors)を表示する
    const data = e?.data
    errorMessage.value =
      (data?.fieldErrors && Object.values(data.fieldErrors as Record<string, string>)[0]) ||
      data?.message ||
      '投稿に失敗しました'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <form class="post-form" @submit.prevent="onSubmit">
    <textarea
      v-model="body"
      class="post-form-body"
      rows="3"
      placeholder="いまどうしてる?"
    />
    <p v-if="errorMessage" class="post-form-error">{{ errorMessage }}</p>
    <div class="post-form-footer">
      <select v-model="categoryId" class="post-form-category">
        <option :value="null" disabled>カテゴリーを選択</option>
        <option v-for="category in categories" :key="category.id" :value="category.id">
          {{ category.name }}
        </option>
      </select>
      <span class="post-form-counter" :class="{ over: remaining < 0 }">{{ remaining }}</span>
      <button type="submit" class="post-form-submit" :disabled="!canSubmit">投稿する</button>
    </div>
  </form>
</template>

<style scoped>
.post-form {
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  padding: 0.9rem 1rem;
}

.post-form-body {
  width: 100%;
  border: none;
  resize: vertical;
  font: inherit;
  outline: none;
}

.post-form-error {
  color: #dc2626;
  font-size: 0.85rem;
  margin: 0.3rem 0 0;
}

.post-form-footer {
  display: flex;
  align-items: center;
  gap: 0.7rem;
  margin-top: 0.5rem;
}

.post-form-category {
  font: inherit;
  font-size: 0.85rem;
  padding: 0.25rem 0.4rem;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
}

.post-form-counter {
  margin-left: auto;
  font-size: 0.85rem;
  color: #64748b;
}

.post-form-counter.over {
  color: #dc2626;
  font-weight: 700;
}

.post-form-submit {
  background: #1d4ed8;
  color: #fff;
  border: none;
  border-radius: 999px;
  padding: 0.4rem 1.1rem;
  font: inherit;
  font-size: 0.9rem;
  cursor: pointer;
}

.post-form-submit:disabled {
  background: #93c5fd;
  cursor: not-allowed;
}
</style>
