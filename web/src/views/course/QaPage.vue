<template>
  <section class="page-container">
    <PageHeader title="智能问答" subtitle="在同一对话中继续追问，每次回答都重新核验课程资料来源" />
    <div class="conversation-tools">
      <el-button :disabled="busy || loading" @click="startNew">开始新对话</el-button>
      <router-link to="/history">查看历史记录</router-link>
      <span v-if="turns.length" class="muted">本次对话 {{ turns.length }} 轮</span>
    </div>
    <el-alert v-if="loadError" type="error" :title="loadError" :closable="false" show-icon />
    <el-card v-for="turn in turns" :key="turn.qaId" shadow="never" class="result-card">
      <QaResult :value="turn" :retrying="busy" @retry="ask($event, turn.parentQaId ?? undefined)" />
    </el-card>
    <el-card v-loading="loading" shadow="never" class="question-card">
      <el-form data-testid="qa-form" label-position="top" @submit.prevent="ask()">
        <el-form-item :label="turns.length ? '继续追问' : '课程问题'">
          <el-input
            v-model="drafts.question"
            type="textarea"
            :rows="4"
            :disabled="busy || loading || !!loadError"
            :placeholder="
              turns.length ? '可以继续问：它有什么作用？能举个例子吗？' : '输入 1—500 字课程问题'
            "
            data-testid="question-draft"
            @keydown.ctrl.enter.prevent="ask()"
          />
          <small>{{ length(drafts.question.trim()) }} / 500 · Ctrl + Enter 提问</small>
        </el-form-item>
        <el-button
          type="primary"
          native-type="submit"
          :loading="busy"
          :disabled="busy || loading || !!loadError"
        >
          {{ busy ? '正在检索与生成…' : turns.length ? '发送追问' : '提交问题' }}
        </el-button>
      </el-form>
      <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon />
    </el-card>
    <el-empty
      v-if="!turns.length && !busy && !loading && !error && !loadError"
      description="回答与可核验的来源将显示在这里"
    />
  </section>
</template>
<script setup lang="ts">
import { ref, watch, onBeforeUnmount, onActivated } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { courseApi, type QaDetail } from '@/api/course'
import { useDraftStore } from '@/stores/drafts'
import { useCourse, length, message } from '@/composables/useCourse'
import PageHeader from '@/components/PageHeader.vue'
import QaResult from '@/components/course/QaResult.vue'

const drafts = useDraftStore(),
  { auth, uid, client, refresh } = useCourse()
const route = useRoute(),
  router = useRouter()
const busy = ref(false),
  loading = ref(false),
  error = ref(''),
  loadError = ref(''),
  turns = ref<QaDetail[]>([])
let epoch = 0,
  alive = true
onBeforeUnmount(() => {
  alive = false
  epoch++
})

watch(
  () => [route.path, route.query.turn, uid.value] as const,
  async ([path, value, ownerId], previous) => {
    const sameOwner = previous && ownerId === previous[2]
    // The layout caches this page: navigation away must not unlock a pending request.
    if (sameOwner && path !== '/qa') return
    const last = turns.value.at(-1)?.qaId
    if (sameOwner && (String(last) === value || value === undefined)) return
    const stamp = ++epoch,
      owner = uid.value
    turns.value = []
    error.value = ''
    loadError.value = ''
    busy.value = false
    loading.value = false
    if (value === undefined) {
      loading.value = false
      return
    }
    const id = typeof value === 'string' && /^[1-9][0-9]*$/.test(value) ? Number(value) : NaN
    if (!Number.isSafeInteger(id)) {
      loadError.value = '对话记录地址无效，请开始新对话'
      return
    }
    loading.value = true
    try {
      const response = await courseApi.qa.conversation(id)
      if (alive && epoch === stamp && uid.value === owner && auth.isLoggedIn)
        turns.value = response.data
    } catch (failure: unknown) {
      if (alive && epoch === stamp && uid.value === owner) loadError.value = message(failure)
    } finally {
      if (epoch === stamp) loading.value = false
    }
  },
  { immediate: true }
)
onActivated(() => {
  const last = turns.value.at(-1)?.qaId
  if (route.path === '/qa' && last && route.query.turn === undefined)
    void router.replace({ path: '/qa', query: { turn: last } })
})

function startNew() {
  epoch++
  turns.value = []
  error.value = ''
  loadError.value = ''
  drafts.question = ''
  void router.replace({ path: '/qa' })
}
async function ask(retry?: string, retryParent?: number) {
  if (busy.value || loading.value || loadError.value) return
  const question = (retry ?? drafts.question).trim()
  if (!question || length(question) > 500) {
    error.value = '请输入 1—500 字问题'
    return
  }
  const parent = retry === undefined ? turns.value.at(-1)?.qaId : retryParent
  busy.value = true
  error.value = ''
  const owner = uid.value,
    stamp = epoch
  try {
    const response =
      parent === undefined
        ? await courseApi.qa.ask(question)
        : await courseApi.qa.ask(question, parent)
    if (!alive || stamp !== epoch || !auth.isLoggedIn || uid.value !== owner) return
    // Retrying an earlier failed turn creates a branch, preserving the original history.
    if (retry !== undefined) {
      const index = parent === undefined ? -1 : turns.value.findIndex(t => t.qaId === parent)
      turns.value = turns.value.slice(0, index + 1)
    }
    turns.value.push(response.data)
    client.setQueryData(['course', owner, 'qa', response.data.qaId], response.data)
    if (drafts.question.trim() === question) drafts.question = ''
    if (route.path === '/qa')
      void router.replace({ path: '/qa', query: { turn: response.data.qaId } })
    await refresh()
  } catch (failure: unknown) {
    if (alive && stamp === epoch && uid.value === owner) error.value = message(failure)
  } finally {
    if (epoch === stamp) busy.value = false
  }
}
</script>
<style scoped>
.conversation-tools {
  display: flex;
  align-items: center;
  gap: 20px;
  flex-wrap: wrap;
  margin-bottom: 18px;
}
.result-card,
.question-card {
  margin-top: 18px;
}
</style>
