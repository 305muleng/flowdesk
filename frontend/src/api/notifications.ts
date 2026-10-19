import request from './request'
import type { ApiResult, PageResult } from '@/types/api'

export interface NotificationItem {
  id: number
  type: string
  title: string
  content: string
  projectId?: number | null
  targetType?: string | null
  targetId?: number | null
  readAt?: string | null
  createdAt: string
}

export const getNotificationsApi = (params: { page?: number; size?: number; readFilter?: string; category?: string } = {}) =>
  request.get<ApiResult<PageResult<NotificationItem>>>('/notifications', { params })

export const getUnreadNotificationCountApi = () => request.get<ApiResult<number>>('/notifications/unread-count')

export const markNotificationReadApi = (notificationId: number) =>
  request.put<ApiResult<void>>(`/notifications/${notificationId}/read`)

export const markAllNotificationsReadApi = () => request.put<ApiResult<void>>('/notifications/read-all')
