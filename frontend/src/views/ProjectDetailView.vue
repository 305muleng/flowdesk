<script setup lang="ts">
import { ArrowLeft, Message, Plus, Refresh, UserFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { candidatesApi, leavesApi, createLeaveApi, createTransferApi, removeMemberApi, pendingTransfersApi, type Candidate, type LeaveRequest } from '@/api/membership'
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
const actionBusy = ref(false)
const activeTab = ref(route.query.tab === 'requests' ? 'requests' : 'tasks')
const taskDialog = ref(false)
const requestDialog = ref(false)
const inviteDialog = ref(false)
const reviewDialog = ref(false)
const taskFormRef = ref()
const requestFormRef = ref()
const selectedRequest = ref<TaskRequest>()
const candidates = ref<Candidate[]>([])
const candidateSearch = ref('')
const leaveRequests = ref<LeaveRequest[]>([])
const transferDialog = ref(false)
const transferForm = ref({ targetUsername: '', currentPassword: '', oldManagerAction: 'STAY' })
const pendingTransfers = ref<{ id: number; toUserName: string }[]>([])
const mutable = computed(() => ['PREPARING', 'IN_PROGRESS'].includes(project.value?.status || ''))
const visibleCandidates = computed(() => candidates.value.filter(item => (item.username + ' ' + item.realName).toLowerCase().includes(candidateSearch.value.trim().toLowerCase())))
const candidateLabels = { JOINED: '已加入', DISABLED: '账号已禁用', PENDING: '待接受', REINVITE: '重新邀请', INVITE: '邀请' }
async function openMembers(transfer = false) {
  candidates.value = (await candidatesApi(id.value)).data.data
  candidateSearch.value = ''
  if (transfer) {
    transferForm.value = { targetUsername: '', currentPassword: '', oldManagerAction: 'STAY' }
    transferDialog.value = true
  }
  else inviteDialog.value = true
}
async function sendInvite(item: Candidate) {
  saving.value = true
  try {
    await sendInvitationApi(id.value, item.username)
    candidates.value = (await candidatesApi(id.value)).data.data
    ElMessage.success('邀请已发送')
  } finally { saving.value = false }
}
function recordPath(kind: string, recordId: number) { return '/membership/' + id.value + '/' + kind + '/' + recordId }
async function leaveProject() {
  let value: string
  try {
    value = (await ElMessageBox.prompt('退出申请不会冻结成员；真正退出前须完成所有未完成任务。请填写退出原因。', '申请退出', { inputType: 'textarea', inputPattern: /\S+/, inputErrorMessage: '退出原因必填' })).value
  } catch (error) { if (isDismissed(error)) return; throw error }
  const result = await createLeaveApi(id.value, value)
  await router.push(recordPath('leave-requests', result.data.data))
}
async function removeMember(member: ProjectMember) {
  let value: string
  try {
    value = (await ElMessageBox.prompt('确认移除 ' + member.realName + '？可填写移除原因。', '移除成员')).value
  } catch (error) { if (isDismissed(error)) return; throw error }
  await removeMemberApi(id.value, member.userId, value)
  ElMessage.success('成员已移除')
  await load()
}
async function transferManager() {
  if (!transferForm.value.targetUsername || !transferForm.value.currentPassword) { ElMessage.warning('请选择目标用户并填写当前密码'); return }
  saving.value = true
  try {
    const result = await createTransferApi(id.value, transferForm.value)
    transferForm.value.currentPassword = ''
    transferDialog.value = false
    await router.push(recordPath('manager-transfers', result.data.data))
  } finally { saving.value = false }
}
const currentRole = computed(
  () => members.value.find((item) => item.userId === auth.user?.userId)?.role,
)
const focusedRequestId = computed(() => Number(route.query.requestId) || undefined)
const isManager = computed(() => currentRole.value === 'PROJECT_MANAGER')
const assignableMembers = computed(() => members.value.filter((member) => member.effective))
const taskProgress = computed(() =>
  tasks.value.filter((item) => item.status !== 'CANCELLED').length
    ? Math.round(
        (tasks.value.filter((item) => item.status === 'DONE').length /
          tasks.value.filter((item) => item.status !== 'CANCELLED').length) *
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
function resetRequestForm() {
  requestForm.value = { title: '', description: '', goal: '', suggestedDeadline: '' }
  requestFormRef.value?.clearValidate()
}
function openRequest() {
  resetRequestForm()
  requestDialog.value = true
}
function isDismissed(error: unknown) { return error === 'cancel' || error === 'close' }
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
    requests.value = []
    leaveRequests.value = []
    pendingTransfers.value = []
    if (isManager.value) {
      const [taskRequests, pendingLeaves, transfers] = await Promise.all([
        getProjectTaskRequestsApi(id.value), leavesApi(id.value), pendingTransfersApi(id.value),
      ])
      requests.value = taskRequests.data.data
      leaveRequests.value = pendingLeaves.data.data
      pendingTransfers.value = transfers.data.data
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
    resetRequestForm()
  } finally {
    saving.value = false
  }
}
async function changeProject(action: 'start' | 'cancel' | 'archive') {
  if (actionBusy.value) return
  let reason = ''
  try {
    if (action === 'archive') await ElMessageBox.confirm('归档后项目将进入只读状态，确认继续归档？', '归档项目', { type: 'warning' })
    if (action === 'cancel') {
      const result = await ElMessageBox.prompt('请填写取消原因', '取消项目', {
        inputPattern: /\S+/, inputErrorMessage: '取消原因不能为空', type: 'warning',
      })
      reason = result.value
    }
  } catch (error) { if (isDismissed(error)) return; throw error }
  if (actionBusy.value) return
  actionBusy.value = true
  try {
    if (action === 'start') await startProjectApi(id.value)
    if (action === 'archive') await archiveProjectApi(id.value)
    if (action === 'cancel') await cancelProjectApi(id.value, reason)
    ElMessage.success('项目状态已更新')
    await load()
  } finally { actionBusy.value = false }
}
async function submitAcceptance() {
  if (actionBusy.value) return
  let value: string
  try {
    value = (await ElMessageBox.prompt(
      '说明本次项目交付范围、测试情况与验收依据', '提交项目验收',
      { inputType: 'textarea', inputPattern: /\S+/, inputErrorMessage: '验收说明不能为空' },
    )).value
  } catch (error) { if (isDismissed(error)) return; throw error }
  if (actionBusy.value) return
  actionBusy.value = true
  try {
    await submitAcceptanceApi(id.value, value)
    ElMessage.success('项目已提交验收')
    await load()
  } finally { actionBusy.value = false }
}
function openReview(item: TaskRequest) {
  selectedRequest.value = item
  reviewForm.value = { priority: 'MEDIUM', deadline: item.suggestedDeadline || '', reviewNote: '' }
  reviewDialog.value = true
}
async function approveRequest() {
  if (actionBusy.value) return
  if (!selectedRequest.value || !reviewForm.value.deadline) {
    ElMessage.warning('请设置正式截止时间')
    return
  }
  actionBusy.value = true
  try {
    await reviewTaskRequestApi(id.value, selectedRequest.value.id, {
      action: 'APPROVE', ...reviewForm.value,
    })
    ElMessage.success('任务申请已批准并生成正式任务')
    reviewDialog.value = false
    await load()
  } finally { actionBusy.value = false }
}
async function rejectRequest(item: TaskRequest) {
  if (actionBusy.value) return
  let value: string
  try {
    value = (await ElMessageBox.prompt('填写驳回原因', '驳回任务申请', {
      inputType: 'textarea', inputPattern: /\S+/, inputErrorMessage: '请填写驳回原因',
    })).value
  } catch (error) { if (isDismissed(error)) return; throw error }
  if (actionBusy.value) return
  actionBusy.value = true
  try {
    await reviewTaskRequestApi(id.value, item.id, { action: 'REJECT', reviewNote: value })
    ElMessage.success('任务申请已驳回')
    await load()
  } finally { actionBusy.value = false }
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
          :loading="actionBusy"
          @click="changeProject('start')"
          >启动项目</el-button
        ><el-button
          v-if="isManager && ['PREPARING', 'IN_PROGRESS'].includes(project.status)"
          :disabled="actionBusy"
          @click="changeProject('cancel')"
          >取消项目</el-button
        ><el-button
          v-if="isManager && project.status === 'IN_PROGRESS'"
          type="primary"
          :loading="actionBusy"
          @click="submitAcceptance"
          >提交验收</el-button
        ><el-button
          v-if="isManager && project.status === 'COMPLETED'"
          :loading="actionBusy"
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
              @click="openRequest"
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
            @click="openMembers()"
            >添加成员</el-button
          >
        </section>
        <div class="member-grid">
          <article v-for="member in members" :key="member.userId" class="surface-card member-card">
            <div class="member-avatar">{{ member.realName.slice(0, 1) }}</div>
            <div>
              <strong>{{ member.realName }}</strong
              ><small>@{{ member.username }}</small>
              <el-tag v-if="member.userStatus === 'DISABLED'" type="danger" size="small">账号已禁用</el-tag>
              <small>未完成任务：{{ member.unfinishedTaskCount }}</small>
              <el-tag v-if="leaveRequests.some(item => item.applicantId === member.userId)" type="warning" size="small">退出申请待处理</el-tag>
            </div>
            <StatusTag :value="member.role" />
            <el-tooltip v-if="isManager && mutable && member.role === 'DEVELOPER'" :disabled="member.unfinishedTaskCount === 0" :content="`还有 ${member.unfinishedTaskCount} 个未完成任务，暂不能移除`">
              <span><el-button :disabled="member.unfinishedTaskCount > 0" @click="removeMember(member)">移除</el-button></span>
            </el-tooltip>
          </article></div>
        <div v-if="mutable" class="member-actions">
          <h3>成员管理</h3>
          <el-button v-if="isManager" @click="openMembers(true)">转让项目负责人</el-button>
          <el-button v-if="currentRole === 'DEVELOPER'" @click="leaveProject">申请退出项目</el-button>
        </div>
        <div v-if="pendingTransfers.length" class="member-actions">
          <h3>待处理负责人转让</h3>
          <p v-for="transfer in pendingTransfers" :key="transfer.id"><el-button @click="router.push(recordPath('manager-transfers', transfer.id))">转让目标：{{ transfer.toUserName }}</el-button></p>
        </div>
        <div v-if="isManager" class="member-actions">
          <h3>退出申请</h3>
          <p v-for="item in leaveRequests" :key="item.id"><el-button @click="router.push(recordPath('leave-requests', item.id))">{{ item.applicantName }}：{{ item.reason }} · 待处理</el-button></p>
          <p v-if="!leaveRequests.length">暂无有效待处理退出申请</p>
        </div
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
              <el-button :loading="actionBusy" @click="rejectRequest(item)">驳回</el-button
              ><el-button type="primary" :disabled="actionBusy" @click="openReview(item)">批准并创建任务</el-button>
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
                v-for="member in assignableMembers"
                :key="member.userId"
                :label="`${member.realName} · ${statusLabel(member.role)} · 未完成任务 ${member.unfinishedTaskCount}`"
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
    <el-dialog v-model="inviteDialog" title="添加成员" width="min(92vw,640px)">
      <el-input v-model="candidateSearch" placeholder="搜索用户名或姓名" />
      <el-table :data="visibleCandidates" max-height="400">
        <el-table-column prop="realName" label="姓名" /><el-table-column prop="username" label="用户名" />
        <el-table-column label="操作"><template #default="{ row }"><el-button :loading="saving" :disabled="!['INVITE', 'REINVITE'].includes(row.candidateStatus)" @click="sendInvite(row)">{{ candidateLabels[row.candidateStatus as keyof typeof candidateLabels] }}{{ row.candidateStatus === 'JOINED' && row.userStatus === 'DISABLED' ? ' · 账号已禁用' : '' }}</el-button></template></el-table-column>
      </el-table>
    </el-dialog>
    <el-dialog v-model="transferDialog" title="转让项目负责人" width="min(92vw,560px)">
      <el-form label-position="top">
        <el-form-item label="目标用户"><el-select v-model="transferForm.targetUsername" filterable><el-option v-for="item in candidates.filter(item => item.userStatus === 'ACTIVE' && item.userId !== auth.user?.userId)" :key="item.userId" :value="item.username" :label="item.realName + ' @' + item.username" /></el-select></el-form-item>
        <el-form-item label="当前密码"><el-input v-model="transferForm.currentPassword" type="password" show-password /></el-form-item>
        <el-form-item label="转让后"><el-radio-group v-model="transferForm.oldManagerAction"><el-radio value="STAY">留下成为开发成员</el-radio><el-radio value="LEAVE">退出项目</el-radio></el-radio-group></el-form-item>
        <p>目标接受后生效。选择退出时，你不能有未完成任务。</p>
      </el-form>
      <template #footer><el-button :loading="saving" @click="transferManager">发起转让</el-button></template>
    </el-dialog>
    <el-dialog v-model="reviewDialog" title="批准任务申请" width="min(92vw,560px)"
      ><el-form :model="reviewForm" label-position="top"
        ><el-form-item label="任务负责人"
          ><el-select v-model="reviewForm.assigneeId" clearable
            ><el-option
              v-for="member in assignableMembers"
              :key="member.userId"
              :label="`${member.realName} · ${statusLabel(member.role)} · 未完成任务 ${member.unfinishedTaskCount}`"
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
        ><el-button type="primary" :loading="actionBusy" @click="approveRequest">批准并创建任务</el-button></template
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
.member-actions { margin-top: 24px; }
.member-actions h3 { font-size: 15px; }
.member-card {
  display: flex;
  flex-wrap: wrap;
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
