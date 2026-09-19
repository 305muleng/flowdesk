<script setup lang="ts">
import { statusLabel, statusType } from '@/utils/format'

defineProps<{ value?: string; effect?: 'dark' | 'light' | 'plain' }>()
</script>

<template>
  <el-tag :type="statusType(value)" :effect="effect || 'light'" round>{{
    statusLabel(value)
  }}</el-tag>
</template>
