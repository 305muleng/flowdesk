export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

export type SystemRole = 'SYSTEM_ADMIN' | 'USER'
export type UserStatus = 'ACTIVE' | 'DISABLED' | string
export type ProjectRole = 'PROJECT_MANAGER' | 'DEVELOPER'
export type ProjectStatus = 'PREPARING' | 'IN_PROGRESS' | 'PENDING_ACCEPTANCE' | 'COMPLETED' | 'ARCHIVED' | 'CANCELLED'
export type TaskStatus = 'TODO' | 'IN_PROGRESS' | 'REVIEW' | 'DONE' | 'CANCELLED'
export type Priority = 'LOW' | 'MEDIUM' | 'HIGH'

export interface LoginPayload { username: string; password: string }
export interface LoginUser { userId: number; username: string; realName: string; systemRole: SystemRole; token: string }
export interface UserProfile {
  id: number
  username: string
  realName: string
  systemRole: SystemRole
  status: UserStatus
  createdAt: string
  updatedAt: string
}

export interface Project {
  id: number
  creatorId: number
  name: string
  description?: string
  goal?: string
  status: ProjectStatus
  startTime?: string
  expectedEndTime?: string
  actualEndTime?: string
  createdAt: string
  updatedAt: string
}

export interface ProjectMember {
  userId: number
  username: string
  realName: string
  role: ProjectRole
  joinedAt: string
}

export interface CreateProjectPayload { name: string; description?: string; goal?: string; expectedEndTime?: string }

export interface Task {
  id: number
  projectId: number
  creatorId: number
  creatorName?: string
  assigneeId?: number
  assigneeName?: string
  title: string
  description?: string
  goal?: string
  status: TaskStatus
  priority?: Priority
  deadline: string
  completedAt?: string
  createdAt: string
  updatedAt: string
}

export interface CreateTaskPayload { title: string; description?: string; goal?: string; assigneeId?: number; priority?: Priority; deadline: string }

export interface TaskRequest {
  id: number
  projectId: number
  requesterId: number
  requesterName: string
  title: string
  description?: string
  goal?: string
  suggestedDeadline?: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED'
  reviewerId?: number
  reviewerName?: string
  reviewNote?: string
  taskId?: number
  createdAt: string
  reviewedAt?: string
  cancelledAt?: string
}

export interface TaskSubmission {
  id: number
  submissionNo: number
  submitterId: number
  submitterName: string
  completionNote: string
  resultUrl?: string
  testNote: string
  reviewStatus: string
  reviewerId?: number
  reviewerName?: string
  reviewNote?: string
  submittedAt: string
  reviewedAt?: string
}

export interface TaskComment {
  id: number
  parentId?: number
  authorId: number
  authorName: string
  content: string
  createdAt: string
  editedAt?: string
}

export interface ProjectAcceptance {
  id: number
  projectId: number
  projectName: string
  acceptanceNo: number
  submitterId: number
  submitterName: string
  submissionNote: string
  reviewStatus: 'PENDING' | 'APPROVED' | 'REJECTED'
  reviewerId?: number
  reviewerName?: string
  reviewNote?: string
  submittedAt: string
  reviewedAt?: string
}

export interface OperationLog {
  id: number
  projectId: number
  actorId: number
  actorName: string
  targetType: string
  targetId: number
  action: string
  description: string
  beforeData?: string
  afterData?: string
  createdAt: string
}
