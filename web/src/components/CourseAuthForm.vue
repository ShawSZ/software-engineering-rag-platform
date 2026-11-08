<template>
  <div class="auth-page">
    <LoginHero />
    <el-card class="auth-card" shadow="never">
      <div class="auth-brand">
        <span class="course-mark">SE</span>
        <h1>{{ PRODUCT_NAME }}</h1>
        <p>
          {{ register ? '创建学生账号，开始课程学习' : '集中查阅课程资料，核验每一条回答的出处' }}
        </p>
      </div>
      <h2>{{ register ? '学生注册' : '账号登录' }}</h2>
      <el-form :model="form" label-position="top" size="large" novalidate @submit.prevent="submit">
        <el-form-item label="用户名" :error="errors.username">
          <el-input
            v-model="form.username"
            name="username"
            autocomplete="username"
            :disabled="loading"
            :prefix-icon="User"
            placeholder="请输入用户名"
            data-testid="account-username"
          />
        </el-form-item>
        <el-form-item v-if="register" label="显示名称（选填）" :error="errors.displayName">
          <el-input
            v-model="form.displayName"
            name="displayName"
            autocomplete="nickname"
            :disabled="loading"
            placeholder="不填则使用用户名"
            data-testid="account-display-name"
          />
        </el-form-item>
        <el-form-item label="密码" :error="errors.password">
          <el-input
            v-model="form.password"
            name="password"
            type="password"
            :autocomplete="register ? 'new-password' : 'current-password'"
            :disabled="loading"
            show-password
            :prefix-icon="Lock"
            placeholder="请输入密码"
            data-testid="account-password"
          />
        </el-form-item>
        <el-form-item v-if="register" label="确认密码" :error="errors.confirmation">
          <el-input
            v-model="form.confirmation"
            name="confirmation"
            type="password"
            autocomplete="new-password"
            :disabled="loading"
            show-password
            placeholder="再次输入密码"
            data-testid="account-confirmation"
          />
        </el-form-item>
        <el-alert
          v-if="error"
          :title="error"
          type="error"
          show-icon
          :closable="false"
          class="auth-error"
          role="alert"
          data-testid="account-error"
        />
        <el-button
          type="primary"
          native-type="submit"
          :loading="loading"
          :disabled="loading"
          class="auth-submit"
          data-testid="account-submit"
          >{{ register ? '注册学生账号' : '登录' }}</el-button
        >
      </el-form>
      <p class="auth-link">
        {{ register ? '已有账号？' : '还没有账号？' }}
        <router-link :to="alternate">{{ register ? '返回登录' : '注册学生账号' }}</router-link>
      </p>
      <p v-if="register" class="auth-hint">注册账号统一为学生身份。</p>
      <p v-else class="auth-hint">管理员账号由课程负责人提供。</p>
    </el-card>
  </div>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Lock, User } from '@element-plus/icons-vue'
import LoginHero from '@/components/design/LoginHero.vue'
import { PRODUCT_NAME } from '@/constants/course'
import { useAuthStore } from '@/stores/auth'
import { courseApi } from '@/api/course'
import { loginDestination } from '@/router/access'
import { prefetchAfterLogin } from '@/router/prefetch'
import { validateAccount, type AccountErrors } from '@/utils/account-validation'

const props = defineProps<{ register?: boolean }>()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const error = ref('')
const errors = ref<AccountErrors>({})
const form = reactive({
  username: typeof route.query.username === 'string' ? route.query.username : '',
  password: '',
  displayName: '',
  confirmation: ''
})
const alternate = computed(() => ({
  path: props.register ? '/login' : '/register',
  query: typeof route.query.redirect === 'string' ? { redirect: route.query.redirect } : {}
}))
let disposed = false
onBeforeUnmount(() => {
  disposed = true
  form.password = ''
  form.confirmation = ''
})
async function submit() {
  if (loading.value) return
  errors.value = validateAccount(form, !!props.register)
  if (Object.keys(errors.value).length) return
  loading.value = true
  error.value = ''
  try {
    if (props.register) {
      await courseApi.auth.register({
        username: form.username.trim(),
        password: form.password,
        ...(form.displayName.trim() ? { displayName: form.displayName.trim() } : {})
      })
      if (disposed) return
      form.password = ''
      form.confirmation = ''
      await router.replace({
        path: '/login',
        query: {
          username: form.username.trim(),
          ...(typeof route.query.redirect === 'string' ? { redirect: route.query.redirect } : {})
        }
      })
    } else {
      const identity = await auth.login(form.username.trim(), form.password)
      if (disposed) return
      form.password = ''
      const target = loginDestination(router, route.query.redirect, identity.role)
      prefetchAfterLogin(target)
      await router.replace(target)
    }
  } catch (failure: unknown) {
    if (!disposed)
      error.value = failure instanceof Error ? failure.message : '操作失败，请稍后重试。'
  } finally {
    if (!disposed) loading.value = false
  }
}
</script>
<style scoped lang="scss">
.auth-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  padding: 32px 16px;
  background: $fk-login-gradient;
}
.auth-card {
  width: 440px;
  max-width: 100%;
  position: relative;
  z-index: 1;
  border-radius: $fk-card-radius;
  border: 1px solid $fk-border;
  box-shadow: $fk-card-shadow;
  padding: 16px 24px;
  background: $fk-card-bg;
}
.auth-brand {
  text-align: center;
  margin-bottom: 24px;
}
.course-mark {
  display: inline-flex;
  justify-content: center;
  align-items: center;
  width: 42px;
  height: 42px;
  border-radius: 10px;
  background: $fk-primary;
  color: #fff;
  font-weight: 700;
  letter-spacing: 2px;
}
h1 {
  margin: 16px 0 8px;
  font-size: 21px;
  line-height: 1.5;
  color: $fk-text-primary;
}
h2 {
  font-size: 17px;
  margin: 0 0 20px;
  color: $fk-text-primary;
}
.auth-brand p,
.auth-hint {
  font-size: 12px;
  color: $fk-text-secondary;
  line-height: 1.7;
}
.auth-submit {
  width: 100%;
  height: 44px;
  margin-top: 6px;
}
.auth-error {
  margin-bottom: 18px;
}
.auth-link {
  text-align: center;
  font-size: 14px;
  margin-top: 24px;
  color: $fk-text-regular;
}
.auth-link a {
  color: $fk-primary;
}
.auth-hint {
  text-align: center;
}
@media (max-width: 480px) {
  .auth-page {
    padding: 16px 12px;
  }
  .auth-card {
    padding: 8px 4px;
  }
}
</style>
