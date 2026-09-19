<script setup lang="ts">
import { DocumentAdd, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, ref, watch } from 'vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { cancelTaskRequestApi, getMyTaskRequestsApi } from '@/api/taskRequests'
import { getMyProjectsApi } from '@/api/projects'
import { formatDateTime } from '@/utils/format'
import type { Project, TaskRequest } from '@/types/api'

const requests = ref<TaskRequest[]>([])
const projects = ref<Project[]>([])
const filter = ref('ALL')
const loading = ref(false)
const projectMap = computed(() => new Map(projects.value.map((item) => [item.id, item.name])))

async function load() {
  loading.value = true
  try {
    const [requestResult, projectResult] = await Promise.allSettled([
      getMyTaskRequestsApi(filter.value),
      getMyProjectsApi(),
    ])
    requests.value = requestResult.status === 'fulfilled' ? requestResult.value.data.data : []
    projects.value = projectResult.status === 'fulfilled' ? projectResult.value.data.data : []
  } finally {
    loading.value = false
  }
}

async function cancel(item: TaskRequest) {
  await ElMessageBox.confirm('撤回后不能重新提交这条申请，确认继续吗？', '撤回任务申请', {
    type: 'warning',
  })
  await cancelTaskRequestApi(item.projectId, item.id)
  ElMessage.success('任务申请已撤回')
  load()
}

watch(filter, load)
onMounted(load)
</script>

<template>
  <PageHeader
    eyebrow="TASK REQUESTS"
    title="我的任务申请"
    description="跟踪你向各项目提交的任务建议及审批结果。"
  >
    <el-button :icon="Refresh" circle @click="load" />
    <el-button type="primary" :icon="DocumentAdd" @click="$router.push('/projects')"
      >前往项目提交申请</el-button
    >
  </PageHeader>
  <section class="toolbar">
    <div>
      <h3>申请记录</h3>
      <p>共 {{ requests.length }} 条</p>
    </div>
    <el-segmented
      v-model="filter"
      :options="[
        { label: '全部', value: 'ALL' },
        { label: '待处理', value: 'PENDING' },
        { label: '已通过', value: 'APPROVED' },
        { label: '已驳回', value: 'REJECTED' },
      ]"
    />
  </section>
  <section v-loading="loading" class="request-list">
    <article v-for="item in requests" :key="item.id" class="surface-card request-card">
      <div class="request-main">
        <div class="request-top">
          <StatusTag :value="item.status" /><span>{{
            projectMap.get(item.projectId) || '项目 #' + item.projectId
          }}</span
          ><small>{{ formatDateTime(item.createdAt) }}</small>
        </div>
        <h3>{{ item.title }}</h3>
        <p>{{ item.description || item.goal || '未填写补充说明' }}</p>
      </div>
      <div class="request-side">
        <p v-if="item.reviewNote"><strong>审批意见</strong>{{ item.reviewNote }}</p>
        <el-button v-if="item.status === 'PENDING'" type="danger" link @click="cancel(item)"
          >撤回申请</el-button
        ><el-button
          v-if="item.taskId"
          type="primary"
          link
          @click="$router.push('/tasks/' + item.taskId)"
          >查看正式任务</el-button
        >
      </div>
    </article>
    <el-empty v-if="!loading && !requests.length" description="暂无符合条件的任务申请" />
  </section>
</template>

<style scoped>
.request-list {
  display: grid;
  gap: 12px;
}
.request-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 28px;
  padding: 22px 24px;
}
.request-main {
  min-width: 0;
}
.request-top {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--muted);
  font-size: 12px;
}
.request-top small {
  color: #a2aabb;
}
.request-main h3 {
  margin: 13px 0 7px;
  color: var(--ink);
  font-size: 17px;
}
.request-main p {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
}
.request-side {
  max-width: 320px;
  text-align: right;
}
.request-side p {
  margin: 0 0 8px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}
.request-side strong {
  display: block;
  color: #475467;
}
@media (max-width: 720px) {
  .request-card {
    align-items: flex-start;
    flex-direction: column;
  }
  .request-side {
    text-align: left;
  }
}
</style>
