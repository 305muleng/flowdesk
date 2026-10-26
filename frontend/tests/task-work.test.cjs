const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const { parse } = require('@vue/compiler-sfc')
const source = (file) => fs.readFileSync(path.join(__dirname, '../src', file), 'utf8')
const compile = (text) => ts.transpileModule(text, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
const moduleObject = { exports: {} }
vm.runInNewContext(compile(source('utils/taskWork.ts')), { exports: moduleObject.exports })
const { isActionableTask, taskWorkLabel } = moduleObject.exports

test('项目状态和任务状态共同决定待办，历史与等待审核不会被催办', () => {
  for (const projectStatus of ['PREPARING', 'IN_PROGRESS', 'PENDING_ACCEPTANCE', 'COMPLETED', 'ARCHIVED', 'CANCELLED']) {
    for (const status of ['TODO', 'IN_PROGRESS', 'REVIEW', 'DONE', 'CANCELLED']) {
      assert.equal(isActionableTask({ projectStatus, status }), projectStatus === 'IN_PROGRESS' && ['TODO', 'IN_PROGRESS'].includes(status))
    }
  }
  assert.equal(taskWorkLabel({ projectStatus: 'PREPARING', status: 'TODO' }), '计划中的工作')
  assert.equal(taskWorkLabel({ projectStatus: 'IN_PROGRESS', status: 'REVIEW' }), '等待审核')
  assert.equal(taskWorkLabel({ projectStatus: 'CANCELLED', status: 'TODO' }), '历史任务')
})

function deadlineEditor(api = async () => {}) {
  const script = parse(source('views/TaskDetailView.vue')).descriptor.scriptSetup.content
  const ast = ts.createSourceFile('view.ts', script, ts.ScriptTarget.Latest, true)
  const functions = ast.statements.filter((node) => ts.isFunctionDeclaration(node) && ['openDeadlineEdit', 'saveDeadline'].includes(node.name.text))
  const ref = (value) => ({ value })
  const state = {
    id: ref('1'), task: ref({ deadline: '2099-01-01T12:00:00' }), canAdjustDeadline: ref(true), actionBusy: ref(false),
    deadlineVisible: ref(false), deadlineSaving: ref(false), deadlineForm: ref({}), loads: 0, warnings: [], successes: [],
    adjustTaskDeadlineApi: api,
  }
  state.ElMessage = { warning: (text) => state.warnings.push(text), success: (text) => state.successes.push(text) }
  state.load = async () => { state.loads++ }
  const context = vm.createContext(state)
  vm.runInContext(compile(functions.map((node) => node.getText(ast)).join('\n')), context)
  context.openDeadlineEdit()
  return { state, save: context.saveDeadline }
}

test('调整时间携带页面原值和原因，提交后刷新，不修改任务目标或状态', async () => {
  const calls = []
  const { state, save } = deadlineEditor(async (id, payload) => calls.push({ id, payload: { ...payload } }))
  state.deadlineForm.value.deadline = '2099-01-02T12:00:00'
  state.deadlineForm.value.reason = '  交付计划变化  '
  await save()
  assert.deepEqual(calls, [{ id: '1', payload: { expectedDeadline: '2099-01-01T12:00:00', deadline: '2099-01-02T12:00:00', reason: '交付计划变化' } }])
  assert.equal(state.loads, 1)
  assert.equal(state.deadlineVisible.value, false)
  assert.equal(state.actionBusy.value, false)
})

test('无原因、无效日期、过去时间或原时间不会发送调整请求', async () => {
  for (const [deadline, reason] of [['2099-01-02T12:00:00', '  '], ['bad', '原因'], ['2000-01-01T12:00:00', '原因'], ['2099-01-01T12:00:00', '原因']]) {
    let calls = 0
    const { state, save } = deadlineEditor(async () => calls++)
    Object.assign(state.deadlineForm.value, { deadline, reason })
    await save()
    assert.equal(calls, 0)
    assert.equal(state.warnings.length, 1)
  }
})

test('状态冲突后重新加载，普通失败保留输入', async () => {
  for (const status of [409, 500]) {
    const error = { response: { status } }
    const { state, save } = deadlineEditor(async () => { throw error })
    Object.assign(state.deadlineForm.value, { deadline: '2099-01-02T12:00:00', reason: '原因' })
    await assert.rejects(save(), (received) => received === error)
    assert.equal(state.loads, status === 409 ? 1 : 0)
    assert.equal(state.deadlineVisible.value, status !== 409)
    assert.equal(state.deadlineForm.value.reason, '原因')
    assert.equal(state.actionBusy.value, false)
  }
})

test('重复提交被阻止，切换任务后旧响应不会刷新新页面', async () => {
  let finish, calls = 0
  const pending = new Promise((resolve) => { finish = resolve })
  const { state, save } = deadlineEditor(async () => { calls++; await pending })
  Object.assign(state.deadlineForm.value, { deadline: '2099-01-02T12:00:00', reason: '原因' })
  const first = save()
  await save()
  assert.equal(calls, 1)
  state.id.value = '2'
  finish()
  await first
  assert.equal(state.loads, 0)
  assert.equal(state.successes.length, 0)
})
