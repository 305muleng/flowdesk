<script setup lang="ts">
defineProps<{ title: string; description?: string }>()
</script>

<template>
  <header class="page-header">
    <div>
      <h1>{{ title }}</h1>
      <p v-if="description" class="page-description">{{ description }}</p>
    </div>
    <div v-if="$slots.actions || $slots.default" class="page-actions">
      <slot name="actions"><slot /></slot>
    </div>
  </header>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 24px;
}
.page-header > div:first-child {
  min-width: 0;
}
.page-header h1 {
  margin: 0;
  color: var(--ink);
  font-size: clamp(25px, 3vw, 34px);
  letter-spacing: -0.04em;
}
.page-description {
  margin: 9px 0 0;
  color: var(--muted);
  font-size: 14px;
  line-height: 1.7;
}
.page-actions {
  max-width: 100%;
  align-items: center;
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
.page-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
@media (max-width: 680px) {
  .page-header {
    align-items: flex-start;
    flex-direction: column;
  }
  .page-actions {
    width: 100%;
  }
}
</style>
