<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { apiGet, apiPatch, apiPost } from '@/api/operations'
const projectId = ref('')
const taskId = ref('')
const requestId = ref('')
const acceptanceId = ref('')
const username = ref('')
const text = ref('')
const result = ref('')
async function run(label: string, call: () => Promise<{ data: { data: unknown } }>) {
  try {
    const response = await call()
    result.value = JSON.stringify(response.data.data, null, 2)
    ElMessage.success(`${label}成功`)
  } catch {}
}
</script>
<template>
  <section class="intro">
    <h3>协作操作中心</h3>
    <p>填写对应 ID 后执行真实后端操作。</p>
  </section>
  <section class="inputs">
    <el-input v-model="projectId" placeholder="项目 ID" /><el-input
      v-model="taskId"
      placeholder="任务 ID"
    /><el-input v-model="requestId" placeholder="任务申请 ID" /><el-input
      v-model="acceptanceId"
      placeholder="验收 ID"
    /><el-input v-model="username" placeholder="用户名 / 用户 ID" /><el-input
      v-model="text"
      placeholder="备注 / 原因 / 内容"
    />
  </section>
  <div class="grid">
    <article>
      <h4>项目与成员</h4>
      <el-button @click="run('启动项目', () => apiPost(`/projects/${projectId}/start`))"
        >启动</el-button
      ><el-button
        @click="run('取消项目', () => apiPost(`/projects/${projectId}/cancel`, { reason: text }))"
        >取消</el-button
      ><el-button @click="run('归档项目', () => apiPost(`/projects/${projectId}/archive`))"
        >归档</el-button
      ><el-button
        @click="run('发送邀请', () => apiPost(`/projects/${projectId}/invitations`, { username }))"
        >邀请成员</el-button
      ><el-button @click="run('成员列表', () => apiGet(`/projects/${projectId}/members`))"
        >成员列表</el-button
      >
    </article>
    <article>
      <h4>任务协作</h4>
      <el-button
        @click="
          run('分配负责人', () =>
            apiPatch(`/tasks/${taskId}/assignee`, { assigneeId: Number(username) }),
          )
        "
        >分配负责人</el-button
      ><el-button
        @click="run('发表评论', () => apiPost(`/tasks/${taskId}/comments`, { content: text }))"
        >发表评论</el-button
      ><el-button @click="run('评论列表', () => apiGet(`/tasks/${taskId}/comments`))"
        >评论列表</el-button
      ><el-button @click="run('提交历史', () => apiGet(`/tasks/${taskId}/submissions`))"
        >提交历史</el-button
      >
    </article>
    <article>
      <h4>任务申请</h4>
      <el-button @click="run('申请列表', () => apiGet(`/projects/${projectId}/task-requests`))"
        >申请列表</el-button
      ><el-button
        @click="
          run('创建申请', () =>
            apiPost(`/projects/${projectId}/task-requests`, {
              title: text,
              description: '',
              goal: '',
            }),
          )
        "
        >创建申请</el-button
      ><el-button
        @click="
          run('批准申请', () =>
            apiPost(`/projects/${projectId}/task-requests/${requestId}/review`, {
              action: 'APPROVE',
              priority: 'MEDIUM',
            }),
          )
        "
        >批准</el-button
      ><el-button
        @click="
          run('驳回申请', () =>
            apiPost(`/projects/${projectId}/task-requests/${requestId}/review`, {
              action: 'REJECT',
              reviewNote: text,
            }),
          )
        "
        >驳回</el-button
      ><el-button
        @click="
          run('撤回申请', () => apiPost(`/projects/${projectId}/task-requests/${requestId}/cancel`))
        "
        >撤回</el-button
      >
    </article>
    <article>
      <h4>项目验收与管理员</h4>
      <el-button
        @click="
          run('提交验收', () =>
            apiPost(`/projects/${projectId}/acceptances`, { submissionNote: text }),
          )
        "
        >提交验收</el-button
      ><el-button @click="run('验收列表', () => apiGet('/admin/project-acceptances'))"
        >验收列表</el-button
      ><el-button
        @click="
          run('验收通过', () =>
            apiPost(`/admin/project-acceptances/${acceptanceId}/review`, {
              action: 'APPROVE',
              reviewNote: text,
            }),
          )
        "
        >通过</el-button
      ><el-button
        @click="
          run('验收驳回', () =>
            apiPost(`/admin/project-acceptances/${acceptanceId}/review`, {
              action: 'REJECT',
              reviewNote: text,
            }),
          )
        "
        >驳回</el-button
      >
    </article>
  </div>
  <section class="result">
    <h4>接口返回</h4>
    <pre>{{ result || '执行操作后，返回数据会显示在这里。' }}</pre>
  </section>
</template>
<style scoped>
.intro {
  margin-bottom: 18px;
}
.intro h3 {
  margin: 0 0 8px;
  color: #27334d;
}
.intro p {
  margin: 0;
  color: #8c97aa;
  font-size: 14px;
}
.inputs {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}
.grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}
.grid article,
.result {
  padding: 20px;
  background: #fff;
  border: 1px solid #edf0f6;
  border-radius: 14px;
}
.grid h4,
.result h4 {
  margin: 0 0 15px;
  color: #27334d;
}
.grid .el-button {
  margin: 0 8px 9px 0;
}
.grid p {
  margin: 8px 0 0;
  color: #98a2b5;
  font-size: 12px;
  line-height: 1.6;
}
.result {
  margin-top: 16px;
}
.result pre {
  max-height: 280px;
  overflow: auto;
  margin: 0;
  color: #4a5876;
  font-size: 12px;
  white-space: pre-wrap;
}
@media (max-width: 800px) {
  .inputs,
  .grid {
    grid-template-columns: 1fr;
  }
}
</style>
