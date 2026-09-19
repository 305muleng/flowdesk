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

async function loadLogs() {
  if (!projectId.value) {
    logs.value = []
    return
  }
  loading.value = true
  try {
    logs.value = (await getProjectLogsApi(projectId.value, 100)).data.data
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  projects.value = (await getMyProjectsApi()).data.data
  projectId.value = projects.value[0]?.id
})
watch(projectId, loadLogs)
</script>

<template>
  <PageHeader
    eyebrow="ACTIVITY LOG"
    title="项目动态"
    description="查看项目生命周期中的关键业务操作与审计记录。"
  >
    <el-select v-model="projectId" placeholder="选择项目" filterable style="width: 220px"
      ><el-option
        v-for="project in projects"
        :key="project.id"
        :label="project.name"
        :value="project.id"
    /></el-select>
    <el-button :icon="Refresh" circle @click="loadLogs" />
  </PageHeader>
  <section v-loading="loading" class="surface-card activity-panel">
    <div v-for="log in logs" :key="log.id" class="log-row">
      <div class="log-mark" />
      <div class="log-content">
        <div>
          <strong>{{ log.description }}</strong
          ><span>{{ log.actorName }}</span>
        </div>
        <p>{{ log.targetType }} #{{ log.targetId }} · {{ log.action }}</p>
      </div>
      <time>{{ formatDateTime(log.createdAt) }}</time>
    </div>
    <el-empty v-if="!loading && !logs.length" description="该项目还没有操作记录" />
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
