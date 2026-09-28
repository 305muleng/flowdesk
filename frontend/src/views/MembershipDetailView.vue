<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { leaveDetailApi, transferDetailApi, leaveActionApi, transferActionApi, type LeaveRequest, type ManagerTransfer } from '@/api/membership'
import { formatDateTime } from '@/utils/format'
const route = useRoute()
const auth = useAuthStore()
const leave = ref<LeaveRequest>()
const transfer = ref<ManagerTransfer>()
const error = ref('')
const busy = ref(false)
const isLeave = computed(() => route.params.kind === 'leave-requests')
const projectId = computed(() => String(route.params.projectId))
const recordId = computed(() => String(route.params.recordId))
const pending = computed(() => (leave.value || transfer.value)?.status === 'PENDING')
const statusNames: Record<string, string> = { PENDING: '待处理', APPROVED: '已批准', ACCEPTED: '已接受', REJECTED: '已拒绝', CANCELLED: '已撤回或取消', EXPIRED: '已过期' }
async function load() {
  busy.value = true
  leave.value = undefined
  transfer.value = undefined
  error.value = ''
  try {
    if (isLeave.value) leave.value = (await leaveDetailApi(projectId.value, recordId.value)).data.data
    else transfer.value = (await transferDetailApi(projectId.value, recordId.value)).data.data
  } catch { error.value = '记录无法加载，请确认你是业务参与人或当前项目负责人。' }
  finally { busy.value = false }
}
async function act(action: string) {
  let note = ''
  try {
    if (isLeave.value && action !== 'cancel') {
      const result = await ElMessageBox.prompt('填写审批意见', action === 'APPROVE' ? '批准退出' : '拒绝退出', { inputType: 'textarea', ...(action === 'REJECT' ? { inputPattern: /\S+/, inputErrorMessage: '请填写拒绝原因' } : {}) })
      note = result.value
    } else await ElMessageBox.confirm('确认执行此操作？', '确认')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    throw error
  }
  busy.value = true
  try {
    if (isLeave.value) await leaveActionApi(projectId.value, recordId.value, action, note)
    else await transferActionApi(projectId.value, recordId.value, action)
    ElMessage.success('操作成功')
    await load()
  } finally { busy.value = false }
}
watch(() => route.fullPath, load, { immediate: true })
</script>
<template>
  <section v-loading="busy" class="surface-card" style="padding: 24px">
    <h1>{{ isLeave ? '退出申请详情' : '负责人转让详情' }}</h1>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="leave">
      <p>申请人：{{ leave.applicantName }}</p><p>退出原因：{{ leave.reason }}</p>
      <p>状态：{{ statusNames[leave.status] || leave.status }}</p><p>有效期至：{{ formatDateTime(leave.expiresAt) }}</p>
      <p v-if="leave.reviewNote">审批意见：{{ leave.reviewNote }}</p>
      <template v-if="pending">
        <el-button v-if="auth.user?.userId === leave.applicantId" :disabled="busy" @click="act('cancel')">撤回申请</el-button>
        <template v-else><el-button :disabled="busy" @click="act('REJECT')">拒绝</el-button><el-button type="primary" :disabled="busy" @click="act('APPROVE')">批准退出</el-button></template>
      </template>
    </template>
    <template v-if="transfer">
      <p>项目：{{ transfer.projectName }}</p><p>{{ transfer.fromUserName }} → {{ transfer.toUserName }}</p>
      <p>原负责人：{{ transfer.oldManagerAction === 'STAY' ? '保留为开发成员' : '退出项目' }}</p>
      <p>状态：{{ statusNames[transfer.status] || transfer.status }}</p><p>有效期至：{{ formatDateTime(transfer.expiresAt) }}</p>
      <template v-if="pending">
        <el-button v-if="auth.user?.userId === transfer.fromUserId" :disabled="busy" @click="act('cancel')">撤回转让</el-button>
        <template v-if="auth.user?.userId === transfer.toUserId"><el-button :disabled="busy" @click="act('reject')">拒绝</el-button><el-button type="primary" :disabled="busy" @click="act('accept')">接受转让</el-button></template>
      </template>
    </template>
    <p><el-button @click="load">刷新记录</el-button><el-button @click="$router.push('/notifications')">通知中心</el-button></p>
  </section>
</template>
