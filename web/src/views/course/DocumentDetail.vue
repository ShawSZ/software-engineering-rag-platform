<template>
  <section class="page-container">
    <PageHeader title="资料详情" show-back />
    <el-card shadow="never"
      ><QueryPanel :loading="isPending" :error="error" :has-data="!!data" @retry="refetch()">
        <template v-if="data"
          ><h2>{{ data.title }}</h2>
          <p>{{ data.categoryName }} · {{ data.status === 'OFFLINE' ? '已下架' : '在架' }}</p>
          <VersionState :document="data" /><el-descriptions :column="2" border class="detail-meta"
            ><el-descriptions-item label="文件类型">{{
              data.currentVersion?.fileType || data.latestVersion.fileType
            }}</el-descriptions-item
            ><el-descriptions-item label="文件大小">{{
              formatFileSize(data.currentVersion?.fileSize || data.latestVersion.fileSize)
            }}</el-descriptions-item
            ><el-descriptions-item label="原文件名">{{
              data.currentVersion?.originalFilename || data.latestVersion.originalFilename
            }}</el-descriptions-item
            ><el-descriptions-item label="创建时间">{{
              formatDateTime(data.createdAt)
            }}</el-descriptions-item></el-descriptions
          >
          <div class="detail-actions">
            <el-button
              v-if="readable"
              @click="$router.push({ path: '/study', query: { document: data.id } })"
              >用此资料生成复习内容</el-button
            >
            <el-button
              type="primary"
              :disabled="!readable || downloading"
              :loading="downloading"
              @click="download"
              >下载当前原文件</el-button
            ><el-button
              v-if="auth.isAdmin"
              :disabled="data.status !== 'ACTIVE'"
              @click="edit('edit')"
              >编辑信息</el-button
            ><el-button
              v-if="auth.isAdmin"
              :disabled="
                data.status !== 'ACTIVE' ||
                ['QUEUED', 'PARSING'].includes(data.latestVersion.status)
              "
              @click="edit('replace')"
              >替换原文件</el-button
            >
          </div>
          <el-alert
            v-if="!readable"
            title="当前没有可访问的有效原件"
            type="info"
            :closable="false"
          /><el-alert v-if="actionError" :title="actionError" type="error" :closable="false" />
        </template> </QueryPanel
    ></el-card>
    <el-card v-if="readable" shadow="never" class="detail-blocks"
      ><h3>当前版本原文与定位</h3>
      <el-form inline @submit.prevent="locate"
        ><el-form-item label="块号"
          ><el-input-number v-model="location" :min="0" :precision="0" /></el-form-item
        ><el-form-item
          ><el-button native-type="submit">定位</el-button
          ><el-button @click="clearLocation">全部原文</el-button></el-form-item
        ></el-form
      >
      <QueryPanel
        :loading="chunks.isPending.value"
        :error="chunks.error.value"
        :has-data="!!chunks.data.value"
        :empty="!chunks.data.value?.items.length"
        @retry="chunks.refetch()"
        ><article v-for="chunk in chunks.data.value?.items || []" :key="chunk.chunkId">
          <h4>
            {{
              chunk.pageNo === null
                ? `块 ${chunk.chunkIndex}`
                : `第 ${chunk.pageNo} 页 · 块 ${chunk.chunkIndex}`
            }}
          </h4>
          <pre class="source-text">{{ chunk.content }}</pre>
        </article></QueryPanel
      >
      <TablePager
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :total="chunks.data.value?.total || 0"
      />
    </el-card>
    <DocumentForm
      v-model:open="dialogOpen"
      :mode="mode"
      :document="data"
      :categories="categories.data.value || []"
      @saved="refresh()"
      @conflict="refresh()"
    />
  </section>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { courseApi } from '@/api/course'
import { courseQueryKeys } from '@/lib/course-query-keys'
import { useCourse, message, downloadDocument } from '@/composables/useCourse'
import { formatDateTime, formatFileSize } from '@/utils/format'
import PageHeader from '@/components/PageHeader.vue'
import TablePager from '@/components/TablePager.vue'
import QueryPanel from '@/components/course/QueryPanel.vue'
import VersionState from '@/components/course/VersionState.vue'
import DocumentForm from '@/components/course/DocumentForm.vue'
const route = useRoute(),
  { uid, auth, enabled, refresh } = useCourse()
const id = computed(() => Number(route.params.id))
const { data, isPending, error, refetch } = useQuery({
  queryKey: computed(() => courseQueryKeys.document(uid.value, id.value)),
  queryFn: async () => (await courseApi.documents.detail(id.value)).data,
  enabled,
  refetchOnMount: 'always'
})
const readable = computed(
  () => data.value?.status === 'ACTIVE' && data.value.currentVersion?.status === 'READY'
)
const page = ref(1),
  pageSize = ref(10),
  location = ref<number>(),
  chunkIndex = ref<number>()
watch(pageSize, () => {
  page.value = 1
})
watch(id, () => {
  page.value = 1
  chunkIndex.value = undefined
  location.value = undefined
})
const chunks = useQuery({
  queryKey: computed(() =>
    courseQueryKeys.chunks(uid.value, id.value, {
      page: page.value,
      pageSize: pageSize.value,
      chunkIndex: chunkIndex.value
    })
  ),
  queryFn: async () =>
    (
      await courseApi.documents.chunks(id.value, {
        page: page.value,
        pageSize: pageSize.value,
        chunkIndex: chunkIndex.value
      })
    ).data,
  enabled: computed(() => enabled.value && !!readable.value),
  refetchOnMount: 'always'
})
const categories = useQuery({
  queryKey: computed(() => courseQueryKeys.categories(uid.value)),
  queryFn: async () => (await courseApi.categories.list()).data,
  enabled: computed(() => enabled.value && auth.isAdmin)
})
const downloading = ref(false),
  actionError = ref(''),
  dialogOpen = ref(false),
  mode = ref<'edit' | 'replace'>('edit')
function edit(value: typeof mode.value) {
  mode.value = value
  dialogOpen.value = true
}
function locate() {
  chunkIndex.value = location.value
  page.value = 1
}
function clearLocation() {
  location.value = undefined
  locate()
}
async function download() {
  if (downloading.value) return
  downloading.value = true
  actionError.value = ''
  try {
    await downloadDocument(id.value)
  } catch (failure: unknown) {
    actionError.value = message(failure)
  } finally {
    downloading.value = false
  }
}
</script>
<style scoped>
.detail-meta,
.detail-actions,
.detail-blocks {
  margin-top: 20px;
}
.detail-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 20px;
}
.source-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: inherit;
  line-height: 1.8;
}
article {
  padding: 12px 0;
  border-bottom: 1px solid var(--fk-border);
}
</style>
