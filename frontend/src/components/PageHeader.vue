<script setup lang="ts">
defineProps<{ eyebrow?: string; title: string; description?: string }>()
</script>

<template>
  <header class="page-header">
    <div>
      <p v-if="eyebrow" class="page-eyebrow">{{ eyebrow }}</p>
      <h1>{{ title }}</h1>
      <p v-if="description" class="page-description">{{ description }}</p>
    </div>
    <div class="page-actions">
      <slot name="actions"><slot /></slot>
    </div>
  </header>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 24px;
}
.page-eyebrow {
  margin: 0 0 8px;
  color: var(--brand);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.14em;
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
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
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
