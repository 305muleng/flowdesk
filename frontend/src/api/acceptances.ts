import request from './request'
import type { ApiResult, ProjectAcceptance } from '@/types/api'

export const getAdminAcceptancesApi = (status = 'PENDING') =>
  request.get<ApiResult<ProjectAcceptance[]>>('/admin/project-acceptances', { params: { status } })
export const reviewAcceptanceApi = (
  id: number,
  payload: { action: 'APPROVE' | 'REJECT'; reviewNote?: string },
) => request.post<ApiResult<void>>(`/admin/project-acceptances/${id}/review`, payload)
