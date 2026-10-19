<script setup lang="ts">
import { formatDateTime, requestCloseReasonLabel } from '@/utils/format'

defineProps<{ status: string; closedAt?: string | null; closeReason?: string | null }>()
</script>

<template>
  <p v-if="status === 'CLOSED'">
    已关闭：{{ requestCloseReasonLabel(closeReason) }} · 关闭时间：{{ formatDateTime(closedAt || undefined) }}
  </p>
</template>
