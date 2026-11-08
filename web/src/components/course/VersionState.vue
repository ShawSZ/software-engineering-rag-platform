<template>
  <div class="version-state">
    <div v-if="state.currentVersion">
      <el-tag :type="document.status === 'OFFLINE' ? 'info' : 'success'" size="small"
        >当前 v{{ state.currentVersion.versionNumber }}
        {{ document.status === 'OFFLINE' ? '已下架' : '可用' }}</el-tag
      >
    </div>
    <span v-else>尚无可用版本</span>
    <div v-if="!state.currentVersion || state.latestVersion.id !== state.currentVersion.id">
      <el-tag :type="state.latestVersion.status === 'FAILED' ? 'danger' : 'warning'" size="small"
        >新 v{{ state.latestVersion.versionNumber }} ·
        {{ labels[state.latestVersion.status] }}</el-tag
      >
      <p v-if="state.latestVersion.errorMessage" class="error-text">
        {{ state.latestVersion.errorMessage }}<span v-if="state.currentVersion">；旧版仍可用</span>
      </p>
    </div>
    <small v-if="pending"
      >后台正在处理，页面可见时自动更新；若长时间无变化，请刷新或联系管理员</small
    ><small v-if="status.error.value" class="error-text">{{ message(status.error.value) }}</small>
  </div>
</template>
<script setup lang="ts">
import { computed, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { courseApi, type Document } from '@/api/course'
import { courseQueryKeys } from '@/lib/course-query-keys'
import { useCourse, usePageVisible, message } from '@/composables/useCourse'
const props = defineProps<{ document: Document }>()
const { auth, uid, refresh } = useCourse()
const visible = usePageVisible()
const labels = { QUEUED: '排队中', PARSING: '解析中', READY: '已就绪', FAILED: '解析失败' }
const pending = computed(() => ['QUEUED', 'PARSING'].includes(props.document.latestVersion.status))
const status = useQuery({
  queryKey: computed(() => courseQueryKeys.documentStatus(uid.value, props.document.id)),
  queryFn: async () => (await courseApi.documents.status(props.document.id)).data,
  enabled: computed(() => auth.isLoggedIn && auth.isAdmin && visible.value && pending.value),
  refetchInterval: query =>
    ['QUEUED', 'PARSING'].includes(
      query.state.data?.latestVersion.status || props.document.latestVersion.status
    )
      ? 2000
      : false,
  staleTime: 0
})
const state = computed(() =>
  status.data.value?.latestVersion.id === props.document.latestVersion.id
    ? status.data.value
    : props.document
)
let published = 0
watch(status.data, value => {
  if (
    value &&
    value.latestVersion.id === props.document.latestVersion.id &&
    !['QUEUED', 'PARSING'].includes(value.latestVersion.status) &&
    published !== value.latestVersion.id
  ) {
    published = value.latestVersion.id
    void refresh()
  }
})
</script>
<style scoped>
.version-state {
  display: grid;
  gap: 6px;
}
.version-state p {
  margin: 4px 0;
}
.error-text {
  color: var(--fk-danger);
}
small {
  color: var(--fk-text-secondary);
}
</style>
