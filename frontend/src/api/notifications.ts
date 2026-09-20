import request from './request'
import type { ApiResult } from '@/types/api'

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

export const getNotificationsApi = () =>
  request.get<ApiResult<NotificationItem[]>>('/notifications')

export const getUnreadNotificationCountApi = () =>
  request.get<ApiResult<number>>('/notifications/unread-count')

export const markNotificationReadApi = (notificationId: number) =>
  request.put<ApiResult<void>>(`/notifications/${notificationId}/read`)

export const markAllNotificationsReadApi = () =>
  request.put<ApiResult<void>>('/notifications/read-all')
