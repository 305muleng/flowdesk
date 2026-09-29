const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const { parse } = require('@vue/compiler-sfc')

function loadTypeScript(relative, requireModule = () => { throw new Error('Unexpected import') }) {
  const source = fs.readFileSync(path.join(__dirname, '../src', relative), 'utf8')
  const context = vm.createContext({ exports: {}, require: requireModule, URL })
  vm.runInContext(ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText, context)
  return context.exports
}

const submission = loadTypeScript('utils/acceptanceSubmission.ts')

test('normal submission sends a typed payload with a delivery link', async () => {
  const form = { ...submission.emptyAcceptanceSubmission(), submissionNote: '  提交验收  ', repositoryUrl: '  https://example.com/repo  ' }
  assert.equal(submission.acceptanceSubmissionError(form), '')
  const payload = submission.acceptanceSubmissionPayload(form)
  assert.equal(payload.submissionNote, '提交验收')
  assert.equal(payload.repositoryUrl, 'https://example.com/repo')
  assert.equal(payload.deployUrl, null)

  const calls = []
  const api = loadTypeScript('api/projects.ts', (name) => {
    if (name === './request') return { __esModule: true, default: { post: async (...args) => { calls.push(args) } } }
    throw new Error(`Unexpected import: ${name}`)
  })
  await api.submitAcceptanceApi(4, payload)
  assert.equal(calls[0][0], '/projects/4/acceptances')
  assert.equal(calls[0][1].repositoryUrl, 'https://example.com/repo')
})

test('submission requires a note and at least one delivery URL', () => {
  const empty = submission.emptyAcceptanceSubmission()
  assert.equal(submission.acceptanceSubmissionError(empty), '请填写验收说明')
  assert.equal(submission.acceptanceSubmissionError({ ...empty, submissionNote: '完成' }), '请至少填写一个交付链接')
})

test('frontend uses the agreed ASCII HTTP(S) hostname and port rules', () => {
  const empty = submission.emptyAcceptanceSubmission()
  const accepted = [
    'https://github.com/test/project', 'http://localhost:8080',
    'https://127.0.0.1:8443/path', 'https://example.com/path?x=1',
    'http://[::1]:8080',
  ]
  const rejected = [
    'ftp://example.com', 'javascript:alert(1)', 'example.com',
    'https://例子.中国/path', 'https://example.com:65536',
    'https://example.com:0', 'http://[:::]:8080',
  ]
  for (const url of accepted) {
    assert.equal(submission.isValidHttpUrl(url), true, url)
    assert.equal(submission.acceptanceSubmissionError({ ...empty, submissionNote: '完成', repositoryUrl: url }), '')
  }
  for (const url of rejected) {
    assert.equal(submission.isValidHttpUrl(url), false, url)
    assert.match(submission.acceptanceSubmissionError({ ...empty, submissionNote: '完成', repositoryUrl: url }), /代码仓库.*http:\/\/.*https:\/\//)
  }
  assert.equal(submission.isValidHttpUrl(''), false)
  assert.equal(submission.acceptanceSubmissionError({ ...empty, submissionNote: '完成', repositoryUrl: '', deployUrl: 'http://localhost:8080' }), '')
})

test('both existing submission entry points use the shared form', () => {
  for (const view of ['ProjectDetailView.vue', 'OperationsView.vue']) {
    const source = fs.readFileSync(path.join(__dirname, '../src/views', view), 'utf8')
    const descriptor = parse(source).descriptor
    assert.ok(descriptor.template.content.includes('<AcceptanceSubmissionDialog'))
    assert.ok(descriptor.scriptSetup.content.includes('submitAcceptanceApi'))
  }
})
