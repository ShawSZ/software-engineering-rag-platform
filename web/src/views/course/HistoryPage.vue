<template>
  <section class="page-container">
    <PageHeader
      :title="favorites ? '我的收藏' : '我的问答历史'"
      subtitle="仅展示当前账号的记录；成功、拒答和失败均可回看"
    /><el-card shadow="never"
      ><el-form v-if="!favorites" inline
        ><el-form-item label="结果状态"
          ><el-select v-model="status" clearable placeholder="全部状态"
            ><el-option label="回答成功" value="SUCCESS" /><el-option
              label="依据不足"
              value="NO_EVIDENCE" /><el-option
              label="问答失败"
              value="FAILED" /></el-select></el-form-item></el-form
      ><QueryPanel
        :loading="isPending"
        :error="error"
        :has-data="!!data"
        :empty="!data?.items.length"
        :refreshing="isFetching && !isPending"
        :empty-text="favorites ? '还没有收藏的回答' : '还没有问答记录'"
        @retry="refetch()"
        ><el-table :data="data?.items || []" row-key="qaId"
          ><HistoryColumn label="问题" min-width="220"
            ><template #default="{ row }"
              ><router-link :to="`/history/${row.qaId}`">{{ row.question }}</router-link>
              <p class="history-preview">{{ row.answerPreview || row.errorMessage }}</p></template
            ></HistoryColumn
          ><HistoryColumn label="结果" width="120"
            ><template #default="{ row }"
              ><el-tag
                :type="
                  row.status === 'SUCCESS'
                    ? 'success'
                    : row.status === 'FAILED'
                      ? 'danger'
                      : 'warning'
                "
                >{{ labels[row.status as QaStatus] }}</el-tag
              ></template
            ></HistoryColumn
          ><HistoryColumn label="时间" width="170"
            ><template #default="{ row }">{{
              formatDateTime(row.createdAt)
            }}</template></HistoryColumn
          ><HistoryColumn label="耗时" width="100"
            ><template #default="{ row }">{{ row.latencyMs }} ms</template></HistoryColumn
          ><HistoryColumn label="收藏" width="110"
            ><template #default="{ row }"
              ><el-button
                v-if="row.status === 'SUCCESS'"
                link
                :disabled="changing === row.qaId"
                @click="toggle(row)"
                >{{ row.favorited ? '取消收藏' : '收藏' }}</el-button
              ><span v-else>—</span></template
            ></HistoryColumn
          ></el-table
        ></QueryPanel
      ><el-alert
        v-if="actionError"
        type="error"
        :title="actionError"
        :closable="false" /><TablePager
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :total="data?.total || 0"
    /></el-card>
  </section>
</template>
<script setup lang="ts">
import { ElTableColumn } from 'element-plus'
const HistoryColumn = ElTableColumn<QaSummary>
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { courseApi, type QaStatus, type QaSummary } from '@/api/course'
import { courseQueryKeys } from '@/lib/course-query-keys'
import { useCourse, message } from '@/composables/useCourse'
import { formatDateTime } from '@/utils/format'
import PageHeader from '@/components/PageHeader.vue'
import TablePager from '@/components/TablePager.vue'
import QueryPanel from '@/components/course/QueryPanel.vue'
const route = useRoute(),
  { uid, enabled, refresh } = useCourse(),
  favorites = computed(() => route.path === '/favorites')
const page = ref(1),
  pageSize = ref(10),
  status = ref<QaStatus>(),
  changing = ref(0),
  actionError = ref('')
const labels = { SUCCESS: '回答成功', NO_EVIDENCE: '依据不足', FAILED: '问答失败' }
watch([pageSize, status, favorites], () => {
  page.value = 1
})
const filters = computed(() => ({
  page: page.value,
  pageSize: pageSize.value,
  status: status.value
}))
const { data, isPending, error, isFetching, refetch } = useQuery({
  queryKey: computed(() =>
    favorites.value
      ? courseQueryKeys.favorites(uid.value, filters.value)
      : courseQueryKeys.history(uid.value, filters.value)
  ),
  queryFn: async () =>
    (
      await (favorites.value
        ? courseApi.qa.favorites({ page: page.value, pageSize: pageSize.value })
        : courseApi.qa.history(filters.value))
    ).data,
  enabled,
  refetchOnMount: 'always'
})
async function toggle(row: QaSummary) {
  if (changing.value) return
  changing.value = row.qaId
  actionError.value = ''
  try {
    await (row.favorited ? courseApi.qa.unfavorite(row.qaId) : courseApi.qa.favorite(row.qaId))
    await refresh()
    if (data.value && !data.value.items.length && page.value > 1) page.value--
  } catch (failure: unknown) {
    actionError.value = message(failure)
  } finally {
    changing.value = 0
  }
}
</script>
<style scoped>
.history-preview {
  font-size: 13px;
  color: var(--fk-text-secondary);
  line-height: 1.6;
  margin: 8px 0;
}
</style>
