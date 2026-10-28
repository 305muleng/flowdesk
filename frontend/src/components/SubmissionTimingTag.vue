<script setup lang="ts">
defineProps<{ late?: boolean | null; detailed?: boolean }>()
</script>

<template>
  <el-tag v-if="late === true" type="warning">逾期提交</el-tag>
  <el-tag v-else-if="detailed" type="info">{{ late === false ? '按时提交' : '提交时限未记录' }}</el-tag>
</template>
