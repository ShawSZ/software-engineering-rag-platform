<template>
  <section class="page-container">
    <PageHeader
      title="用户管理"
      subtitle="管理学生与管理员的账号启停状态；最后一个可用管理员受保护"
    /><el-card shadow="never"
      ><el-form inline class="course-filters" @submit.prevent="search"
        ><el-form-item label="关键词"
          ><el-input v-model="keyword" clearable placeholder="用户名或显示名称" /></el-form-item
        ><el-form-item label="账号状态"
          ><el-select v-model="status" clearable placeholder="全部"
            ><el-option label="正常" value="ENABLED" /><el-option
              label="已停用"
              value="DISABLED" /></el-select></el-form-item
        ><el-form-item
          ><el-button type="primary" native-type="submit">查询</el-button></el-form-item
        ></el-form
      ><el-alert
        v-if="actionError"
        type="error"
        :title="actionError"
        :closable="false" /><QueryPanel
        :loading="isPending"
        :error="error"
        :has-data="!!data"
        :empty="!data?.items.length"
        @retry="refetch()"
        ><el-table :data="data?.items || []" row-key="userId"
          ><UserColumn prop="username" label="用户名" /><UserColumn
            prop="displayName"
            label="显示名称"
          /><UserColumn label="角色" width="110"
            ><template #default="{ row }">{{
              row.role === 'ADMIN' ? '管理员' : '学生'
            }}</template></UserColumn
          ><UserColumn label="状态" width="110"
            ><template #default="{ row }"
              ><el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'">{{
                row.status === 'ENABLED' ? '正常' : '已停用'
              }}</el-tag></template
            ></UserColumn
          ><UserColumn label="注册时间" width="180"
            ><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></UserColumn
          ><UserColumn label="操作" width="100"
            ><template #default="{ row }"
              ><el-button
                link
                :type="row.status === 'ENABLED' ? 'danger' : 'primary'"
                :disabled="changing === row.userId"
                @click="toggle(row)"
                >{{ row.status === 'ENABLED' ? '停用' : '启用' }}</el-button
              ></template
            ></UserColumn
          ></el-table
        ></QueryPanel
      ><TablePager
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :total="data?.total || 0"
    /></el-card>
  </section>
</template>
<script setup lang="ts">
import { ElTableColumn } from 'element-plus'
const UserColumn = ElTableColumn<User>
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { ElMessageBox } from 'element-plus'
import { courseApi, type User, type UserStatus } from '@/api/course'
import { courseQueryKeys } from '@/lib/course-query-keys'
import { useCourse, length, message } from '@/composables/useCourse'
import { formatDateTime } from '@/utils/format'
import PageHeader from '@/components/PageHeader.vue'
import QueryPanel from '@/components/course/QueryPanel.vue'
import TablePager from '@/components/TablePager.vue'
const { uid, auth, enabled, refresh } = useCourse(),
  router = useRouter()
const keyword = ref(''),
  status = ref<UserStatus>(),
  applied = ref<{ keyword?: string; status?: UserStatus }>({}),
  page = ref(1),
  pageSize = ref(10),
  changing = ref(0),
  actionError = ref('')
watch(pageSize, () => {
  page.value = 1
})
const filters = computed(() => ({ ...applied.value, page: page.value, pageSize: pageSize.value }))
const { data, isPending, error, refetch } = useQuery({
  queryKey: computed(() => courseQueryKeys.users(uid.value, filters.value)),
  queryFn: async () => (await courseApi.admin.users(filters.value)).data,
  enabled,
  refetchOnMount: 'always'
})
function search() {
  actionError.value = length(keyword.value.trim()) > 100 ? '关键词最多 100 字' : ''
  if (!actionError.value) {
    const next = {
      keyword: keyword.value.trim() || undefined,
      status: status.value || undefined
    }
    const unchanged = page.value === 1 && JSON.stringify(next) === JSON.stringify(applied.value)
    applied.value = next
    page.value = 1
    if (unchanged) void refetch()
  }
}
async function toggle(user: User) {
  if (changing.value) return
  const next = user.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  try {
    await ElMessageBox.confirm(
      `确认${next === 'DISABLED' ? '停用' : '启用'}“${user.displayName || user.username}”？${user.userId === uid.value && next === 'DISABLED' ? '停用自己后将退出登录。' : ''}`,
      '变更账号状态',
      { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  changing.value = user.userId
  actionError.value = ''
  try {
    await courseApi.admin.setUserStatus(user.userId, next)
    if (user.userId === uid.value && next === 'DISABLED') {
      auth.clearSession()
      await router.replace('/login')
    } else await refresh()
  } catch (failure: unknown) {
    actionError.value = message(failure)
    await refresh()
  } finally {
    changing.value = 0
  }
}
</script>
