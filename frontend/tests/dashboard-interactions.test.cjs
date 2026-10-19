const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const { parse } = require('@vue/compiler-sfc')

function script(relativePath) {
  const source = fs.readFileSync(path.join(__dirname, '../src', relativePath), 'utf8')
  return parse(source).descriptor.scriptSetup.content
}
function compiled(text) {
  return ts.transpileModule(text, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText
}

const dashboardScript = script('views/DashboardView.vue')
const dashboardAst = ts.createSourceFile('dashboard.ts', dashboardScript, ts.ScriptTarget.Latest, true)
const loadFunction = dashboardAst.statements.find((node) => ts.isFunctionDeclaration(node) && node.name.text === 'load')
async function runDashboardLoad({ failProjects = false, failTasks = false, failOneProgress = false } = {}) {
  const state = {
    projects: { value: [] },
    tasks: { value: [] },
    myTaskSummary: { value: null },
    activities: { value: [] },
    loading: { value: false },
    projectLoadFailed: { value: false },
    taskLoadFailed: { value: false },
    projectProgress: { value: {} },
  }
  const projects = [
    { id: 1, name: 'One' },
    { id: 2, name: 'Two' },
    { id: 3, name: 'Three' },
    { id: 4, name: 'Four' },
    { id: 5, name: 'Five' },
    { id: 6, name: 'Six' },
  ]
  const queried = []
  const context = vm.createContext({
    ...state,
    recentProjects: {
      get value() {
        return state.projects.value.slice(0, 5)
      },
    },
    getMyProjectsApi: () =>
      failProjects ? Promise.reject(new Error('projects failed')) : Promise.resolve({ data: { data: projects } }),
    getMyTasksApi: () =>
      failTasks
        ? Promise.reject(new Error('tasks failed'))
        : Promise.resolve({ data: { data: { records: [{ status: 'TODO' }], total: 55, page: 1, size: 20, pages: 3 } } }),
    getMyTaskSummaryApi: () =>
      Promise.resolve({ data: { data: { totalTaskCount: 55, unfinishedTaskCount: 30, doneTaskCount: 20, todoTaskCount: 10 } } }),
    getProjectTaskSummaryApi: (id) => {
      queried.push(id)
      return failOneProgress && id === 2
        ? Promise.reject(new Error('project tasks failed'))
        : Promise.resolve({ data: { data: { completionRate: 50 } } })
    },
    getProjectLogsApi: () => Promise.resolve({ data: { data: [] } }),
  })
  vm.runInContext(compiled(`let loadRevision = 0;\n${loadFunction.getText(dashboardAst)}`), context)
  await vm.runInContext('load()', context)
  return { state, queried }
}

test('dashboard keeps project data when personal tasks fail and isolates one progress failure', async () => {
  const { state, queried } = await runDashboardLoad({ failTasks: true, failOneProgress: true })
  assert.equal(state.taskLoadFailed.value, true)
  assert.equal(state.projectLoadFailed.value, false)
  assert.equal(state.projects.value.length, 6)
  assert.deepEqual(queried, [1, 2, 3, 4, 5])
  assert.equal(state.projectProgress.value[1], 50)
  assert.equal(state.projectProgress.value[2], null)
  assert.equal(state.projectProgress.value[3], 50)
  assert.equal(state.loading.value, false)
})

test('dashboard keeps task data when project list fails', async () => {
  const { state, queried } = await runDashboardLoad({ failProjects: true })
  assert.equal(state.projectLoadFailed.value, true)
  assert.equal(state.taskLoadFailed.value, false)
  assert.equal(state.tasks.value.length, 1)
  assert.equal(state.myTaskSummary.value.totalTaskCount, 55)
  assert.equal(state.myTaskSummary.value.unfinishedTaskCount, 30)
  assert.deepEqual(queried, [])
})

test('project progress and personal totals use full summaries, not paged tasks', () => {
  assert.ok(dashboardScript.includes('getProjectTaskSummaryApi(project.id)'))
  assert.ok(!dashboardScript.includes('getProjectTasksApi('))
  const dashboardTemplate = fs.readFileSync(path.join(__dirname, '../src/views/DashboardView.vue'), 'utf8')
  assert.ok(dashboardTemplate.includes('myTaskSummary?.unfinishedTaskCount'))
  assert.ok(dashboardTemplate.includes('myTaskSummary?.doneTaskCount'))
  assert.ok(dashboardScript.includes('?? null'))
})

const layoutScript = script('layouts/AppLayout.vue')
const layoutAst = ts.createSourceFile('layout.ts', layoutScript, ts.ScriptTarget.Latest, true)
const activeMenuDeclaration = layoutAst.statements
  .filter(ts.isVariableStatement)
  .flatMap((statement) => statement.declarationList.declarations)
  .find((declaration) => declaration.name.getText(layoutAst) === 'activeMenu')
const menuContext = vm.createContext({
  route: { path: '/dashboard' },
  computed: (fn) => ({
    get value() {
      return fn()
    },
  }),
})
vm.runInContext(compiled(`const activeMenu = ${activeMenuDeclaration.initializer.getText(layoutAst)}`), menuContext)

test('sidebar selects the parent menu for project and task detail routes', () => {
  for (const [path, expected] of [
    ['/projects', '/projects'],
    ['/projects/4', '/projects'],
    ['/tasks', '/tasks'],
    ['/tasks/9', '/tasks'],
    ['/task-requests', '/task-requests'],
    ['/invitations', '/invitations'],
    ['/activity', '/activity'],
    ['/dashboard', '/dashboard'],
    ['/admin/users', '/admin/users'],
    ['/admin/acceptances', '/admin/acceptances'],
    ['/membership/4/leave-requests/20', ''],
  ]) {
    menuContext.route.path = path
    assert.equal(vm.runInContext('activeMenu.value', menuContext), expected, path)
  }
})
