const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')
const ts = require('typescript')
const { parse, compileScript } = require('@vue/compiler-sfc')
const { createSSRApp } = require('vue')
const { renderToString } = require('@vue/server-renderer')

// Node 内存渲染，不启动浏览器，不请求后端或外部环境。
function evaluate(source) {
  const js = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText
  const module = { exports: {} }
  new Function('require', 'module', 'exports', js)(
    (id) => (id === '@/utils/format' ? format : require(id)),
    module,
    module.exports,
  )
  return module.exports
}

const format = evaluate(fs.readFileSync(path.join(__dirname, '../src/utils/format.ts'), 'utf8'))
const source = fs.readFileSync(path.join(__dirname, '../src/components/RequestClosureNotice.vue'), 'utf8')
const { descriptor } = parse(source)
const component = evaluate(compileScript(descriptor, { id: 'request-closure-test', inlineTemplate: true }).content).default

test('closed status and both closure reasons are readable', async () => {
  assert.equal(format.statusLabel('CLOSED'), '已关闭')
  for (const [reason, label] of [
    ['PROJECT_COMPLETED', '项目已完成'],
    ['PROJECT_CANCELLED', '项目已取消'],
  ]) {
    const html = await renderToString(
      createSSRApp(component, { status: 'CLOSED', closeReason: reason, closedAt: '2026-10-18T12:00:00' }),
    )
    assert.ok(html.includes('已关闭'))
    assert.ok(html.includes(label))
    assert.ok(html.includes('关闭时间'))
    assert.ok(html.includes('2026'))
  }
})

test('legacy null fields and unknown reasons have safe fallback', async () => {
  for (const closeReason of [null, undefined, 'FUTURE_REASON']) {
    const html = await renderToString(createSSRApp(component, { status: 'CLOSED', closeReason, closedAt: null }))
    assert.ok(html.includes('其他原因'))
    assert.ok(html.includes('—'))
  }
})

test('nonclosed records do not render a closure notice', async () => {
  for (const status of ['PENDING', 'EXPIRED', 'ACCEPTED', 'APPROVED', 'REJECTED', 'CANCELLED']) {
    const html = await renderToString(createSSRApp(component, { status }))
    assert.ok(!html.includes('关闭时间'))
  }
})
