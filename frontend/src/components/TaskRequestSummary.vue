<script setup lang="ts">
import type { TaskRequest } from '@/types/api'
import { formatDateTime } from '@/utils/format'

defineProps<{ request: Pick<TaskRequest, 'description' | 'goal' | 'suggestedDeadline'> }>()
</script>

<template>
  <dl class="request-summary">
    <div>
      <dt>任务描述</dt>
      <dd>{{ request.description?.trim() || '未填写' }}</dd>
    </div>
    <div>
      <dt>任务目标</dt>
      <dd>{{ request.goal?.trim() || '未填写' }}</dd>
    </div>
    <div>
      <dt>建议截止时间</dt>
      <dd>{{ request.suggestedDeadline ? formatDateTime(request.suggestedDeadline) : '未提供建议' }}</dd>
    </div>
  </dl>
</template>

<style scoped>
.request-summary {
  display: grid;
  gap: 8px;
  margin: 12px 0;
  font-size: 13px;
  line-height: 1.65;
}
.request-summary > div {
  display: grid;
  grid-template-columns: 6em minmax(0, 1fr);
  gap: 12px;
}
dt {
  color: var(--muted);
}
dd {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
