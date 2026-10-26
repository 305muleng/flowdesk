const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const vm = require('node:vm')
const ts = require('typescript')
const { parse } = require('@vue/compiler-sfc')
const path = require('node:path')

const source = fs.readFileSync(path.join(__dirname, '../src/views/MembershipDetailView.vue'), 'utf8')
const ast = ts.createSourceFile('detail.ts', parse(source).descriptor.scriptSetup.content, ts.ScriptTarget.Latest, true)
const functions = ast.statements
  .filter((node) => ts.isFunctionDeclaration(node) && ['load', 'act'].includes(node.name.text))
  .map((node) => node.getText(ast)).join('\n')
const executable = ts.transpileModule('let loadRevision = 0;\n' + functions,
  { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText

function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
function setup() {
  const route = { id: '10', kind: 'leave' }
  const requests = []
  const calls = []
  const dialog = deferred()
  const operation = deferred()
  const refs = { leave: { value: undefined }, transfer: { value: undefined }, error: { value: '' }, busy: { value: false } }
  function request(kind, project, id) {
    const pending = deferred()
    requests.push({ ...pending, kind, project, id })
    return pending.promise
  }
  const context = vm.createContext({
    ...refs, projectId: { value: '4' }, recordId: { get value() { return route.id } },
    isLeave: { get value() { return route.kind === 'leave' } },
    leaveDetailApi: (project, id) => request('leave', project, id),
    transferDetailApi: (project, id) => request('transfer', project, id),
    leaveActionApi: (...args) => { calls.push(args); return operation.promise },
    transferActionApi: (...args) => { calls.push(args); return operation.promise },
    ElMessage: { success() {} },
    ElMessageBox: { prompt: () => dialog.promise, confirm: () => dialog.promise },
  })
  vm.runInContext(executable, context)
  return { route, requests, calls, dialog, operation, refs, run: (code) => vm.runInContext(code, context) }
}
const response = (id) => ({ data: { data: { id } } })

test('切换记录后，旧响应不能覆盖当前详情', async () => {
  const s = setup()
  const first = s.run('load()')
  s.route.id = '20'
  const second = s.run('load()')
  s.requests[1].resolve(response(20)); await second
  s.requests[0].resolve(response(10)); await first
  assert.equal(s.refs.leave.value.id, 20)
})
test('旧请求失败不能覆盖新请求的状态', async () => {
  const s = setup()
  const first = s.run('load()')
  s.route.id = '20'
  const second = s.run('load()')
  s.requests[0].reject(new Error('旧请求失败')); await first
  assert.equal(s.refs.busy.value, true)
  assert.equal(s.refs.error.value, '')
  s.requests[1].resolve(response(20)); await second
  assert.equal(s.refs.busy.value, false)
})
test('退出申请切换为转让记录时，只显示新类型的详情', async () => {
  const s = setup()
  const first = s.run('load()')
  s.route.kind = 'transfer'; s.route.id = '20'
  const second = s.run('load()')
  s.requests[1].resolve(response(20)); await second
  s.requests[0].resolve(response(10)); await first
  assert.equal(s.refs.leave.value, undefined)
  assert.equal(s.refs.transfer.value.id, 20)
})
test('相同记录刷新时，也只接收最新请求', async () => {
  const s = setup()
  const first = s.run('load()'), second = s.run('load()')
  s.requests[1].resolve(response(22)); await second
  s.requests[0].resolve(response(11)); await first
  assert.equal(s.refs.leave.value.id, 22)
})
test('确认弹窗期间切换记录，不得向新记录发送旧操作', async () => {
  const s = setup()
  const action = s.run("act('APPROVE')")
  s.route.id = '20'
  const loading = s.run('load()')
  s.dialog.resolve({ value: '同意' }); await action
  assert.equal(s.calls.length, 0)
  s.requests[0].resolve(response(20)); await loading
})
test('旧操作完成不能解除另一个类型详情页的加载状态', async () => {
  const s = setup()
  const action = s.run("act('APPROVE')")
  s.dialog.resolve({ value: '同意' })
  await Promise.resolve()
  s.route.kind = 'transfer'
  const loading = s.run('load()')
  s.operation.resolve(); await action
  assert.equal(s.refs.busy.value, true)
  s.requests[0].resolve(response(10)); await loading
  assert.equal(s.refs.busy.value, false)
})
