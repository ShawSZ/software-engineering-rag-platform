<template>
  <section class="page-container">
    <PageHeader title="统计概览" subtitle="按业务自然日筛选，默认显示全部数据" /><el-card
      shadow="never"
      ><el-form inline class="course-filters" @submit.prevent="search"
        ><DateFilters v-model:from="from" v-model:to="to" /><el-form-item
          ><el-button type="primary" native-type="submit">更新统计</el-button
          ><el-button @click="reset">全部时间</el-button></el-form-item
        ></el-form
      ><el-alert
        v-if="validation"
        type="warning"
        :title="validation"
        :closable="false"
      /><QueryPanel
        :loading="isPending"
        :error="error"
        :has-data="!!data"
        :refreshing="isFetching && !isPending"
        @retry="refetch()"
        ><template v-if="data"
          ><p class="muted">
            当前范围：{{ data.from || '不限起始日期' }} 至
            {{ data.to || '不限结束日期' }}（Asia/Shanghai）
          </p>
          <div class="stat-grid">
            <div v-for="item in cards" :key="item.key" class="stat-card">
              <span>{{ item.label }}</span
              ><strong>{{ data[item.key] }}</strong>
            </div>
          </div>
          <div class="stat-notes">
            <p>资料记录数、用户数按创建时间筛选；资料包含下架与解析失败记录，用户包含停用账号。</p>
            <p>
              问答按创建时间分别统计成功、拒答与失败。反馈按当前记录更新时间统计，不按提交次数累计。
            </p>
            <p>起始日包含当日 00:00，结束日包含当天全部时间。</p>
          </div></template
        ></QueryPanel
      ></el-card
    >
  </section>
</template>
<script setup lang="ts">
import { computed, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { courseApi, type DateFilters as Filters } from '@/api/course'
import { courseQueryKeys } from '@/lib/course-query-keys'
import { useCourse, dateError } from '@/composables/useCourse'
import PageHeader from '@/components/PageHeader.vue'
import QueryPanel from '@/components/course/QueryPanel.vue'
import DateFilters from '@/components/course/DateFilters.vue'
const { uid, enabled } = useCourse(),
  from = ref(''),
  to = ref(''),
  validation = ref(''),
  filters = ref<Filters>({})
const cards = [
  { key: 'documentCount', label: '资料记录' },
  { key: 'userCount', label: '注册用户' },
  { key: 'qaSuccessCount', label: '成功回答' },
  { key: 'qaNoEvidenceCount', label: '依据不足' },
  { key: 'qaFailedCount', label: '问答失败' },
  { key: 'feedbackHelpfulCount', label: '有帮助反馈' },
  { key: 'feedbackUnhelpfulCount', label: '无帮助反馈' }
] as const
const { data, isPending, error, isFetching, refetch } = useQuery({
  queryKey: computed(() => courseQueryKeys.stats(uid.value, filters.value)),
  queryFn: async () => (await courseApi.admin.stats(filters.value)).data,
  enabled,
  refetchOnMount: 'always'
})
function search() {
  validation.value = dateError(from.value, to.value)
  if (!validation.value) {
    const next = { from: from.value || undefined, to: to.value || undefined }
    const unchanged = JSON.stringify(next) === JSON.stringify(filters.value)
    filters.value = next
    if (unchanged) void refetch()
  }
}
function reset() {
  from.value = ''
  to.value = ''
  search()
}
</script>
<style scoped>
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
  gap: 16px;
  margin: 20px 0;
}
.stat-card {
  display: grid;
  gap: 16px;
  padding: 20px;
  border: 1px solid var(--fk-border);
  border-radius: 10px;
}
.stat-card span {
  color: var(--fk-text-secondary);
  font-size: 14px;
}
.stat-card strong {
  color: var(--fk-primary);
  font-size: 32px;
}
.stat-notes {
  line-height: 1.8;
  font-size: 13px;
  color: var(--fk-text-secondary);
}
</style>
