<template>
  <el-config-provider :locale="zhCn">
    <ErrorBoundary>
      <div v-if="auth.isInitializing" class="session-state" role="status" aria-live="polite">
        <el-icon class="is-loading" :size="28"><Loading /></el-icon>
        <p>正在验证登录身份…</p>
      </div>
      <el-result
        v-else-if="auth.restoreError"
        icon="warning"
        title="暂时无法验证登录身份"
        :sub-title="auth.restoreError"
      >
        <template #extra
          ><el-button type="primary" :loading="retrying" @click="retry">重新验证</el-button
          ><el-button @click="signOut">退出并重新登录</el-button></template
        >
      </el-result>
      <router-view
        v-else-if="route.meta.public || auth.isLoggedIn"
        :key="route.meta.public ? route.path : auth.sessionRevision"
      />
    </ErrorBoundary>
  </el-config-provider>
</template>
<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { Loading } from '@element-plus/icons-vue'
import ErrorBoundary from '@/components/ErrorBoundary.vue'
import { useAuthStore } from '@/stores/auth'
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const retrying = ref(false)
async function retry() {
  if (retrying.value) return
  retrying.value = true
  try {
    await auth.ensureSession()
    await router.replace(auth.pendingPath)
  } catch {
    /* Keep the recoverable initialization error. */
  } finally {
    retrying.value = false
  }
}
async function signOut() {
  auth.clearSession()
  await router.replace('/login')
}
</script>
<style scoped lang="scss">
.session-state {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: $fk-text-secondary;
  background: $fk-page-bg;
}
</style>
