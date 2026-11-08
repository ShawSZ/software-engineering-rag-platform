<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="logo">
        <span class="logo-icon">SE</span><span>{{ PRODUCT_NAME }}</span>
      </div>
      <el-menu :default-active="activePath" router class="side-menu">
        <el-menu-item
          v-for="item in visibleMenus"
          :key="item.path"
          :index="item.path"
          @mouseenter="prefetchView(item.path)"
        >
          <el-icon><component :is="icons[item.icon]" /></el-icon><span>{{ item.label }}</span>
        </el-menu-item>
      </el-menu>
      <p class="aside-note">软件工程 · 课程资料与来源问答</p>
    </el-aside>
    <el-container class="main-shell">
      <el-header class="header">
        <span class="header-title">{{ route.meta.title }}</span>
        <div class="header-right">
          <el-button circle :aria-label="themeTooltip" :title="themeTooltip" @click="theme.toggle()"
            ><el-icon><component :is="theme.resolvedTheme === 'dark' ? Sunny : Moon" /></el-icon
          ></el-button>
          <span>{{ auth.user?.displayName || auth.user?.username }}</span>
          <el-tag size="small" effect="plain">{{ auth.isAdmin ? '管理员' : '学生' }}</el-tag>
          <el-button type="primary" link :loading="loggingOut" @click="handleLogout"
            >退出</el-button
          >
        </div>
      </el-header>
      <el-main class="main-content">
        <router-view v-slot="{ Component, route: childRoute }">
          <keep-alive :key="auth.sessionRevision" :max="8">
            <component
              :is="Component"
              v-if="childRoute.meta.keepAlive"
              :key="childRoute.path === '/qa' ? childRoute.path : childRoute.fullPath"
            />
          </keep-alive>
          <component :is="Component" v-if="!childRoute.meta.keepAlive" :key="childRoute.fullPath" />
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  Collection,
  QuestionFilled,
  Clock,
  Star,
  Document,
  Folder,
  User,
  ChatDotRound,
  DataAnalysis,
  Moon,
  Sunny
} from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import { PRODUCT_NAME } from '@/constants/course'
import { menusForRole } from '@/router/navigation'
import { prefetchAllViewsOnIdle, prefetchView } from '@/router/prefetch'
const icons = {
  Collection,
  QuestionFilled,
  Clock,
  Star,
  Document,
  Folder,
  User,
  ChatDotRound,
  DataAnalysis
}
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const theme = useThemeStore()
const loggingOut = ref(false)
const visibleMenus = computed(() => menusForRole(auth.isAdmin))
const activePath = computed(
  () => [...visibleMenus.value].reverse().find(item => route.path.startsWith(item.path))?.path || ''
)
const themeTooltip = computed(() =>
  theme.resolvedTheme === 'dark' ? '切换为浅色主题' : '切换为暗色主题'
)
onMounted(() => prefetchAllViewsOnIdle(visibleMenus.value.map(item => item.path)))
async function handleLogout() {
  if (loggingOut.value) return
  loggingOut.value = true
  try {
    try {
      await ElMessageBox.confirm('确认退出当前账号？未提交的草稿将被清除。', '退出登录', {
        confirmButtonText: '退出',
        cancelButtonText: '取消',
        type: 'warning'
      })
    } catch {
      return
    }
    try {
      await auth.logout()
    } catch {
      /* The request layer reports failures; local logout still completes. */
    }
    await router.replace('/login')
  } finally {
    loggingOut.value = false
  }
}
</script>
<style scoped lang="scss">
.layout {
  min-height: 100vh;
  background: $fk-page-bg;
}
.aside {
  background: $fk-sidebar-bg;
  border-right: 1px solid $fk-border;
  display: flex;
  flex-direction: column;
}
.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 20px 16px;
  border-bottom: 1px solid $fk-border;
  color: $fk-text-primary;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.5;
}
.logo-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  flex-shrink: 0;
  border-radius: 8px;
  background: $fk-primary;
  color: #fff;
  font-size: 12px;
}
.side-menu {
  border: 0;
  flex: 1;
  background: transparent;
  padding: 12px 0;
}
.side-menu :deep(.el-menu-item) {
  margin: 3px 8px;
  border-radius: 8px;
  color: $fk-text-regular;
}
.side-menu :deep(.el-menu-item.is-active) {
  background: var(--fk-menu-active-bg);
  color: $fk-primary;
}
.side-menu :deep(.el-menu-item:hover) {
  background: var(--fk-menu-hover-bg);
}
.aside-note {
  padding: 16px;
  font-size: 12px;
  color: $fk-text-secondary;
  line-height: 1.8;
}
.main-shell {
  min-width: 0;
}
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  background: $fk-header-bg;
  border-bottom: 1px solid $fk-border;
  height: 64px;
  padding: 0 24px;
}
.header-title {
  font-size: 16px;
  font-weight: 600;
  white-space: nowrap;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 14px;
}
.main-content {
  padding: 24px;
}
@media (max-width: 700px) {
  .layout {
    flex-direction: column;
  }
  .aside {
    width: 100% !important;
    border-right: 0;
  }
  .logo {
    padding: 12px 16px;
  }
  .side-menu {
    display: flex;
    overflow-x: auto;
    padding: 4px;
  }
  .side-menu :deep(.el-menu-item) {
    flex-shrink: 0;
    margin: 0 2px;
    height: 44px;
  }
  .aside-note {
    display: none;
  }
  .header {
    padding: 12px 16px;
    height: auto;
    flex-wrap: wrap;
  }
  .main-content {
    padding: 16px;
  }
}
</style>
