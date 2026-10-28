const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript } = require('@vue/compiler-sfc')
const { renderToString } = require('@vue/server-renderer')

const source = fs.readFileSync(path.join(__dirname, '../src/components/SubmissionTimingTag.vue'), 'utf8')
const descriptor = parse(source).descriptor
const script = compileScript(descriptor, { id: 'submission-timing', inlineTemplate: true }).content
const compiled = ts.transpileModule(script, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText
const output = { exports: {} }
vm.runInNewContext(compiled, { exports: output.exports, require })

async function render(props) {
  const app = vue.createSSRApp(output.exports.default, props)
  app.component('el-tag', vue.defineComponent({
    props: ['type'],
    setup(props, { slots }) { return () => vue.h('span', { 'data-type': props.type }, slots.default?.()) },
  }))
  return renderToString(app)
}

test('逾期提交始终有明确标记，任务完成后也可复用该标记', async () => {
  const html = await render({ late: true })
  assert.match(html, /逾期提交/)
  assert.match(html, /data-type="warning"/)
})

test('按时标记只在提交历史详细展示中出现', async () => {
  assert.doesNotMatch(await render({ late: false }), /按时提交|逾期提交/)
  assert.match(await render({ late: false, detailed: true }), /按时提交/)
})

test('缺少截止时间快照时明确显示未记录，不推断按时或逾期', async () => {
  for (const late of [null, undefined]) {
    const html = await render({ late, detailed: true })
    assert.match(html, /提交时限未记录/)
    assert.doesNotMatch(html, /按时提交|逾期提交/)
    assert.doesNotMatch(await render({ late }), /提交时限未记录|按时提交|逾期提交/)
  }
})
