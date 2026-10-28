const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const { parse } = require('@vue/compiler-sfc')
const read = (file) => fs.readFileSync(path.join(__dirname, '../src', file), 'utf8')
const compile = (source) => ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
const tick = async () => { await Promise.resolve(); await Promise.resolve() }

function transport(requestTicket) {
  const sockets = [], timers = new Map()
  let timerId = 0, ready = 0, expired = 0
  const notices = []
  class FakeSocket {
    constructor(url) { this.url = String(url); sockets.push(this) }
    close() { this.wasClosed = true }
    message(message) { this.onmessage({ data: typeof message === 'string' ? message : JSON.stringify(message) }) }
    end(code = 1006) { this.onclose({ code }) }
  }
  const context = { exports: {}, URL, WebSocket: FakeSocket, window: { location: { href: 'http://localhost:8088/dashboard' } },
    setTimeout(fn, delay) { const id = ++timerId; timers.set(id, { fn, delay }); return id }, clearTimeout(id) { timers.delete(id) } }
  vm.runInNewContext(compile(read('utils/notificationConnection.ts')), context)
  let ticket = 0
  const stop = context.exports.connectNotifications({
    requestTicket: requestTicket || (async () => 'ticket-' + ++ticket),
    onReady: () => { ready++ }, onNotification: (notice) => notices.push({ ...notice }), onExpired: () => { expired++ },
  })
  return { sockets, timers, notices, stop, get ready() { return ready }, get expired() { return expired } }
}

test('重连使用新票据，并重新同步；旧连接晚到的消息被忽略', async () => {
  const t = transport(); await tick()
  t.sockets[0].message({ type: 'ready' })
  t.sockets[0].message({ type: 'ready' })
  assert.equal(t.ready, 1)
  assert.equal(new URL(t.sockets[0].url).searchParams.get('ticket'), 'ticket-1')
  t.sockets[0].end()
  const retry = [...t.timers.values()][0]; assert.equal(retry.delay, 1000)
  t.timers.clear(); retry.fn(); await tick()
  assert.equal(new URL(t.sockets[1].url).searchParams.get('ticket'), 'ticket-2')
  t.sockets[1].message({ type: 'ready' }); assert.equal(t.ready, 2)
  t.sockets[0].message({ type: 'notification.created', notificationId: 1, title: '旧连接' })
  t.sockets[1].message({ type: 'notification.created', notificationId: 2, title: '新通知' })
  assert.deepEqual(t.notices, [{ notificationId: 2, title: '新通知' }])
  t.stop()
})

test('退出后待完成的票据请求不能重新建立连接', async () => {
  let finish
  const t = transport(() => new Promise((resolve) => { finish = resolve }))
  t.stop(); finish('late-ticket'); await tick()
  assert.equal(t.sockets.length, 0); assert.equal(t.timers.size, 0)
})

test('临时失败退避重试，登录失效与账号禁用停止重连', async () => {
  const temporary = transport(async () => { throw { response: { status: 503 } } }); await tick()
  assert.equal(temporary.expired, 0); assert.equal(temporary.timers.size, 1); temporary.stop()
  for (const status of [401, 403]) {
    const failed = transport(async () => { throw { response: { status } } }); await tick()
    assert.equal(failed.expired, 1); assert.equal(failed.timers.size, 0)
  }
  for (const code of [4001, 4003]) {
    const connected = transport(); await tick(); connected.sockets[0].end(code)
    assert.equal(connected.expired, 1); assert.equal(connected.timers.size, 0)
  }
})

test('格式错误的消息不妨碍后续合法通知', async () => {
  const t = transport(); await tick()
  t.sockets[0].message('not-json')
  t.sockets[0].message({ type: 'notification.created', notificationId: -1, title: '无效' })
  t.sockets[0].message({ type: 'notification.created', notificationId: 8, title: '有效' })
  assert.equal(t.notices.length, 1); assert.equal(t.notices[0].notificationId, 8); t.stop()
})

test('第一页收到通知刷新，历史页保留位置并提示，退出后不再加载', () => {
  const script = parse(read('views/NotificationsView.vue')).descriptor.scriptSetup.content
  const ast = ts.createSourceFile('view.ts', script, ts.ScriptTarget.Latest, true)
  const fn = ast.statements.find((node) => ts.isFunctionDeclaration(node) && node.name.text === 'handleLiveUpdate')
  let loads = 0
  const state = { auth: { token: 'test' }, page: { value: 2 }, pendingLiveUpdate: { value: false }, load: () => { loads++ } }
  const context = vm.createContext(state)
  vm.runInContext(compile(fn.getText(ast)), context)
  context.handleLiveUpdate(); assert.equal(loads, 0); assert.equal(state.pendingLiveUpdate.value, true)
  state.page.value = 1; context.handleLiveUpdate(); assert.equal(loads, 1)
  state.auth.token = ''; context.handleLiveUpdate(); assert.equal(loads, 1)
})

test('重复实时事件只提示一次，旧未读响应不能覆盖新事件后的计数', async () => {
  const { createPinia, setActivePinia } = require('pinia'); setActivePinia(createPinia())
  const pending = []
  const context = { exports: {}, require: (id) => id === '@/api/notifications' ? {
    getUnreadNotificationCountApi: () => new Promise((resolve) => pending.push(resolve)),
    markNotificationReadApi: async () => {}, markAllNotificationsReadApi: async () => {},
  } : require(id) }
  vm.runInNewContext(compile(read('stores/notifications.ts')), context)
  const store = context.exports.useNotificationStore()
  const old = store.loadUnreadCount()
  assert.equal(store.acceptLive(10), true); assert.equal(store.acceptLive(10), false)
  const fresh = store.loadUnreadCount()
  pending[1]({ data: { data: 2 } }); await fresh
  pending[0]({ data: { data: 1 } }); await old
  assert.equal(store.unreadCount, 2)
  store.reset(); assert.equal(store.liveRevision, 0); assert.equal(store.acceptLive(10), true)
})


test('已读请求的晚响应不会重复扣减实时查询的新计数，也不会清掉新通知', async () => {
  const { createPinia, setActivePinia } = require('pinia')
  for (const all of [false, true]) {
    setActivePinia(createPinia())
    let finishRead
    const counters = []
    const context = { exports: {}, require: (id) => id === '@/api/notifications' ? {
      getUnreadNotificationCountApi: () => new Promise((resolve) => counters.push(resolve)),
      markNotificationReadApi: () => new Promise((resolve) => { finishRead = resolve }),
      markAllNotificationsReadApi: () => new Promise((resolve) => { finishRead = resolve }),
    } : require(id) }
    vm.runInNewContext(compile(read('stores/notifications.ts')), context)
    const store = context.exports.useNotificationStore()
    store.unreadCount = 3
    const mutation = all ? store.markAllAsRead() : store.markAsRead(1)
    if (all) store.acceptLive(99)
    const background = store.loadUnreadCount()
    const expected = all ? 1 : 2
    counters[0]({ data: { data: expected } }); await background
    finishRead(); await tick()
    assert.equal(counters.length, 2)
    counters[1]({ data: { data: expected } }); await mutation
    assert.equal(store.unreadCount, expected)
  }
})

function realtimeSession() {
  const { reactive, watch } = require('vue')
  const connections = [], popups = [], pushes = [], replacements = [], listeners = new Map()
  let unmount, logoutCount = 0
  const auth = reactive({ token: 'account-a', logout() { logoutCount++; this.token = '' } })
  const seen = new Set()
  const notifications = {
    unreadCount: 0, ready: 0,
    reset() { this.unreadCount = 0; seen.clear() },
    acceptLive(id) { if (seen.has(id)) return false; seen.add(id); return true },
    async loadUnreadCount() { this.unreadCount++ },
    async syncLive() { this.ready++ },
  }
  const context = { exports: {}, window: {
    addEventListener(name, fn) { listeners.set(name, fn) },
    removeEventListener(name, fn) { if (listeners.get(name) === fn) listeners.delete(name) },
  }, require(id) {
    if (id === 'vue') return { watch, onUnmounted: (fn) => { unmount = fn } }
    if (id === 'vue-router') return { useRouter: () => ({ push: (value) => pushes.push(value), replace: (value) => replacements.push(value) }) }
    if (id === 'element-plus') return { ElNotification: (options) => {
      const popup = { options, closed: false, close() { this.closed = true; options.onClose?.() } }
      popups.push(popup); return popup
    } }
    if (id === '@/api/notifications') return { getNotificationTicketApi: async () => ({ data: { data: { ticket: 'test' } } }) }
    if (id === '@/stores/auth') return { useAuthStore: () => auth }
    if (id === '@/stores/notifications') return { useNotificationStore: () => notifications }
    if (id === '@/utils/notificationConnection') return { connectNotifications: (callbacks) => {
      const connection = { callbacks, closed: false }; connections.push(connection)
      return () => { connection.closed = true }
    } }
    return require(id)
  } }
  vm.runInNewContext(compile(read('composables/useRealtimeNotifications.ts')), context)
  context.exports.useRealtimeNotifications()
  return { auth, notifications, connections, popups, pushes, replacements, listeners, unmount: () => unmount(),
    get logoutCount() { return logoutCount } }
}

test('退出立即清除旧弹窗和计数，晚消息与旧弹窗点击不再进入业务页', () => {
  const s = realtimeSession(), first = s.connections[0]
  first.callbacks.onNotification({ notificationId: 1, title: '旧账号任务' })
  first.callbacks.onNotification({ notificationId: 2, title: '另一条任务' })
  assert.equal(s.popups.length, 2); assert.equal(s.notifications.unreadCount, 2)
  s.auth.logout()
  assert.equal(first.closed, true); assert.equal(s.notifications.unreadCount, 0)
  assert.ok(s.popups.every((popup) => popup.closed))
  first.callbacks.onNotification({ notificationId: 3, title: '晚到的旧消息' })
  s.popups[0].options.onClick()
  assert.equal(s.popups.length, 2); assert.equal(s.pushes.length, 0)
  s.unmount()
})

test('切换账号后旧连接失效回调不能登出新账号，新账号仍可正常收通知', async () => {
  const s = realtimeSession(), old = s.connections[0]
  old.callbacks.onNotification({ notificationId: 1, title: '账号 A' })
  s.auth.token = 'account-b'
  assert.equal(old.closed, true); assert.equal(s.popups[0].closed, true)
  assert.equal(s.notifications.unreadCount, 0); assert.equal(s.connections.length, 2)
  old.callbacks.onExpired(); await old.callbacks.onReady()
  assert.equal(s.auth.token, 'account-b'); assert.equal(s.logoutCount, 0)
  assert.equal(s.replacements.length, 0); assert.equal(s.notifications.ready, 0)
  const fresh = s.connections[1]
  await fresh.callbacks.onReady()
  fresh.callbacks.onNotification({ notificationId: 1, title: '账号 B' })
  assert.equal(s.notifications.ready, 1); assert.equal(s.popups.length, 2)
  s.popups[1].options.onClick()
  assert.equal(s.popups[1].closed, true); assert.equal(s.pushes[0].path, '/notifications')
  s.unmount()
})

test('离开布局会清理弹窗、计数和监听，之后令牌变化不会重新连接', () => {
  const s = realtimeSession()
  s.connections[0].callbacks.onNotification({ notificationId: 1, title: '任务' })
  s.unmount()
  assert.equal(s.connections[0].closed, true); assert.equal(s.popups[0].closed, true)
  assert.equal(s.notifications.unreadCount, 0); assert.equal(s.listeners.size, 0)
  s.auth.token = 'account-b'
  assert.equal(s.connections.length, 1)
  s.connections[0].callbacks.onNotification({ notificationId: 2, title: '晚到消息' })
  assert.equal(s.popups.length, 1)
})

test('登录过期和其他标签页退出均清理当前提示并回到登录页', () => {
  for (const trigger of ['expired', 'storage']) {
    const s = realtimeSession()
    s.connections[0].callbacks.onNotification({ notificationId: 1, title: '任务' })
    if (trigger === 'expired') s.connections[0].callbacks.onExpired()
    else s.listeners.get('storage')({ key: 'flowdesk_token', newValue: null })
    assert.equal(s.auth.token, ''); assert.equal(s.popups[0].closed, true)
    assert.equal(s.notifications.unreadCount, 0); assert.equal(s.replacements[0], '/login')
    s.unmount()
  }
})

test('退出前发出的未读数和已读请求不能污染新会话', async () => {
  const { createPinia, setActivePinia } = require('pinia'); setActivePinia(createPinia())
  const counts = []; let finishRead
  const context = { exports: {}, require: (id) => id === '@/api/notifications' ? {
    getUnreadNotificationCountApi: () => new Promise((resolve) => counts.push(resolve)),
    markNotificationReadApi: () => new Promise((resolve) => { finishRead = resolve }),
    markAllNotificationsReadApi: async () => {},
  } : require(id) }
  vm.runInNewContext(compile(read('stores/notifications.ts')), context)
  const store = context.exports.useNotificationStore()
  const oldCount = store.loadUnreadCount(), oldRead = store.markAsRead(1)
  store.reset()
  const freshCount = store.loadUnreadCount()
  counts[1]({ data: { data: 3 } }); await freshCount
  counts[0]({ data: { data: 99 } }); await oldCount
  finishRead(); await oldRead
  assert.equal(store.unreadCount, 3); assert.equal(store.loading, false)
})
