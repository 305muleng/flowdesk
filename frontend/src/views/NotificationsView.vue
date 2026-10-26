<script setup lang="ts">
import { Bell, Check, Refresh, Right } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import { usePagedList } from '@/composables/usePagedList'
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
  | 'MEMBER_LEAVE_REQUEST'
  | 'PROJECT_MANAGER_TRANSFER'
  | 'PROJECT_MEMBER_REMOVED'
  | 'PROJECT_MEMBER_DISABLED'

const router = useRouter()
const auth = useAuthStore()
const notificationStore = useNotificationStore()
const listing = usePagedList<NotificationItem>()
const { records: notifications, page, size, total, loading, error } = listing
const processingIds = ref(new Set<number>())
const markingAll = ref(false)
const readFilter = ref<ReadFilter>('UNREAD')
const categoryFilter = ref<CategoryFilter>('ALL')
const readRequests = new Map<number, Promise<void>>()
const hasUnread = computed(() => notificationStore.unreadCount > 0)

function isManagerTransferNotification(type: string): boolean {
  return type === 'PROJECT_MANAGER_TRANSFER' || type.startsWith('PROJECT_MANAGER_TRANSFER_')
}

function categoryFor(type: string): Exclude<CategoryFilter, 'ALL'> | 'OTHER' {
  if (type.startsWith('PROJECT_INVITATION')) return 'PROJECT_INVITATION'
  if (['TASK_ASSIGNED', 'TASK_DUE_SOON', 'TASK_OVERDUE'].includes(type)) return 'TASK'
  if (type.startsWith('TASK_REQUEST')) return 'TASK_REQUEST'
  if (type.startsWith('TASK_SUBMISSION')) return 'TASK_SUBMISSION'
  if (type.startsWith('PROJECT_ACCEPTANCE')) return 'PROJECT_ACCEPTANCE'
  if (type.startsWith('MEMBER_LEAVE_REQUEST_')) return 'MEMBER_LEAVE_REQUEST'
  if (isManagerTransferNotification(type)) return 'PROJECT_MANAGER_TRANSFER'
  if (type === 'PROJECT_MEMBER_REMOVED') return 'PROJECT_MEMBER_REMOVED'
  if (type === 'PROJECT_MEMBER_DISABLED') return 'PROJECT_MEMBER_DISABLED'
  return 'OTHER'
}

function actionFor(item: NotificationItem): NotificationAction | undefined {
  const category = categoryFor(item.type)
  if (category === 'PROJECT_MEMBER_REMOVED') return undefined
  if (category === 'PROJECT_MEMBER_DISABLED' && item.projectId)
    return { label: '查看项目成员', to: '/projects/' + item.projectId + '?tab=members' }
  if (
    item.projectId &&
    item.targetId &&
    (item.type.startsWith('MEMBER_LEAVE_REQUEST_') || isManagerTransferNotification(item.type))
  ) {
    const kind = item.type.startsWith('MEMBER_LEAVE_REQUEST_') ? 'leave-requests' : 'manager-transfers'
    return { label: '查看详情', to: `/membership/${item.projectId}/${kind}/${item.targetId}` }
  }
  if (item.type === 'PROJECT_INVITATION') {
    const invitationQuery = item.targetId ? `?invitationId=${item.targetId}` : ''
    return { label: '查看邀请', to: `/invitations${invitationQuery}` }
  }
  if (item.type === 'PROJECT_ACCEPTANCE_SUBMITTED' && auth.isSystemAdmin) {
    return { label: '查看验收', to: item.targetId ? `/admin/acceptances/${item.targetId}` : '/admin/acceptances' }
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
  if (['PROJECT_INVITATION_ACCEPTED', 'PROJECT_INVITATION_REJECTED'].includes(item.type) && (item.targetId || item.projectId)) {
    return { label: '进入项目', to: `/projects/${item.targetId || item.projectId}` }
  }
  if (['PROJECT_ACCEPTANCE_APPROVED', 'PROJECT_ACCEPTANCE_REJECTED'].includes(item.type) && item.projectId) {
    return { label: '查看项目', to: `/projects/${item.projectId}` }
  }
  if (item.targetType === 'PROJECT' && item.targetId) {
    return { label: '查看项目', to: `/projects/${item.targetId}` }
  }
  return undefined
}

async function load() {
  const read = readFilter.value
  const category = categoryFilter.value
  await listing.load((page, size) => getNotificationsApi({ page, size, readFilter: read, category }).then((r) => r.data.data))
  await notificationStore.loadUnreadCount().catch(() => undefined)
}
function changePage(value: number) {
  if (page.value !== value) {
    page.value = value
    void load()
  }
}
function changeSize(value: number) {
  size.value = value
  listing.reset()
  void load()
}
watch([readFilter, categoryFilter], () => {
  listing.reset()
  void load()
})

function markRead(item: NotificationItem, showSuccess = true): Promise<void> {
  if (item.readAt) return Promise.resolve()

  const pendingRequest = readRequests.get(item.id)
  if (pendingRequest) return pendingRequest

  processingIds.value = new Set(processingIds.value).add(item.id)
  const request = notificationStore
    .markAsRead(item.id)
    .then(async () => {
      item.readAt = new Date().toISOString()
      if (showSuccess) ElMessage.success('已标记为已读')
      await load()
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
    await load()
    ElMessage.success('全部通知已标记为已读')
  } finally {
    markingAll.value = false
  }
}

onMounted(load)
</script>

<template>
  <PageHeader eyebrow="NOTIFICATIONS" title="通知中心" description="集中查看项目、任务与验收动态，并按需进入对应工作项。">
    <el-button :icon="Refresh" circle :loading="loading" @click="load" />
    <el-button :icon="Check" :loading="markingAll" :disabled="!hasUnread" @click="markAllRead"> 全部已读 </el-button>
  </PageHeader>

  <el-alert v-if="error" class="load-error" :title="error" type="error" show-icon :closable="false">
    <template #default><el-button link type="primary" @click="load">重新加载</el-button></template>
  </el-alert>

  <section class="surface-card notification-filters">
    <el-segmented
      v-model="readFilter"
      :options="[
        { label: '未读', value: 'UNREAD' },
        { label: '已读', value: 'READ' },
        { label: '全部', value: 'ALL' },
      ]"
    />
    <el-select v-model="categoryFilter" class="category-filter" aria-label="通知类别">
      <el-option label="全部类别" value="ALL" />
      <el-option label="项目邀请" value="PROJECT_INVITATION" />
      <el-option label="任务" value="TASK" />
      <el-option label="任务申请" value="TASK_REQUEST" />
      <el-option label="任务提交" value="TASK_SUBMISSION" />
      <el-option label="项目验收" value="PROJECT_ACCEPTANCE" />
      <el-option label="退出申请" value="MEMBER_LEAVE_REQUEST" />
      <el-option label="负责人转让" value="PROJECT_MANAGER_TRANSFER" />
      <el-option label="成员移除" value="PROJECT_MEMBER_REMOVED" />
      <el-option label="成员账号禁用" value="PROJECT_MEMBER_DISABLED" />
    </el-select>
  </section>

  <section v-loading="loading" class="notification-list">
    <el-empty v-if="!loading && !error && !notifications.length" description="当前筛选条件下没有通知" />
    <article v-for="item in notifications" :key="item.id" class="notification-card" :class="{ unread: !item.readAt }">
      <div class="notification-icon">
        <el-icon><Bell /></el-icon>
      </div>
      <div class="notification-main">
        <div class="notification-heading">
          <h3>{{ item.title }}</h3>
          <span v-if="!item.readAt" class="unread-label">未读</span>
        </div>
        <p>{{ item.content }}</p>
        <small>{{ formatDateTime(item.createdAt) }}</small>
      </div>
      <div class="notification-actions">
        <el-button v-if="actionFor(item)" type="primary" plain :icon="Right" @click="openAction(item, actionFor(item)!)">
          {{ actionFor(item)?.label }}
        </el-button>
        <el-button v-if="!item.readAt" :loading="processingIds.has(item.id)" @click="markRead(item)"> 标记已读 </el-button>
      </div>
    </article>
    <el-pagination
      :current-page="page"
      :page-size="size"
      :page-sizes="[20, 50, 100]"
      layout="total, sizes, prev, pager, next"
      :total="total"
      @current-change="changePage"
      @size-change="changeSize"
    />
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
