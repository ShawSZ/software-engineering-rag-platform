<template>
  <el-skeleton v-if="loading" :rows="4" animated />
  <template v-else>
    <el-alert v-if="error" type="error" :closable="false" show-icon class="query-error"
      ><template #title>{{ message(error) }}</template
      ><el-button link type="primary" @click="$emit('retry')">重新加载</el-button></el-alert
    >
    <p v-if="refreshing" class="muted" role="status">正在刷新…</p>
    <template v-if="!error || hasData"
      ><EmptyState v-if="empty" :description="emptyText || '暂无数据'"
        ><slot name="empty" /></EmptyState
      ><slot v-else
    /></template>
  </template>
</template>
<script setup lang="ts">
import EmptyState from '@/components/EmptyState.vue'
import { message } from '@/composables/useCourse'
defineProps<{
  loading: boolean
  error?: unknown
  empty?: boolean
  hasData?: boolean
  refreshing?: boolean
  emptyText?: string
}>()
defineEmits<{ retry: [] }>()
</script>
<style scoped>
.query-error {
  margin-bottom: 16px;
}
.muted {
  color: var(--fk-text-secondary);
  font-size: 13px;
}
</style>
