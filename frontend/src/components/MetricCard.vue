<script setup lang="ts">
defineProps<{
  label: string
  value: string | number
  note: string
  tone?: 'blue' | 'amber' | 'green' | 'violet'
}>()
</script>

<template>
  <article class="metric-card" :class="tone || 'blue'">
    <div class="metric-icon"><slot /></div>
    <div>
      <p>{{ label }}</p>
      <strong>{{ value }}</strong
      ><span>{{ note }}</span>
    </div>
  </article>
</template>

<style scoped>
.metric-card {
  display: flex;
  gap: 15px;
  min-height: 132px;
  padding: 22px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  box-shadow: var(--shadow-soft);
}
.metric-icon {
  width: 42px;
  height: 42px;
  flex: 0 0 42px;
  display: grid;
  place-items: center;
  border-radius: 12px;
  font-size: 20px;
}
.blue .metric-icon {
  color: #315fd9;
  background: #edf2ff;
}
.amber .metric-icon {
  color: #d97706;
  background: #fff6df;
}
.green .metric-icon {
  color: #16855b;
  background: #e9f8f1;
}
.violet .metric-icon {
  color: #7254c8;
  background: #f2edff;
}
.metric-card p {
  margin: 1px 0 8px;
  color: var(--muted);
  font-size: 13px;
}
.metric-card strong {
  display: block;
  color: var(--ink);
  font-size: 29px;
  line-height: 1;
}
.metric-card span {
  display: block;
  margin-top: 10px;
  color: var(--muted-soft);
  font-size: 12px;
}
</style>
