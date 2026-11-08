<template>
  <section class="page-container">
    <PageHeader title="分类管理" subtitle="分类更新后，资料筛选与详情将同步刷新"
      ><template #actions
        ><el-button type="primary" @click="edit()">新增分类</el-button></template
      ></PageHeader
    ><el-card shadow="never"
      ><QueryPanel
        :loading="isPending"
        :error="error"
        :has-data="!!data"
        :empty="!data?.length"
        @retry="refetch()"
        ><el-table :data="data || []" row-key="id"
          ><CategoryColumn prop="name" label="分类名称" /><CategoryColumn
            prop="sortOrder"
            label="排序"
            width="100"
          /><CategoryColumn label="操作" width="180"
            ><template #default="{ row }"
              ><el-button link @click="edit(row)">编辑</el-button
              ><el-button link type="danger" :disabled="deleting === row.id" @click="remove(row)"
                >删除</el-button
              ></template
            ></CategoryColumn
          ></el-table
        ></QueryPanel
      ><el-alert v-if="actionError" type="error" :title="actionError" :closable="false"
    /></el-card>
    <el-dialog
      v-model="open"
      :title="selected ? '编辑分类' : '新增分类'"
      width="min(480px,95vw)"
      :close-on-click-modal="!saving"
      :show-close="!saving"
      :close-on-press-escape="!saving"
      ><el-form label-position="top" @submit.prevent="save"
        ><el-form-item label="分类名称（最多 50 字）"
          ><el-input v-model="name" :disabled="saving" /></el-form-item
        ><el-form-item label="排序（非负整数）"
          ><el-input-number
            v-model="sortOrder"
            :min="0"
            :max="2147483647"
            :precision="0"
            :disabled="saving" /></el-form-item
        ><el-alert v-if="formError" type="error" :title="formError" :closable="false" />
        <div class="dialog-actions">
          <el-button :disabled="saving" @click="open = false">取消</el-button
          ><el-button type="primary" native-type="submit" :disabled="saving" :loading="saving"
            >保存</el-button
          >
        </div></el-form
      ></el-dialog
    >
  </section>
</template>
<script setup lang="ts">
import { ElTableColumn } from 'element-plus'
const CategoryColumn = ElTableColumn<Category>
import { computed, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { ElMessageBox } from 'element-plus'
import { courseApi, type Category } from '@/api/course'
import { courseQueryKeys } from '@/lib/course-query-keys'
import { useCourse, message, length } from '@/composables/useCourse'
import PageHeader from '@/components/PageHeader.vue'
import QueryPanel from '@/components/course/QueryPanel.vue'
const { uid, enabled, refresh } = useCourse()
const { data, isPending, error, refetch } = useQuery({
  queryKey: computed(() => courseQueryKeys.categories(uid.value)),
  queryFn: async () => (await courseApi.categories.list()).data,
  enabled,
  refetchOnMount: 'always'
})
const open = ref(false),
  selected = ref<Category>(),
  name = ref(''),
  sortOrder = ref(0),
  saving = ref(false),
  deleting = ref(0),
  formError = ref(''),
  actionError = ref('')
function edit(category?: Category) {
  selected.value = category
  name.value = category?.name || ''
  sortOrder.value = category?.sortOrder || 0
  formError.value = ''
  open.value = true
}
async function save() {
  if (saving.value) return
  formError.value =
    !name.value.trim() ||
    length(name.value.trim()) > 50 ||
    !Number.isInteger(sortOrder.value) ||
    sortOrder.value < 0
      ? '请输入 1—50 字名称及非负整数排序'
      : ''
  if (formError.value) return
  saving.value = true
  try {
    await (selected.value
      ? courseApi.categories.update(selected.value.id, name.value.trim(), sortOrder.value)
      : courseApi.categories.create(name.value.trim(), sortOrder.value))
    open.value = false
    await refresh()
  } catch (failure: unknown) {
    formError.value = message(failure)
  } finally {
    saving.value = false
  }
}
async function remove(category: Category) {
  if (deleting.value) return
  try {
    await ElMessageBox.confirm(
      `确认删除分类“${category.name}”？被资料引用的分类不能删除。`,
      '删除分类',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  deleting.value = category.id
  actionError.value = ''
  try {
    await courseApi.categories.remove(category.id)
    await refresh()
  } catch (failure: unknown) {
    actionError.value = message(failure)
  } finally {
    deleting.value = 0
  }
}
</script>
