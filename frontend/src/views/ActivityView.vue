<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue'
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { usePagedList } from '@/composables/usePagedList'
import PageHeader from '@/components/PageHeader.vue'
import { getMyProjectsApi, getProjectLogsApi } from '@/api/projects'
import { formatDateTime } from '@/utils/format'
import type { OperationLog, Project } from '@/types/api'

const projects = ref<Project[]>([])
const projectId = ref<number>()
const listing = usePagedList<OperationLog>()
const { records: logs, page, size, total, loading, error } = listing
const route = useRoute()
const router = useRouter()
const selectionError = ref('')
const projectsError = ref('')
const projectsLoading = ref(false)
const targetNames: Record<string, string> = {
  PROJECT: '项目',
  TASK: '任务',
  PROJECT_MEMBER: '项目成员',
  TASK_REQUEST: '任务申请',
  PROJECT_ACCEPTANCE: '项目验收',
  MEMBER_LEAVE_REQUEST: '退出申请',
  PROJECT_MANAGER_TRANSFER: '负责人转让',
}

async function loadLogs() {
  const currentProjectId = projectId.value
  logs.value = []
  await listing.load((page, size) =>
    currentProjectId
      ? getProjectLogsApi(currentProjectId, { page, size }).then((result) => result.data.data)
      : Promise.resolve({ records: [], total: 0, page: 1, size, pages: 0 }),
  )
}

function selectProjectFromRoute() {
  selectionError.value = ''
  const requested = route.query.projectId
  if (requested !== undefined) {
    const parsed = typeof requested === 'string' && /^[1-9]\d*$/.test(requested) ? Number(requested) : NaN
    const selected = Number.isSafeInteger(parsed) && projects.value.find((item) => item.id === parsed)
    projectId.value = selected ? selected.id : undefined
    if (!selected) selectionError.value = '指定项目不存在或你已无权访问，请选择其他项目'
  } else {
    projectId.value = projects.value.find((item) => item.id === projectId.value)?.id ?? projects.value[0]?.id
  }
}

function changeProject(value: number) {
  void router.replace({ name: 'activity', query: { projectId: String(value) } })
}

function changePage(value: number) {
  if (page.value !== value) {
    page.value = value
    void loadLogs()
  }
}

function changeSize(value: number) {
  size.value = value
  listing.reset()
  void loadLogs()
}

async function loadProjects() {
  projectsLoading.value = true
  projectsError.value = ''
  try {
    projects.value = (await getMyProjectsApi()).data.data
    selectProjectFromRoute()
  } catch {
    projects.value = []
    selectionError.value = ''
    projectId.value = undefined
    projectsError.value = '项目列表加载失败，请重试'
  } finally {
    projectsLoading.value = false
  }
}
onMounted(loadProjects)
watch(projectId, () => {
  listing.reset()
  total.value = 0
  void loadLogs()
})
watch(
  () => route.query.projectId,
  () => {
    if (!projectsLoading.value && !projectsError.value) selectProjectFromRoute()
  },
)
</script>

<template>
  <PageHeader title="项目操作记录" description="查看项目中的关键操作记录。">
    <el-select :model-value="projectId" @change="changeProject" placeholder="选择项目" filterable style="width: 220px"
      ><el-option v-for="project in projects" :key="project.id" :label="project.name" :value="project.id"
    /></el-select>
    <el-button :icon="Refresh" circle aria-label="刷新操作记录" @click="projectsError ? loadProjects() : loadLogs()" />
  </PageHeader>
  <section v-loading="loading || projectsLoading" class="surface-card activity-panel">
    <el-alert v-if="projectsError" :title="projectsError" type="error" :closable="false"
      ><el-button link @click="loadProjects">重试</el-button></el-alert
    >
    <el-alert v-else-if="selectionError" :title="selectionError" type="warning" :closable="false" />
    <el-alert v-else-if="error" :title="error" type="error" :closable="false"
      ><el-button link @click="loadLogs">重试</el-button></el-alert
    >
    <div v-for="log in logs" :key="log.id" class="log-row">
      <div class="log-mark" />
      <div class="log-content">
        <div>
          <strong>{{ log.description }}</strong
          ><span>{{ log.actorName }}</span>
        </div>
        <p>{{ targetNames[log.targetType] || '记录' }} · 记录 #{{ log.targetId }}</p>
      </div>
      <time>{{ formatDateTime(log.createdAt) }}</time>
    </div>
    <el-empty
      v-if="!loading && !projectsLoading && !error && !projectsError && !selectionError && !logs.length"
      :description="!projects.length ? '还没有参与任何项目' : !projectId ? '请选择项目查看操作记录' : '该项目还没有操作记录'"
    />
    <el-pagination
      v-if="projectId && !projectsError && !selectionError"
      :current-page="page"
      :page-size="size"
      :page-sizes="[20, 50, 100]"
      layout="total, sizes, prev, pager, next"
      :total="total"
      :disabled="loading"
      @current-change="changePage"
      @size-change="changeSize"
    />
  </section>
</template>

<style scoped>
.activity-panel :deep(.el-pagination) {
  padding: 16px 0;
}
.activity-panel {
  padding: 8px 26px;
}
.log-row {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 15px;
  padding: 20px 0;
  border-bottom: 1px solid #eff1f5;
}
.log-row:last-child {
  border-bottom: 0;
}
.log-mark {
  width: 11px;
  height: 11px;
  flex: 0 0 11px;
  margin-top: 4px;
  border: 3px solid #6985d8;
  border-radius: 50%;
  background: #fff;
}
.log-content {
  min-width: 0;
  flex: 1;
}
.log-content > div {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}
.log-content strong {
  color: #344054;
  font-size: 14px;
}
.log-content span {
  padding: 3px 8px;
  border-radius: 99px;
  background: #f2f4f8;
  color: #667085;
  font-size: 11px;
}
.log-content p {
  margin: 7px 0 0;
  color: #98a2b3;
  font-size: 11px;
}
.log-row time {
  color: #98a2b3;
  font-size: 12px;
}
@media (max-width: 680px) {
  .log-row {
    flex-wrap: wrap;
  }
  .log-row time {
    width: 100%;
    padding-left: 26px;
  }
}
</style>
