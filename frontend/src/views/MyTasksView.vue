<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getMyTasksApi } from '@/api/tasks'
import PageHeader from '@/components/PageHeader.vue'
import { usePagedList } from '@/composables/usePagedList'
import StatusTag from '@/components/StatusTag.vue'
import type { Task, TaskStatus } from '@/types/api'
import { isActionableTask, taskWorkLabel } from '@/utils/taskWork'
import { formatDateTime, isOverdue } from '@/utils/format'

const router = useRouter()
const listing = usePagedList<Task>()
const { records: tasks, page, size, total, loading, error } = listing
const projectScope = ref<'CURRENT' | 'HISTORY' | 'ALL'>('CURRENT')
const filter = ref<'ALL' | TaskStatus>('ALL')
const keyword = ref('')

async function load() {
  const status = filter.value
  const title = keyword.value
  const scope = projectScope.value
  await listing.load((page, size) => getMyTasksApi({ page, size, status, keyword: title, projectScope: scope }).then((r) => r.data.data))
}
function changePage(value: number) {
  if (page.value !== value) {
    page.value = value
    void load()
  }
}
function changeSize(value: number) {
  size.value = value
  listing.reset()
  void load()
}
watch([filter, keyword, projectScope], () => {
  listing.reset()
  void load()
})
onMounted(load)
</script>

<template>
  <PageHeader title="我的任务" description="聚焦分配给你的工作，及时推进到交付与审核。">
    <template #actions><el-button @click="load">刷新</el-button></template>
  </PageHeader>
  <section class="filters">
    <el-segmented v-model="projectScope" :options="[
      { label: '当前项目', value: 'CURRENT' }, { label: '历史项目', value: 'HISTORY' }, { label: '全部项目', value: 'ALL' },
    ]" />
    <el-input v-model="keyword" clearable placeholder="搜索任务名称" style="max-width: 280px" />
    <el-segmented
      v-model="filter"
      :options="[
        { label: '全部', value: 'ALL' },
        { label: '待开始', value: 'TODO' },
        { label: '进行中', value: 'IN_PROGRESS' },
        { label: '待审核', value: 'REVIEW' },
        { label: '已完成', value: 'DONE' },
        { label: '已取消', value: 'CANCELLED' },
      ]"
    />
  </section>
  <section v-loading="loading" class="task-list">
    <el-alert v-if="error" :title="error" type="error" :closable="false"
      ><el-button link type="primary" @click="load">重新加载</el-button></el-alert
    >
    <el-empty v-if="!loading && !error && !tasks.length" description="当前筛选下没有任务" />
    <article v-for="task in tasks" :key="task.id" class="task-row" @click="router.push('/tasks/' + task.id)">
      <div class="priority" :class="(task.priority || 'MEDIUM').toLowerCase()" />
      <div class="task-main">
        <div class="task-title">
          <h3>{{ task.title }}</h3>
          <StatusTag :value="task.status" /><el-tag type="info">{{ taskWorkLabel(task) }}</el-tag>
        </div>
        <p>{{ task.description || task.goal || '暂无任务说明' }}</p>
      </div>
      <div class="meta">
        <span>所属项目</span><strong>{{ task.projectName }}</strong><StatusTag :value="task.projectStatus" />
      </div>
      <div class="meta">
        <span>截止时间</span
        ><strong
          :class="{
            overdue: isOverdue(task.deadline) && isActionableTask(task),
          }"
          >{{ formatDateTime(task.deadline) }}</strong
        >
      </div>
      <StatusTag :value="task.priority || 'MEDIUM'" />
    </article>
    <el-pagination
      :current-page="page"
      :page-size="size"
      :page-sizes="[20, 50, 100]"
      layout="total, sizes, prev, pager, next"
      :total="total"
      @current-change="changePage"
      @size-change="changeSize"
    />
  </section>
</template>

<style scoped>
.filters {
  display: flex;
  flex-wrap: wrap;
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
