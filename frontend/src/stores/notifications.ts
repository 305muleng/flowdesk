import { ref } from 'vue'
import { defineStore } from 'pinia'
import { getUnreadNotificationCountApi, markAllNotificationsReadApi, markNotificationReadApi } from '@/api/notifications'

export const useNotificationStore = defineStore('notifications', () => {
  const unreadCount = ref(0)
  const loading = ref(false)
  const liveRevision = ref(0)
  const liveIds = new Set<number>()
  const readNotificationIds = new Set<number>()
  const pendingReadRequests = new Map<number, Promise<void>>()
  let revision = 0
  let generation = 0
  let countRequest = 0

  async function loadUnreadCount() {
    const requestId = ++countRequest
    const startedAtRevision = revision
    const startedAtGeneration = generation
    loading.value = true
    try {
      const count = (await getUnreadNotificationCountApi()).data.data
      if (requestId === countRequest && startedAtRevision === revision && startedAtGeneration === generation) {
        unreadCount.value = count
      }
    } finally {
      if (requestId === countRequest) loading.value = false
    }
  }

  function markAsRead(notificationId: number) {
    if (readNotificationIds.has(notificationId)) return Promise.resolve()

    const pendingRequest = pendingReadRequests.get(notificationId)
    if (pendingRequest) return pendingRequest

    const startedAtGeneration = generation
    const startedAtCountRequest = countRequest
    const startedAtLiveRevision = liveRevision.value
    const request = markNotificationReadApi(notificationId)
      .then(async () => {
        if (startedAtGeneration !== generation) return
        readNotificationIds.add(notificationId)
        revision += 1
        if (countRequest === startedAtCountRequest && liveRevision.value === startedAtLiveRevision) {
          unreadCount.value = Math.max(0, unreadCount.value - 1)
        } else {
          await loadUnreadCount().catch(() => undefined)
        }
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
    const startedAtCountRequest = countRequest
    const startedAtLiveRevision = liveRevision.value
    await markAllNotificationsReadApi()
    if (startedAtGeneration !== generation) return
    revision += 1
    if (countRequest === startedAtCountRequest && liveRevision.value === startedAtLiveRevision) {
      unreadCount.value = 0
    } else {
      await loadUnreadCount().catch(() => undefined)
    }
  }

  function acceptLive(notificationId: number) {
    if (liveIds.has(notificationId)) return false
    liveIds.add(notificationId)
    if (liveIds.size > 300) liveIds.delete(liveIds.values().next().value!)
    revision += 1
    liveRevision.value += 1
    return true
  }

  async function syncLive() {
    const startedAtGeneration = generation
    await loadUnreadCount()
    if (generation === startedAtGeneration) liveRevision.value += 1
  }

  function reset() {
    countRequest += 1
    loading.value = false
    generation += 1
    revision += 1
    unreadCount.value = 0
    liveRevision.value = 0
    liveIds.clear()
    readNotificationIds.clear()
    pendingReadRequests.clear()
  }

  return { unreadCount, loading, liveRevision, acceptLive, syncLive, loadUnreadCount, markAsRead, markAllAsRead, reset }
})
