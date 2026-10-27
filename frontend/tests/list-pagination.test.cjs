const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse } = require('@vue/compiler-sfc')

const read = (file) => fs.readFileSync(path.join(__dirname, '../src', file), 'utf8')
const compile = (source) =>
  ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
const moduleContext = { exports: {}, require }
vm.runInNewContext(compile(read('composables/usePagedList.ts')), moduleContext)
const usePagedList = moduleContext.exports.usePagedList
function listing() {
  const scope = vue.effectScope()
  return { list: scope.run(() => usePagedList()), stop: () => scope.stop() }
}
const result = (page, total, records = [], size = 20) => ({ records, total, page, size, pages: Math.ceil(total / size) })
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
function script(file) {
  return parse(read(`views/${file}.vue`)).descriptor.scriptSetup.content
}
function functions(source, names, state) {
  const ast = ts.createSourceFile('view.ts', source, ts.ScriptTarget.Latest, true)
  const selected = ast.statements.filter((node) => ts.isFunctionDeclaration(node) && names.includes(node.name.text))
  assert.equal(selected.length, names.length)
  const context = vm.createContext(state)
  vm.runInContext(compile(selected.map((node) => node.getText(ast)).join('\n')), context)
  return context
}

test('paged list defaults and true total come from PageResult rather than records length', async () => {
  const { list, stop } = listing()
  assert.equal(list.page.value, 1)
  assert.equal(list.size.value, 20)
  await list.load(async (page, size) => {
    assert.equal(page, 1)
    assert.equal(size, 20)
    return result(1, 81, [{ id: 1 }])
  })
  assert.equal(list.total.value, 81)
  assert.equal(list.records.value.length, 1)
  assert.equal(list.loading.value, false)
  stop()
})

test('page emptied by processing falls back exactly once to the last valid page', async () => {
  const { list, stop } = listing()
  list.page.value = 4
  const calls = []
  await list.load(async (page) => {
    calls.push(page)
    return result(page, 41, page === 3 ? [{ id: 41 }] : [])
  })
  assert.deepEqual(calls, [4, 3])
  assert.equal(list.page.value, 3)
  assert.equal(list.records.value[0].id, 41)
  stop()
})

test('zero total resets page one without another request', async () => {
  const { list, stop } = listing()
  list.page.value = 8
  let calls = 0
  await list.load(async (page) => {
    calls++
    return result(page, 0)
  })
  assert.equal(calls, 1)
  assert.equal(list.page.value, 1)
  assert.equal(list.total.value, 0)
  stop()
})

test('continued shrinking cannot produce an infinite fallback loop', async () => {
  const { list, stop } = listing()
  list.page.value = 4
  const calls = []
  assert.equal(
    await list.load(async (page) => {
      calls.push(page)
      return result(page, calls.length === 1 ? 41 : 1)
    }),
    false,
  )
  assert.deepEqual(calls, [4, 3])
  assert.equal(list.page.value, 1)
  assert.equal(list.total.value, 1)
  assert.match(list.error.value, /请刷新/)
  stop()
})

test('newer filter results win over both old successful responses and old failures', async () => {
  for (const fail of [false, true]) {
    const { list, stop } = listing(),
      old = deferred()
    const first = list.load(() => old.promise)
    list.reset()
    await list.load(async () => result(1, 1, [{ id: 'new' }]))
    if (fail) old.reject(new Error('old'))
    else old.resolve(result(1, 80, [{ id: 'old' }]))
    await first
    assert.equal(list.records.value[0].id, 'new')
    assert.equal(list.total.value, 1)
    assert.equal(list.error.value, '')
    assert.equal(list.loading.value, false)
    stop()
  }
})

test('old fallback response and disposed view cannot overwrite state', async () => {
  const { list, stop } = listing(),
    fallback = deferred(),
    entered = deferred()
  list.page.value = 3
  const first = list.load(async (page) => {
    if (page === 3) return result(3, 1)
    entered.resolve()
    return fallback.promise
  })
  await entered.promise
  list.reset()
  await list.load(async () => result(1, 2, [{ id: 'latest' }]))
  fallback.resolve(result(1, 1, [{ id: 'fallback' }]))
  await first
  assert.equal(list.records.value[0].id, 'latest')
  const pending = deferred(),
    last = list.load(() => pending.promise)
  stop()
  pending.resolve(result(1, 3, [{ id: 'disposed' }]))
  await last
  assert.equal(list.records.value[0].id, 'latest')
})

test('notification read and read-all reload filtered pages and global unread count', async () => {
  const { list, stop } = listing(),
    calls = []
  const context = functions(script('NotificationsView'), ['load', 'markRead', 'markAllRead'], {
    listing: list,
    readFilter: { value: 'UNREAD' },
    categoryFilter: { value: 'TASK' },
    pendingLiveUpdate: { value: false },
    getNotificationsApi: async (params) => {
      calls.push(['list', params])
      return { data: { data: result(params.page, 0) } }
    },
    notificationStore: {
      markAsRead: async (id) => calls.push(['read', id]),
      markAllAsRead: async () => calls.push(['all']),
      loadUnreadCount: async () => calls.push(['count']),
    },
    readRequests: new Map(),
    processingIds: { value: new Set() },
    markingAll: { value: false },
    hasUnread: { value: true },
    ElMessage: { success() {} },
  })
  list.page.value = 5
  await context.markRead({ id: 9, readAt: null })
  assert.equal(list.page.value, 1)
  assert.equal(calls[1][1].readFilter, 'UNREAD')
  assert.equal(calls[1][1].category, 'TASK')
  assert.deepEqual(
    calls.map((c) => c[0]),
    ['read', 'list', 'count'],
  )
  calls.length = 0
  await context.markAllRead()
  assert.deepEqual(
    calls.map((c) => c[0]),
    ['all', 'list', 'count'],
  )
  assert.equal(context.markingAll.value, false)
  stop()
})

test('member task locator sends server filters and each view resets page on filter or size change', async () => {
  const source = script('ProjectDetailView'),
    { list, stop } = listing(),
    calls = []
  const context = functions(source, ['loadTasks'], {
    taskListing: list,
    id: { value: '7' },
    focusedAssignee: { value: 13 },
    getProjectTasksApi: async (id, params) => {
      calls.push({ id, params })
      return { data: { data: result(1, 0) } }
    },
  })
  await context.loadTasks()
  assert.equal(calls[0].id, '7')
  assert.equal(calls[0].params.assigneeId, 13)
  assert.equal(calls[0].params.unfinishedOnly, true)
  assert.equal(calls[0].params.page, 1)
  list.page.value = 3
  const clicked = functions(source, ['viewMemberTasks'], {
    activeTab: { value: 'members' },
    taskListing: list,
    focusedAssignee: { value: 13 },
    loadTasks: () => context.loadTasks(),
    route: { query: {} },
    router: { replace() {} },
  })
  clicked.viewMemberTasks({ userId: 13 })
  await Promise.resolve()
  assert.equal(list.page.value, 1)
  assert.equal(calls[1].params.page, 1)
  assert.match(source, /watch\(focusedAssignee,[\s\S]*?taskListing.reset\(\)/)
  assert.doesNotMatch(source, /visibleTasks|tasks.value.filter/)
  for (const [view, filters] of [
    ['MyTasksView', 'filter, keyword, projectScope'],
    ['NotificationsView', 'readFilter, categoryFilter'],
  ]) {
    const text = script(view)
    const watchPattern = new RegExp(
      `watch\\(\\s*\\[${filters.replaceAll(', ', ',\\s*')}\\],\\s*\\(\\)\\s*=>\\s*\\{\\s*listing.reset\\(\\)`,
    )
    assert.match(text, watchPattern)
    assert.match(text, /function changeSize[\s\S]*?listing.reset\(\)/)
  }
  assert.match(script('MyTasksView'), /getMyTasksApi\(\{\s*page,\s*size,\s*status,\s*keyword: title,\s*projectScope: scope,?\s*\}/)
  stop()
})

test('global unread count ignores old responses and reset clears in-flight loading', async () => {
  const { createPinia, setActivePinia } = require('pinia')
  setActivePinia(createPinia())
  const requests = []
  const context = {
    exports: {},
    require: (id) =>
      id === '@/api/notifications'
        ? {
            getUnreadNotificationCountApi: () => {
              const pending = deferred()
              requests.push(pending)
              return pending.promise
            },
            markNotificationReadApi: async () => {},
            markAllNotificationsReadApi: async () => {},
          }
        : require(id),
  }
  vm.runInNewContext(compile(read('stores/notifications.ts')), context)
  const store = context.exports.useNotificationStore()
  const first = store.loadUnreadCount(),
    second = store.loadUnreadCount()
  requests[1].resolve({ data: { data: 6 } })
  await second
  requests[0].resolve({ data: { data: 99 } })
  await first
  assert.equal(store.unreadCount, 6)
  assert.equal(store.loading, false)
  const pending = store.loadUnreadCount()
  assert.equal(store.loading, true)
  store.reset()
  assert.equal(store.loading, false)
  requests[2].resolve({ data: { data: 88 } })
  await pending
  assert.equal(store.unreadCount, 0)
  assert.equal(store.loading, false)
})

test('dashboard/profile totals and project progress use full summaries and callers read records', () => {
  const dashboard = read('views/DashboardView.vue'),
    profile = read('views/ProfileView.vue'),
    project = read('views/ProjectDetailView.vue')
  for (const source of [dashboard, profile]) {
    assert.match(source, /getMyTaskSummaryApi\(\)/)
    assert.match(source, /taskResponse.data.data.records|taskResult.value.data.data.records/)
    assert.doesNotMatch(source, /:value="tasks.length"|:value="tasks.filter/)
    assert.match(source, /统计暂不可用/)
  }
  assert.match(project, /taskSummary.value\?\.completionRate \?\? null/)
  assert.match(project, /getProjectTaskSummaryApi\(projectId\)/)
  assert.doesNotMatch(dashboard, /getProjectTasksApi/)
  assert.match(dashboard, /进行中项目的待处理任务/)
  for (const file of ['api/projects.ts', 'api/tasks.ts', 'api/notifications.ts']) assert.match(read(file), /PageResult</)
})
