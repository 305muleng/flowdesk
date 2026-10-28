const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const { parse } = require('@vue/compiler-sfc')

function contextFor(file, names, state) {
  const source = fs.readFileSync(path.join(__dirname, '../src/views', file + '.vue'), 'utf8')
  const ast = ts.createSourceFile('view.ts', parse(source).descriptor.scriptSetup.content, ts.ScriptTarget.Latest, true)
  const selected = ast.statements.filter((node) => ts.isFunctionDeclaration(node) && names.includes(node.name.text))
  assert.equal(selected.length, names.length)
  const code = ts.transpileModule('let loadRevision = 0;\n' + selected.map((node) => node.getText(ast)).join('\n'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022 },
  }).outputText
  const context = vm.createContext(state)
  vm.runInContext(code, context)
  return context
}
const ref = (value) => ({ value })
const response = (data) => ({ data: { data } })
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
function profilePage(overrides = {}) {
  const state = {
    profile: ref(undefined),
    projects: ref([]),
    submittableTasks: ref([]),
    taskSummary: ref(null),
    projectRoles: ref(new Map()),
    loading: ref(false),
    loadFailed: ref(false),
    projectsLoadFailed: ref(false),
    tasksLoadFailed: ref(false),
    getCurrentUserApi: async () => response({ id: 9, systemRole: 'USER', username: 'tester' }),
    getMyProjectsApi: async () => response([{ id: 1, name: '项目' }]),
    getMyTasksApi: async () => response({ records: [{ id: 8 }] }),
    getMyTaskSummaryApi: async () => response({ actionableTaskCount: 2 }),
    getProjectMembersApi: async () => response([{ userId: 9, role: 'PROJECT_MANAGER' }]),
    ...overrides,
  }
  return { state, run: contextFor('ProfileView', ['load'], state).load }
}

test('个人资料不被项目列表失败阻断，任务仍可独立显示', async () => {
  const { state, run } = profilePage({
    getMyProjectsApi: async () => {
      throw new Error('offline')
    },
  })
  await run()
  assert.equal(state.profile.value?.id, 9)
  assert.equal(state.loadFailed.value, false)
  assert.equal(state.projectsLoadFailed.value, true)
  assert.equal(state.tasksLoadFailed.value, false)
  assert.equal(state.submittableTasks.value[0].id, 8)
})

test('任务接口失败不清除基本资料、项目角色或全量统计', async () => {
  const { state, run } = profilePage({
    getMyTasksApi: async () => {
      throw new Error('offline')
    },
  })
  await run()
  assert.equal(state.profile.value?.id, 9)
  assert.equal(state.loadFailed.value, false)
  assert.equal(state.tasksLoadFailed.value, true)
  assert.equal(state.projects.value.length, 1)
  assert.equal(state.projectRoles.value.get(1), 'PROJECT_MANAGER')
  assert.equal(state.taskSummary.value.actionableTaskCount, 2)
})

test('管理员资料页只读取个人资料，不发送普通项目和任务请求', async () => {
  const calls = []
  const unexpected = async () => {
    calls.push('unexpected')
    throw new Error('不应查询普通业务')
  }
  const { state, run } = profilePage({
    getCurrentUserApi: async () => response({ id: 1, systemRole: 'SYSTEM_ADMIN' }),
    getMyProjectsApi: unexpected,
    getMyTasksApi: unexpected,
    getMyTaskSummaryApi: unexpected,
  })
  await run()
  assert.deepEqual(calls, [])
  assert.equal(state.profile.value.systemRole, 'SYSTEM_ADMIN')
  assert.equal(state.loadFailed.value, false)
})

test('待提交任务的筛选和前五条分页一起交给后端，保留截止时间顺序', async () => {
  let params
  const { state, run } = profilePage({
    getMyTasksApi: async (value) => {
      params = value
      return response({
        records: [
          { id: 1, updatedAt: '2026-01-01' },
          { id: 2, updatedAt: '2026-10-01' },
        ],
      })
    },
  })
  await run()
  assert.deepEqual(JSON.parse(JSON.stringify(params)), { page: 1, size: 5, projectScope: 'ACTIONABLE', status: 'IN_PROGRESS' })
  assert.deepEqual(
    Array.from(state.submittableTasks.value, (task) => task.id),
    [1, 2],
  )
})

test('刷新个人资料时旧响应不能覆盖新用户结果', async () => {
  const old = deferred()
  let count = 0
  const { state, run } = profilePage({
    getCurrentUserApi: () => (++count === 1 ? old.promise : Promise.resolve(response({ id: 2, systemRole: 'SYSTEM_ADMIN' }))),
  })
  const pending = run()
  await run()
  old.resolve(response({ id: 1, systemRole: 'USER' }))
  await pending
  assert.equal(state.profile.value.id, 2)
  assert.equal(state.loading.value, false)
})

function adminPage(api) {
  const state = {
    users: ref([]),
    loading: ref(false),
    error: ref(''),
    total: ref(0),
    pages: ref(0),
    processingIds: ref(new Set()),
    query: { page: 1, size: 10, keyword: '', status: '', systemRole: '' },
    getAdminUsersApi: api,
  }
  return { state, run: contextFor('AdminUsersView', ['requestParams', 'load'], state).load }
}
const page = (name, current = 1, total = 1) =>
  response({ records: [{ username: name }], total, page: current, size: 10, pages: Math.ceil(total / 10) })

test('用户筛选的新结果不会被先发后到的旧结果覆盖', async () => {
  const old = deferred()
  const fresh = deferred()
  const { state, run } = adminPage((params) => (params.keyword === 'old' ? old.promise : fresh.promise))
  state.query.keyword = 'old'
  const pending = run()
  state.query.keyword = 'new'
  const latest = run()
  fresh.resolve(page('new'))
  await latest
  old.resolve(page('old'))
  await pending
  assert.equal(state.users.value[0].username, 'new')
  assert.equal(state.error.value, '')
})

test('旧用户请求失败不会清除新结果或提前结束新请求的加载状态', async () => {
  const old = deferred()
  const fresh = deferred()
  const { state, run } = adminPage((params) => (params.keyword === 'old' ? old.promise : fresh.promise))
  state.query.keyword = 'old'
  const pending = run()
  state.query.keyword = 'new'
  const latest = run()
  old.reject(new Error('old failed'))
  await pending
  assert.equal(state.loading.value, true)
  assert.equal(state.error.value, '')
  fresh.resolve(page('new'))
  await latest
  assert.equal(state.users.value[0].username, 'new')
})

test('最新用户请求失败清除旧列表，避免在新筛选下操作旧记录', async () => {
  const { state, run } = adminPage(async () => {
    throw new Error('offline')
  })
  state.users.value = [{ username: 'old' }]
  state.total.value = 100
  await run()
  assert.equal(state.users.value.length, 0)
  assert.equal(state.total.value, 0)
  assert.notEqual(state.error.value, '')
})

test('取消用户状态确认不发送请求，也不抛出未处理的取消异常', async () => {
  for (const dismissal of ['cancel', 'close']) {
    let writes = 0
    const state = {
      processingIds: ref(new Set()),
      ElMessageBox: {
        confirm: async () => {
          throw dismissal
        },
      },
      disableAdminUserApi: async () => {
        writes++
      },
      enableAdminUserApi: async () => {
        writes++
      },
      ElMessage: { success: () => {} },
      load: async () => {},
    }
    const context = contextFor('AdminUsersView', ['changeStatus'], state)
    await context.changeStatus({ id: 5, username: 'tester', status: 'ACTIVE' })
    assert.equal(writes, 0)
    assert.equal(state.processingIds.value.size, 0)
  }
})
