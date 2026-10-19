import type { TagProps } from 'element-plus'

type TagType = TagProps['type']

const labels: Record<string, string> = {
  PREPARING: '筹备中',
  IN_PROGRESS: '进行中',
  PENDING_ACCEPTANCE: '待验收',
  COMPLETED: '已完成',
  ARCHIVED: '已归档',
  CANCELLED: '已取消',
  TODO: '待开始',
  REVIEW: '待审核',
  DONE: '已完成',
  PENDING: '待处理',
  APPROVED: '已通过',
  ACCEPTED: '已接受',
  REJECTED: '已驳回',
  EXPIRED: '已过期',
  CLOSED: '已关闭',
  LOW: '低',
  MEDIUM: '中',
  HIGH: '高',
  PROJECT_MANAGER: '项目负责人',
  DEVELOPER: '开发成员',
  SYSTEM_ADMIN: '系统管理员',
  USER: '普通用户',
  ACTIVE: '正常',
  DISABLED: '已禁用',
}

const tagTypes: Record<string, TagType> = {
  PREPARING: 'info',
  IN_PROGRESS: 'primary',
  PENDING_ACCEPTANCE: 'warning',
  COMPLETED: 'success',
  ARCHIVED: 'info',
  CANCELLED: 'danger',
  TODO: 'info',
  REVIEW: 'warning',
  DONE: 'success',
  PENDING: 'warning',
  APPROVED: 'success',
  ACCEPTED: 'success',
  REJECTED: 'danger',
  EXPIRED: 'info',
  CLOSED: 'info',
  LOW: 'info',
  MEDIUM: 'warning',
  HIGH: 'danger',
  PROJECT_MANAGER: 'primary',
  DEVELOPER: 'info',
  ACTIVE: 'success',
  DISABLED: 'danger',
}

export const statusLabel = (value?: string) => (value ? labels[value] || value : '—')
export const statusType = (value?: string): TagType => (value ? tagTypes[value] || 'info' : 'info')
export const formatDate = (value?: string) => (value ? new Date(value).toLocaleDateString('zh-CN') : '—')
export const formatDateTime = (value?: string) => (value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—')
export const isOverdue = (value?: string) => Boolean(value && new Date(value).getTime() < Date.now())

export const projectRestrictionMessage = (status?: string) => {
  if (status === 'PENDING_ACCEPTANCE')
    return '项目正在等待验收，任务推进、申请处理和成员变更已暂停；验收驳回后可继续处理仍有效的请求。'
  if (status === 'COMPLETED') return '项目已完成，普通业务操作只读；项目负责人仍可归档项目。'
  if (status === 'ARCHIVED') return '项目已归档，任务、申请和成员资料仅供查看。'
  if (status === 'CANCELLED') return '项目已取消，正常推进已停止；正式任务及历史记录保留，不会因项目取消而自动取消全部任务。'
  return ''
}

export const requestCloseReasonLabel = (reason?: string | null) => {
  if (reason === 'PROJECT_COMPLETED') return '项目已完成'
  if (reason === 'PROJECT_CANCELLED') return '项目已取消'
  return '请求已关闭（其他原因）'
}
