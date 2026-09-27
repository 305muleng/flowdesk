const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const { parse } = require('@vue/compiler-sfc')
const { baseParse } = require('@vue/compiler-dom')

const source = (relative) => fs.readFileSync(path.join(__dirname, '../src', relative), 'utf8')

// Execute the view's actual pure notification functions without mounting its API-backed page.
const notificationScript = parse(source('views/NotificationsView.vue')).descriptor.scriptSetup.content
const ast = ts.createSourceFile('notifications.ts', notificationScript, ts.ScriptTarget.Latest, true)
const names = ['isManagerTransferNotification', 'categoryFor', 'actionFor']
const functions = ast.statements
  .filter((node) => ts.isFunctionDeclaration(node) && names.includes(node.name.text))
  .map((node) => node.getText(ast)).join('\n')
const context = vm.createContext({ auth: { isSystemAdmin: false } })
vm.runInContext(ts.transpileModule(functions, {
  compilerOptions: { target: ts.ScriptTarget.ES2022 },
}).outputText, context)

test('initial and subsequent manager transfer notifications classify and link to independent detail', () => {
  for (const type of ['PROJECT_MANAGER_TRANSFER', 'PROJECT_MANAGER_TRANSFER_ACCEPTED', 'PROJECT_MANAGER_TRANSFER_REJECTED', 'PROJECT_MANAGER_TRANSFER_CANCELLED']) {
    assert.equal(context.categoryFor(type), 'PROJECT_MANAGER_TRANSFER')
    for (const readAt of [null, '2026-09-27T12:00:00']) {
      const action = context.actionFor({ type, projectId: 4, targetId: 20, readAt })
      assert.equal(action.label, '查看详情')
      assert.equal(action.to, '/membership/4/manager-transfers/20')
    }
  }
})

test('member removal remains a historical notification without project navigation', () => {
  assert.equal(context.categoryFor('PROJECT_MEMBER_REMOVED'), 'PROJECT_MEMBER_REMOVED')
  assert.equal(context.actionFor({ type: 'PROJECT_MEMBER_REMOVED', projectId: 4, targetId: 4, targetType: 'PROJECT' }), undefined)
})

test('leave notifications retain independent detail and transfer links require record identifiers', () => {
  assert.equal(context.actionFor({ type: 'MEMBER_LEAVE_REQUEST_APPROVED', projectId: 4, targetId: 40 }).to, '/membership/4/leave-requests/40')
  assert.equal(context.actionFor({ type: 'PROJECT_MANAGER_TRANSFER', projectId: 4 }), undefined)
  assert.equal(context.isManagerTransferNotification('PROJECT_MANAGER_TRANSFERRING'), false)
})

function elements(node) {
  return [node, ...(node.children || []).flatMap(elements)]
}
const template = baseParse(parse(source('views/TaskDetailView.vue')).descriptor.template.content)
const allElements = elements(template).filter((node) => node.type === 1)
const dialog = allElements.find((node) => node.tag === 'el-dialog' && node.props.some((prop) => prop.name === 'title' && prop.value?.content === '分配任务负责人'))
const select = elements(dialog).find((node) => node.tag === 'el-select')
const option = elements(select).find((node) => node.tag === 'el-option')
const loop = option.props.find((prop) => prop.name === 'for').exp.content
const label = option.props.find((prop) => prop.name === 'bind' && prop.arg.content === 'label').exp.content
const formatContext = vm.createContext({ exports: {} })
vm.runInContext(ts.transpileModule(source('utils/format.ts'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
}).outputText, formatContext)

test('task assignee options include effective managers and developers with role and workload', () => {
  assert.equal(select.props.find((prop) => prop.name === 'placeholder').value.content, '选择项目成员')
  const members = [
    { realName: 'Tom', role: 'PROJECT_MANAGER', effective: true, unfinishedTaskCount: 1 },
    { realName: '张三', role: 'DEVELOPER', effective: true, unfinishedTaskCount: 2 },
    { realName: 'Disabled', role: 'DEVELOPER', effective: false, unfinishedTaskCount: 5 },
  ]
  const options = new Function('members', `return ${loop.slice(loop.indexOf(' in ') + 4)}`)(members)
  assert.deepEqual(options.map((member) => member.realName), ['Tom', '张三'])
  const optionLabel = new Function('member', 'statusLabel', `return ${label}`)
  assert.equal(optionLabel(options[0], formatContext.exports.statusLabel), 'Tom · 项目负责人 · 未完成任务 1')
  assert.equal(optionLabel(options[1], formatContext.exports.statusLabel), '张三 · 开发成员 · 未完成任务 2')
})
