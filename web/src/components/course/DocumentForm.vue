<template>
  <el-dialog
    :model-value="open"
    :title="
      mode === 'upload' ? '上传课程资料' : mode === 'replace' ? '替换资料原件' : '修改资料信息'
    "
    width="min(560px, 95vw)"
    destroy-on-close
    :close-on-click-modal="!busy"
    :close-on-press-escape="!busy"
    :show-close="!busy"
    @update:model-value="close"
  >
    <el-form label-position="top" @submit.prevent="submit">
      <template v-if="mode !== 'replace'"
        ><el-form-item label="资料标题"
          ><el-input v-model="form.title" :disabled="busy" /></el-form-item
        ><el-form-item label="分类"
          ><el-select v-model="form.categoryId" :disabled="busy" placeholder="选择分类"
            ><el-option
              v-for="category in categories"
              :key="category.id"
              :label="category.name"
              :value="category.id" /></el-select></el-form-item
      ></template>
      <el-form-item v-if="mode !== 'edit'" label="PDF / UTF-8 TXT / DOCX / PPTX 原文件"
        ><input type="file" accept=".pdf,.txt,.docx,.pptx" :disabled="busy" @change="pick" />
        <p>单文件最大 20 MB；提取正文、表格及幻灯片文字，图片和扫描件暂不支持 OCR。</p></el-form-item
      >
      <el-alert v-if="error" type="error" :closable="false" :title="error" show-icon />
      <p v-if="mode === 'replace'">新版解析成功前，当前有效版本仍可使用；失败将保留旧版。</p>
      <div class="dialog-actions">
        <el-button :disabled="busy" @click="close(false)">取消</el-button
        ><el-button type="primary" native-type="submit" :loading="busy" :disabled="busy">{{
          mode === 'edit' ? '保存' : '上传并解析'
        }}</el-button>
      </div>
    </el-form>
  </el-dialog>
</template>
<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { courseApi, type Category, type Document } from '@/api/course'
import { fileError, length, message } from '@/composables/useCourse'
const props = defineProps<{
  open: boolean
  mode: 'upload' | 'replace' | 'edit'
  document?: Document
  categories: Category[]
}>()
const emit = defineEmits<{
  'update:open': [value: boolean]
  saved: [value: Document]
  conflict: []
}>()
const form = reactive({ title: '', categoryId: undefined as number | undefined })
const file = ref<File | null>(null),
  busy = ref(false),
  error = ref('')
watch(
  () => props.open,
  open => {
    if (open) {
      form.title = props.document?.title || ''
      form.categoryId = props.document?.categoryId
      file.value = null
      error.value = ''
    }
  },
  { immediate: true }
)
function close(value: boolean) {
  if (!busy.value) emit('update:open', value)
}
function pick(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] || null
  error.value = fileError(file.value)
}
async function submit() {
  if (busy.value) return
  error.value =
    props.mode !== 'replace' &&
    (!form.title.trim() || length(form.title.trim()) > 200 || !form.categoryId)
      ? '请输入 1—200 字标题并选择分类'
      : props.mode !== 'edit'
        ? fileError(file.value)
        : ''
  if (error.value) return
  busy.value = true
  try {
    const result =
      props.mode === 'upload'
        ? await courseApi.documents.upload(file.value!, form.title.trim(), form.categoryId!)
        : props.mode === 'replace'
          ? await courseApi.documents.replace(props.document!.id, file.value!)
          : await courseApi.documents.update(
              props.document!.id,
              form.title.trim(),
              form.categoryId!
            )
    emit('saved', result.data)
    emit('update:open', false)
  } catch (failure: unknown) {
    error.value = message(failure)
    emit('conflict')
  } finally {
    busy.value = false
  }
}
</script>
<style scoped>
.dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 24px;
}
p {
  color: var(--fk-text-secondary);
  font-size: 13px;
  line-height: 1.7;
}
</style>
