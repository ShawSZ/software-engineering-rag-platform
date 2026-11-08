<template>
  <el-drawer
    v-model="open"
    :title="target?.kind === 'source' ? '当时引用的来源快照' : '当前有效版本原文'"
    size="min(680px, 95vw)"
    destroy-on-close
  >
    <QueryPanel :loading="isPending" :error="error" :has-data="!!data" @retry="refetch()">
      <template v-if="data && 'excerpt' in data"
        ><h3>{{ data.documentTitle }}</h3>
        <p>
          v{{ data.versionNumber }} ·
          {{
            data.pageNo === null
              ? `块 ${data.chunkIndex}`
              : `第 ${data.pageNo} 页 · 块 ${data.chunkIndex}`
          }}
          <el-tag>{{ availability[data.availability] }}</el-tag>
        </p>
        <p v-if="data.availability !== 'CURRENT'">
          资料已更新或下架，以下保留回答当时的原文，不代表当前版本。
        </p>
        <pre class="source-text">{{ data.excerpt }}</pre>
      </template>
      <template v-else-if="data"
        ><article v-for="chunk in data.items" :key="chunk.chunkId">
          <h4>
            {{
              chunk.pageNo === null
                ? `块 ${chunk.chunkIndex}`
                : `第 ${chunk.pageNo} 页 · 块 ${chunk.chunkIndex}`
            }}
          </h4>
          <pre class="source-text">{{ chunk.content }}</pre>
        </article>
        <EmptyState v-if="!data.items.length" description="未找到对应原文块" /><TablePager
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :total="data.total"
      /></template>
    </QueryPanel>
  </el-drawer>
</template>
<script setup lang="ts">
import { computed, onDeactivated, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { courseApi, type Source, type Page, type Chunk } from '@/api/course'
import { courseQueryKeys } from '@/lib/course-query-keys'
import { useCourse } from '@/composables/useCourse'
import QueryPanel from './QueryPanel.vue'
import TablePager from '@/components/TablePager.vue'
import EmptyState from '@/components/EmptyState.vue'
export type PreviewTarget =
  | { kind: 'source'; qaId: number; sourceId: number }
  | { kind: 'current'; documentId: number; chunkIndex?: number }
const props = defineProps<{ target?: PreviewTarget }>()
const open = defineModel<boolean>({ default: false })
onDeactivated(() => {
  open.value = false
})
const page = ref(1),
  pageSize = ref(10)
const { uid, auth } = useCourse()
const availability = { CURRENT: '当前有效', UPDATED: '资料已更新', OFFLINE: '资料已下架' }
watch(
  () => props.target,
  () => {
    page.value = 1
  }
)
const { data, isPending, error, refetch } = useQuery<Source | Page<Chunk>>({
  queryKey: computed(() => {
    const target = props.target
    return target?.kind === 'source'
      ? courseQueryKeys.source(uid.value, target.qaId, target.sourceId)
      : courseQueryKeys.chunks(uid.value, target?.documentId || 0, {
          page: page.value,
          pageSize: pageSize.value,
          chunkIndex: target?.chunkIndex
        })
  }),
  queryFn: async () => {
    const target = props.target
    if (!target) throw new Error('未选择原文')
    return target.kind === 'source'
      ? (await courseApi.qa.source(target.qaId, target.sourceId)).data
      : (
          await courseApi.documents.chunks(target.documentId, {
            page: page.value,
            pageSize: pageSize.value,
            chunkIndex: target.chunkIndex
          })
        ).data
  },
  enabled: computed(() => open.value && !!props.target && auth.isLoggedIn),
  staleTime: 0
})
</script>
<style scoped>
.source-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: inherit;
  line-height: 1.8;
  padding: 16px;
  background: var(--fk-surface-muted);
  border-radius: 8px;
}
article + article {
  border-top: 1px solid var(--fk-border);
}
</style>
