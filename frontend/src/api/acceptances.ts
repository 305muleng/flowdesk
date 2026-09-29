import request from './request'
import type { ApiResult, ProjectAcceptance } from '@/types/api'

export interface AcceptanceCompletedTask {
  taskId: number
  title: string
  completedAt: string | null
}

export interface AcceptanceMember {
  userId: number
  userName: string
  role: string
  completedTaskCount: number
  completedTasks: AcceptanceCompletedTask[]
}

export interface AcceptanceCancelledTask {
  taskId: number
  title: string
  assigneeId: number | null
  assigneeName: string | null
  cancelReason: string | null
  cancelledAt: string | null
}

export interface ProjectAcceptanceDetail extends ProjectAcceptance {
  projectDescription: string | null
  projectGoal: string | null
  projectStatus: string
  repositoryUrl: string | null
  deployUrl: string | null
  documentUrl: string | null
  totalTaskCount: number
  completedTaskCount: number
  cancelledTaskCount: number
  completionRate: number
  members: AcceptanceMember[]
  cancelledTasks: AcceptanceCancelledTask[]
  acceptanceHistory: ProjectAcceptance[]
}

export const getAdminAcceptancesApi = (status = 'PENDING') =>
  request.get<ApiResult<ProjectAcceptance[]>>('/admin/project-acceptances', { params: { status } })
export const getAdminAcceptanceDetailApi = (id: number) =>
  request.get<ApiResult<ProjectAcceptanceDetail>>(`/admin/project-acceptances/${id}`)
export const reviewAcceptanceApi = (
  id: number,
  payload: { action: 'APPROVE' | 'REJECT'; reviewNote?: string },
) => request.post<ApiResult<void>>(`/admin/project-acceptances/${id}/review`, payload)
