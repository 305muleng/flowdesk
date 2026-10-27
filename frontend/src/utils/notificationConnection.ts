export interface LiveNotification {
  notificationId: number
  title: string
}

interface Callbacks {
  requestTicket: () => Promise<string>
  onReady: () => void | Promise<void>
  onNotification: (notification: LiveNotification) => void
  onExpired: () => void
}

export function connectNotifications(callbacks: Callbacks): () => void {
  let stopped = false
  let attempt = 0
  let serial = 0
  let socket: WebSocket | undefined
  let timer: ReturnType<typeof setTimeout> | undefined

  function stop() {
    if (stopped) return
    stopped = true
    serial++
    if (timer !== undefined) clearTimeout(timer)
    socket?.close(1000, '离开通知连接')
    socket = undefined
  }

  function expired() {
    stop()
    callbacks.onExpired()
  }

  function retry() {
    if (stopped || timer !== undefined) return
    const delay = Math.min(30000, 1000 * 2 ** Math.min(attempt++, 5))
    timer = setTimeout(() => {
      timer = undefined
      void open()
    }, delay)
  }

  async function open() {
    const current = ++serial
    try {
      const ticket = await callbacks.requestTicket()
      if (stopped || current !== serial) return
      const url = new URL('/ws/notifications', window.location.href)
      url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
      url.searchParams.set('ticket', ticket)
      const connection = new WebSocket(url)
      socket = connection
      let ready = false
      connection.onmessage = (event) => {
        if (stopped || current !== serial || typeof event.data !== 'string') return
        try {
          const message = JSON.parse(event.data)
          if (message.type === 'ready' && !ready) {
            ready = true
            attempt = 0
            void Promise.resolve(callbacks.onReady()).catch(() => undefined)
          } else if (
            message.type === 'notification.created' &&
            Number.isSafeInteger(message.notificationId) &&
            message.notificationId > 0 &&
            typeof message.title === 'string'
          ) {
            callbacks.onNotification({ notificationId: message.notificationId, title: message.title })
          }
        } catch {
          /* 无效消息不影响后续通知。 */
        }
      }
      connection.onclose = (event) => {
        if (stopped || current !== serial) return
        socket = undefined
        if (event.code === 4001 || event.code === 4003) expired()
        else retry()
      }
      connection.onerror = () => {
        /* 浏览器随后触发 close，由同一入口安排重连。 */
      }
    } catch (error) {
      if (stopped || current !== serial) return
      const status = (error as { response?: { status?: number } }).response?.status
      if (status === 401 || status === 403) expired()
      else retry()
    }
  }

  void open()
  return stop
}
