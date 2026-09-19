import request from './request'
import type {
  ApiResult,
  CreateProjectPayload,
  CreateTaskPayload,
  OperationLog,
  Project,
  ProjectAcceptance,
  ProjectMember,
  Task,
} from '@/types/api'

export const getMyProjectsApi = () => request.get<ApiResult<Project[]>>('/projects')
export const createProjectApi = (payload: CreateProjectPayload) =>
  request.post<ApiResult<number>>('/projects', payload)
export const getProjectApi = (projectId: string | number) =>
  request.get<ApiResult<Project>>(`/projects/${projectId}`)
export const getProjectMembersApi = (projectId: string | number) =>
  request.get<ApiResult<ProjectMember[]>>(`/projects/${projectId}/members`)
export const getProjectTasksApi = (projectId: string | number) =>
  request.get<ApiResult<Task[]>>(`/projects/${projectId}/tasks`)
export const createTaskApi = (projectId: string | number, payload: CreateTaskPayload) =>
  request.post<ApiResult<number>>(`/projects/${projectId}/tasks`, payload)
export const sendInvitationApi = (projectId: string | number, username: string) =>
  request.post<ApiResult<number>>(`/projects/${projectId}/invitations`, { username })
export const startProjectApi = (projectId: string | number) =>
  request.post<ApiResult<void>>(`/projects/${projectId}/start`)
export const cancelProjectApi = (projectId: string | number, reason: string) =>
  request.post<ApiResult<void>>(`/projects/${projectId}/cancel`, { reason })
export const archiveProjectApi = (projectId: string | number) =>
  request.post<ApiResult<void>>(`/projects/${projectId}/archive`)
export const submitAcceptanceApi = (projectId: string | number, submissionNote: string) =>
  request.post<ApiResult<number>>(`/projects/${projectId}/acceptances`, { submissionNote })
export const getProjectAcceptancesApi = (projectId: string | number) =>
  request.get<ApiResult<ProjectAcceptance[]>>(`/projects/${projectId}/acceptances`)
export const getProjectLogsApi = (projectId: string | number, limit = 50) =>
  request.get<ApiResult<OperationLog[]>>(`/projects/${projectId}/operation-logs`, {
    params: { limit },
  })
