<script setup lang="ts">
import { Check, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { getAdminAcceptancesApi, reviewAcceptanceApi } from '@/api/acceptances'
import { formatDateTime } from '@/utils/format'
import type { ProjectAcceptance } from '@/types/api'

const list = ref<ProjectAcceptance[]>([])
const route = useRoute()
const focusedAcceptanceId = computed(() => Number(route.query.acceptanceId) || undefined)
const filter = ref('PENDING')
const loading = ref(false)
const processingIds = ref(new Set<number>())

async function load() {
  loading.value = true
  try {
    list.value = (await getAdminAcceptancesApi(filter.value)).data.data
  } finally {
    loading.value = false
  }
}

async function review(item: ProjectAcceptance, action: 'APPROVE' | 'REJECT') {
  if (processingIds.value.has(item.id)) return
  try {
    const title = action === 'APPROVE' ? '通过项目验收' : '驳回项目验收'
    const { value } = await ElMessageBox.prompt('填写审核意见', title, {
      inputType: 'textarea',
      inputPlaceholder:
        action === 'APPROVE' ? '确认项目交付满足验收标准' : '说明需要补充或修改的内容',
      ...(action === 'REJECT'
        ? { inputPattern: /\S+/, inputErrorMessage: '请填写驳回原因' }
        : {}),
    })
    processingIds.value.add(item.id)
    await reviewAcceptanceApi(item.id, { action, reviewNote: value || undefined })
    ElMessage.success('验收审核已完成')
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') throw error
  } finally {
    processingIds.value.delete(item.id)
  }
}

watch(filter, load)
onMounted(load)
</script>

<template>
  <PageHeader
    eyebrow="SYSTEM ADMIN"
    title="项目验收审核"
    description="复核项目最终交付，形成明确、可审计的验收结论。"
  >
    <el-button :icon="Refresh" circle @click="load" />
  </PageHeader>
  <section class="toolbar">
    <div>
      <h3>验收申请</h3>
      <p>共 {{ list.length }} 条记录</p>
    </div>
    <el-segmented
      v-model="filter"
      :options="[
        { label: '待审核', value: 'PENDING' },
        { label: '全部', value: 'ALL' },
        { label: '已通过', value: 'APPROVED' },
        { label: '已驳回', value: 'REJECTED' },
      ]"
    />
  </section>
  <section v-loading="loading" class="acceptance-grid">
    <article
      v-for="item in list"
      :key="item.id"
      class="surface-card acceptance-card"
      :class="{ focused: item.id === focusedAcceptanceId }"
    >
      <div class="card-top">
        <StatusTag :value="item.reviewStatus" /><span>第 {{ item.acceptanceNo }} 次验收</span>
      </div>
      <h3>{{ item.projectName }}</h3>
      <p>{{ item.submissionNote }}</p>
      <dl>
        <div>
          <dt>提交人</dt>
          <dd>{{ item.submitterName }}</dd>
        </div>
        <div>
          <dt>提交时间</dt>
          <dd>{{ formatDateTime(item.submittedAt) }}</dd>
        </div>
      </dl>
      <div v-if="item.reviewNote" class="review-note">
        <strong>审核意见</strong>{{ item.reviewNote }}
      </div>
      <footer v-if="item.reviewStatus === 'PENDING'">
        <el-button :disabled="processingIds.has(item.id)" @click="review(item, 'REJECT')">驳回</el-button
        ><el-button
          type="primary"
          :icon="Check"
          :loading="processingIds.has(item.id)"
          @click="review(item, 'APPROVE')"
          >通过验收</el-button
        >
      </footer>
    </article>
    <el-empty v-if="!loading && !list.length" description="当前没有验收申请" />
  </section>
</template>

<style scoped>
.acceptance-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}
.acceptance-card {
  padding: 24px;
}
.acceptance-card.focused {
  border-color: #91a8e8;
  box-shadow: 0 0 0 3px rgba(49, 91, 216, 0.1);
}
.card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--muted);
  font-size: 12px;
}
.acceptance-card h3 {
  margin: 18px 0 9px;
  color: var(--ink);
  font-size: 20px;
}
.acceptance-card > p {
  min-height: 45px;
  margin: 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.7;
}
.acceptance-card dl {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin: 20px 0;
}
.acceptance-card dl div {
  padding: 12px;
  border-radius: 10px;
  background: #f8f9fc;
}
.acceptance-card dt {
  color: #98a2b3;
  font-size: 11px;
}
.acceptance-card dd {
  margin: 5px 0 0;
  color: #475467;
  font-size: 12px;
}
.review-note {
  padding: 12px;
  border-left: 3px solid #91a6e7;
  background: #f7f9ff;
  color: #667085;
  font-size: 12px;
  line-height: 1.6;
}
.review-note strong {
  display: block;
  color: #344054;
}
.acceptance-card footer {
  display: flex;
  justify-content: flex-end;
  margin-top: 20px;
}
@media (max-width: 820px) {
  .acceptance-grid {
    grid-template-columns: 1fr;
  }
}
</style>
