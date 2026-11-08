<template>
  <section class="page-container">
    <PageHeader
      :title="admin ? '资料管理' : '课程资料库'"
      :subtitle="
        admin ? '维护课程原件、解析状态与有效版本' : '按分类、标题和正文关键词查找课程资料'
      "
      ><template #actions
        ><el-button v-if="admin" type="primary" @click="edit('upload')"
          >上传资料</el-button
        ></template
      ></PageHeader
    >
    <el-card shadow="never">
      <el-alert
        v-if="categories.error.value"
        type="error"
        :title="message(categories.error.value)"
        :closable="false"
        ><el-button link @click="categories.refetch()">重新加载分类</el-button></el-alert
      >
      <el-form class="course-filters" inline @submit.prevent="search">
        <el-form-item label="分类"
          ><el-select v-model="form.categoryId" clearable placeholder="全部分类" @change="search"
            ><el-option
              v-for="category in categories.data.value || []"
              :key="category.id"
              :label="category.name"
              :value="category.id" /></el-select
        ></el-form-item>
        <el-form-item label="标题"
          ><el-input
            v-model="form.title"
            data-testid="title-draft"
            clearable
            placeholder="资料标题" /></el-form-item
        ><el-form-item label="关键词"
          ><el-input v-model="form.keyword" clearable placeholder="标题或正文关键词"
        /></el-form-item>
        <el-form-item v-if="admin" label="状态"
          ><el-select v-model="form.status" clearable placeholder="全部状态"
            ><el-option label="在架" value="ACTIVE" /><el-option
              label="已下架"
              value="OFFLINE" /></el-select
        ></el-form-item>
        <el-form-item
          ><el-button type="primary" native-type="submit">搜索</el-button
          ><el-button @click="reset">清除筛选</el-button></el-form-item
        >
      </el-form>
      <el-alert v-if="validation" type="warning" :title="validation" :closable="false" />
      <QueryPanel
        :loading="isPending"
        :error="error"
        :has-data="!!data"
        :empty="!data?.items.length"
        :refreshing="isFetching && !isPending"
        empty-text="没有符合条件的资料"
        @retry="refetch()"
        ><template #empty
          ><el-button link type="primary" @click="reset">清除筛选条件</el-button></template
        >
        <el-table :data="data?.items || []" row-key="id">
          <DocumentColumn label="资料标题" min-width="180"
            ><template #default="{ row }"
              ><router-link :to="`/documents/${row.id}`">{{ row.title }}</router-link></template
            ></DocumentColumn
          >
          <DocumentColumn prop="categoryName" label="分类" width="130" /><DocumentColumn
            label="有效版本与解析"
            min-width="260"
            ><template #default="{ row }"><VersionState :document="row" /></template
          ></DocumentColumn>
          <DocumentColumn v-if="admin" label="状态" width="100"
            ><template #default="{ row }"
              ><el-tag :type="row.status === 'OFFLINE' ? 'info' : 'success'">{{
                row.status === 'OFFLINE' ? '已下架' : '在架'
              }}</el-tag></template
            ></DocumentColumn
          >
          <DocumentColumn label="操作" :min-width="admin ? 260 : 100"
            ><template #default="{ row }"
              ><el-button
                link
                type="primary"
                :disabled="row.status !== 'ACTIVE' || !row.currentVersion || downloading === row.id"
                @click="download(row.id)"
                >下载</el-button
              ><template v-if="admin"
                ><el-button link :disabled="row.status !== 'ACTIVE'" @click="edit('edit', row)"
                  >编辑</el-button
                ><el-button
                  link
                  :disabled="
                    row.status !== 'ACTIVE' ||
                    ['QUEUED', 'PARSING'].includes(row.latestVersion.status)
                  "
                  @click="edit('replace', row)"
                  >替换文件</el-button
                ><el-button
                  link
                  type="danger"
                  :disabled="row.status === 'OFFLINE' || offlining === row.id"
                  @click="offline(row)"
                  >下架</el-button
                ></template
              ></template
            ></DocumentColumn
          >
        </el-table>
      </QueryPanel>
      <el-alert v-if="actionError" type="error" :title="actionError" :closable="false" />
      <TablePager
        :total="data?.total || 0"
        :current-page="filters.page"
        :page-size="filters.pageSize"
        @update:current-page="paginate($event, filters.pageSize)"
        @update:page-size="paginate(1, $event)"
      />
    </el-card>
    <DocumentForm
      v-model:open="dialogOpen"
      :mode="mode"
      :document="selected"
      :categories="categories.data.value || []"
      @saved="saved"
      @conflict="refresh()"
    />
  </section>
</template>
<script setup lang="ts">
import { ElTableColumn } from 'element-plus'
const DocumentColumn = ElTableColumn<Document>
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { ElMessageBox } from 'element-plus'
import { courseApi, type Document, type DocumentState } from '@/api/course'
import { courseQueryKeys } from '@/lib/course-query-keys'
import { useCourse, message, length, downloadDocument } from '@/composables/useCourse'
import { useDraftStore } from '@/stores/drafts'
import PageHeader from '@/components/PageHeader.vue'
import TablePager from '@/components/TablePager.vue'
import QueryPanel from '@/components/course/QueryPanel.vue'
import VersionState from '@/components/course/VersionState.vue'
import DocumentForm from '@/components/course/DocumentForm.vue'
const route = useRoute(),
  router = useRouter(),
  drafts = useDraftStore()
const { uid, enabled, refresh } = useCourse()
const admin = computed(() => route.path.startsWith('/admin/'))
const scalar = (value: unknown) => (typeof value === 'string' ? value : '')
const form = reactive({
  title: scalar(route.query.title) || drafts.documents.title,
  keyword: scalar(route.query.keyword) || drafts.documents.keyword,
  categoryId: Number(route.query.categoryId) || drafts.documents.categoryId,
  status: scalar(route.query.status) as DocumentState | ''
})
watch(
  () => [form.title, form.keyword, form.categoryId],
  () => {
    drafts.documents.title = form.title
    drafts.documents.keyword = form.keyword
    drafts.documents.categoryId = form.categoryId
  }
)
const filters = computed(() => ({
  title: scalar(route.query.title),
  keyword: scalar(route.query.keyword),
  categoryId: Number(route.query.categoryId) || undefined,
  status: (scalar(route.query.status) || undefined) as DocumentState | undefined,
  page: Number(route.query.page || 1),
  pageSize: Number(route.query.pageSize || 10)
}))
watch(
  () => route.query,
  () => {
    form.title = filters.value.title
    form.keyword = filters.value.keyword
    form.categoryId = filters.value.categoryId
    form.status = filters.value.status || ''
  }
)
const categories = useQuery({
  queryKey: computed(() => courseQueryKeys.categories(uid.value)),
  queryFn: async () => (await courseApi.categories.list()).data,
  enabled
})
const { data, isPending, isFetching, error, refetch } = useQuery({
  queryKey: computed(() =>
    admin.value
      ? courseQueryKeys.adminDocuments(uid.value, filters.value)
      : courseQueryKeys.documents(uid.value, filters.value)
  ),
  queryFn: async () =>
    (
      await (admin.value
        ? courseApi.documents.adminList(filters.value)
        : courseApi.documents.list(filters.value))
    ).data,
  enabled,
  refetchOnMount: 'always'
})
const validation = ref(''),
  actionError = ref(''),
  downloading = ref(0),
  offlining = ref(0)
const dialogOpen = ref(false),
  mode = ref<'upload' | 'edit' | 'replace'>('upload'),
  selected = ref<Document>()
function edit(value: typeof mode.value, document?: Document) {
  mode.value = value
  selected.value = document
  dialogOpen.value = true
}
function search() {
  validation.value =
    length(form.title.trim()) > 200 || length(form.keyword.trim()) > 100
      ? '标题最多 200 字，关键词最多 100 字'
      : ''
  if (!validation.value) {
    const target = {
      path: route.path,
      query: {
        title: form.title.trim() || undefined,
        keyword: form.keyword.trim() || undefined,
        categoryId: form.categoryId,
        status: admin.value ? form.status || undefined : undefined,
        page: 1,
        pageSize: filters.value.pageSize
      }
    }
    if (router.resolve(target).fullPath === route.fullPath) void refetch()
    else void router.push(target)
  }
}
function paginate(page: number, pageSize: number) {
  void router.push({ query: { ...route.query, page, pageSize } })
}
function reset() {
  form.title = ''
  form.keyword = ''
  form.categoryId = undefined
  form.status = ''
  search()
}
async function saved() {
  await refresh()
}
async function download(id: number) {
  if (downloading.value) return
  downloading.value = id
  actionError.value = ''
  try {
    await downloadDocument(id)
  } catch (failure: unknown) {
    actionError.value = message(failure)
  } finally {
    downloading.value = 0
  }
}
async function offline(document: Document) {
  if (offlining.value) return
  try {
    await ElMessageBox.confirm(
      `下架“${document.title}”后，将停止新的检索与下载。确认下架？`,
      '下架资料',
      { type: 'warning', confirmButtonText: '确认下架', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  offlining.value = document.id
  actionError.value = ''
  try {
    await courseApi.documents.offline(document.id)
    await refresh()
  } catch (failure: unknown) {
    actionError.value = message(failure)
    await refresh()
  } finally {
    offlining.value = 0
  }
}
</script>
