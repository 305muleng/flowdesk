<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue'
import { onMounted, ref, watch } from 'vue'
import PageHeader from '@/components/PageHeader.vue'
import { getMyProjectsApi, getProjectLogsApi } from '@/api/projects'
import { formatDateTime } from '@/utils/format'
import type { OperationLog, Project } from '@/types/api'

const projects = ref<Project[]>([])
const projectId = ref<number>()
const logs = ref<OperationLog[]>([])
const loading = ref(false)
const error = ref('')
const projectsError = ref('')
const projectsLoading = ref(false)
let logRevision = 0
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
  const revision = ++logRevision
  const currentProjectId = projectId.value
  error.value = ''
  logs.value = []
  if (!currentProjectId) {
    loading.value = false
    return
  }
  loading.value = true
  try {
    const result = await getProjectLogsApi(currentProjectId, 100)
    if (revision === logRevision) logs.value = result.data.data
  } catch {
    if (revision === logRevision) error.value = '操作记录加载失败，请重试'
  } finally {
    if (revision === logRevision) loading.value = false
  }
}

async function loadProjects() {
  projectsLoading.value = true
  projectsError.value = ''
  try {
    projects.value = (await getMyProjectsApi()).data.data
    projectId.value = projects.value[0]?.id
  } catch {
    projects.value = []
    projectId.value = undefined
    projectsError.value = '项目列表加载失败，请重试'
  } finally {
    projectsLoading.value = false
  }
}
onMounted(loadProjects)
watch(projectId, loadLogs)
</script>

<template>
  <PageHeader eyebrow="ACTIVITY LOG" title="项目操作记录" description="查看项目中的关键操作记录。">
    <el-select v-model="projectId" placeholder="选择项目" filterable style="width: 220px"
      ><el-option v-for="project in projects" :key="project.id" :label="project.name" :value="project.id"
    /></el-select>
    <el-button :icon="Refresh" circle aria-label="刷新操作记录" @click="projectsError ? loadProjects() : loadLogs()" />
  </PageHeader>
  <section v-loading="loading || projectsLoading" class="surface-card activity-panel">
    <el-alert v-if="projectsError" :title="projectsError" type="error" :closable="false"
      ><el-button link @click="loadProjects">重试</el-button></el-alert
    >
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
      v-if="!loading && !projectsLoading && !error && !projectsError && !logs.length"
      :description="!projects.length ? '还没有参与任何项目' : !projectId ? '请选择项目查看操作记录' : '该项目还没有操作记录'"
    />
  </section>
</template>

<style scoped>
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
