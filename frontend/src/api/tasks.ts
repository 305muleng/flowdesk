import request from './request'
import type { ApiResult, Task, TaskComment, TaskSubmission } from '@/types/api'

export const getTaskApi = (id: string | number) => request.get<ApiResult<Task>>(`/tasks/${id}`)
export const getMyTasksApi = () => request.get<ApiResult<Task[]>>('/tasks/my')
export const startTaskApi = (id: string | number) =>
  request.post<ApiResult<void>>(`/tasks/${id}/start`)
export const submitTaskApi = (
  id: string | number,
  payload: { completionNote: string; resultUrl?: string; testNote: string },
) => request.post<ApiResult<number>>(`/tasks/${id}/submit`, payload)
export const reviewTaskApi = (
  id: string | number,
  payload: { action: 'APPROVE' | 'REJECT'; reviewNote?: string },
) => request.post<ApiResult<void>>(`/tasks/${id}/review`, payload)
export const cancelTaskApi = (id: string | number, payload: { reason: string }) =>
  request.post<ApiResult<void>>(`/tasks/${id}/cancel`, payload)
export const assignTaskApi = (id: string | number, assigneeId: number) =>
  request.patch<ApiResult<void>>(`/tasks/${id}/assignee`, { assigneeId })
export const getTaskCommentsApi = (id: string | number) =>
  request.get<ApiResult<TaskComment[]>>(`/tasks/${id}/comments`)
export const addTaskCommentApi = (id: string | number, content: string, parentId?: number) =>
  request.post<ApiResult<number>>(`/tasks/${id}/comments`, { content, parentId })
export const getTaskSubmissionsApi = (id: string | number) =>
  request.get<ApiResult<TaskSubmission[]>>(`/tasks/${id}/submissions`)
