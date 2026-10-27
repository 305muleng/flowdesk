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
  let disconnect: (() => void) | undefined
  const stopWatching = watch(
    () => auth.token,
    (token) => {
      disconnect?.()
      disconnect = undefined
      if (!token) return
      disconnect = connectNotifications({
        requestTicket: async () => (await getNotificationTicketApi()).data.data.ticket,
        onReady: () => notifications.syncLive(),
        onExpired: () => {
          notifications.reset()
          auth.logout()
          void router.replace('/login')
        },
        onNotification: (notification) => {
          if (!notifications.acceptLive(notification.notificationId)) return
          void notifications.loadUnreadCount().catch(() => undefined)
          const popup = ElNotification({
            title: notification.title,
            message: '点击查看通知',
            type: 'info',
            duration: 5000,
            onClick: () => {
              popup.close()
              void router.push({ path: '/notifications', query: { notice: notification.notificationId } })
            },
          })
        },
      })
    },
    { immediate: true, flush: 'sync' },
  )

  function onStorage(event: StorageEvent) {
    if (event.key === 'flowdesk_token' && !event.newValue) {
      notifications.reset()
      auth.logout()
      void router.replace('/login')
    }
  }
  window.addEventListener('storage', onStorage)
  onUnmounted(() => {
    stopWatching()
    disconnect?.()
    window.removeEventListener('storage', onStorage)
  })
}
