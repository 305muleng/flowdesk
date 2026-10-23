const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const { parse } = require('@vue/compiler-sfc')
const { compile } = require('@vue/compiler-dom')
const Vue = require('vue')
const presentationModule = { exports: {} }
vm.runInNewContext(ts.transpileModule(
  fs.readFileSync(path.join(__dirname, '../src/utils/acceptanceSnapshot.ts'), 'utf8'),
  { compilerOptions: { module: ts.ModuleKind.CommonJS } },
).outputText, presentationModule)

const source = fs.readFileSync(path.join(__dirname, '../src/views/AdminAcceptanceDetailView.vue'), 'utf8')
const descriptor = parse(source).descriptor
const template = descriptor.template.content
const render = new Function('Vue', compile(template, { mode: 'function' }).code)(Vue)
const script = ts.createSourceFile('detail.ts', descriptor.scriptSetup.content, ts.ScriptTarget.Latest, true)
const helpersSource = script.statements
  .filter(
    (statement) =>
      ts.isVariableStatement(statement) &&
      statement.declarationList.declarations.some((declaration) =>
        ['display', 'safeLink'].includes(declaration.name.getText(script)),
      ),
  )
  .map((statement) => statement.getText(script))
  .join('\n')
const helperContext = vm.createContext({ URL })
vm.runInContext(
  ts.transpileModule(`${helpersSource}\nglobalThis.helpers = { display, safeLink }`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022 },
  }).outputText,
  helperContext,
)

function nodes(vnode) {
  if (Array.isArray(vnode)) return vnode.flatMap(nodes)
  if (vnode == null || typeof vnode !== 'object') return []
  const children = vnode.children
  return [
    vnode,
    ...(Array.isArray(children)
      ? children.flatMap(nodes)
      : children && typeof children === 'object'
        ? Object.values(children)
            .filter((slot) => typeof slot === 'function')
            .flatMap((slot) => nodes(slot()))
        : []),
  ]
}

function page(status = 'PENDING', dataSource = 'LEGACY_CURRENT') {
  const detail = {
    dataSource,
    snapshotVersion: dataSource === 'SUBMISSION_SNAPSHOT' ? 1 : null,
    snapshotCapturedAt: dataSource === 'SUBMISSION_SNAPSHOT' ? '2026-09-28' : null,
    id: 7,
    acceptanceNo: 2,
    projectName: 'FlowDesk',
    projectStatus: 'IN_PROGRESS',
    projectDescription: '项目简介正文',
    projectGoal: '项目目标正文',
    reviewStatus: status,
    submitterName: '张三',
    submittedAt: '2026-09-28',
    submissionNote: '交付完成',
    reviewerName: '管理员',
    reviewedAt: '2026-09-29',
    reviewNote: '检查通过',
    repositoryUrl: null,
    deployUrl: null,
    documentUrl: null,
    totalTaskCount: 4,
    completedTaskCount: 3,
    cancelledTaskCount: 1,
    completionRate: 75,
    members: [
      {
        userId: 1,
        userName: '李四',
        role: 'DEVELOPER',
        completedTaskCount: 1,
        completedTasks: [{ taskId: 9, title: '完成登录', completedAt: '2026-09-28' }],
      },
    ],
    cancelledTasks: [{ taskId: 10, title: '旧任务', assigneeName: '王五', cancelReason: '需求调整', cancelledAt: '2026-09-27' }],
    acceptanceHistory: [
      {
        id: 7,
        acceptanceNo: 2,
        reviewStatus: status,
        submittedAt: '2026-09-28',
        submitterName: '张三',
        reviewedAt: null,
        reviewerName: null,
        reviewNote: null,
      },
      {
        id: 6,
        acceptanceNo: 1,
        reviewStatus: 'REJECTED',
        submittedAt: '2026-09-01',
        submitterName: '李四',
        reviewedAt: '2026-09-02',
        reviewerName: '管理员',
        reviewNote: '需补充交付',
      },
    ],
  }
  const context = {
    detail,
    presentation: presentationModule.exports.acceptancePresentation(dataSource),
    loading: false,
    error: '',
    processing: false,
    Refresh: {},
    ...helperContext.helpers,
    date: (value) => value || '—',
    load() {},
    review() {},
    $router: { push() {} },
  }
  const originalWarn = console.warn
  console.warn = () => {}
  try {
    return nodes(render(context, []))
  } finally {
    console.warn = originalWarn
  }
}

function content(tree) {
  return tree.map((node) => (typeof node.children === 'string' ? node.children : '')).join(' ')
}

test('detail template renders project, tasks, members and history', () => {
  const text = content(page())
  for (const expected of [
    'FlowDesk',
    '项目简介正文',
    '项目目标正文',
    '交付完成',
    '75%',
    '李四',
    '完成登录',
    '旧任务',
    '需求调整',
    '第 2 次验收',
  ]) {
    assert.ok(text.includes(expected), `${expected} should appear`)
  }
})

test('null delivery links show placeholders and never produce anchors', () => {
  const tree = page()
  assert.ok((content(tree).match(/未提供/g) || []).length >= 3)
  assert.ok(!content(tree).includes('null'))
  assert.equal(tree.filter((node) => node.type === 'a').length, 0)
})

test('pending acceptance shows review actions; reviewed acceptance hides them', () => {
  const pending = content(page('PENDING'))
  const approved = content(page('APPROVED'))
  assert.ok(pending.includes('通过验收'))
  assert.ok(pending.includes('驳回'))
  assert.ok(!approved.includes('通过验收'))
  assert.ok(!approved.includes('驳回'))
  assert.ok(approved.includes('检查通过'))
})

test('legacy rounds explicitly label current data and cannot reconstruct old delivery', () => {
  const tree = page()
  const notice = tree.find((node) => node.type === 'el-alert' && node.props?.type === 'warning')
  assert.ok(notice)
  assert.match(notice.props.title, /未保存提交快照.*当前数据.*无法还原提交时状态/)
  const text = content(tree)
  for (const label of ['当前项目状态', '当前交付信息', '当前任务统计', '当前成员贡献', '当前取消任务']) {
    assert.ok(text.includes(label))
  }
})

test('snapshot rounds label frozen sections while project and review state stay current', () => {
  const tree = page('APPROVED', 'SUBMISSION_SNAPSHOT')
  const notice = tree.find((node) => node.type === 'el-alert' && node.props?.type === 'info')
  assert.match(notice.props.title, /本轮提交时的数据/)
  const text = content(tree)
  for (const label of ['本轮提交快照', '快照时间', '本轮提交时的交付信息', '本轮提交时的任务统计', '本轮提交时的成员贡献', '当前项目状态', '检查通过']) {
    assert.ok(text.includes(label), `${label} should appear`)
  }
  assert.ok(!text.includes('旧记录 · 当前数据'))
})
