import type { Task } from '@/types/api'

export function isActionableTask(task: Task): boolean {
  return task.projectStatus === 'IN_PROGRESS' && ['TODO', 'IN_PROGRESS'].includes(task.status)
}

export function taskWorkLabel(task: Task): string {
  if (['COMPLETED', 'ARCHIVED', 'CANCELLED'].includes(task.projectStatus)) return '历史任务'
  if (['DONE', 'CANCELLED'].includes(task.status)) return '已结束'
  if (task.projectStatus === 'PREPARING') return '计划中的工作'
  if (task.projectStatus === 'PENDING_ACCEPTANCE') return '项目待验收'
  if (task.status === 'REVIEW') return '等待审核'
  return isActionableTask(task) ? '待处理' : '已结束'
}
