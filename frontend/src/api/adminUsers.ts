import request from './request'
import type { ApiResult, SystemRole, UserStatus } from '@/types/api'

export interface AdminUser {
  id: number
  username: string
  realName: string
  systemRole: SystemRole
  status: UserStatus
  createdAt: string
  updatedAt: string
}

export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  size: number
  pages: number
}

export interface AdminUserQuery {
  page: number
  size: number
  keyword?: string
  status?: 'ACTIVE' | 'DISABLED'
  systemRole?: SystemRole
}

export const getAdminUsersApi = (params: AdminUserQuery) =>
  request.get<ApiResult<PageResult<AdminUser>>>('/admin/users', { params })

export const disableAdminUserApi = (userId: number) =>
  request.put<ApiResult<void>>(`/admin/users/${userId}/disable`)

export const enableAdminUserApi = (userId: number) =>
  request.put<ApiResult<void>>(`/admin/users/${userId}/enable`)
