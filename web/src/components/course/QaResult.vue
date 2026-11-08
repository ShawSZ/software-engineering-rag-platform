<template>
  <section class="qa-result">
    <div class="result-heading">
      <el-tag
        :type="
          value.status === 'SUCCESS' ? 'success' : value.status === 'FAILED' ? 'danger' : 'warning'
        "
        >{{ labels[value.status] }}</el-tag
      ><span>{{ value.latencyMs }} ms · {{ formatDateTime(value.createdAt) }}</span>
    </div>
    <h3 class="question-text">{{ value.question }}</h3>
    <template v-if="value.status === 'SUCCESS'"
      ><MarkdownBody :content="value.answer || ''" /><SourceList
        :qa-id="value.qaId"
        :sources="value.sources"
      />
      <div class="qa-actions">
        <el-button :loading="favoriting" :disabled="favoriting" @click="favorite">{{
          favorited ? '取消收藏' : '收藏回答'
        }}</el-button
        ><router-link :to="`/history/${value.qaId}`">查看完整记录</router-link>
      </div>
      <el-form label-position="top" class="feedback-form" @submit.prevent="saveFeedback"
        ><el-form-item label="这条回答对你有帮助吗？"
          ><el-radio-group v-model="rating" :disabled="saving"
            ><el-radio-button value="HELPFUL">有帮助</el-radio-button
            ><el-radio-button value="UNHELPFUL">无帮助</el-radio-button></el-radio-group
          ></el-form-item
        ><el-form-item label="反馈原因（选填，最多 500 字）"
          ><el-input v-model="reason" type="textarea" :rows="3" :disabled="saving" /><small
            >{{ length(reason) }} / 500</small
          ></el-form-item
        ><el-button
          type="primary"
          native-type="submit"
          :loading="saving"
          :disabled="!rating || saving"
          >保存反馈</el-button
        ><span v-if="savedFeedback" class="feedback-saved"
          >已保存：{{ savedFeedback.rating === 'HELPFUL' ? '有帮助' : '无帮助' }} ·
          {{ formatDateTime(savedFeedback.updatedAt) }}</span
        ></el-form
      >
    </template>
    <template v-else
      ><el-alert
        :title="
          value.errorMessage ||
          (value.status === 'NO_EVIDENCE' ? '当前资料不足以回答该问题' : '问答暂时不可用')
        "
        :type="value.status === 'FAILED' ? 'error' : 'warning'"
        :closable="false"
        show-icon
      />
      <p>
        {{
          value.errorCode === 'MODEL_NOT_CONFIGURED'
            ? '请联系课程管理员配置问答服务；资料浏览和下载仍可使用。'
            : value.status === 'NO_EVIDENCE'
              ? '可以换一种问法，或请管理员补充相关资料。'
              : '记录已保留，可稍后查看或重试。'
        }}
      </p>
      <p class="muted">{{ value.errorCode }}</p>
      <el-button v-if="value.retryable" :disabled="retrying" @click="$emit('retry', value.question)"
        >重新提问</el-button
      ></template
    >
    <el-alert v-if="actionError" :title="actionError" type="error" :closable="false" />
  </section>
</template>
<script setup lang="ts">
import { ref, watch } from 'vue'
import { courseApi, type QaDetail, type Rating, type Feedback } from '@/api/course'
import { useCourse, length, message } from '@/composables/useCourse'
import { formatDateTime } from '@/utils/format'
import MarkdownBody from '@/components/MarkdownBody.vue'
import SourceList from './CourseSourceList.vue'
const props = defineProps<{ value: QaDetail; retrying?: boolean }>()
const emit = defineEmits<{ changed: []; retry: [question: string] }>()
const { refresh } = useCourse()
const labels = { SUCCESS: '回答成功', NO_EVIDENCE: '依据不足', FAILED: '问答失败' }
const favorited = ref(false),
  favoriting = ref(false),
  saving = ref(false),
  rating = ref<Rating>(),
  reason = ref(''),
  savedFeedback = ref<Feedback | null>(null),
  actionError = ref('')
watch(
  () => props.value,
  (value, previous) => {
    favorited.value = value.favorited
    const dirty =
      rating.value !== savedFeedback.value?.rating ||
      reason.value !== (savedFeedback.value?.reason || '')
    if (previous?.qaId !== value.qaId || !dirty) {
      rating.value = value.feedback?.rating
      reason.value = value.feedback?.reason || ''
      savedFeedback.value = value.feedback
    }
    if (previous?.qaId !== value.qaId) actionError.value = ''
  },
  { immediate: true }
)
async function favorite() {
  if (favoriting.value || props.value.status !== 'SUCCESS') return
  favoriting.value = true
  actionError.value = ''
  try {
    const result = await (favorited.value
      ? courseApi.qa.unfavorite(props.value.qaId)
      : courseApi.qa.favorite(props.value.qaId))
    favorited.value = result.data.favorited
    emit('changed')
    await refresh()
  } catch (failure: unknown) {
    actionError.value = message(failure)
  } finally {
    favoriting.value = false
  }
}
async function saveFeedback() {
  if (saving.value || !rating.value || props.value.status !== 'SUCCESS') return
  if (length(reason.value) > 500) {
    actionError.value = '反馈原因不能超过 500 字'
    return
  }
  saving.value = true
  actionError.value = ''
  try {
    const result = await courseApi.qa.feedback(props.value.qaId, rating.value, reason.value)
    savedFeedback.value = result.data
    rating.value = result.data.rating
    reason.value = result.data.reason
    emit('changed')
    await refresh()
  } catch (failure: unknown) {
    actionError.value = message(failure)
  } finally {
    saving.value = false
  }
}
</script>
<style scoped>
.result-heading {
  display: flex;
  gap: 12px;
  align-items: center;
  color: var(--fk-text-secondary);
  font-size: 13px;
}
.question-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.qa-actions {
  display: flex;
  gap: 16px;
  align-items: center;
  margin: 20px 0;
}
.feedback-form {
  border-top: 1px solid var(--fk-border);
  padding-top: 20px;
}
.feedback-saved {
  margin-left: 12px;
  font-size: 13px;
  color: var(--fk-success);
}
.muted {
  color: var(--fk-text-secondary);
}
</style>
