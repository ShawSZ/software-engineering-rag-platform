<template>
  <section class="page-container">
    <PageHeader title="问答记录" show-back />
    <p v-if="data">
      <router-link :to="{ path: '/qa', query: { turn: data.qaId } }"
        >从这条记录继续对话</router-link
      >
    </p>
    <el-card shadow="never"
      ><QueryPanel :loading="isPending" :error="error" :has-data="!!data" @retry="refetch()"
        ><QaResult
          v-if="data"
          :key="data.qaId"
          :value="data"
          @changed="refetch()"
          @retry="retry" /></QueryPanel
    ></el-card>
  </section>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { courseApi } from '@/api/course'
import { courseQueryKeys } from '@/lib/course-query-keys'
import { useCourse } from '@/composables/useCourse'
import { useDraftStore } from '@/stores/drafts'
import PageHeader from '@/components/PageHeader.vue'
import QueryPanel from '@/components/course/QueryPanel.vue'
import QaResult from '@/components/course/QaResult.vue'
const route = useRoute(),
  router = useRouter(),
  { uid, enabled } = useCourse(),
  drafts = useDraftStore()
const id = computed(() => Number(route.params.qaId))
const { data, isPending, error, refetch } = useQuery({
  queryKey: computed(() => courseQueryKeys.qa(uid.value, id.value)),
  queryFn: async () => (await courseApi.qa.detail(id.value)).data,
  enabled,
  refetchOnMount: 'always'
})
function retry(question: string) {
  drafts.question = question
  void router.push({ path: '/qa', query: { turn: id.value } })
}
</script>
