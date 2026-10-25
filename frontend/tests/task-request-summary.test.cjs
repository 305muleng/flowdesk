const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const test = require('node:test')
const ts = require('typescript')
const { parse, compileScript } = require('@vue/compiler-sfc')
const { createSSRApp } = require('vue')
const { renderToString } = require('@vue/server-renderer')
const Schema = require('async-validator').default

const source = (relative) => fs.readFileSync(path.join(__dirname, '../src', relative), 'utf8')
const compile = (text) => ts.transpileModule(text, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText
function evaluate(text) {
  const module = { exports: {} }
  new Function('require', 'module', 'exports', compile(text))(
    (id) => id === '@/utils/format' ? format : require(id), module, module.exports,
  )
  return module.exports
}
const format = evaluate(source('utils/format.ts'))
const { descriptor } = parse(source('components/TaskRequestSummary.vue'))
const component = evaluate(compileScript(descriptor, { id: 'request-summary-test', inlineTemplate: true }).content).default

test('申请描述与目标同时展示，截止时间具有明确标签', async () => {
  const html = await renderToString(createSSRApp(component, { request: {
    description: '实现批量导出', goal: '使成员能够下载项目进展', suggestedDeadline: '2026-11-10T12:00:00',
  } }))
  for (const text of ['任务描述', '任务目标', '建议截止时间', '实现批量导出', '使成员能够下载项目进展', '2026']) {
    assert.ok(html.includes(text), text)
  }
})

test('旧记录缺少字段时分别提示，不用描述替代目标', async () => {
  for (const request of [{}, { description: null, goal: '', suggestedDeadline: null }]) {
    const html = await renderToString(createSSRApp(component, { request }))
    assert.equal((html.match(/未填写/g) || []).length, 2)
    assert.ok(html.includes('未提供建议'))
  }
})

test('用户填写的内容按文本转义展示', async () => {
  const html = await renderToString(createSSRApp(component, { request: {
    description: '<script>alert(1)</script>', goal: '<img src=x onerror=alert(1)>',
  } }))
  assert.ok(!html.includes('<script>'))
  assert.ok(!html.includes('<img'))
  assert.ok(html.includes('&lt;script&gt;'))
})

test('项目申请列表、个人申请列表和批准弹窗均绑定完整申请摘要', () => {
  const project = parse(source('views/ProjectDetailView.vue')).descriptor.template.content
  const personal = parse(source('views/TaskRequestsView.vue')).descriptor.template.content
  assert.match(project, /<TaskRequestSummary\s+:request="item"/)
  assert.match(personal, /<TaskRequestSummary\s+:request="item"/)
  assert.match(project, /v-if="selectedRequest"[\s\S]*?<TaskRequestSummary\s+:request="selectedRequest"/)
  assert.match(project, /prop="goal"[\s\S]*?v-model="taskForm.goal"/)
})

const script = parse(source('views/ProjectDetailView.vue')).descriptor.scriptSetup.content
const ast = ts.createSourceFile('view.ts', script, ts.ScriptTarget.Latest, true)
const declaration = ast.statements.filter(ts.isVariableStatement)
  .flatMap((node) => [...node.declarationList.declarations])
  .find((node) => node.name.getText(ast) === 'taskRules')
const rules = vm.runInNewContext('(' + declaration.initializer.getText(ast) + ')')

test('正式任务表单拒绝描述或目标缺失、空串、纯空白', async () => {
  for (const field of ['description', 'goal']) {
    for (const value of [undefined, null, '', ' \t\n']) {
      const payload = { title: '实现导出', description: '导出功能', goal: '获取项目数据', deadline: '2026-11-10T12:00:00', [field]: value }
      await assert.rejects(new Schema(rules).validate(payload), (error) => Boolean(error.fields[field]))
    }
  }
})

test('正式任务表单接受完整描述和目标', async () => {
  await new Schema(rules).validate({ title: '实现导出', description: '导出功能', goal: '获取项目数据', deadline: '2026-11-10T12:00:00' })
})
