<template>
  <el-result
    :icon="forbidden ? 'warning' : 'info'"
    :title="forbidden ? '403 · 无访问权限' : '404 · 页面不存在'"
    :sub-title="forbidden ? '当前账号没有访问此页面的权限。' : '请检查地址，或返回课程资料库。'"
  >
    <template #extra
      ><el-button type="primary" @click="router.replace('/documents')"
        >返回资料库</el-button
      ></template
    >
  </el-result>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
const route = useRoute()
const router = useRouter()
const forbidden = computed(() => route.meta.status === 403)
</script>
