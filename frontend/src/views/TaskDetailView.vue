<script setup lang="ts">
import { ArrowLeft, ChatDotRound } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getProjectApi, getProjectMembersApi } from '@/api/projects'
import {
  addTaskCommentApi,
  assignTaskApi,
  cancelTaskApi,
  getTaskApi,
  getTaskCommentsApi,
  getTaskSubmissionsApi,
  reviewTaskApi,
  startTaskApi,
  submitTaskApi,
} from '@/api/tasks'
import StatusTag from '@/components/StatusTag.vue'
import { useAuthStore } from '@/stores/auth'
import type { Project, ProjectMember, Task, TaskComment, TaskSubmission } from '@/types/api'
import { formatDateTime } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const id = computed(() => route.params.taskId as string)
const task = ref<Task>()
const project = ref<Project>()
const members = ref<ProjectMember[]>([])
const comments = ref<TaskComment[]>([])
const submissions = ref<TaskSubmission[]>([])
const loading = ref(false)
const commentContent = ref('')
const submittingComment = ref(false)
const submitVisible = ref(false)
const assignVisible = ref(false)
const selectedAssignee = ref<number>()
const submitForm = reactive({ completionNote: '', resultUrl: '', testNote: '' })

const myMembership = computed(() =>
  members.value.find((member) => member.userId === auth.user?.userId),
)
const isManager = computed(() => myMembership.value?.role === 'PROJECT_MANAGER')
const isAssignee = computed(() => task.value?.assigneeId === auth.user?.userId)
const projectAllowsPlanning = computed(() =>
  ['PREPARING', 'IN_PROGRESS'].includes(project.value?.status || ''),
)
const projectIsRunning = computed(() => project.value?.status === 'IN_PROGRESS')

async function load() {
  loading.value = true
  try {
    task.value = (await getTaskApi(id.value)).data.data
    const [projectResponse, memberResponse, commentResponse, submissionResponse] = await Promise.all([
      getProjectApi(task.value.projectId),
      getProjectMembersApi(task.value.projectId),
      getTaskCommentsApi(id.value),
      getTaskSubmissionsApi(id.value),
    ])
    project.value = projectResponse.data.data
    members.value = memberResponse.data.data
    comments.value = commentResponse.data.data
    submissions.value = submissionResponse.data.data
    selectedAssignee.value = task.value.assigneeId
  } finally {
    loading.value = false
  }
}

async function start() {
  await startTaskApi(id.value)
  ElMessage.success('任务已开始')
  await load()
}

async function submitTask() {
  if (!submitForm.completionNote.trim() || !submitForm.testNote.trim()) {
    ElMessage.warning('请填写完成说明和测试说明')
    return
  }
  await submitTaskApi(id.value, {
    completionNote: submitForm.completionNote.trim(),
    resultUrl: submitForm.resultUrl.trim() || undefined,
    testNote: submitForm.testNote.trim(),
  })
  submitVisible.value = false
  Object.assign(submitForm, { completionNote: '', resultUrl: '', testNote: '' })
  ElMessage.success('已提交审核')
  await load()
}

async function review(action: 'APPROVE' | 'REJECT') {
  try {
    const result = await ElMessageBox.prompt(
      action === 'APPROVE' ? '可填写审核备注' : '请说明需要修改的内容',
      action === 'APPROVE' ? '通过任务' : '驳回任务',
      action === 'REJECT' ? { inputPattern: /\S+/, inputErrorMessage: '请填写驳回原因' } : {},
    )
    await reviewTaskApi(id.value, { action, reviewNote: result.value || undefined })
    ElMessage.success(action === 'APPROVE' ? '任务已通过' : '任务已驳回')
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') throw error
  }
}

async function cancel() {
  try {
    const result = await ElMessageBox.prompt('请填写取消原因', '取消任务', {
      inputPattern: /\S+/,
      inputErrorMessage: '请填写取消原因',
    })
    await cancelTaskApi(id.value, { reason: result.value })
    ElMessage.success('任务已取消')
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') throw error
  }
}

async function assign() {
  if (!selectedAssignee.value) return ElMessage.warning('请选择负责人')
  await assignTaskApi(id.value, selectedAssignee.value)
  assignVisible.value = false
  ElMessage.success('负责人已更新')
  await load()
}

async function addComment() {
  if (!commentContent.value.trim()) return
  submittingComment.value = true
  try {
    await addTaskCommentApi(id.value, commentContent.value.trim())
    commentContent.value = ''
    comments.value = (await getTaskCommentsApi(id.value)).data.data
    ElMessage.success('评论已发布')
  } finally {
    submittingComment.value = false
  }
}

onMounted(load)
</script>

<template>
  <section v-loading="loading" class="task-page">
    <el-button text :icon="ArrowLeft" @click="router.back()">返回</el-button>
    <template v-if="task">
      <article class="task-hero">
        <div class="hero-main">
          <div class="eyebrow">
            <StatusTag :value="task.status" /><StatusTag :value="task.priority || 'MEDIUM'" />
          </div>
          <h1>{{ task.title }}</h1>
          <p>{{ task.description || '暂无任务描述' }}</p>
        </div>
        <div class="actions">
          <el-button v-if="isManager && projectAllowsPlanning && task.status === 'TODO'" @click="assignVisible = true"
            >分配负责人</el-button
          >
          <el-button v-if="isAssignee && projectIsRunning && task.status === 'TODO'" type="primary" @click="start"
            >开始任务</el-button
          >
          <el-button
            v-if="isAssignee && projectIsRunning && task.status === 'IN_PROGRESS'"
            type="primary"
            @click="submitVisible = true"
            >提交审核</el-button
          >
          <el-button
            v-if="isManager && projectIsRunning && task.status === 'REVIEW'"
            type="success"
            @click="review('APPROVE')"
            >审核通过</el-button
          >
          <el-button v-if="isManager && projectIsRunning && task.status === 'REVIEW'" @click="review('REJECT')"
            >驳回修改</el-button
          >
          <el-button
            v-if="isManager && projectAllowsPlanning && ['TODO', 'IN_PROGRESS'].includes(task.status)"
            type="danger"
            plain
            @click="cancel"
            >取消任务</el-button
          >
        </div>
      </article>

      <div class="info-grid">
        <div>
          <span>负责人</span><strong>{{ task.assigneeName || '待分配' }}</strong>
        </div>
        <div>
          <span>截止时间</span><strong>{{ formatDateTime(task.deadline) }}</strong>
        </div>
        <div>
          <span>创建时间</span><strong>{{ formatDateTime(task.createdAt) }}</strong>
        </div>
        <div>
          <span>任务目标</span><strong>{{ task.goal || '未单独设置' }}</strong>
        </div>
      </div>

      <div class="content-grid">
        <section class="panel">
          <div class="panel-title">
            <h2>提交记录</h2>
            <span>{{ submissions.length }} 次</span>
          </div>
          <el-empty v-if="!submissions.length" description="还没有提交记录" :image-size="80" />
          <div v-else class="timeline">
            <article v-for="submission in submissions" :key="submission.id" class="timeline-item">
              <div class="timeline-dot" />
              <div class="submission-card">
                <div class="submission-head">
                  <strong>第 {{ submission.submissionNo }} 次提交</strong
                  ><StatusTag :value="submission.reviewStatus" />
                </div>
                <p>{{ submission.completionNote }}</p>
                <p class="muted">测试说明：{{ submission.testNote }}</p>
                <a
                  v-if="submission.resultUrl"
                  :href="submission.resultUrl"
                  target="_blank"
                  rel="noreferrer"
                  >查看交付结果 ↗</a
                >
                <p v-if="submission.reviewNote" class="review-note">
                  审核意见：{{ submission.reviewNote }}
                </p>
                <small
                  >{{ submission.submitterName }} ·
                  {{ formatDateTime(submission.submittedAt) }}</small
                >
              </div>
            </article>
          </div>
        </section>

        <section class="panel">
          <div class="panel-title">
            <h2>协作讨论</h2>
            <span>{{ comments.length }} 条</span>
          </div>
          <div v-if="projectAllowsPlanning" class="comment-compose">
            <el-input
              v-model="commentContent"
              type="textarea"
              :rows="3"
              maxlength="500"
              show-word-limit
              placeholder="记录进展、风险或需要协助的事项…"
            />
            <el-button
              type="primary"
              :icon="ChatDotRound"
              :loading="submittingComment"
              @click="addComment"
              >发布评论</el-button
            >
          </div>
          <el-empty
            v-if="!comments.length"
            description="暂无讨论，来写第一条评论吧"
            :image-size="72"
          />
          <article v-for="comment in comments" :key="comment.id" class="comment-item">
            <div class="avatar">{{ comment.authorName.slice(0, 1) }}</div>
            <div>
              <div class="comment-meta">
                <strong>{{ comment.authorName }}</strong
                ><span>{{ formatDateTime(comment.createdAt) }}</span>
              </div>
              <p>{{ comment.content }}</p>
            </div>
          </article>
        </section>
      </div>
    </template>

    <el-dialog v-model="submitVisible" title="提交任务成果" width="520px">
      <el-form label-position="top">
        <el-form-item label="完成说明" required
          ><el-input v-model="submitForm.completionNote" type="textarea" :rows="3"
        /></el-form-item>
        <el-form-item label="成果链接"
          ><el-input v-model="submitForm.resultUrl" placeholder="https://…"
        /></el-form-item>
        <el-form-item label="测试说明" required
          ><el-input v-model="submitForm.testNote" type="textarea" :rows="3"
        /></el-form-item>
      </el-form>
      <template #footer
        ><el-button @click="submitVisible = false">取消</el-button
        ><el-button type="primary" @click="submitTask">提交审核</el-button></template
      >
    </el-dialog>

    <el-dialog v-model="assignVisible" title="分配任务负责人" width="440px">
      <el-select v-model="selectedAssignee" placeholder="选择开发成员" style="width: 100%">
        <el-option
          v-for="member in members.filter((item) => item.role === 'DEVELOPER')"
          :key="member.userId"
          :label="member.realName + '（' + member.username + '）'"
          :value="member.userId"
        />
      </el-select>
      <template #footer
        ><el-button @click="assignVisible = false">取消</el-button
        ><el-button type="primary" @click="assign">确认分配</el-button></template
      >
    </el-dialog>
  </section>
</template>

<style scoped>
.task-page {
  display: grid;
  gap: 18px;
}
.task-hero,
.panel,
.info-grid {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 20px;
  box-shadow: var(--shadow-sm);
}
.task-hero {
  display: flex;
  justify-content: space-between;
  gap: 28px;
  padding: 30px;
  background: linear-gradient(135deg, #fff 65%, #f0f5ff);
}
.hero-main {
  max-width: 720px;
}
.eyebrow,
.actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
h1 {
  margin: 15px 0 10px;
  font-size: 28px;
  color: var(--text-strong);
}
.hero-main p {
  margin: 0;
  color: var(--text-muted);
  line-height: 1.75;
}
.actions {
  justify-content: flex-end;
  align-items: flex-start;
}
.info-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  padding: 22px 26px;
}
.info-grid > div {
  display: grid;
  gap: 8px;
  padding: 0 20px;
  border-right: 1px solid var(--border);
}
.info-grid > div:first-child {
  padding-left: 0;
}
.info-grid > div:last-child {
  border: 0;
}
.info-grid span,
.panel-title span,
.muted,
small {
  color: var(--text-muted);
}
.info-grid strong {
  color: var(--text-strong);
  font-size: 14px;
}
.content-grid {
  display: grid;
  grid-template-columns: 1.08fr 0.92fr;
  gap: 18px;
  align-items: start;
}
.panel {
  padding: 24px;
}
.panel-title,
.submission-head,
.comment-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}
.panel-title {
  margin-bottom: 20px;
}
.panel-title h2 {
  margin: 0;
  font-size: 18px;
}
.timeline-item {
  position: relative;
  display: grid;
  grid-template-columns: 18px 1fr;
  gap: 12px;
}
.timeline-item:not(:last-child)::before {
  content: '';
  position: absolute;
  left: 5px;
  top: 13px;
  bottom: -12px;
  width: 2px;
  background: #e5ebf5;
}
.timeline-dot {
  width: 12px;
  height: 12px;
  margin-top: 6px;
  border: 3px solid #dbe7ff;
  border-radius: 50%;
  background: var(--primary);
  z-index: 1;
}
.submission-card {
  padding-bottom: 24px;
}
.submission-card p {
  margin: 10px 0;
  line-height: 1.65;
}
.submission-card a {
  color: var(--primary);
  text-decoration: none;
}
.review-note {
  padding: 10px 12px;
  border-radius: 10px;
  background: #f7f8fb;
}
.comment-compose {
  display: grid;
  justify-items: end;
  gap: 10px;
  padding-bottom: 20px;
  border-bottom: 1px solid var(--border);
}
.comment-item {
  display: grid;
  grid-template-columns: 36px 1fr;
  gap: 12px;
  padding: 18px 0;
  border-bottom: 1px solid var(--border);
}
.avatar {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border-radius: 11px;
  background: #eaf1ff;
  color: var(--primary);
  font-weight: 700;
}
.comment-meta span {
  color: var(--text-muted);
  font-size: 12px;
}
.comment-item p {
  margin: 8px 0 0;
  line-height: 1.65;
}
@media (max-width: 980px) {
  .content-grid {
    grid-template-columns: 1fr;
  }
  .info-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
@media (max-width: 720px) {
  .task-hero {
    flex-direction: column;
    padding: 22px;
  }
  .actions {
    justify-content: flex-start;
  }
  .info-grid {
    grid-template-columns: 1fr;
  }
  .info-grid > div {
    padding: 10px 0;
    border: 0;
  }
  .panel {
    padding: 18px;
  }
}
</style>
