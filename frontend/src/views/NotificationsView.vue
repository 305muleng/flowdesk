<script setup lang="ts">
import { Bell, Check, Refresh, Right } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import { getNotificationsApi, type NotificationItem } from '@/api/notifications'
import { useNotificationStore } from '@/stores/notifications'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/format'

interface NotificationAction {
  label: string
  to: string
}

type ReadFilter = 'ALL' | 'UNREAD' | 'READ'
type CategoryFilter =
  | 'ALL'
  | 'PROJECT_INVITATION'
  | 'TASK'
  | 'TASK_REQUEST'
  | 'TASK_SUBMISSION'
  | 'PROJECT_ACCEPTANCE'

const router = useRouter()
const auth = useAuthStore()
const notificationStore = useNotificationStore()
const notifications = ref<NotificationItem[]>([])
const loading = ref(false)
const error = ref('')
const processingIds = ref(new Set<number>())
const markingAll = ref(false)
const readFilter = ref<ReadFilter>('ALL')
const categoryFilter = ref<CategoryFilter>('ALL')
const readRequests = new Map<number, Promise<void>>()
const hasUnread = computed(() => notifications.value.some((item) => !item.readAt))

function categoryFor(type: string): Exclude<CategoryFilter, 'ALL'> | 'OTHER' {
  if (type.startsWith('PROJECT_INVITATION')) return 'PROJECT_INVITATION'
  if (type === 'TASK_ASSIGNED') return 'TASK'
  if (type.startsWith('TASK_REQUEST')) return 'TASK_REQUEST'
  if (type.startsWith('TASK_SUBMISSION')) return 'TASK_SUBMISSION'
  if (type.startsWith('PROJECT_ACCEPTANCE')) return 'PROJECT_ACCEPTANCE'
  return 'OTHER'
}

const filteredNotifications = computed(() =>
  notifications.value.filter((item) => {
    const matchesReadStatus =
      readFilter.value === 'ALL' ||
      (readFilter.value === 'UNREAD' && !item.readAt) ||
      (readFilter.value === 'READ' && Boolean(item.readAt))
    const matchesCategory =
      categoryFilter.value === 'ALL' || categoryFor(item.type) === categoryFilter.value
    return matchesReadStatus && matchesCategory
  }),
)

function actionFor(item: NotificationItem): NotificationAction | undefined {
  if (item.type === 'PROJECT_INVITATION') {
    const invitationQuery = item.targetId ? `?invitationId=${item.targetId}` : ''
    return { label: '处理邀请', to: `/invitations${invitationQuery}` }
  }
  if (item.type === 'PROJECT_ACCEPTANCE_SUBMITTED' && auth.isSystemAdmin) {
    const acceptanceQuery = item.targetId ? `?acceptanceId=${item.targetId}` : ''
    return { label: '审核验收', to: `/admin/acceptances${acceptanceQuery}` }
  }
  if (item.type === 'TASK_REQUEST_SUBMITTED' && item.projectId) {
    const requestQuery = item.targetId ? `&requestId=${item.targetId}` : ''
    return {
      label: '查看申请',
      to: `/projects/${item.projectId}?tab=requests${requestQuery}`,
    }
  }
  if (item.type === 'TASK_REQUEST_REJECTED') {
    const requestQuery = item.targetId ? `?requestId=${item.targetId}` : ''
    return { label: '查看申请', to: `/task-requests${requestQuery}` }
  }
  if (item.targetType === 'TASK' && item.targetId) {
    return {
      label: item.type === 'TASK_SUBMISSION_SUBMITTED' ? '审核任务' : '查看任务',
      to: `/tasks/${item.targetId}`,
    }
  }
  if (
    ['PROJECT_INVITATION_ACCEPTED', 'PROJECT_INVITATION_REJECTED'].includes(item.type) &&
    (item.targetId || item.projectId)
  ) {
    return { label: '进入项目', to: `/projects/${item.targetId || item.projectId}` }
  }
  if (
    ['PROJECT_ACCEPTANCE_APPROVED', 'PROJECT_ACCEPTANCE_REJECTED'].includes(item.type) &&
    item.projectId
  ) {
    return { label: '查看项目', to: `/projects/${item.projectId}` }
  }
  if (item.targetType === 'PROJECT' && item.targetId) {
    return { label: '查看项目', to: `/projects/${item.targetId}` }
  }
  return undefined
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const listResponse = await getNotificationsApi()
    notifications.value = listResponse.data.data
    await notificationStore.loadUnreadCount().catch(() => undefined)
  } catch {
    error.value = '通知加载失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

function markRead(item: NotificationItem, showSuccess = true): Promise<void> {
  if (item.readAt) return Promise.resolve()

  const pendingRequest = readRequests.get(item.id)
  if (pendingRequest) return pendingRequest

  processingIds.value = new Set(processingIds.value).add(item.id)
  const request = notificationStore
    .markAsRead(item.id)
    .then(() => {
      item.readAt = new Date().toISOString()
      if (showSuccess) ElMessage.success('已标记为已读')
    })
    .finally(() => {
      readRequests.delete(item.id)
      const next = new Set(processingIds.value)
      next.delete(item.id)
      processingIds.value = next
    })

  readRequests.set(item.id, request)
  return request
}

async function openAction(item: NotificationItem, action: NotificationAction) {
  if (!item.readAt) await markRead(item, false)
  await router.push(action.to)
}

async function markAllRead() {
  if (!hasUnread.value) return
  markingAll.value = true
  try {
    await notificationStore.markAllAsRead()
    const readAt = new Date().toISOString()
    notifications.value.forEach((item) => {
      if (!item.readAt) item.readAt = readAt
    })
    ElMessage.success('全部通知已标记为已读')
  } finally {
    markingAll.value = false
  }
}

onMounted(load)
</script>

<template>
  <PageHeader
    eyebrow="NOTIFICATIONS"
    title="通知中心"
    description="集中查看项目、任务与验收动态，并按需进入对应工作项。"
  >
    <el-button :icon="Refresh" circle :loading="loading" @click="load" />
    <el-button
      :icon="Check"
      :loading="markingAll"
      :disabled="!hasUnread"
      @click="markAllRead"
    >
      全部已读
    </el-button>
  </PageHeader>

  <el-alert
    v-if="error"
    class="load-error"
    :title="error"
    type="error"
    show-icon
    :closable="false"
  >
    <template #default><el-button link type="primary" @click="load">重新加载</el-button></template>
  </el-alert>

  <section class="surface-card notification-filters">
    <el-segmented
      v-model="readFilter"
      :options="[
        { label: '全部', value: 'ALL' },
        { label: '未读', value: 'UNREAD' },
        { label: '已读', value: 'READ' },
      ]"
    />
    <el-select v-model="categoryFilter" class="category-filter" aria-label="通知类别">
      <el-option label="全部类别" value="ALL" />
      <el-option label="项目邀请" value="PROJECT_INVITATION" />
      <el-option label="任务" value="TASK" />
      <el-option label="任务申请" value="TASK_REQUEST" />
      <el-option label="任务提交" value="TASK_SUBMISSION" />
      <el-option label="项目验收" value="PROJECT_ACCEPTANCE" />
    </el-select>
  </section>

  <section v-loading="loading" class="notification-list">
    <el-empty
      v-if="!loading && !error && !filteredNotifications.length"
      :description="notifications.length ? '当前筛选条件下没有通知' : '当前没有通知'"
    />
    <article
      v-for="item in filteredNotifications"
      :key="item.id"
      class="notification-card"
      :class="{ unread: !item.readAt }"
    >
      <div class="notification-icon"><el-icon><Bell /></el-icon></div>
      <div class="notification-main">
        <div class="notification-heading">
          <h3>{{ item.title }}</h3>
          <span v-if="!item.readAt" class="unread-label">未读</span>
        </div>
        <p>{{ item.content }}</p>
        <small>{{ formatDateTime(item.createdAt) }}</small>
      </div>
      <div class="notification-actions">
        <el-button
          v-if="actionFor(item)"
          type="primary"
          plain
          :icon="Right"
          @click="openAction(item, actionFor(item)!)"
        >
          {{ actionFor(item)?.label }}
        </el-button>
        <el-button
          v-if="!item.readAt"
          :loading="processingIds.has(item.id)"
          @click="markRead(item)"
        >
          标记已读
        </el-button>
      </div>
    </article>
  </section>
</template>

<style scoped>
.load-error {
  margin-bottom: 18px;
}
.notification-filters {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
  padding: 14px 16px;
}
.category-filter {
  width: 180px;
}
.notification-list {
  display: grid;
  gap: 12px;
  min-height: 260px;
}
.notification-list :deep(.el-empty) {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--radius);
}
.notification-card {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr) auto;
  gap: 16px;
  align-items: center;
  padding: 20px 22px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 15px;
  box-shadow: var(--shadow-soft);
}
.notification-card.unread {
  background: #f7f9ff;
  border-color: #cfdbf7;
}
.notification-icon {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  border-radius: 12px;
  background: #edf2ff;
  color: var(--brand);
  font-size: 18px;
}
.notification-heading {
  display: flex;
  align-items: center;
  gap: 9px;
}
.notification-heading h3 {
  margin: 0;
  color: var(--ink);
  font-size: 15px;
}
.unread-label {
  padding: 2px 7px;
  border-radius: 999px;
  background: #dfe8ff;
  color: var(--brand);
  font-size: 10px;
  font-weight: 700;
}
.notification-main p {
  margin: 7px 0 6px;
  color: var(--text);
  font-size: 13px;
  line-height: 1.65;
}
.notification-main small {
  color: var(--muted);
  font-size: 11px;
}
.notification-actions {
  display: flex;
  gap: 8px;
}
@media (max-width: 760px) {
  .notification-filters {
    align-items: stretch;
    flex-direction: column;
  }
  .category-filter {
    width: 100%;
  }
  .notification-card {
    grid-template-columns: 40px 1fr;
  }
  .notification-actions {
    grid-column: 1/-1;
    justify-content: flex-end;
  }
}
</style>
