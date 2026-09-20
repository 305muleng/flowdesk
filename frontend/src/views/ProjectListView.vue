<script setup lang="ts">
import { Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createProjectApi, getMyProjectsApi } from '@/api/projects'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useAuthStore } from '@/stores/auth'
import type { CreateProjectPayload, Project } from '@/types/api'
import { formatDate } from '@/utils/format'

const router = useRouter()
const auth = useAuthStore()
const projects = ref<Project[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const formRef = ref<FormInstance>()
const saving = ref(false)
const form = ref<CreateProjectPayload>({ name: '', description: '', goal: '', expectedEndTime: '' })
const rules: FormRules<CreateProjectPayload> = {
  name: [{ required: true, message: '请输入项目名称', trigger: 'blur' }],
}

async function load() {
  loading.value = true
  try {
    projects.value = (await getMyProjectsApi()).data.data
  } finally {
    loading.value = false
  }
}
function openCreate() {
  form.value = { name: '', description: '', goal: '', expectedEndTime: '' }
  dialogVisible.value = true
}
async function create() {
  await formRef.value?.validate()
  saving.value = true
  try {
    await createProjectApi({
      ...form.value,
      expectedEndTime: form.value.expectedEndTime || undefined,
    })
    ElMessage.success('项目创建成功')
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}
onMounted(load)
</script>

<template>
  <PageHeader title="全部项目" description="查看你参与的项目，并快速进入协作空间。">
    <template #actions
      ><el-button :icon="Refresh" circle @click="load" /><el-button
        v-if="!auth.isSystemAdmin"
        type="primary"
        :icon="Plus"
        @click="openCreate"
        >新建项目</el-button
      ></template
    >
  </PageHeader>
  <section v-loading="loading" class="project-grid">
    <el-empty v-if="!loading && projects.length === 0" description="还没有参与任何项目"
      ><el-button v-if="!auth.isSystemAdmin" type="primary" @click="openCreate">创建第一个项目</el-button></el-empty
    >
    <article
      v-for="project in projects"
      :key="project.id"
      class="project-card"
      @click="router.push('/projects/' + project.id)"
    >
      <div class="card-top">
        <StatusTag :value="project.status" /><span>#{{ project.id }}</span>
      </div>
      <h3>{{ project.name }}</h3>
      <p>{{ project.description || project.goal || '暂未填写项目描述' }}</p>
      <div class="project-goal">
        <span>项目目标</span><strong>{{ project.goal || '等待补充' }}</strong>
      </div>
      <footer>
        <span>更新于 {{ formatDate(project.updatedAt) }}</span
        ><el-button text type="primary">查看详情 →</el-button>
      </footer>
    </article>
  </section>
  <el-dialog v-model="dialogVisible" title="新建项目" width="min(92vw, 560px)" destroy-on-close>
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
      <el-form-item label="项目名称" prop="name"
        ><el-input
          v-model="form.name"
          maxlength="100"
          show-word-limit
          placeholder="例如：FlowDesk 前端 V1"
      /></el-form-item>
      <el-form-item label="项目简介"
        ><el-input
          v-model="form.description"
          type="textarea"
          :rows="3"
          placeholder="说明项目背景或范围"
      /></el-form-item>
      <el-form-item label="项目目标"
        ><el-input v-model="form.goal" placeholder="希望达成的结果"
      /></el-form-item>
      <el-form-item label="预计完成时间"
        ><el-date-picker
          v-model="form.expectedEndTime"
          type="datetime"
          value-format="YYYY-MM-DDTHH:mm:ss"
          placeholder="可选"
          style="width: 100%"
      /></el-form-item>
    </el-form>
    <template #footer
      ><el-button @click="dialogVisible = false">取消</el-button
      ><el-button type="primary" :loading="saving" @click="create">创建项目</el-button></template
    >
  </el-dialog>
</template>

<style scoped>
.project-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(290px, 1fr));
  gap: 22px;
  min-height: 280px;
}
.project-grid:deep(.el-empty) {
  grid-column: 1/-1;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 20px;
}
.project-card {
  min-height: 260px;
  padding: 22px;
  background: #fff;
  border: 1px solid #dce3ee;
  border-radius: 18px;
  box-shadow: 0 5px 18px rgba(31, 48, 82, 0.055);
  display: flex;
  flex-direction: column;
  cursor: pointer;
  transition: 0.2s;
}
.project-card:hover {
  transform: translateY(-4px);
  border-color: #b8c8ec;
  box-shadow: 0 16px 36px rgba(29, 47, 91, 0.11);
}
.card-top,
footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.card-top > span {
  color: #aab2c2;
  font-size: 12px;
}
.project-card h3 {
  margin: 22px 0 10px;
  color: var(--text-strong);
  font-size: 19px;
}
.project-card > p {
  margin: 0;
  color: var(--text-muted);
  font-size: 14px;
  line-height: 1.7;
}
.project-goal {
  display: grid;
  gap: 7px;
  margin-top: 20px;
  padding: 13px 14px;
  background: #f7f9fd;
  border-radius: 11px;
}
.project-goal span {
  color: var(--text-muted);
  font-size: 11px;
}
.project-goal strong {
  font-size: 13px;
  color: var(--text);
}
footer {
  margin-top: auto;
  padding-top: 20px;
  color: var(--text-muted);
  font-size: 12px;
}
</style>
