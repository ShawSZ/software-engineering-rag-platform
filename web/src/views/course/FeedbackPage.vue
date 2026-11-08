<template>
  <section class="page-container">
    <PageHeader
      title="用户反馈"
      subtitle="查看当前反馈；重复提交会更新原记录，不累计为多条"
    /><el-card shadow="never"
      ><el-form inline class="course-filters" @submit.prevent="search"
        ><el-form-item label="评价"
          ><el-select v-model="rating" clearable placeholder="全部"
            ><el-option label="有帮助" value="HELPFUL" /><el-option
              label="无帮助"
              value="UNHELPFUL" /></el-select></el-form-item
        ><DateFilters v-model:from="from" v-model:to="to" /><el-form-item
          ><el-button type="primary" native-type="submit">查询</el-button></el-form-item
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
        :empty="!data?.items.length"
        @retry="refetch()"
        ><el-table :data="data?.items || []" row-key="id"
          ><el-table-column prop="question" label="问题" min-width="220" /><el-table-column
            prop="username"
            label="反馈人"
            width="150"
          /><el-table-column label="评价" width="110"
            ><template #default="{ row }"
              ><el-tag :type="row.rating === 'HELPFUL' ? 'success' : 'warning'">{{
                row.rating === 'HELPFUL' ? '有帮助' : '无帮助'
              }}</el-tag></template
            ></el-table-column
          ><el-table-column prop="reason" label="原因" min-width="220" /><el-table-column
            label="更新时间"
            width="180"
            ><template #default="{ row }">{{
              formatDateTime(row.updatedAt)
            }}</template></el-table-column
          ></el-table
        ></QueryPanel
      ><TablePager
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :total="data?.total || 0"
      />
      <p class="muted">
        日期按当前反馈的更新时间筛选。管理员不能通过此列表访问他人的个人问答详情。
      </p></el-card
    >
  </section>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { courseApi, type Rating, type FeedbackFilters } from '@/api/course'
import { courseQueryKeys } from '@/lib/course-query-keys'
import { useCourse, dateError } from '@/composables/useCourse'
import { formatDateTime } from '@/utils/format'
import PageHeader from '@/components/PageHeader.vue'
import QueryPanel from '@/components/course/QueryPanel.vue'
import DateFilters from '@/components/course/DateFilters.vue'
import TablePager from '@/components/TablePager.vue'
const { uid, enabled } = useCourse(),
  rating = ref<Rating>(),
  from = ref(''),
  to = ref(''),
  validation = ref(''),
  page = ref(1),
  pageSize = ref(10),
  applied = ref<FeedbackFilters>({})
watch(pageSize, () => {
  page.value = 1
})
const filters = computed(() => ({ ...applied.value, page: page.value, pageSize: pageSize.value }))
const { data, isPending, error, refetch } = useQuery({
  queryKey: computed(() => courseQueryKeys.feedback(uid.value, filters.value)),
  queryFn: async () => (await courseApi.admin.feedback(filters.value)).data,
  enabled,
  refetchOnMount: 'always'
})
function search() {
  validation.value = dateError(from.value, to.value)
  if (!validation.value) {
    const next = {
      rating: rating.value || undefined,
      from: from.value || undefined,
      to: to.value || undefined
    }
    const unchanged = page.value === 1 && JSON.stringify(next) === JSON.stringify(applied.value)
    applied.value = next
    page.value = 1
    if (unchanged) void refetch()
  }
}
</script>
