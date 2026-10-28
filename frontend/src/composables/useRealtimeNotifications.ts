import { onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElNotification } from 'element-plus'
import { getNotificationTicketApi } from '@/api/notifications'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'
import { connectNotifications } from '@/utils/notificationConnection'

export function useRealtimeNotifications() {
  const auth = useAuthStore()
  const notifications = useNotificationStore()
  const router = useRouter()
  const popups = new Set<ReturnType<typeof ElNotification>>()
  let disconnect: (() => void) | undefined
  let connectionRevision = 0

  function clearSession() {
    connectionRevision += 1
    disconnect?.()
    disconnect = undefined
    for (const popup of popups) popup.close()
    popups.clear()
    notifications.reset()
  }

  const stopWatching = watch(
    () => auth.token,
    (token) => {
      clearSession()
      if (!token) return
      const revision = connectionRevision
      const isCurrentSession = () => revision === connectionRevision && auth.token === token
      disconnect = connectNotifications({
        requestTicket: async () => (await getNotificationTicketApi()).data.data.ticket,
        onReady: () => {
          if (isCurrentSession()) return notifications.syncLive()
        },
        onExpired: () => {
          if (!isCurrentSession()) return
          auth.logout()
          void router.replace('/login')
        },
        onNotification: (notification) => {
          if (!isCurrentSession() || !notifications.acceptLive(notification.notificationId)) return
          void notifications.loadUnreadCount().catch(() => undefined)
          const popup = ElNotification({
            title: notification.title,
            message: '点击查看通知',
            type: 'info',
            duration: 5000,
            onClose: () => popups.delete(popup),
            onClick: () => {
              popup.close()
              if (!isCurrentSession()) return
              void router.push({ path: '/notifications', query: { notice: notification.notificationId } })
            },
          })
          popups.add(popup)
        },
      })
    },
    { immediate: true, flush: 'sync' },
  )

  function onStorage(event: StorageEvent) {
    if (event.key === 'flowdesk_token' && !event.newValue) {
      auth.logout()
      void router.replace('/login')
    }
  }
  window.addEventListener('storage', onStorage)
  onUnmounted(() => {
    stopWatching()
    clearSession()
    window.removeEventListener('storage', onStorage)
  })
}
