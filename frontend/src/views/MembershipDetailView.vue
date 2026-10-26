<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import {
  leaveDetailApi,
  transferDetailApi,
  leaveActionApi,
  transferActionApi,
  type LeaveRequest,
  type ManagerTransfer,
} from '@/api/membership'
import { formatDateTime } from '@/utils/format'
import RequestClosureNotice from '@/components/RequestClosureNotice.vue'
const route = useRoute()
const auth = useAuthStore()
const leave = ref<LeaveRequest>()
const transfer = ref<ManagerTransfer>()
const error = ref('')
const busy = ref(false)
const isLeave = computed(() => route.params.kind === 'leave-requests')
const projectId = computed(() => String(route.params.projectId))
const recordId = computed(() => String(route.params.recordId))
const record = computed(() => leave.value || transfer.value)
const pending = computed(
  () => record.value?.status === 'PENDING' && ['PREPARING', 'IN_PROGRESS'].includes(record.value.projectStatus),
)
const statusNames: Record<string, string> = {
  PENDING: '待处理',
  APPROVED: '已批准',
  ACCEPTED: '已接受',
  REJECTED: '已拒绝',
  CANCELLED: '已撤回或取消',
  EXPIRED: '已过期',
  CLOSED: '已关闭',
}
let loadRevision = 0
onScopeDispose(() => { loadRevision += 1 })

async function load() {
  const revision = ++loadRevision
  const project = projectId.value
  const id = recordId.value
  const loadingLeave = isLeave.value
  busy.value = true
  leave.value = undefined
  transfer.value = undefined
  error.value = ''
  try {
    const result = loadingLeave
      ? await leaveDetailApi(project, id)
      : await transferDetailApi(project, id)
    if (revision !== loadRevision) return
    if (loadingLeave) leave.value = result.data.data as LeaveRequest
    else transfer.value = result.data.data as ManagerTransfer
  } catch {
    if (revision === loadRevision) error.value = '记录无法加载，请确认你是业务参与人或当前项目负责人。'
  } finally {
    if (revision === loadRevision) busy.value = false
  }
}
async function act(action: string) {
  const project = projectId.value
  const id = recordId.value
  const actingOnLeave = isLeave.value
  const revision = loadRevision
  let note = ''
  try {
    if (actingOnLeave && action !== 'cancel') {
      const result = await ElMessageBox.prompt('填写审批意见', action === 'APPROVE' ? '批准退出' : '拒绝退出', {
        inputType: 'textarea',
        ...(action === 'REJECT' ? { inputPattern: /\S+/, inputErrorMessage: '请填写拒绝原因' } : {}),
      })
      note = result.value
    } else await ElMessageBox.confirm('确认执行此操作？', '确认')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    throw error
  }
  if (revision !== loadRevision) return
  busy.value = true
  try {
    if (actingOnLeave) await leaveActionApi(project, id, action, note)
    else await transferActionApi(project, id, action)
    if (revision !== loadRevision) return
    ElMessage.success('操作成功')
    await load()
  } finally {
    if (revision === loadRevision) busy.value = false
  }
}
watch(() => route.fullPath, load, { immediate: true })
</script>
<template>
  <section v-loading="busy" class="surface-card" style="padding: 24px">
    <h1>{{ isLeave ? '退出申请详情' : '负责人转让详情' }}</h1>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <RequestClosureNotice v-if="record" :status="record.status" :closed-at="record.closedAt" :close-reason="record.closeReason" />
    <el-alert
      v-if="record?.status === 'PENDING' && !pending"
      title="当前项目状态暂停或禁止处理此请求；记录仍保留原状态。"
      type="info"
      :closable="false"
    />
    <template v-if="leave">
      <p>申请人：{{ leave.applicantName }}</p>
      <p>退出原因：{{ leave.reason }}</p>
      <p>状态：{{ statusNames[leave.status] || leave.status }}</p>
      <p>有效期至：{{ formatDateTime(leave.expiresAt) }}</p>
      <p v-if="leave.reviewNote">审批意见：{{ leave.reviewNote }}</p>
      <template v-if="pending">
        <el-button v-if="auth.user?.userId === leave.applicantId" :disabled="busy" @click="act('cancel')">撤回申请</el-button>
        <template v-else
          ><el-button :disabled="busy" @click="act('REJECT')">拒绝</el-button
          ><el-button type="primary" :disabled="busy" @click="act('APPROVE')">批准退出</el-button></template
        >
      </template>
    </template>
    <template v-if="transfer">
      <p>项目：{{ transfer.projectName }}</p>
      <p>{{ transfer.fromUserName }} → {{ transfer.toUserName }}</p>
      <p>原负责人：{{ transfer.oldManagerAction === 'STAY' ? '保留为开发成员' : '退出项目' }}</p>
      <p>状态：{{ statusNames[transfer.status] || transfer.status }}</p>
      <p>有效期至：{{ formatDateTime(transfer.expiresAt) }}</p>
      <template v-if="pending">
        <el-button v-if="auth.user?.userId === transfer.fromUserId" :disabled="busy" @click="act('cancel')">撤回转让</el-button>
        <template v-if="auth.user?.userId === transfer.toUserId"
          ><el-button :disabled="busy" @click="act('reject')">拒绝</el-button
          ><el-button type="primary" :disabled="busy" @click="act('accept')">接受转让</el-button></template
        >
      </template>
    </template>
    <p><el-button @click="load">刷新记录</el-button><el-button @click="$router.push('/notifications')">通知中心</el-button></p>
  </section>
</template>
