import request from './request'
import type { ApiResult } from '@/types/api'
export interface Candidate { userId: number; username: string; realName: string; userStatus: string; candidateStatus: 'JOINED' | 'DISABLED' | 'PENDING' | 'REINVITE' | 'INVITE' }
export interface LeaveRequest { id: number; projectId: number; applicantId: number; applicantName: string; reason: string; status: string; expiresAt: string; reviewNote?: string }
export interface ManagerTransfer { id: number; projectId: number; projectName: string; fromUserId: number; fromUserName: string; toUserId: number; toUserName: string; status: string; oldManagerAction: string; expiresAt: string }
export const candidatesApi = (id: string) => request.get<ApiResult<Candidate[]>>(`/projects/${id}/member-candidates`)
export const leavesApi = (id: string) => request.get<ApiResult<LeaveRequest[]>>(`/projects/${id}/leave-requests`)
export const createLeaveApi = (id: string, reason: string) => request.post<ApiResult<number>>(`/projects/${id}/leave-requests`, { reason })
export const removeMemberApi = (id: string, userId: number, reason: string) => request.post(`/projects/${id}/members/${userId}/remove`, { reason })
export const createTransferApi = (id: string, payload: { targetUsername: string; currentPassword: string; oldManagerAction: string }) => request.post<ApiResult<number>>(`/projects/${id}/manager-transfers`, payload)
export const leaveDetailApi = (id: string, recordId: string) => request.get<ApiResult<LeaveRequest>>(`/projects/${id}/leave-requests/${recordId}`)
export const transferDetailApi = (id: string, recordId: string) => request.get<ApiResult<ManagerTransfer>>(`/projects/${id}/manager-transfers/${recordId}`)
export const leaveActionApi = (id: string, recordId: string, action: string, reviewNote = '') => request.post(`/projects/${id}/leave-requests/${recordId}/${action === 'cancel' ? 'cancel' : 'review'}`, action === 'cancel' ? undefined : { action, reviewNote })
export const transferActionApi = (id: string, recordId: string, action: string) => request.post(`/projects/${id}/manager-transfers/${recordId}/${action}`)

export const pendingTransfersApi = (id: string) => request.get<ApiResult<ManagerTransfer[]>>(`/projects/${id}/manager-transfers`)
