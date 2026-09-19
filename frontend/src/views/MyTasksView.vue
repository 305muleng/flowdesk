<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getMyTasksApi } from '@/api/tasks'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import type { Task, TaskStatus } from '@/types/api'
import { formatDateTime, isOverdue } from '@/utils/format'

const router = useRouter()
const tasks = ref<Task[]>([])
const loading = ref(false)
const filter = ref<'ALL' | TaskStatus>('ALL')
const keyword = ref('')
const filteredTasks = computed(() =>
  tasks.value.filter(
    (task) =>
      (filter.value === 'ALL' || task.status === filter.value) &&
      task.title.toLowerCase().includes(keyword.value.trim().toLowerCase()),
  ),
)

async function load() {
  loading.value = true
  try {
    tasks.value = (await getMyTasksApi()).data.data
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>

<template>
  <PageHeader title="我的任务" description="聚焦分配给你的工作，及时推进到交付与审核。">
    <template #actions><el-button @click="load">刷新</el-button></template>
  </PageHeader>
  <section class="filters">
    <el-input v-model="keyword" clearable placeholder="搜索任务名称" style="max-width: 280px" />
    <el-segmented
      v-model="filter"
      :options="[
        { label: '全部', value: 'ALL' },
        { label: '待开始', value: 'TODO' },
        { label: '进行中', value: 'IN_PROGRESS' },
        { label: '待审核', value: 'REVIEW' },
        { label: '已完成', value: 'DONE' },
      ]"
    />
  </section>
  <section v-loading="loading" class="task-list">
    <el-empty v-if="!loading && !filteredTasks.length" description="当前筛选下没有任务" />
    <article
      v-for="task in filteredTasks"
      :key="task.id"
      class="task-row"
      @click="router.push('/tasks/' + task.id)"
    >
      <div class="priority" :class="(task.priority || 'MEDIUM').toLowerCase()" />
      <div class="task-main">
        <div class="task-title">
          <h3>{{ task.title }}</h3>
          <StatusTag :value="task.status" />
        </div>
        <p>{{ task.description || task.goal || '暂无任务说明' }}</p>
      </div>
      <div class="meta">
        <span>负责人</span><strong>{{ task.assigneeName || '待分配' }}</strong>
      </div>
      <div class="meta">
        <span>截止时间</span
        ><strong
          :class="{
            overdue: isOverdue(task.deadline) && !['DONE', 'CANCELLED'].includes(task.status),
          }"
          >{{ formatDateTime(task.deadline) }}</strong
        >
      </div>
      <StatusTag :value="task.priority || 'MEDIUM'" />
    </article>
  </section>
</template>

<style scoped>
.filters {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
  padding: 14px 16px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 16px;
}
.task-list {
  display: grid;
  gap: 10px;
  min-height: 240px;
}
.task-list:deep(.el-empty) {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 18px;
}
.task-row {
  display: grid;
  grid-template-columns: 4px minmax(240px, 1fr) 130px 190px auto;
  align-items: center;
  gap: 18px;
  padding: 19px 22px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 16px;
  cursor: pointer;
  transition: 0.2s;
}
.task-row:hover {
  transform: translateY(-1px);
  border-color: #cbd9f7;
  box-shadow: var(--shadow-sm);
}
.priority {
  align-self: stretch;
  border-radius: 9px;
  background: #f1b94f;
}
.priority.high {
  background: #ef6c72;
}
.priority.low {
  background: #78b6a0;
}
.task-title {
  display: flex;
  align-items: center;
  gap: 10px;
}
.task-title h3 {
  margin: 0;
  color: var(--text-strong);
  font-size: 15px;
}
.task-main p {
  margin: 7px 0 0;
  color: var(--text-muted);
  font-size: 13px;
}
.meta {
  display: grid;
  gap: 6px;
}
.meta span {
  color: var(--text-muted);
  font-size: 12px;
}
.meta strong {
  font-size: 13px;
  color: var(--text);
}
.meta .overdue {
  color: #d64c55;
}
@media (max-width: 980px) {
  .task-row {
    grid-template-columns: 4px 1fr auto;
  }
  .meta {
    display: none;
  }
}
@media (max-width: 700px) {
  .filters {
    align-items: stretch;
    flex-direction: column;
  }
  .filters:deep(.el-segmented) {
    overflow: auto;
  }
  .task-row {
    padding: 16px;
  }
}
</style>
