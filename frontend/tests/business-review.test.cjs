const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const { parse } = require('@vue/compiler-sfc')

const source = (name) => fs.readFileSync(path.join(__dirname, '../src', name), 'utf8')
const view = (name) => parse(source(`views/${name}.vue`)).descriptor
const ref = (value) => ({ value })
const compile = (text) =>
  ts.transpileModule(text, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText
function runFunctions(name, names, state, prefix = '') {
  const script = view(name).scriptSetup.content
  const ast = ts.createSourceFile('view.ts', script, ts.ScriptTarget.Latest, true)
  const selected = ast.statements.filter((n) => ts.isFunctionDeclaration(n) && names.includes(n.name.text))
  assert.equal(selected.length, names.length)
  const context = vm.createContext(state)
  vm.runInContext(compile(prefix + '\n' + selected.map((n) => n.getText(ast)).join('\n')), context)
  return context
}
const deferred = () => {
  let resolve, reject
  const promise = new Promise((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}

for (const [name, field, api] of [
  ['ProjectListView', 'projects', 'getMyProjectsApi'],
  ['InvitationView', 'list', 'getInvitations'],
  ['AdminAcceptancesView', 'list', 'getAdminAcceptancesApi'],
]) {
  test(`${name}: failure clears stale records, persists an error, and can retry`, async () => {
    let failing = true
    const state = {
      loading: ref(false),
      error: ref(''),
      [field]: ref([{ id: 'old' }]),
      filter: ref('PENDING'),
      [api]: async () => {
        if (failing) throw new Error('offline')
        return { data: { data: [{ id: 'new' }] } }
      },
    }
    const context = runFunctions(name, ['load'], state, 'let loadRevision = 0;')
    await context.load()
    assert.equal(state[field].value.length, 0)
    assert.match(state.error.value, /加载失败/)
    assert.equal(state.loading.value, false)
    assert.match(view(name).template.content, /v-if="!loading && !error &&/)
    failing = false
    await context.load()
    assert.equal(state[field].value[0].id, 'new')
    assert.equal(state.error.value, '')
  })

  test(`${name}: earlier response cannot overwrite a newer refresh or filter`, async () => {
    const old = deferred()
    let calls = 0
    const state = {
      loading: ref(false),
      error: ref(''),
      [field]: ref([]),
      filter: ref('PENDING'),
      [api]: () => (++calls === 1 ? old.promise : Promise.resolve({ data: { data: [{ id: 'new' }] } })),
    }
    const context = runFunctions(name, ['load'], state, 'let loadRevision = 0;')
    const first = context.load()
    await context.load()
    old.resolve({ data: { data: [{ id: 'old' }] } })
    await first
    assert.equal(state[field].value[0].id, 'new')
    assert.equal(state.loading.value, false)
  })
}

test('activity failure, no-project empty state and rapid project changes stay distinct', async () => {
  const old = deferred()
  let calls = 0
  const state = {
    projectId: ref(5),
    projects: ref([{ id: 5 }]),
    logs: ref([{ id: 'stale' }]),
    loading: ref(false),
    error: ref(''),
    projectsError: ref(''),
    projectsLoading: ref(false),
    getProjectLogsApi: () => (++calls === 1 ? old.promise : Promise.resolve({ data: { data: [{ id: 'latest' }] } })),
    getMyProjectsApi: async () => {
      throw new Error('offline')
    },
  }
  const context = runFunctions('ActivityView', ['loadLogs', 'loadProjects'], state, 'let logRevision = 0;')
  const first = context.loadLogs()
  state.projectId.value = 6
  await context.loadLogs()
  old.reject(new Error('old'))
  await first
  assert.equal(state.logs.value[0].id, 'latest')
  assert.equal(state.error.value, '')
  await context.loadProjects()
  assert.match(state.projectsError.value, /加载失败/)
  assert.equal(state.projectId.value, undefined)
  state.projectsError.value = ''
  await context.loadLogs()
  assert.equal(state.logs.value.length, 0)
  assert.match(view('ActivityView').template.content, /还没有参与任何项目/)
  assert.match(view('ActivityView').template.content, /!error && !projectsError/)
})

function projectState() {
  const state = Object.fromEntries(
    ['project', 'members', 'acceptances', 'logs', 'requests', 'leaveRequests', 'pendingTransfers'].map((k) => [
      k,
      ref(k === 'project' ? { id: 'stale' } : []),
    ]),
  )
  Object.assign(
    state,
    Object.fromEntries(
      ['loading', 'acceptancesLoadFailed', 'logsLoadFailed', 'requestsLoadFailed', 'memberRequestsLoadFailed'].map((k) => [
        k,
        ref(false),
      ]),
    ),
  )
  return Object.assign(state, {
    id: ref('5'),
    loadError: ref(''),
    activeTab: ref('tasks'),
    currentRole: ref('PROJECT_MANAGER'),
    isManager: ref(true),
    loadTasks: async () => {},
    loadSummary: async () => {},
    getProjectApi: async () => ({ data: { data: { id: 5 } } }),
    getProjectMembersApi: async () => ({ data: { data: [{ userId: 1 }] } }),
    getProjectAcceptancesApi: async () => {
      throw new Error('offline')
    },
    getProjectLogsApi: async () => {
      throw new Error('offline')
    },
    getProjectTaskRequestsApi: async () => {
      throw new Error('offline')
    },
    leavesApi: async () => {
      throw new Error('offline')
    },
    pendingTransfersApi: async () => ({ data: { data: [] } }),
  })
}
test('project detail keeps failed secondary lists distinct from valid empty results', async () => {
  const state = projectState(),
    context = runFunctions('ProjectDetailView', ['load'], state, 'let loadRevision = 0;')
  await context.load()
  assert.equal(state.project.value.id, 5)
  assert.equal(state.loadError.value, '')
  for (const name of ['acceptancesLoadFailed', 'logsLoadFailed', 'requestsLoadFailed', 'memberRequestsLoadFailed'])
    assert.equal(state[name].value, true)
  for (const flag of ['acceptancesLoadFailed', 'logsLoadFailed', 'requestsLoadFailed', 'memberRequestsLoadFailed'])
    assert.ok(view('ProjectDetailView').template.content.includes('!' + flag))
})
test('forbidden project detail clears stale identity and displays server explanation', async () => {
  const state = projectState()
  state.getProjectApi = async () => {
    throw { response: { data: { message: '你不是该项目的有效成员' } } }
  }
  const context = runFunctions('ProjectDetailView', ['load'], state, 'let loadRevision = 0;')
  await context.load()
  assert.equal(state.project.value, undefined)
  assert.equal(state.members.value.length, 0)
  assert.equal(state.loadError.value, '你不是该项目的有效成员')
  assert.equal(state.loading.value, false)
  assert.match(view('ProjectDetailView').template.content, /<el-tabs v-if="project"/)
})
test('task detail forbidden access has persistent feedback and no stale task actions', async () => {
  const state = {
    loading: ref(false),
    loadError: ref(''),
    task: ref({ id: 'old' }),
    project: ref({ id: 'old' }),
    members: ref([]),
    id: ref('11'),
    getTaskApi: async () => {
      throw { response: { data: { message: '你不是该项目的有效成员' } } }
    },
  }
  const context = runFunctions('TaskDetailView', ['load'], state, 'let loadRevision = 0;')
  await context.load()
  assert.equal(state.task.value, undefined)
  assert.equal(state.project.value, undefined)
  assert.equal(state.loadError.value, '你不是该项目的有效成员')
  assert.equal(state.loading.value, false)
  assert.match(view('TaskDetailView').template.content, /v-if="loadError"/)
})

test('administrator default routing follows governance role and preserves ordinary-user restrictions', async () => {
  const ast = ts.createSourceFile('router.ts', source('router/index.ts'), ts.ScriptTarget.Latest, true)
  const registration = ast.statements.find(
    (n) => ts.isExpressionStatement(n) && n.expression.expression?.getText(ast) === 'router.beforeEach',
  )
  const auth = { token: 'test', isSystemAdmin: true, verify: async () => true }
  const context = vm.createContext({ useAuthStore: () => auth })
  vm.runInContext(compile('var guard = ' + registration.expression.arguments[0].getText(ast)), context)
  for (const to of [
    { name: 'dashboard', meta: { requiresAuth: true } },
    { name: 'login', meta: { guestOnly: true } },
    { name: 'tasks', meta: { ordinaryUser: true } },
  ])
    assert.equal((await context.guard(to)).name, 'admin-acceptances')
  assert.equal(await context.guard({ name: 'admin-acceptances', meta: { requiresAuth: true, systemAdmin: true } }), true)
  auth.isSystemAdmin = false
  assert.equal((await context.guard({ name: 'admin-acceptances', meta: { systemAdmin: true } })).name, 'dashboard')
  assert.equal(await context.guard({ name: 'dashboard', meta: { requiresAuth: true } }), true)
})
test('accepted invitation label is translated without changing CLOSED or EXPIRED meaning', () => {
  const context = { exports: {} }
  vm.runInNewContext(compile(source('utils/format.ts')), context)
  assert.equal(context.exports.statusLabel('ACCEPTED'), '已接受')
  assert.equal(context.exports.statusLabel('CLOSED'), '已关闭')
  assert.equal(context.exports.statusLabel('EXPIRED'), '已过期')
})
test('frozen and terminal project explanations retain the existing business distinctions', () => {
  const context = { exports: {} }
  vm.runInNewContext(compile(source('utils/format.ts')), context)
  const message = context.exports.projectRestrictionMessage
  assert.match(message('PENDING_ACCEPTANCE'), /驳回后.*仍有效/)
  assert.match(message('COMPLETED'), /负责人仍可归档/)
  assert.match(message('ARCHIVED'), /仅供查看/)
  assert.match(message('CANCELLED'), /不会.*自动取消全部任务/)
  assert.equal(message('IN_PROGRESS'), '')
  assert.equal(message('PREPARING'), '')
})
test('acceptance contribution scope explicitly includes historical members without claiming a snapshot', () => {
  const content = view('AdminAcceptanceDetailView').template.content
  assert.match(content, /成员贡献（含历史成员）/)
  assert.match(content, /包含已退出或被移除的历史成员/)
  assert.match(content, /并非本轮验收提交时的历史快照/)
})
test('historical acceptance notifications open the record rather than the pending-only list', () => {
  const context = runFunctions('NotificationsView', ['isManagerTransferNotification', 'categoryFor', 'actionFor'], {
    auth: { isSystemAdmin: true },
  })
  const action = context.actionFor({ type: 'PROJECT_ACCEPTANCE_SUBMITTED', targetId: 7, projectId: 5 })
  assert.equal(action.to, '/admin/acceptances/7')
  assert.equal(action.label, '查看验收')
  assert.equal(context.actionFor({ type: 'PROJECT_ACCEPTANCE_SUBMITTED' }).to, '/admin/acceptances')
})
test('historical task requests keep history when project lookup fails and do not infer membership', async () => {
  const state = {
    loading: ref(false),
    requestLoadFailed: ref(false),
    projectLoadFailed: ref(false),
    filter: ref('ALL'),
    requests: ref([]),
    projects: ref([]),
    getMyTaskRequestsApi: async () => ({ data: { data: [{ id: 6, taskId: 16402, projectId: 5 }] } }),
    getMyProjectsApi: async () => {
      throw new Error('offline')
    },
  }
  const context = runFunctions('TaskRequestsView', ['load'], state, 'let loadRevision = 0;')
  await context.load()
  assert.equal(state.requests.value.length, 1)
  assert.equal(state.requestLoadFailed.value, false)
  assert.equal(state.projectLoadFailed.value, true)
  const template = view('TaskRequestsView').template.content
  assert.match(template, /item.taskId && !projectLoadFailed && projectMap.has\(item.projectId\)/)
  assert.match(template, /当前已无项目访问权限，申请历史仍保留/)
  assert.match(template, /任务访问状态暂不可用，请刷新重试/)
})
test('task-request filters ignore older responses and failed panel counts remain unavailable', async () => {
  const old = deferred(),
    filters = []
  const state = {
    loading: ref(false),
    requestLoadFailed: ref(false),
    projectLoadFailed: ref(false),
    filter: ref('ALL'),
    requests: ref([]),
    projects: ref([]),
    getMyTaskRequestsApi: (status) => {
      filters.push(status)
      return filters.length === 1 ? old.promise : Promise.resolve({ data: { data: [{ id: 'latest' }] } })
    },
    getMyProjectsApi: async () => ({ data: { data: [] } }),
  }
  const context = runFunctions('TaskRequestsView', ['load'], state, 'let loadRevision = 0;')
  const first = context.load()
  state.filter.value = 'CLOSED'
  await context.load()
  old.resolve({ data: { data: [{ id: 'old' }] } })
  await first
  assert.deepEqual(filters, ['ALL', 'CLOSED'])
  assert.equal(state.requests.value[0].id, 'latest')
  assert.equal(state.loading.value, false)
  assert.match(view('TaskRequestsView').template.content, /requestLoadFailed \? '列表暂不可用'/)
  assert.match(view('TaskDetailView').template.content, /submissionsLoadFailed \? '—'/)
  assert.match(view('TaskDetailView').template.content, /commentsLoadFailed \? '—'/)
})
