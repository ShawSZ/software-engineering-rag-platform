<template>
  <el-form-item label="开始日期"
    ><el-date-picker
      v-model="from"
      type="date"
      value-format="YYYY-MM-DD"
      placeholder="不限" /></el-form-item
  ><el-form-item label="结束日期"
    ><el-date-picker v-model="to" type="date" value-format="YYYY-MM-DD" placeholder="不限"
  /></el-form-item>
</template>
<script setup lang="ts">
const from = defineModel<string>('from', { default: '' }),
  to = defineModel<string>('to', { default: '' })
</script>
