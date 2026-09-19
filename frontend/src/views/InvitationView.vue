<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  acceptInvitation,
  getInvitations,
  rejectInvitation,
  type Invitation,
} from '@/api/invitations'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { formatDateTime } from '@/utils/format'

const router = useRouter()
const list = ref<Invitation[]>([])
const loading = ref(false)
async function load() {
  loading.value = true
  try {
    list.value = (await getInvitations()).data.data
  } finally {
    loading.value = false
  }
}
async function respond(item: Invitation, accepted: boolean) {
  await (accepted ? acceptInvitation(item.id) : rejectInvitation(item.id))
  ElMessage.success(accepted ? '已加入项目' : '已拒绝邀请')
  await load()
  if (accepted) await router.push('/projects/' + item.projectId)
}
onMounted(load)
</script>

<template>
  <PageHeader title="项目邀请" description="处理收到的协作邀请，加入团队后即可查看项目内容。">
    <template #actions><el-button @click="load">刷新</el-button></template>
  </PageHeader>
  <section v-loading="loading" class="invite-list">
    <el-empty v-if="!loading && !list.length" description="目前没有项目邀请" />
    <article v-for="item in list" :key="item.id" class="invite-card">
      <div class="project-icon">{{ item.projectName.slice(0, 1) }}</div>
      <div class="invite-main">
        <div>
          <h3>{{ item.projectName }}</h3>
          <StatusTag :value="item.expired ? 'CANCELLED' : item.status" />
        </div>
        <p>
          <strong>{{ item.inviterName }}</strong> 邀请你加入项目协作
        </p>
        <small>有效期至 {{ formatDateTime(item.expiresAt) }}</small>
      </div>
      <div v-if="item.status === 'PENDING' && !item.expired" class="actions">
        <el-button @click="respond(item, false)">拒绝</el-button
        ><el-button type="primary" @click="respond(item, true)">接受邀请</el-button>
      </div>
      <span v-else class="handled">{{ item.expired ? '邀请已过期' : '已处理' }}</span>
    </article>
  </section>
</template>

<style scoped>
.invite-list {
  display: grid;
  gap: 12px;
  min-height: 260px;
}
.invite-list:deep(.el-empty) {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 18px;
}
.invite-card {
  display: grid;
  grid-template-columns: 52px 1fr auto;
  gap: 18px;
  align-items: center;
  padding: 22px 24px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 17px;
}
.project-icon {
  display: grid;
  place-items: center;
  width: 52px;
  height: 52px;
  border-radius: 15px;
  background: linear-gradient(145deg, #e7efff, #f3f6ff);
  color: var(--primary);
  font-weight: 800;
  font-size: 20px;
}
.invite-main > div {
  display: flex;
  align-items: center;
  gap: 10px;
}
.invite-main h3 {
  margin: 0;
  color: var(--text-strong);
}
.invite-main p {
  margin: 8px 0 5px;
  color: var(--text);
}
.invite-main small,
.handled {
  color: var(--text-muted);
}
.actions {
  display: flex;
  gap: 8px;
}
@media (max-width: 700px) {
  .invite-card {
    grid-template-columns: 44px 1fr;
  }
  .project-icon {
    width: 44px;
    height: 44px;
  }
  .actions,
  .handled {
    grid-column: 1/-1;
    justify-self: end;
  }
}
</style>
