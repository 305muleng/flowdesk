import { ref } from 'vue'
import { defineStore } from 'pinia'
import {
  getUnreadNotificationCountApi,
  markAllNotificationsReadApi,
  markNotificationReadApi,
} from '@/api/notifications'

export const useNotificationStore = defineStore('notifications', () => {
  const unreadCount = ref(0)
  const loading = ref(false)
  const readNotificationIds = new Set<number>()
  const pendingReadRequests = new Map<number, Promise<void>>()
  let revision = 0
  let generation = 0

  async function loadUnreadCount() {
    const startedAtRevision = revision
    const startedAtGeneration = generation
    loading.value = true
    try {
      const count = (await getUnreadNotificationCountApi()).data.data
      if (startedAtRevision === revision && startedAtGeneration === generation) {
        unreadCount.value = count
      }
    } finally {
      loading.value = false
    }
  }

  function markAsRead(notificationId: number) {
    if (readNotificationIds.has(notificationId)) return Promise.resolve()

    const pendingRequest = pendingReadRequests.get(notificationId)
    if (pendingRequest) return pendingRequest

    const startedAtGeneration = generation
    const request = markNotificationReadApi(notificationId)
      .then(() => {
        if (startedAtGeneration !== generation) return
        readNotificationIds.add(notificationId)
        revision += 1
        unreadCount.value = Math.max(0, unreadCount.value - 1)
      })
      .finally(() => {
        if (pendingReadRequests.get(notificationId) === request) {
          pendingReadRequests.delete(notificationId)
        }
      })

    pendingReadRequests.set(notificationId, request)
    return request
  }

  async function markAllAsRead() {
    const startedAtGeneration = generation
    await markAllNotificationsReadApi()
    if (startedAtGeneration !== generation) return
    revision += 1
    unreadCount.value = 0
  }

  function reset() {
    generation += 1
    revision += 1
    unreadCount.value = 0
    readNotificationIds.clear()
    pendingReadRequests.clear()
  }

  return { unreadCount, loading, loadUnreadCount, markAsRead, markAllAsRead, reset }
})
