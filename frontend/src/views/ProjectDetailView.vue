<script setup lang="ts">
import RequestClosureNotice from '@/components/RequestClosureNotice.vue'
import TaskRequestSummary from '@/components/TaskRequestSummary.vue'
import { ArrowLeft, Message, Plus, Refresh, UserFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, ref, watch } from 'vue'
import { usePagedList } from '@/composables/usePagedList'
import { useRoute, useRouter } from 'vue-router'
import {
  candidatesApi,
  leavesApi,
  createLeaveApi,
  createTransferApi,
  removeMemberApi,
  pendingTransfersApi,
  type Candidate,
  type LeaveRequest,
} from '@/api/membership'
import StatusTag from '@/components/StatusTag.vue'
import AcceptanceSubmissionDialog from '@/components/AcceptanceSubmissionDialog.vue'
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
  getProjectTaskSummaryApi,
  sendInvitationApi,
  startProjectApi,
  updateProjectApi,
  submitAcceptanceApi,
  type SubmitAcceptancePayload,
} from '@/api/projects'
import { createTaskRequestApi, getProjectTaskRequestsApi, reviewTaskRequestApi } from '@/api/taskRequests'
import { formatDate, formatDateTime, statusLabel, projectRestrictionMessage } from '@/utils/format'
import type {
  CreateTaskPayload,
  UpdateProjectPayload,
  OperationLog,
  Priority,
  Project,
  ProjectAcceptance,
  ProjectMember,
  Task,
  TaskRequest,
  ProjectTaskSummary,
} from '@/types/api'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const id = computed(() => route.params.projectId as string)
const project = ref<Project>()
const taskListing = usePagedList<Task>()
const { records: tasks, page: taskPage, size: taskSize, total: taskTotal, loading: taskLoading, error: taskError } = taskListing
const taskSummary = ref<ProjectTaskSummary | null>(null)
let summaryRevision = 0
let loadRevision = 0
async function loadSummary() {
  const revision = ++summaryRevision
  const projectId = id.value
  taskSummary.value = null
  try {
    const result = await getProjectTaskSummaryApi(projectId)
    if (revision === summaryRevision) taskSummary.value = result.data.data
  } catch {
    if (revision === summaryRevision) taskSummary.value = null
  }
}
async function loadTasks() {
  const projectId = id.value
  const assigneeId = focusedAssignee.value
  await taskListing.load((page, size) =>
    getProjectTasksApi(projectId, { page, size, assigneeId, unfinishedOnly: assigneeId !== undefined }).then((r) => r.data.data),
  )
}
function changeTaskPage(value: number) {
  if (taskPage.value !== value) {
    taskPage.value = value
    void loadTasks()
  }
}
function changeTaskSize(value: number) {
  taskSize.value = value
  taskListing.reset()
  void loadTasks()
}
const members = ref<ProjectMember[]>([])
const requests = ref<TaskRequest[]>([])
const acceptances = ref<ProjectAcceptance[]>([])
const logs = ref<OperationLog[]>([])
const loading = ref(false)
const loadError = ref('')
const acceptancesLoadFailed = ref(false)
const logsLoadFailed = ref(false)
const requestsLoadFailed = ref(false)
const memberRequestsLoadFailed = ref(false)
const saving = ref(false)
const actionBusy = ref(false)
const activeTab = ref(['requests', 'members'].includes(String(route.query.tab)) ? String(route.query.tab) : 'tasks')
const taskDialog = ref(false)
const requestDialog = ref(false)
const inviteDialog = ref(false)
const reviewDialog = ref(false)
const acceptanceDialog = ref(false)
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
const visibleCandidates = computed(() =>
  candidates.value.filter((item) =>
    (item.username + ' ' + item.realName).toLowerCase().includes(candidateSearch.value.trim().toLowerCase()),
  ),
)
const candidateLabels = { JOINED: '已加入', DISABLED: '账号已禁用', PENDING: '待接受', REINVITE: '重新邀请', INVITE: '邀请' }
async function openMembers(transfer = false) {
  candidates.value = (await candidatesApi(id.value)).data.data
  candidateSearch.value = ''
  if (transfer) {
    transferForm.value = { targetUsername: '', currentPassword: '', oldManagerAction: 'STAY' }
    transferDialog.value = true
  } else inviteDialog.value = true
}
async function sendInvite(item: Candidate) {
  saving.value = true
  try {
    await sendInvitationApi(id.value, item.username)
    candidates.value = (await candidatesApi(id.value)).data.data
    ElMessage.success('邀请已发送')
  } finally {
    saving.value = false
  }
}
function recordPath(kind: string, recordId: number) {
  return '/membership/' + id.value + '/' + kind + '/' + recordId
}
async function leaveProject() {
  let value: string
  try {
    value = (
      await ElMessageBox.prompt('退出申请不会冻结成员；真正退出前须完成所有未完成任务。请填写退出原因。', '申请退出', {
        inputType: 'textarea',
        inputPattern: /\S+/,
        inputErrorMessage: '退出原因必填',
      })
    ).value
  } catch (error) {
    if (isDismissed(error)) return
    throw error
  }
  const result = await createLeaveApi(id.value, value)
  await router.push(recordPath('leave-requests', result.data.data))
}
async function removeMember(member: ProjectMember) {
  let value: string
  try {
    value = (await ElMessageBox.prompt('确认移除 ' + member.realName + '？可填写移除原因。', '移除成员')).value
  } catch (error) {
    if (isDismissed(error)) return
    throw error
  }
  await removeMemberApi(id.value, member.userId, value)
  ElMessage.success('成员已移除')
  await load()
}
async function transferManager() {
  if (!transferForm.value.targetUsername || !transferForm.value.currentPassword) {
    ElMessage.warning('请选择目标用户并填写当前密码')
    return
  }
  saving.value = true
  try {
    const result = await createTransferApi(id.value, transferForm.value)
    transferForm.value.currentPassword = ''
    transferDialog.value = false
    await router.push(recordPath('manager-transfers', result.data.data))
  } finally {
    saving.value = false
  }
}
const currentRole = computed(() => members.value.find((item) => item.userId === auth.user?.userId)?.role)
const focusedRequestId = computed(() => Number(route.query.requestId) || undefined)
const isManager = computed(() => currentRole.value === 'PROJECT_MANAGER')
const assignableMembers = computed(() => members.value.filter((member) => member.effective))
const focusedAssignee = computed(() => Number(route.query.assigneeId) || undefined)
watch(focusedAssignee, () => {
  taskListing.reset()
  void loadTasks()
})

function viewMemberTasks(member: ProjectMember) {
  activeTab.value = 'tasks'
  taskListing.reset()
  if (focusedAssignee.value === member.userId) void loadTasks()
  void router.replace({ query: { ...route.query, tab: 'tasks', assigneeId: String(member.userId) } })
}
function clearMemberFilter() {
  const query = { ...route.query }
  delete query.assigneeId
  void router.replace({ query })
}

const taskProgress = computed(() => taskSummary.value?.completionRate ?? null)
const pendingRequests = computed(() => requests.value.filter((item) => item.status === 'PENDING').length)
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
function isDismissed(error: unknown) {
  return error === 'cancel' || error === 'close'
}
const reviewForm = ref<{
  assigneeId?: number
  priority: Priority
  deadline: string
  reviewNote: string
}>({ priority: 'MEDIUM', deadline: '', reviewNote: '' })
const taskRules = {
  title: [{ required: true, message: '请输入任务标题', trigger: 'blur' }],
  description: [{ required: true, whitespace: true, message: '请输入任务描述', trigger: 'blur' }],
  goal: [{ required: true, whitespace: true, message: '请输入任务目标', trigger: 'blur' }],
  deadline: [{ required: true, message: '请选择截止时间', trigger: 'change' }],
}
const requestRules = {
  title: [{ required: true, message: '请输入任务申请标题', trigger: 'blur' }],
  description: [{ required: true, whitespace: true, message: '请输入任务描述', trigger: 'blur' }],
  goal: [{ required: true, whitespace: true, message: '请输入任务目标', trigger: 'blur' }],
}

const editProjectVisible = ref(false)
const editProjectSaving = ref(false)
const editProjectForm = ref<UpdateProjectPayload>({ name: '', description: '', goal: '', expectedEndTime: null })
const canEditProject = computed(() => isManager.value && ['PREPARING', 'IN_PROGRESS'].includes(project.value?.status || ''))

function openProjectEdit() {
  if (!canEditProject.value || !project.value) return
  editProjectForm.value = {
    name: project.value.name,
    description: project.value.description || '',
    goal: project.value.goal || '',
    expectedEndTime: project.value.expectedEndTime || null,
  }
  editProjectVisible.value = true
}

async function saveProjectEdit() {
  if (editProjectSaving.value || !canEditProject.value) return
  if (!editProjectForm.value.name.trim()) {
    ElMessage.warning('请输入项目名称')
    return
  }
  const targetId = id.value
  const payload = { ...editProjectForm.value, name: editProjectForm.value.name.trim() }
  editProjectSaving.value = true
  try {
    await updateProjectApi(targetId, payload)
    if (id.value !== targetId) return
    editProjectVisible.value = false
    ElMessage.success('项目资料已更新')
    await load()
  } finally {
    editProjectSaving.value = false
  }
}

async function load() {
  const revision = ++loadRevision
  const projectId = id.value
  loading.value = true
  loadError.value = ''
  project.value = undefined
  members.value = []
  try {
    const [detail, , memberList] = await Promise.all([
      getProjectApi(projectId),
      loadTasks(),
      getProjectMembersApi(projectId),
      loadSummary(),
    ])
    if (revision !== loadRevision) return
    project.value = detail.data.data
    members.value = memberList.data.data
    if (activeTab.value === 'requests' && currentRole.value !== 'PROJECT_MANAGER') {
      activeTab.value = 'tasks'
    }
    const [acceptanceResult, logResult] = await Promise.allSettled([
      getProjectAcceptancesApi(projectId),
      getProjectLogsApi(projectId, { page: 1, size: 30 }),
    ])
    if (revision !== loadRevision) return
    acceptancesLoadFailed.value = acceptanceResult.status === 'rejected'
    logsLoadFailed.value = logResult.status === 'rejected'
    acceptances.value = acceptanceResult.status === 'fulfilled' ? acceptanceResult.value.data.data : []
    logs.value = logResult.status === 'fulfilled' ? logResult.value.data.data.records : []
    requests.value = []
    leaveRequests.value = []
    pendingTransfers.value = []
    requestsLoadFailed.value = false
    memberRequestsLoadFailed.value = false
    if (isManager.value) {
      const [taskRequests, pendingLeaves, transfers] = await Promise.allSettled([
        getProjectTaskRequestsApi(projectId),
        leavesApi(projectId),
        pendingTransfersApi(projectId),
      ])
      if (revision !== loadRevision) return
      requestsLoadFailed.value = taskRequests.status === 'rejected'
      memberRequestsLoadFailed.value = pendingLeaves.status === 'rejected' || transfers.status === 'rejected'
      requests.value = taskRequests.status === 'fulfilled' ? taskRequests.value.data.data : []
      leaveRequests.value = pendingLeaves.status === 'fulfilled' ? pendingLeaves.value.data.data : []
      pendingTransfers.value = transfers.status === 'fulfilled' ? transfers.value.data.data : []
    }
  } catch (error) {
    if (revision === loadRevision) {
      project.value = undefined
      members.value = []
      loadError.value =
        (error as { response?: { data?: { message?: string } } }).response?.data?.message || '项目资料加载失败，请重试'
    }
  } finally {
    if (revision === loadRevision) loading.value = false
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
    if (action === 'archive')
      await ElMessageBox.confirm('归档后项目将进入只读状态，确认继续归档？', '归档项目', { type: 'warning' })
    if (action === 'cancel') {
      const result = await ElMessageBox.prompt('请填写取消原因', '取消项目', {
        inputPattern: /\S+/,
        inputErrorMessage: '取消原因不能为空',
        type: 'warning',
      })
      reason = result.value
    }
  } catch (error) {
    if (isDismissed(error)) return
    throw error
  }
  if (actionBusy.value) return
  actionBusy.value = true
  try {
    if (action === 'start') await startProjectApi(id.value)
    if (action === 'archive') await archiveProjectApi(id.value)
    if (action === 'cancel') await cancelProjectApi(id.value, reason)
    ElMessage.success('项目状态已更新')
    await load()
  } finally {
    actionBusy.value = false
  }
}
async function submitAcceptance(payload: SubmitAcceptancePayload) {
  if (actionBusy.value) return
  actionBusy.value = true
  try {
    await submitAcceptanceApi(id.value, payload)
    ElMessage.success('项目已提交验收')
    // 提交完成后，即使刷新失败也应关闭表单，避免用户重复提交。
    await load().catch(() => {})
  } finally {
    actionBusy.value = false
  }
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
      action: 'APPROVE',
      ...reviewForm.value,
    })
    ElMessage.success('任务申请已批准并生成正式任务')
    reviewDialog.value = false
    await load()
  } finally {
    actionBusy.value = false
  }
}
async function rejectRequest(item: TaskRequest) {
  if (actionBusy.value) return
  let value: string
  try {
    value = (
      await ElMessageBox.prompt('填写驳回原因', '驳回任务申请', {
        inputType: 'textarea',
        inputPattern: /\S+/,
        inputErrorMessage: '请填写驳回原因',
      })
    ).value
  } catch (error) {
    if (isDismissed(error)) return
    throw error
  }
  if (actionBusy.value) return
  actionBusy.value = true
  try {
    await reviewTaskRequestApi(id.value, item.id, { action: 'REJECT', reviewNote: value })
    ElMessage.success('任务申请已驳回')
    await load()
  } finally {
    actionBusy.value = false
  }
}

watch(id, () => {
  editProjectVisible.value = false
  taskListing.reset()
  void load()
})
onMounted(load)
</script>
<template>
  <section v-loading="loading">
    <el-button text :icon="ArrowLeft" @click="router.push('/projects')">返回项目中心</el-button>
    <el-result v-if="loadError" icon="error" title="无法加载项目" :sub-title="loadError">
      <template #extra><el-button @click="load">重试</el-button></template>
    </el-result>
    <header v-if="project" class="project-hero surface-card">
      <div>
        <div class="hero-tags"><StatusTag :value="project.status" /><StatusTag :value="currentRole" /></div>
        <h1>{{ project.name }}</h1>
        <p>{{ project.description || project.goal || '暂未填写项目描述' }}</p>
      </div>
      <div class="hero-actions">
        <el-button v-if="canEditProject" :disabled="editProjectSaving || actionBusy" @click="openProjectEdit">编辑资料</el-button>
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
          @click="acceptanceDialog = true"
          >提交验收</el-button
        ><el-button v-if="isManager && project.status === 'COMPLETED'" :loading="actionBusy" @click="changeProject('archive')"
          >归档项目</el-button
        >
      </div>
    </header>
    <el-alert
      v-if="projectRestrictionMessage(project?.status)"
      :title="projectRestrictionMessage(project?.status)"
      type="info"
      :closable="false"
      show-icon
    />
    <section v-if="project" class="project-facts">
      <article>
        <span>项目进度</span><strong>{{ taskProgress === null ? '统计暂不可用' : taskProgress + '%' }}</strong
        ><el-progress v-if="taskProgress !== null" :percentage="taskProgress" :show-text="false" />
      </article>
      <article>
        <span>任务总数</span><strong>{{ taskSummary?.totalTaskCount ?? '—' }}</strong
        ><small>{{ taskSummary?.doneTaskCount ?? '—' }} 项已完成</small>
      </article>
      <article>
        <span>项目成员</span><strong>{{ members.length }}</strong
        ><small>{{ members.filter((item) => item.role === 'PROJECT_MANAGER').length }} 位负责人</small>
      </article>
      <article>
        <span>预计完成</span><strong class="date-value">{{ formatDate(project.expectedEndTime) }}</strong
        ><small>{{ project.startTime ? '启动于 ' + formatDate(project.startTime) : '尚未启动' }}</small>
      </article>
    </section>
    <el-tabs v-if="project" v-model="activeTab" class="project-tabs">
      <el-tab-pane label="项目任务" name="tasks">
        <section class="section-title">
          <div>
            <h3>项目任务</h3>
            <p>{{ taskError ? '任务列表暂不可用' : '共 ' + taskTotal + ' 项任务' }}</p>
          </div>
          <div>
            <el-button
              v-if="currentRole === 'DEVELOPER' && ['PREPARING', 'IN_PROGRESS'].includes(project?.status || '')"
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
        <div v-loading="taskLoading" class="table-card">
          <el-alert v-if="taskError" :title="taskError" type="error" :closable="false"
            ><el-button link @click="loadTasks">重试</el-button></el-alert
          >
          <el-alert
            v-if="focusedAssignee"
            :title="'正在查看成员 #' + focusedAssignee + ' 承担的 TODO、IN_PROGRESS、REVIEW 任务'"
            type="info"
            :closable="false"
          >
            <el-button link @click="clearMemberFilter">查看全部任务</el-button>
          </el-alert>
          <el-table :data="tasks"
            ><el-table-column label="任务" min-width="260"
              ><template #default="{ row }"
                ><el-button link type="primary" @click="router.push('/tasks/' + row.id)"
                  ><strong>{{ row.title }}</strong></el-button
                ><small class="cell-note">{{ row.description || row.goal || '暂无描述' }}</small></template
              ></el-table-column
            ><el-table-column label="状态" width="110"
              ><template #default="{ row }"><StatusTag :value="row.status" /></template></el-table-column
            ><el-table-column label="优先级" width="90"
              ><template #default="{ row }">{{ statusLabel(row.priority) }}</template></el-table-column
            ><el-table-column label="任务负责人" width="120"
              ><template #default="{ row }">{{ row.assigneeName || '待分配' }}</template></el-table-column
            ><el-table-column label="截止日期" width="125"
              ><template #default="{ row }">{{ formatDate(row.deadline) }}</template></el-table-column
            ></el-table
          ><el-empty v-if="!taskLoading && !taskError && !tasks.length" description="当前筛选下没有任务" />
          <el-pagination
            :current-page="taskPage"
            :page-size="taskSize"
            :page-sizes="[20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            :total="taskTotal"
            @current-change="changeTaskPage"
            @size-change="changeTaskSize"
          />
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
              <el-tag v-if="leaveRequests.some((item) => item.applicantId === member.userId)" type="warning" size="small"
                >退出申请待处理</el-tag
              >
            </div>
            <StatusTag :value="member.role" />
            <el-button v-if="member.userStatus === 'DISABLED'" @click="viewMemberTasks(member)">查看未完成任务</el-button>
            <el-tooltip
              v-if="isManager && mutable && member.role === 'DEVELOPER'"
              :disabled="member.unfinishedTaskCount === 0"
              :content="`还有 ${member.unfinishedTaskCount} 个未完成任务，暂不能移除`"
            >
              <span><el-button :disabled="member.unfinishedTaskCount > 0" @click="removeMember(member)">移除</el-button></span>
            </el-tooltip>
          </article>
        </div>
        <div v-if="mutable" class="member-actions">
          <h3>成员管理</h3>
          <el-button v-if="isManager" @click="openMembers(true)">转让项目负责人</el-button>
          <el-button v-if="currentRole === 'DEVELOPER'" @click="leaveProject">申请退出项目</el-button>
        </div>
        <div v-if="pendingTransfers.length" class="member-actions">
          <h3>待处理负责人转让</h3>
          <p v-for="transfer in pendingTransfers" :key="transfer.id">
            <el-button @click="router.push(recordPath('manager-transfers', transfer.id))"
              >转让目标：{{ transfer.toUserName }}</el-button
            >
          </p>
        </div>
        <div v-if="isManager" class="member-actions">
          <h3>退出申请</h3>
          <el-alert v-if="memberRequestsLoadFailed" title="成员流程记录加载失败，请刷新重试" type="error" :closable="false"
            ><el-button link @click="load">重试</el-button></el-alert
          >
          <p v-for="item in leaveRequests" :key="item.id">
            <el-button @click="router.push(recordPath('leave-requests', item.id))"
              >{{ item.applicantName }}：{{ item.reason }} · 待处理</el-button
            >
          </p>
          <p v-if="!memberRequestsLoadFailed && !leaveRequests.length">暂无有效待处理退出申请</p>
        </div></el-tab-pane
      >
      <el-tab-pane v-if="isManager" name="requests"
        ><template #label
          >任务申请 <span class="tab-count">{{ requestsLoadFailed ? '—' : pendingRequests }}</span></template
        >
        <div class="request-list">
          <el-alert v-if="requestsLoadFailed" title="任务申请加载失败，请刷新重试" type="error" :closable="false"
            ><el-button link @click="load">重试</el-button></el-alert
          >
          <article
            v-for="item in requests"
            :key="item.id"
            class="surface-card request-card"
            :class="{ focused: item.id === focusedRequestId }"
          >
            <div>
              <div class="request-meta">
                <StatusTag :value="item.status" /><span>{{ item.requesterName }} · {{ formatDateTime(item.createdAt) }}</span>
              </div>
              <h3>{{ item.title }}</h3>
              <TaskRequestSummary :request="item" />
              <RequestClosureNotice :status="item.status" :closed-at="item.closedAt" :close-reason="item.closeReason" />
            </div>
            <div v-if="item.status === 'PENDING' && ['PREPARING', 'IN_PROGRESS'].includes(project?.status || '')">
              <el-button :loading="actionBusy" @click="rejectRequest(item)">驳回</el-button
              ><el-button type="primary" :disabled="actionBusy" @click="openReview(item)">批准并创建任务</el-button>
            </div>
          </article>
          <el-empty v-if="!requestsLoadFailed && !requests.length" description="暂无任务申请" /></div
      ></el-tab-pane>
      <el-tab-pane label="验收记录" name="acceptances"
        ><div class="timeline">
          <el-alert v-if="acceptancesLoadFailed" title="验收记录加载失败，请刷新重试" type="error" :closable="false"
            ><el-button link @click="load">重试</el-button></el-alert
          >
          <article v-for="item in acceptances" :key="item.id" class="surface-card timeline-card">
            <span>第 {{ item.acceptanceNo }} 次</span>
            <div>
              <h3>{{ item.submissionNote }}</h3>
              <p>{{ item.submitterName }} 提交于 {{ formatDateTime(item.submittedAt) }}</p>
              <p v-if="item.reviewNote">审核意见：{{ item.reviewNote }}</p>
            </div>
            <StatusTag :value="item.reviewStatus" />
          </article>
          <el-empty v-if="!acceptancesLoadFailed && !acceptances.length" description="还没有验收记录" /></div
      ></el-tab-pane>
      <el-tab-pane label="操作记录" name="logs"
        ><div class="timeline">
          <div class="logs-heading">
            <span>最近 30 条操作记录</span>
            <el-button link type="primary" @click="router.push({ name: 'activity', query: { projectId: id } })">查看全部</el-button>
          </div>
          <el-alert v-if="logsLoadFailed" title="操作记录加载失败，请刷新重试" type="error" :closable="false"
            ><el-button link @click="load">重试</el-button></el-alert
          >
          <article v-for="log in logs" :key="log.id" class="log-item">
            <span />
            <div>
              <strong>{{ log.description }}</strong>
              <p>{{ log.actorName }} · {{ formatDateTime(log.createdAt) }}</p>
            </div>
          </article>
          <el-empty v-if="!logsLoadFailed && !logs.length" description="还没有操作记录" /></div
      ></el-tab-pane>
    </el-tabs>
    <el-dialog v-model="editProjectVisible" title="编辑项目资料" width="min(92vw,600px)" :close-on-click-modal="!editProjectSaving">
      <el-form label-position="top">
        <el-form-item label="项目名称" required><el-input v-model="editProjectForm.name" :maxlength="100" show-word-limit /></el-form-item>
        <el-form-item label="项目描述"><el-input v-model="editProjectForm.description" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="项目目标"><el-input v-model="editProjectForm.goal" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="预计结束时间"><el-date-picker v-model="editProjectForm.expectedEndTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" clearable /></el-form-item>
      </el-form>
      <template #footer><el-button :disabled="editProjectSaving" @click="editProjectVisible = false">取消</el-button><el-button type="primary" :loading="editProjectSaving" @click="saveProjectEdit">保存修改</el-button></template>
    </el-dialog>
    <el-dialog v-model="taskDialog" title="新建正式任务" width="min(92vw,600px)"
      ><el-form ref="taskFormRef" :model="taskForm" :rules="taskRules" label-position="top"
        ><el-form-item label="任务标题" prop="title"
          ><el-input v-model="taskForm.title" maxlength="200" show-word-limit /></el-form-item
        ><el-form-item label="任务描述" prop="description"><el-input v-model="taskForm.description" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="任务目标" prop="goal"><el-input v-model="taskForm.goal" type="textarea" :rows="3" /></el-form-item>
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
        ><el-button type="primary" :loading="saving" @click="createTask">创建任务</el-button></template
      ></el-dialog
    >
    <el-dialog v-model="requestDialog" title="提交任务申请" width="min(92vw,560px)"
      ><el-form ref="requestFormRef" :model="requestForm" :rules="requestRules" label-position="top"
        ><el-form-item label="申请标题" prop="title"><el-input v-model="requestForm.title" maxlength="200" /></el-form-item
        ><el-form-item label="任务描述" prop="description"><el-input v-model="requestForm.description" type="textarea" :rows="4" /></el-form-item
        ><el-form-item label="任务目标" prop="goal"><el-input v-model="requestForm.goal" type="textarea" :rows="3" /></el-form-item
        ><el-form-item label="建议截止时间"
          ><el-date-picker
            v-model="requestForm.suggestedDeadline"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item></el-form
      ><template #footer
        ><el-button @click="requestDialog = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="createRequest">提交申请</el-button></template
      ></el-dialog
    >
    <el-dialog v-model="inviteDialog" title="添加成员" width="min(92vw,640px)">
      <el-input v-model="candidateSearch" placeholder="搜索用户名或姓名" />
      <el-table :data="visibleCandidates" max-height="400">
        <el-table-column prop="realName" label="姓名" /><el-table-column prop="username" label="用户名" />
        <el-table-column label="操作"
          ><template #default="{ row }"
            ><el-button
              :loading="saving"
              :disabled="!['INVITE', 'REINVITE'].includes(row.candidateStatus)"
              @click="sendInvite(row)"
              >{{ candidateLabels[row.candidateStatus as keyof typeof candidateLabels]
              }}{{ row.candidateStatus === 'JOINED' && row.userStatus === 'DISABLED' ? ' · 账号已禁用' : '' }}</el-button
            ></template
          ></el-table-column
        >
      </el-table>
    </el-dialog>
    <el-dialog v-model="transferDialog" title="转让项目负责人" width="min(92vw,560px)">
      <el-form label-position="top">
        <el-form-item label="目标用户"
          ><el-select v-model="transferForm.targetUsername" filterable
            ><el-option
              v-for="item in candidates.filter((item) => item.userStatus === 'ACTIVE' && item.userId !== auth.user?.userId)"
              :key="item.userId"
              :value="item.username"
              :label="item.realName + ' @' + item.username" /></el-select
        ></el-form-item>
        <el-form-item label="当前密码"
          ><el-input v-model="transferForm.currentPassword" type="password" show-password
        /></el-form-item>
        <el-form-item label="转让后"
          ><el-radio-group v-model="transferForm.oldManagerAction"
            ><el-radio value="STAY">留下成为开发成员</el-radio><el-radio value="LEAVE">退出项目</el-radio></el-radio-group
          ></el-form-item
        >
        <p>目标接受后生效。选择退出时，你不能有未完成任务。</p>
      </el-form>
      <template #footer><el-button :loading="saving" @click="transferManager">发起转让</el-button></template>
    </el-dialog>
    <el-dialog v-model="reviewDialog" title="批准任务申请" width="min(92vw,560px)"
      ><section v-if="selectedRequest" class="request-review-summary">
        <h3>{{ selectedRequest.title }}</h3>
        <TaskRequestSummary :request="selectedRequest" />
      </section>
      <el-form :model="reviewForm" label-position="top"
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
            ><el-date-picker v-model="reviewForm.deadline" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"
          /></el-form-item>
        </div>
        <el-form-item label="审批备注"
          ><el-input v-model="reviewForm.reviewNote" type="textarea" :rows="3" /></el-form-item></el-form
      ><template #footer
        ><el-button @click="reviewDialog = false">取消</el-button
        ><el-button type="primary" :loading="actionBusy" @click="approveRequest">批准并创建任务</el-button></template
      ></el-dialog
    >
    <AcceptanceSubmissionDialog v-model="acceptanceDialog" :submit-action="submitAcceptance" />
  </section>
</template>
<style scoped>
.logs-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  color: var(--muted);
  font-size: 13px;
}
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
.member-actions {
  margin-top: 24px;
}
.member-actions h3 {
  font-size: 15px;
}
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
