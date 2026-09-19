import request from './request'
import type { ApiResult, Priority, TaskRequest } from '@/types/api'

export const getMyTaskRequestsApi = (status = 'ALL') =>
  request.get<ApiResult<TaskRequest[]>>('/task-requests/my', { params: { status } })
export const getProjectTaskRequestsApi = (projectId: string | number, status = 'ALL') =>
  request.get<ApiResult<TaskRequest[]>>(`/projects/${projectId}/task-requests`, {
    params: { status },
  })
export const createTaskRequestApi = (
  projectId: string | number,
  payload: { title: string; description?: string; goal?: string; suggestedDeadline?: string },
) => request.post<ApiResult<number>>(`/projects/${projectId}/task-requests`, payload)
export const cancelTaskRequestApi = (projectId: string | number, requestId: number) =>
  request.post<ApiResult<void>>(`/projects/${projectId}/task-requests/${requestId}/cancel`)
export const reviewTaskRequestApi = (
  projectId: string | number,
  requestId: number,
  payload: {
    action: 'APPROVE' | 'REJECT'
    reviewNote?: string
    assigneeId?: number
    priority?: Priority
    deadline?: string
  },
) =>
  request.post<ApiResult<number | null>>(
    `/projects/${projectId}/task-requests/${requestId}/review`,
    payload,
  )
