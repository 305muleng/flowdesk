<script setup lang="ts">
import { ArrowLeft, Message, Plus, Refresh, UserFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import StatusTag from '@/components/StatusTag.vue'
import { useAuthStore } from '@/stores/auth'
import {
  archiveProjectApi,
  cancelProjectApi,
  createTaskApi,
  getProjectAcceptancesApi,
  getProjectApi,
  getProjectLogsApi,
  getProjectMembersApi,
  getProjectTasksApi,
  sendInvitationApi,
  startProjectApi,
  submitAcceptanceApi,
} from '@/api/projects'
import {
  createTaskRequestApi,
  getProjectTaskRequestsApi,
  reviewTaskRequestApi,
} from '@/api/taskRequests'
import { formatDate, formatDateTime, statusLabel } from '@/utils/format'
import type {
  CreateTaskPayload,
  OperationLog,
  Priority,
  Project,
  ProjectAcceptance,
  ProjectMember,
  Task,
  TaskRequest,
} from '@/types/api'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const id = computed(() => route.params.projectId as string)
const project = ref<Project>()
const tasks = ref<Task[]>([])
const members = ref<ProjectMember[]>([])
const requests = ref<TaskRequest[]>([])
const acceptances = ref<ProjectAcceptance[]>([])
const logs = ref<OperationLog[]>([])
const loading = ref(false)
const saving = ref(false)
const activeTab = ref(route.query.tab === 'requests' ? 'requests' : 'tasks')
const taskDialog = ref(false)
const requestDialog = ref(false)
const inviteDialog = ref(false)
const reviewDialog = ref(false)
const taskFormRef = ref()
const requestFormRef = ref()
const selectedRequest = ref<TaskRequest>()
const inviteUsername = ref('')
const currentRole = computed(
  () => members.value.find((item) => item.userId === auth.user?.userId)?.role,
)
const focusedRequestId = computed(() => Number(route.query.requestId) || undefined)
const isManager = computed(() => currentRole.value === 'PROJECT_MANAGER')
const taskProgress = computed(() =>
  tasks.value.length
    ? Math.round(
        (tasks.value.filter((item) => ['DONE', 'CANCELLED'].includes(item.status)).length /
          tasks.value.length) *
          100,
      )
    : 0,
)
const pendingRequests = computed(
  () => requests.value.filter((item) => item.status === 'PENDING').length,
)
const taskForm = ref<CreateTaskPayload>({
  title: '',
  description: '',
  goal: '',
  priority: 'MEDIUM',
  deadline: '',
})
const requestForm = ref({ title: '', description: '', goal: '', suggestedDeadline: '' })
const reviewForm = ref<{
  assigneeId?: number
  priority: Priority
  deadline: string
  reviewNote: string
}>({ priority: 'MEDIUM', deadline: '', reviewNote: '' })
const taskRules = {
  title: [{ required: true, message: '请输入任务标题', trigger: 'blur' }],
  deadline: [{ required: true, message: '请选择截止时间', trigger: 'change' }],
}
const requestRules = { title: [{ required: true, message: '请输入任务申请标题', trigger: 'blur' }] }

async function load() {
  loading.value = true
  try {
    const [detail, taskList, memberList] = await Promise.all([
      getProjectApi(id.value),
      getProjectTasksApi(id.value),
      getProjectMembersApi(id.value),
    ])
    project.value = detail.data.data
    tasks.value = taskList.data.data
    members.value = memberList.data.data
    if (activeTab.value === 'requests' && currentRole.value !== 'PROJECT_MANAGER') {
      activeTab.value = 'tasks'
    }
    const [acceptanceResult, logResult] = await Promise.allSettled([
      getProjectAcceptancesApi(id.value),
      getProjectLogsApi(id.value, 30),
    ])
    acceptances.value =
      acceptanceResult.status === 'fulfilled' ? acceptanceResult.value.data.data : []
    logs.value = logResult.status === 'fulfilled' ? logResult.value.data.data : []
    if (
      members.value.find((item) => item.userId === auth.user?.userId)?.role === 'PROJECT_MANAGER'
    ) {
      try {
        requests.value = (await getProjectTaskRequestsApi(id.value)).data.data
      } catch {
        requests.value = []
      }
    }
  } finally {
    loading.value = false
  }
}

function openTask() {
  taskForm.value = { title: '', description: '', goal: '', priority: 'MEDIUM', deadline: '' }
  taskDialog.value = true
}
async function createTask() {
  await taskFormRef.value?.validate()
  saving.value = true
  try {
    await createTaskApi(id.value, taskForm.value)
    ElMessage.success('任务创建成功')
    taskDialog.value = false
    await load()
  } finally {
    saving.value = false
  }
}
async function createRequest() {
  await requestFormRef.value?.validate()
  saving.value = true
  try {
    await createTaskRequestApi(id.value, {
      ...requestForm.value,
      suggestedDeadline: requestForm.value.suggestedDeadline || undefined,
    })
    ElMessage.success('任务申请已提交')
    requestDialog.value = false
  } finally {
    saving.value = false
  }
}
async function invite() {
  if (!inviteUsername.value.trim()) return
  await sendInvitationApi(id.value, inviteUsername.value.trim())
  ElMessage.success('项目邀请已发送')
  inviteDialog.value = false
  inviteUsername.value = ''
}
async function changeProject(action: 'start' | 'cancel' | 'archive') {
  if (action === 'start') await startProjectApi(id.value)
  if (action === 'archive') await archiveProjectApi(id.value)
  if (action === 'cancel') {
    const { value } = await ElMessageBox.prompt('请填写取消原因', '取消项目', {
      inputPattern: /\S+/,
      inputErrorMessage: '取消原因不能为空',
      type: 'warning',
    })
    await cancelProjectApi(id.value, value)
  }
  ElMessage.success('项目状态已更新')
  load()
}
async function submitAcceptance() {
  const { value } = await ElMessageBox.prompt(
    '说明本次项目交付范围、测试情况与验收依据',
    '提交项目验收',
    { inputType: 'textarea', inputPattern: /\S+/, inputErrorMessage: '验收说明不能为空' },
  )
  await submitAcceptanceApi(id.value, value)
  ElMessage.success('项目已提交验收')
  load()
}
function openReview(item: TaskRequest) {
  selectedRequest.value = item
  reviewForm.value = { priority: 'MEDIUM', deadline: item.suggestedDeadline || '', reviewNote: '' }
  reviewDialog.value = true
}
async function approveRequest() {
  if (!selectedRequest.value || !reviewForm.value.deadline) {
    ElMessage.warning('请设置正式截止时间')
    return
  }
  await reviewTaskRequestApi(id.value, selectedRequest.value.id, {
    action: 'APPROVE',
    ...reviewForm.value,
  })
  ElMessage.success('任务申请已批准并生成正式任务')
  reviewDialog.value = false
  load()
}
async function rejectRequest(item: TaskRequest) {
  const { value } = await ElMessageBox.prompt('填写驳回原因', '驳回任务申请', {
    inputType: 'textarea',
  })
  await reviewTaskRequestApi(id.value, item.id, { action: 'REJECT', reviewNote: value })
  ElMessage.success('任务申请已驳回')
  load()
}

onMounted(load)
</script>
<template>
  <section v-loading="loading">
    <el-button text :icon="ArrowLeft" @click="router.push('/projects')">返回项目中心</el-button>
    <header v-if="project" class="project-hero surface-card">
      <div>
        <div class="hero-tags">
          <StatusTag :value="project.status" /><StatusTag :value="currentRole" />
        </div>
        <h1>{{ project.name }}</h1>
        <p>{{ project.description || project.goal || '暂未填写项目描述' }}</p>
      </div>
      <div class="hero-actions">
        <el-button :icon="Refresh" circle @click="load" /><el-button
          v-if="isManager && project.status === 'PREPARING'"
          type="primary"
          @click="changeProject('start')"
          >启动项目</el-button
        ><el-button
          v-if="isManager && ['PREPARING', 'IN_PROGRESS'].includes(project.status)"
          @click="changeProject('cancel')"
          >取消项目</el-button
        ><el-button
          v-if="isManager && project.status === 'IN_PROGRESS'"
          type="primary"
          @click="submitAcceptance"
          >提交验收</el-button
        ><el-button
          v-if="isManager && project.status === 'COMPLETED'"
          @click="changeProject('archive')"
          >归档项目</el-button
        >
      </div>
    </header>
    <section v-if="project" class="project-facts">
      <article>
        <span>项目进度</span><strong>{{ taskProgress }}%</strong
        ><el-progress :percentage="taskProgress" :show-text="false" />
      </article>
      <article>
        <span>任务总数</span><strong>{{ tasks.length }}</strong
        ><small>{{ tasks.filter((item) => item.status === 'DONE').length }} 项已完成</small>
      </article>
      <article>
        <span>项目成员</span><strong>{{ members.length }}</strong
        ><small
          >{{ members.filter((item) => item.role === 'PROJECT_MANAGER').length }} 位负责人</small
        >
      </article>
      <article>
        <span>预计完成</span
        ><strong class="date-value">{{ formatDate(project.expectedEndTime) }}</strong
        ><small>{{
          project.startTime ? '启动于 ' + formatDate(project.startTime) : '尚未启动'
        }}</small>
      </article>
    </section>
    <el-tabs v-model="activeTab" class="project-tabs">
      <el-tab-pane label="项目任务" name="tasks">
        <section class="section-title">
          <div>
            <h3>项目任务</h3>
            <p>共 {{ tasks.length }} 项任务</p>
          </div>
          <div>
            <el-button
              v-if="
                currentRole === 'DEVELOPER' &&
                ['PREPARING', 'IN_PROGRESS'].includes(project?.status || '')
              "
              :icon="Message"
              @click="requestDialog = true"
              >提交任务申请</el-button
            ><el-button
              v-if="isManager && ['PREPARING', 'IN_PROGRESS'].includes(project?.status || '')"
              type="primary"
              :icon="Plus"
              @click="openTask"
              >新建任务</el-button
            >
          </div>
        </section>
        <div class="table-card">
          <el-table :data="tasks"
            ><el-table-column label="任务" min-width="260"
              ><template #default="{ row }"
                ><el-button link type="primary" @click="router.push('/tasks/' + row.id)"
                  ><strong>{{ row.title }}</strong></el-button
                ><small class="cell-note">{{
                  row.description || row.goal || '暂无描述'
                }}</small></template
              ></el-table-column
            ><el-table-column label="状态" width="110"
              ><template #default="{ row }"
                ><StatusTag :value="row.status" /></template></el-table-column
            ><el-table-column label="优先级" width="90"
              ><template #default="{ row }">{{
                statusLabel(row.priority)
              }}</template></el-table-column
            ><el-table-column label="负责人" width="120"
              ><template #default="{ row }">{{
                row.assigneeName || '待分配'
              }}</template></el-table-column
            ><el-table-column label="截止日期" width="125"
              ><template #default="{ row }">{{
                formatDate(row.deadline)
              }}</template></el-table-column
            ></el-table
          ><el-empty v-if="!tasks.length" description="这个项目还没有任务" />
        </div>
      </el-tab-pane>
      <el-tab-pane name="members"
        ><template #label
          >项目成员 <span class="tab-count">{{ members.length }}</span></template
        >
        <section class="section-title">
          <div>
            <h3>成员与角色</h3>
            <p>项目角色只在当前项目内生效</p>
          </div>
          <el-button
            v-if="isManager && ['PREPARING', 'IN_PROGRESS'].includes(project?.status || '')"
            type="primary"
            :icon="UserFilled"
            @click="inviteDialog = true"
            >邀请成员</el-button
          >
        </section>
        <div class="member-grid">
          <article v-for="member in members" :key="member.userId" class="surface-card member-card">
            <div class="member-avatar">{{ member.realName.slice(0, 1) }}</div>
            <div>
              <strong>{{ member.realName }}</strong
              ><small>@{{ member.username }}</small>
            </div>
            <StatusTag :value="member.role" />
          </article></div
      ></el-tab-pane>
      <el-tab-pane v-if="isManager" name="requests"
        ><template #label
          >任务申请 <span class="tab-count">{{ pendingRequests }}</span></template
        >
        <div class="request-list">
          <article
            v-for="item in requests"
            :key="item.id"
            class="surface-card request-card"
            :class="{ focused: item.id === focusedRequestId }"
          >
            <div>
              <div class="request-meta">
                <StatusTag :value="item.status" /><span
                  >{{ item.requesterName }} · {{ formatDateTime(item.createdAt) }}</span
                >
              </div>
              <h3>{{ item.title }}</h3>
              <p>{{ item.description || item.goal || '未填写补充说明' }}</p>
            </div>
            <div
              v-if="
                item.status === 'PENDING' &&
                ['PREPARING', 'IN_PROGRESS'].includes(project?.status || '')
              "
            >
              <el-button @click="rejectRequest(item)">驳回</el-button
              ><el-button type="primary" @click="openReview(item)">批准并创建任务</el-button>
            </div>
          </article>
          <el-empty v-if="!requests.length" description="暂无任务申请" /></div
      ></el-tab-pane>
      <el-tab-pane label="验收记录" name="acceptances"
        ><div class="timeline">
          <article v-for="item in acceptances" :key="item.id" class="surface-card timeline-card">
            <span>第 {{ item.acceptanceNo }} 次</span>
            <div>
              <h3>{{ item.submissionNote }}</h3>
              <p>{{ item.submitterName }} 提交于 {{ formatDateTime(item.submittedAt) }}</p>
              <p v-if="item.reviewNote">审核意见：{{ item.reviewNote }}</p>
            </div>
            <StatusTag :value="item.reviewStatus" />
          </article>
          <el-empty v-if="!acceptances.length" description="还没有验收记录" /></div
      ></el-tab-pane>
      <el-tab-pane label="操作记录" name="logs"
        ><div class="timeline">
          <article v-for="log in logs" :key="log.id" class="log-item">
            <span />
            <div>
              <strong>{{ log.description }}</strong>
              <p>{{ log.actorName }} · {{ formatDateTime(log.createdAt) }}</p>
            </div>
          </article>
          <el-empty v-if="!logs.length" description="还没有操作记录" /></div
      ></el-tab-pane>
    </el-tabs>
    <el-dialog v-model="taskDialog" title="新建正式任务" width="min(92vw,600px)"
      ><el-form ref="taskFormRef" :model="taskForm" :rules="taskRules" label-position="top"
        ><el-form-item label="任务标题" prop="title"
          ><el-input v-model="taskForm.title" maxlength="200" show-word-limit /></el-form-item
        ><el-form-item label="任务描述"
          ><el-input v-model="taskForm.description" type="textarea" :rows="3"
        /></el-form-item>
        <div class="two">
          <el-form-item label="负责人"
            ><el-select v-model="taskForm.assigneeId" clearable placeholder="暂不分配"
              ><el-option
                v-for="member in members"
                :key="member.userId"
                :label="member.realName"
                :value="member.userId" /></el-select></el-form-item
          ><el-form-item label="优先级"
            ><el-select v-model="taskForm.priority"
              ><el-option label="低" value="LOW" /><el-option label="中" value="MEDIUM" /><el-option
                label="高"
                value="HIGH" /></el-select
          ></el-form-item>
        </div>
        <el-form-item label="截止时间" prop="deadline"
          ><el-date-picker
            v-model="taskForm.deadline"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item></el-form
      ><template #footer
        ><el-button @click="taskDialog = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="createTask"
          >创建任务</el-button
        ></template
      ></el-dialog
    >
    <el-dialog v-model="requestDialog" title="提交任务申请" width="min(92vw,560px)"
      ><el-form ref="requestFormRef" :model="requestForm" :rules="requestRules" label-position="top"
        ><el-form-item label="申请标题" prop="title"
          ><el-input v-model="requestForm.title" maxlength="200" /></el-form-item
        ><el-form-item label="任务设想"
          ><el-input v-model="requestForm.description" type="textarea" :rows="4" /></el-form-item
        ><el-form-item label="建议截止时间"
          ><el-date-picker
            v-model="requestForm.suggestedDeadline"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item></el-form
      ><template #footer
        ><el-button @click="requestDialog = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="createRequest"
          >提交申请</el-button
        ></template
      ></el-dialog
    >
    <el-dialog v-model="inviteDialog" title="邀请项目成员" width="min(92vw,460px)"
      ><p class="dialog-copy">被邀请用户接受后，将以开发成员身份加入项目。</p>
      <el-input v-model="inviteUsername" placeholder="输入用户名" /><template #footer
        ><el-button @click="inviteDialog = false">取消</el-button
        ><el-button type="primary" @click="invite">发送邀请</el-button></template
      ></el-dialog
    >
    <el-dialog v-model="reviewDialog" title="批准任务申请" width="min(92vw,560px)"
      ><el-form :model="reviewForm" label-position="top"
        ><el-form-item label="任务负责人"
          ><el-select v-model="reviewForm.assigneeId" clearable
            ><el-option
              v-for="member in members"
              :key="member.userId"
              :label="member.realName"
              :value="member.userId" /></el-select
        ></el-form-item>
        <div class="two">
          <el-form-item label="优先级"
            ><el-select v-model="reviewForm.priority"
              ><el-option label="低" value="LOW" /><el-option label="中" value="MEDIUM" /><el-option
                label="高"
                value="HIGH" /></el-select></el-form-item
          ><el-form-item label="正式截止时间"
            ><el-date-picker
              v-model="reviewForm.deadline"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ss"
          /></el-form-item>
        </div>
        <el-form-item label="审批备注"
          ><el-input
            v-model="reviewForm.reviewNote"
            type="textarea"
            :rows="3" /></el-form-item></el-form
      ><template #footer
        ><el-button @click="reviewDialog = false">取消</el-button
        ><el-button type="primary" @click="approveRequest">批准并创建任务</el-button></template
      ></el-dialog
    >
  </section>
</template>
<style scoped>
.project-hero {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  margin: 13px 0 16px;
  padding: 28px 30px;
}
.hero-tags {
  display: flex;
  gap: 8px;
}
.project-hero h1 {
  margin: 17px 0 8px;
  color: var(--ink);
  font-size: 29px;
  letter-spacing: -0.04em;
}
.project-hero p {
  max-width: 700px;
  margin: 0;
  color: var(--muted);
  font-size: 14px;
  line-height: 1.7;
}
.hero-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.project-facts {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 22px;
}
.project-facts article {
  padding: 18px 20px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 13px;
}
.project-facts span,
.project-facts small {
  display: block;
  color: var(--muted);
  font-size: 11px;
}
.project-facts strong {
  display: block;
  margin: 8px 0;
  color: var(--ink);
  font-size: 24px;
}
.project-facts .date-value {
  font-size: 16px;
}
.project-tabs :deep(.el-tabs__header) {
  margin-bottom: 20px;
}
.project-tabs :deep(.el-tabs__nav-wrap:after) {
  height: 1px;
  background: var(--line);
}
.tab-count {
  display: inline-grid;
  place-items: center;
  min-width: 19px;
  height: 19px;
  margin-left: 5px;
  padding: 0 5px;
  border-radius: 99px;
  background: #eef2f8;
  font-size: 10px;
}
.cell-note {
  display: block;
  margin-top: 4px;
  color: #98a2b3;
}
.member-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
  gap: 12px;
}
.member-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 17px;
}
.member-avatar {
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  border-radius: 12px;
  background: #eaf0ff;
  color: var(--brand);
  font-weight: 800;
}
.member-card > div:nth-child(2) {
  min-width: 0;
  flex: 1;
}
.member-card strong,
.member-card small {
  display: block;
}
.member-card small {
  margin-top: 4px;
  color: var(--muted);
  font-size: 11px;
}
.request-list,
.timeline {
  display: grid;
  gap: 12px;
}
.request-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 20px 22px;
}
.request-card.focused {
  border-color: #91a8e8;
  box-shadow: 0 0 0 3px rgba(49, 91, 216, 0.1);
}
.request-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--muted);
  font-size: 11px;
}
.request-card h3 {
  margin: 12px 0 6px;
  font-size: 15px;
}
.request-card p {
  margin: 0;
  color: var(--muted);
  font-size: 12px;
}
.timeline-card {
  display: flex;
  align-items: flex-start;
  gap: 18px;
  padding: 20px;
}
.timeline-card > span {
  color: var(--brand);
  font-size: 12px;
  font-weight: 800;
}
.timeline-card > div {
  flex: 1;
}
.timeline-card h3 {
  margin: 0 0 6px;
  font-size: 14px;
}
.timeline-card p {
  margin: 4px 0;
  color: var(--muted);
  font-size: 12px;
}
.log-item {
  display: flex;
  gap: 13px;
  padding: 12px;
}
.log-item > span {
  width: 9px;
  height: 9px;
  margin-top: 4px;
  border-radius: 50%;
  background: #6d88dc;
}
.log-item strong {
  color: #344054;
  font-size: 13px;
}
.log-item p {
  margin: 5px 0 0;
  color: var(--muted);
  font-size: 11px;
}
.two {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 15px;
}
.dialog-copy {
  margin-top: 0;
  color: var(--muted);
  font-size: 13px;
}
@media (max-width: 900px) {
  .project-facts {
    grid-template-columns: repeat(2, 1fr);
  }
  .project-hero,
  .request-card {
    align-items: flex-start;
    flex-direction: column;
  }
}
@media (max-width: 580px) {
  .project-facts,
  .two {
    grid-template-columns: 1fr;
  }
}
</style>
