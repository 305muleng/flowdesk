const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const { parse } = require('@vue/compiler-sfc')
const { computed, ref: vueRef } = require('vue')
const ref = (value) => ({ value })
const source = (name) => parse(fs.readFileSync(path.join(__dirname, `../src/views/${name}.vue`), 'utf8')).descriptor.scriptSetup.content
const compile = (text) => ts.transpileModule(text, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText
function editing(kind, api = async () => {}) {
  const project = kind === 'Project'
  const script = source(`${kind}DetailView`)
  const ast = ts.createSourceFile('view.ts', script, ts.ScriptTarget.Latest, true)
  const names = [`open${kind}Edit`, `save${kind}Edit`]
  const functions = ast.statements.filter((node) => ts.isFunctionDeclaration(node) && names.includes(node.name.text))
  assert.equal(functions.length, 2)
  const state = {
    id: ref('4'), warnings: [], successes: [], loads: 0,
    [`canEdit${kind}`]: ref(true), [`edit${kind}Visible`]: ref(false), [`edit${kind}Saving`]: ref(false),
    [`edit${kind}Form`]: ref({}),
    [project ? 'project' : 'task']: ref(project
      ? { name: '旧项目', description: '旧描述', goal: '旧目标', expectedEndTime: null }
      : { title: '旧任务', description: '旧描述', goal: '旧目标', deadline: '2099-01-01T12:00:00', priority: 'HIGH' }),
    [`update${kind}Api`]: api,
  }
  state.ElMessage = { warning: (message) => state.warnings.push(message), success: (message) => state.successes.push(message) }
  state.load = async () => { state.loads++ }
  const context = vm.createContext(state)
  vm.runInContext(compile(functions.map((node) => node.getText(ast)).join('\n')), context)
  return { state, open: context[names[0]], save: context[names[1]] }
}
for (const kind of ['Project', 'Task']) {
  test(`${kind}: editing uses a copy and successful save refreshes the current page`, async () => {
    const calls = []
    const { state, open, save } = editing(kind, async (id, body) => { calls.push({ id, body }) })
    open()
    const field = kind === 'Project' ? 'name' : 'title'
    const original = state[kind === 'Project' ? 'project' : 'task'].value[field]
    state[`edit${kind}Form`].value[field] = ' 新内容 '
    assert.equal(state[kind === 'Project' ? 'project' : 'task'].value[field], original)
    if (kind === 'Project') state.editProjectForm.value.expectedEndTime = null
    await save()
    assert.equal(calls.length, 1)
    assert.equal(calls[0].id, '4')
    assert.equal(calls[0].body[field], '新内容')
    assert.equal(state[`edit${kind}Visible`].value, false)
    assert.equal(state[`edit${kind}Saving`].value, false)
    assert.equal(state.loads, 1)
    assert.equal('assigneeId' in calls[0].body, false)
    assert.equal('status' in calls[0].body, false)
  })

  test(`${kind}: rejected save retains form contents for retry and releases saving state`, async () => {
    const { state, open, save } = editing(kind, async () => { throw new Error('conflict') })
    open()
    await assert.rejects(save(), /conflict/)
    assert.equal(state[`edit${kind}Visible`].value, true)
    assert.equal(state[`edit${kind}Saving`].value, false)
    assert.equal(state.loads, 0)
    assert.equal(state[`edit${kind}Form`].value.goal, '旧目标')
  })

  test(`${kind}: repeat clicks are ignored and late completion cannot refresh another record`, async () => {
    let resolve
    let calls = 0
    const pending = new Promise((done) => { resolve = done })
    const { state, open, save } = editing(kind, async () => { calls++; return pending })
    open()
    const first = save()
    await save()
    assert.equal(calls, 1)
    state.id.value = '5'
    resolve()
    await first
    assert.equal(state.loads, 0)
    assert.equal(state.successes.length, 0)
    assert.equal(state[`edit${kind}Saving`].value, false)
  })

  test(`${kind}: no permission prevents opening or submitting an edit`, async () => {
    let calls = 0
    const { state, open, save } = editing(kind, async () => { calls++ })
    state[`canEdit${kind}`].value = false
    open()
    await save()
    assert.equal(state[`edit${kind}Visible`].value, false)
    assert.equal(calls, 0)
  })
}

test('task edits reject whitespace-only required fields and invalid or expired deadlines', async () => {
  for (const field of ['title', 'description', 'goal', 'deadline']) {
    let calls = 0
    const { state, open, save } = editing('Task', async () => { calls++ })
    open()
    state.editTaskForm.value[field] = field === 'deadline' ? '' : '   '
    await save()
    assert.equal(calls, 0)
    assert.equal(state.warnings.length, 1)
  }
  for (const deadline of ['2000-01-01T00:00:00', 'invalid']) {
    const { state, open, save } = editing('Task')
    open()
    state.editTaskForm.value.deadline = deadline
    await save()
    assert.match(state.warnings[0], /晚于当前时间/)
    assert.equal(state.loads, 0)
  }
})

test('project name is required but optional fields can be cleared', async () => {
  let body
  const { state, open, save } = editing('Project', async (id, value) => { body = value })
  open()
  state.editProjectForm.value.name = '   '
  await save()
  assert.equal(body, undefined)
  state.editProjectForm.value = { name: '项目', description: null, goal: null, expectedEndTime: null }
  await save()
  assert.equal(body.description, null)
  assert.equal(body.expectedEndTime, null)
})

test('actual computed permissions restrict edit buttons to the approved lifecycle states', () => {
  for (const kind of ['Project', 'Task']) {
    const ast = ts.createSourceFile('view.ts', source(`${kind}DetailView`), ts.ScriptTarget.Latest, true)
    const declaration = ast.statements.filter(ts.isVariableStatement)
      .flatMap((statement) => [...statement.declarationList.declarations])
      .find((node) => node.name.getText(ast) === `canEdit${kind}`)
    const state = { computed, isManager: vueRef(true), projectAllowsPlanning: vueRef(true), project: vueRef({ status: 'PREPARING' }), task: vueRef({ status: 'TODO' }) }
    const context = vm.createContext(state)
    vm.runInContext(compile(`const ${declaration.getText(ast)}`), context)
    const allowed = () => vm.runInContext(`canEdit${kind}.value`, context)
    assert.equal(allowed(), true)
    state.isManager.value = false
    const read = allowed
    assert.equal(read(), false)
    state.isManager.value = true
    if (kind === 'Project') {
      for (const status of ['PENDING_ACCEPTANCE', 'COMPLETED', 'ARCHIVED', 'CANCELLED']) {
        state.project.value.status = status
        assert.equal(read(), false)
      }
      state.project.value.status = 'IN_PROGRESS'
      assert.equal(read(), true)
    } else {
      for (const status of ['IN_PROGRESS', 'REVIEW', 'DONE', 'CANCELLED']) {
        state.task.value.status = status
        assert.equal(read(), false)
      }
      state.task.value.status = 'TODO'
      state.projectAllowsPlanning.value = false
      assert.equal(read(), false)
    }
  }
})
