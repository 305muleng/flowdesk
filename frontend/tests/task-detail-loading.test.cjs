const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const { parse } = require('@vue/compiler-sfc')

const source = fs.readFileSync(path.join(__dirname, '../src/views/TaskDetailView.vue'), 'utf8')
const ast = ts.createSourceFile('task-detail.ts', parse(source).descriptor.scriptSetup.content, ts.ScriptTarget.Latest, true)
const functions = ast.statements
  .filter((node) => ts.isFunctionDeclaration(node) && ['load', 'loadComments', 'loadSubmissions'].includes(node.name.text))
  .map((node) => node.getText(ast)).join('\n')
const executable = ts.transpileModule(functions, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText

async function loadWithFailure(failedPanel) {
  const refs = {
    task: { value: undefined }, project: { value: undefined }, members: { value: [] },
    comments: { value: [] }, submissions: { value: [] }, selectedAssignee: { value: undefined },
    commentsLoadFailed: { value: false }, submissionsLoadFailed: { value: false },
    loading: { value: false }, id: { value: '10' },
  }
  const context = vm.createContext({
    ...refs,
    getTaskApi: async () => ({ data: { data: { id: 10, projectId: 4, assigneeId: 2 } } }),
    getProjectApi: async () => ({ data: { data: { id: 4, name: 'FlowDesk' } } }),
    getProjectMembersApi: async () => ({ data: { data: [{ userId: 2 }] } }),
    getTaskCommentsApi: () => failedPanel === 'comments' ? Promise.reject(new Error('comments failed')) : Promise.resolve({ data: { data: [{ id: 1 }] } }),
    getTaskSubmissionsApi: () => failedPanel === 'submissions' ? Promise.reject(new Error('submissions failed')) : Promise.resolve({ data: { data: [{ id: 2 }] } }),
  })
  vm.runInContext(executable, context)
  await vm.runInContext('load()', context)
  return refs
}

test('comment failure preserves core task and submissions', async () => {
  const refs = await loadWithFailure('comments')
  assert.equal(refs.task.value.id, 10)
  assert.equal(refs.project.value.name, 'FlowDesk')
  assert.equal(refs.members.value.length, 1)
  assert.equal(refs.submissions.value.length, 1)
  assert.equal(refs.commentsLoadFailed.value, true)
  assert.equal(refs.submissionsLoadFailed.value, false)
})

test('submission failure preserves core task and comments', async () => {
  const refs = await loadWithFailure('submissions')
  assert.equal(refs.task.value.id, 10)
  assert.equal(refs.project.value.name, 'FlowDesk')
  assert.equal(refs.comments.value.length, 1)
  assert.equal(refs.submissionsLoadFailed.value, true)
  assert.equal(refs.commentsLoadFailed.value, false)
})
