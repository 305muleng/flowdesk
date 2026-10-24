import request from './request'
import type { ApiResult } from '@/types/api'
export interface Invitation {
  id: number
  projectId: number
  projectStatus: string
  projectName: string
  inviterName: string
  status: string
  expiresAt: string
  expired: boolean
  closedAt?: string | null
  closeReason?: string | null
}
export const getInvitations = () => request.get<ApiResult<Invitation[]>>('/invitations/my')
export const acceptInvitation = (id: number) => request.post<ApiResult<void>>(`/invitations/${id}/accept`)
export const rejectInvitation = (id: number) => request.post<ApiResult<void>>(`/invitations/${id}/reject`)
