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
  REJECTED: '已驳回',
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
  REJECTED: 'danger',
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
export const formatDate = (value?: string) =>
  value ? new Date(value).toLocaleDateString('zh-CN') : '—'
export const formatDateTime = (value?: string) =>
  value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
export const isOverdue = (value?: string) =>
  Boolean(value && new Date(value).getTime() < Date.now())
