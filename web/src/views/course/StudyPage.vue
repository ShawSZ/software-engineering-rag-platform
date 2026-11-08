<template>
  <section class="page-container">
    <PageHeader
      title="复习助手"
      subtitle="依据课程资料生成复习提纲或简单练习题，附参考答案和原文来源"
    />
    <el-card shadow="never">
      <el-form data-testid="study-form" label-position="top" @submit.prevent="generate">
        <el-form-item label="课程资料">
          <el-select
            v-model="documentId"
            filterable
            remote
            clearable
            :remote-method="search"
            :loading="listing"
            :disabled="busy"
            placeholder="选择资料，可输入标题搜索"
            style="width: 100%"
            @change="clearResult"
          >
            <el-option
              v-for="document in documents"
              :key="document.id"
              :value="document.id"
              :label="document.title"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="生成内容">
          <el-radio-group v-model="kind" :disabled="busy" @change="clearResult">
            <el-radio-button value="OUTLINE">复习提纲</el-radio-button>
            <el-radio-button value="EXERCISES">简单练习题</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="kind === 'EXERCISES'" label="题目数量">
          <el-input-number
            v-model="questionCount"
            :min="1"
            :max="5"
            :precision="0"
            :disabled="busy"
          />
          <small class="count-note">含参考答案，适合课后自测</small>
        </el-form-item>
        <p class="muted">
          长资料会选取代表性原文片段，生成内容不保证覆盖全部章节。每个要点或题目均可核对出处。
        </p>
        <el-button
          type="primary"
          native-type="submit"
          :loading="busy"
          :disabled="busy || listing"
          >{{ busy ? '正在生成…' : '生成复习内容' }}</el-button
        >
        <router-link to="/history" class="history-link">查看已保存的生成结果</router-link>
      </el-form>
      <el-alert v-if="listError" :title="listError" type="error" :closable="false" show-icon />
      <el-button v-if="listError" :disabled="listing || busy" @click="search('')"
        >重新加载资料</el-button
      >
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    </el-card>
    <el-card v-if="result" shadow="never" class="study-result">
      <p class="muted">
        本次参考 {{ result.selectedChunkCount }} /
        {{ result.totalChunkCount }} 段原文；结果已保存在我的历史。
      </p>
      <QaResult :value="result.result" :retrying="busy" @retry="generate" />
    </el-card>
    <el-empty v-else-if="!busy" description="选择一份课程资料，生成你的复习内容" />
  </section>
</template>
<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { courseApi, type Document, type StudyKind, type StudyResult } from '@/api/course'
import { useCourse, message } from '@/composables/useCourse'
import PageHeader from '@/components/PageHeader.vue'
import QaResult from '@/components/course/QaResult.vue'
const route = useRoute(),
  { uid, auth, refresh } = useCourse()
const documents = ref<Document[]>([]),
  documentId = ref<number>(),
  kind = ref<StudyKind>('OUTLINE'),
  questionCount = ref(3)
const busy = ref(false),
  listing = ref(false),
  listError = ref(''),
  error = ref(''),
  result = ref<StudyResult>()
let alive = true,
  request = 0
onBeforeUnmount(() => {
  alive = false
  request++
})
watch(uid, () => {
  documents.value = []
  documentId.value = undefined
  result.value = undefined
  error.value = ''
  request++
})
function clearResult() {
  result.value = undefined
  error.value = ''
}
async function search(title: string) {
  const stamp = ++request,
    owner = uid.value
  listing.value = true
  listError.value = ''
  try {
    const response = await courseApi.documents.list({
      title: title.trim() || undefined,
      pageSize: 50
    })
    if (alive && stamp === request && uid.value === owner) documents.value = response.data.items
  } catch (failure: unknown) {
    if (alive && stamp === request) listError.value = message(failure)
  } finally {
    if (stamp === request) listing.value = false
  }
}
onMounted(async () => {
  await search('')
  const value = route.query.document
  if (typeof value !== 'string' || !/^[1-9][0-9]*$/.test(value)) return
  const id = Number(value)
  if (!Number.isSafeInteger(id)) return
  const owner = uid.value
  try {
    const response = await courseApi.documents.detail(id)
    if (!alive || uid.value !== owner) return
    if (response.data.status !== 'ACTIVE' || response.data.currentVersion?.status !== 'READY') {
      error.value = '所选资料当前不可用'
      return
    }
    if (!documents.value.some(d => d.id === id)) documents.value.push(response.data)
    documentId.value = id
  } catch (failure: unknown) {
    if (alive && uid.value === owner) error.value = message(failure)
  }
})
async function generate() {
  if (busy.value || listing.value) return
  if (!documentId.value) {
    error.value = '请选择一份有效课程资料'
    return
  }
  if (
    kind.value === 'EXERCISES' &&
    (!Number.isInteger(questionCount.value) || questionCount.value < 1 || questionCount.value > 5)
  ) {
    error.value = '题目数量须为1—5'
    return
  }
  busy.value = true
  error.value = ''
  result.value = undefined
  const owner = uid.value
  try {
    const response = await courseApi.study.generate(
      documentId.value,
      kind.value,
      kind.value === 'EXERCISES' ? questionCount.value : undefined
    )
    if (!alive || uid.value !== owner || !auth.isLoggedIn) return
    result.value = response.data
    await refresh()
  } catch (failure: unknown) {
    if (alive && uid.value === owner) error.value = message(failure)
  } finally {
    if (alive && uid.value === owner) busy.value = false
  }
}
</script>
<style scoped>
.study-result {
  margin-top: 20px;
}
.history-link,
.count-note {
  margin-left: 18px;
}
</style>
