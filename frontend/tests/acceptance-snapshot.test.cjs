const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const code = fs.readFileSync(path.join(__dirname, '../src/utils/acceptanceSnapshot.ts'), 'utf8')
const compiled = ts.transpileModule(code, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText
const context = { exports: {} }
vm.runInNewContext(compiled, context)
const presentation = context.exports.acceptancePresentation

test('saved delivery is labeled historical while workflow status stays current', () => {
  const view = presentation('SUBMISSION_SNAPSHOT')
  assert.equal(view.isSnapshot, true)
  assert.match(view.notice, /固定为本轮提交时/)
  assert.match(view.notice, /当前项目状态和审核结果/)
  assert.match(view.notice, /外链内容本身未归档/)
  assert.match(view.memberDescription, /提交时保留的历史成员/)
})
test('legacy records explicitly cannot reconstruct submission history', () => {
  const view = presentation('LEGACY_CURRENT')
  assert.equal(view.isSnapshot, false)
  assert.equal(view.prefix, '当前')
  assert.match(view.notice, /未保存提交快照/)
  assert.match(view.notice, /无法还原提交时状态/)
})
test('missing or unknown source metadata is never presented as a verified snapshot or legacy source', () => {
  for (const source of [undefined, null, '', 'FUTURE_SOURCE']) {
    const view = presentation(source)
    assert.equal(view.isSnapshot, false)
    assert.match(view.label, /未确认/)
    assert.match(view.notice, /不能.*视为提交时快照/)
  }
})
