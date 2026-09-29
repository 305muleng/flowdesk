<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue'
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { getAdminAcceptanceDetailApi, type ProjectAcceptanceDetail } from '@/api/acceptances'
import { formatDateTime } from '@/utils/format'
import { promptReviewAcceptance } from '@/utils/reviewAcceptance'

const route = useRoute()
const acceptanceId = computed(() => Number(route.params.acceptanceId))
const detail = ref<ProjectAcceptanceDetail | null>(null)
const loading = ref(false)
const processing = ref(false)
const error = ref('')

const display = (value: string | null | undefined) => value?.trim() || '未提供'
const date = (value: string | null | undefined) => formatDateTime(value || undefined)
const safeLink = (value: string | null | undefined) => {
  if (!value?.trim()) return ''
  try {
    const url = new URL(value)
    return ['http:', 'https:'].includes(url.protocol) ? url.href : ''
  } catch {
    return ''
  }
}

async function load() {
  const id = acceptanceId.value
  detail.value = null
  error.value = ''
  if (!Number.isSafeInteger(id) || id <= 0) {
    error.value = '验收记录编号无效。'
    return
  }
  loading.value = true
  try {
    const result = await getAdminAcceptanceDetailApi(id)
    if (acceptanceId.value === id) detail.value = result.data.data
  } catch {
    if (acceptanceId.value === id) error.value = '验收详情加载失败，请稍后重试。'
  } finally {
    if (acceptanceId.value === id) loading.value = false
  }
}

async function review(action: 'APPROVE' | 'REJECT') {
  if (!detail.value || detail.value.reviewStatus !== 'PENDING' || processing.value) return
  const id = detail.value.id
  processing.value = true
  try {
    if (await promptReviewAcceptance(id, action)) await load()
  } finally {
    processing.value = false
  }
}

watch(acceptanceId, load, { immediate: true })
</script>

<template>
  <PageHeader eyebrow="SYSTEM ADMIN" title="项目验收详情" description="查看本轮交付、成员贡献和该项目的验收记录。">
    <el-button @click="$router.push({ name: 'admin-acceptances' })">返回列表</el-button>
    <el-button :icon="Refresh" circle :loading="loading" @click="load" />
  </PageHeader>

  <div v-loading="loading" class="detail-page">
    <el-result v-if="error" icon="error" title="无法加载验收详情" :sub-title="error">
      <template #extra><el-button type="primary" @click="load">重试</el-button></template>
    </el-result>
    <template v-else-if="detail">
      <el-alert
        v-if="detail.acceptanceHistory?.length > 1"
        title="以下项目状态、交付信息、任务统计、成员贡献和取消任务反映项目当前数据，并非本轮验收提交时的历史快照。"
        type="info"
        :closable="false"
        show-icon
      />
      <section class="surface-card detail-section">
        <h2>项目基本信息</h2>
        <div class="info-grid">
          <div><span>项目名称</span><strong>{{ detail.projectName }}</strong></div>
          <div><span>当前项目状态</span><StatusTag :value="detail.projectStatus" /></div>
          <div class="wide"><span>项目简介</span><p>{{ display(detail.projectDescription) }}</p></div>
          <div class="wide"><span>项目目标</span><p>{{ display(detail.projectGoal) }}</p></div>
        </div>
      </section>

      <section class="surface-card detail-section">
        <div class="section-heading">
          <h2>本轮验收信息</h2>
          <div v-if="detail.reviewStatus === 'PENDING'" class="review-actions">
            <el-button :disabled="processing" @click="review('REJECT')">驳回</el-button>
            <el-button type="primary" :loading="processing" @click="review('APPROVE')">通过验收</el-button>
          </div>
        </div>
        <div class="info-grid">
          <div><span>验收轮次</span><strong>第 {{ detail.acceptanceNo }} 次验收</strong></div>
          <div><span>审核状态</span><StatusTag :value="detail.reviewStatus" /></div>
          <div><span>提交人</span><strong>{{ display(detail.submitterName) }}</strong></div>
          <div><span>提交时间</span><strong>{{ date(detail.submittedAt) }}</strong></div>
          <div class="wide"><span>提交说明</span><p>{{ display(detail.submissionNote) }}</p></div>
          <template v-if="detail.reviewStatus !== 'PENDING'">
            <div><span>审核人</span><strong>{{ display(detail.reviewerName) }}</strong></div>
            <div><span>审核时间</span><strong>{{ date(detail.reviewedAt) }}</strong></div>
            <div class="wide"><span>审核意见</span><p>{{ display(detail.reviewNote) }}</p></div>
          </template>
        </div>
      </section>

      <section class="surface-card detail-section">
        <h2>当前交付信息</h2>
        <div class="info-grid">
          <div v-for="link in [
            { label: '代码仓库', value: detail.repositoryUrl },
            { label: '部署地址', value: detail.deployUrl },
            { label: '项目文档', value: detail.documentUrl },
          ]" :key="link.label">
            <span>{{ link.label }}</span>
            <a v-if="safeLink(link.value)" :href="safeLink(link.value)" target="_blank" rel="noopener noreferrer">{{ link.value }}</a>
            <strong v-else>{{ display(link.value) === '未提供' ? '未提供' : '链接不可用' }}</strong>
          </div>
        </div>
      </section>

      <section class="surface-card detail-section">
        <h2>当前任务统计</h2>
        <div class="stats-grid">
          <div><span>总任务数</span><strong>{{ detail.totalTaskCount }}</strong></div>
          <div><span>已完成任务数</span><strong>{{ detail.completedTaskCount }}</strong></div>
          <div><span>已取消任务数</span><strong>{{ detail.cancelledTaskCount }}</strong></div>
          <div><span>完成率</span><strong>{{ detail.completionRate }}%</strong></div>
        </div>
      </section>

      <section class="surface-card detail-section">
        <h2>当前成员贡献</h2>
        <el-empty v-if="!detail.members?.length" description="暂无成员贡献记录" />
        <div v-else class="record-list">
          <article v-for="member in detail.members" :key="member.userId" class="record-item">
            <div class="record-heading"><strong>{{ member.userName }}</strong><StatusTag :value="member.role" /><span>完成 {{ member.completedTaskCount }} 项任务</span></div>
            <ul v-if="member.completedTasks?.length" class="task-list">
              <li v-for="task in member.completedTasks" :key="task.taskId"><span>{{ task.title }}</span><time>{{ date(task.completedAt) }}</time></li>
            </ul>
            <p v-else class="muted">暂无已完成任务</p>
          </article>
        </div>
      </section>

      <section class="surface-card detail-section">
        <h2>当前取消任务</h2>
        <el-empty v-if="!detail.cancelledTasks?.length" description="没有取消任务" />
        <div v-else class="record-list">
          <article v-for="task in detail.cancelledTasks" :key="task.taskId" class="record-item">
            <div class="record-heading"><strong>{{ task.title }}</strong><time>{{ date(task.cancelledAt) }}</time></div>
            <p>原负责人：{{ display(task.assigneeName) }}</p>
            <p>取消原因：{{ display(task.cancelReason) }}</p>
          </article>
        </div>
      </section>

      <section class="surface-card detail-section">
        <h2>历史验收</h2>
        <el-empty v-if="!detail.acceptanceHistory?.length" description="暂无历史验收记录" />
        <div v-else class="record-list">
          <article v-for="item in detail.acceptanceHistory" :key="item.id" class="record-item" :class="{ current: item.id === detail.id }">
            <div class="record-heading"><strong>第 {{ item.acceptanceNo }} 次验收</strong><StatusTag :value="item.reviewStatus" /><el-tag v-if="item.id === detail.id" size="small" effect="plain">当前查看</el-tag></div>
            <div class="history-grid">
              <p>提交人：{{ display(item.submitterName) }}</p><p>提交时间：{{ date(item.submittedAt) }}</p>
              <p>审核人：{{ display(item.reviewerName) }}</p><p>审核时间：{{ date(item.reviewedAt) }}</p>
              <p class="wide">审核意见：{{ display(item.reviewNote) }}</p>
            </div>
            <el-button v-if="item.id !== detail.id" link type="primary" @click="$router.push({ name: 'admin-acceptance-detail', params: { acceptanceId: item.id } })">查看该轮详情</el-button>
          </article>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.detail-page { min-height: 240px; display: grid; gap: 16px; }
.detail-section { padding: 24px; min-width: 0; }
.detail-section h2 { margin: 0 0 20px; color: var(--ink); font-size: 18px; }
.section-heading, .record-heading { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; }
.section-heading h2 { margin-bottom: 0; }
.review-actions { display: flex; gap: 8px; }
.info-grid, .history-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px 24px; }
.info-grid { margin-top: 20px; }
.info-grid > div { min-width: 0; display: flex; flex-direction: column; align-items: flex-start; gap: 7px; }
.info-grid span, .stats-grid span { color: var(--muted); font-size: 12px; }
.info-grid strong, .info-grid p { color: var(--ink); font-size: 14px; font-weight: 500; overflow-wrap: anywhere; }
.info-grid p { margin: 0; white-space: pre-wrap; line-height: 1.7; }
.info-grid a { overflow-wrap: anywhere; font-size: 14px; }
.wide { grid-column: 1 / -1; }
.stats-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.stats-grid > div { display: flex; flex-direction: column; gap: 7px; padding: 18px; border-radius: 10px; background: #f8f9fc; }
.stats-grid strong { color: var(--ink); font-size: 24px; }
.record-list { display: grid; gap: 12px; }
.record-item { padding: 18px; border: 1px solid #e7eaf0; border-radius: 10px; }
.record-item.current { border-color: #91a8e8; background: #f7f9ff; }
.record-heading { justify-content: flex-start; }
.record-heading strong { color: var(--ink); margin-right: auto; }
.record-heading > span, .record-item time, .muted { color: var(--muted); font-size: 12px; }
.record-item p { color: #667085; font-size: 13px; line-height: 1.6; }
.task-list { padding: 0; margin: 14px 0 0; list-style: none; }
.task-list li { display: flex; justify-content: space-between; gap: 12px; padding: 9px 0; border-top: 1px solid #eef0f4; color: #475467; font-size: 13px; }
.history-grid { gap: 0 18px; margin: 8px 0; }
.history-grid p { margin: 6px 0; }
@media (max-width: 700px) { .stats-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .info-grid, .history-grid { grid-template-columns: 1fr; } .wide { grid-column: auto; } }
</style>
