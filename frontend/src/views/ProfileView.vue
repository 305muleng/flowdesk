<script setup lang="ts">
import { FolderOpened, List, Refresh, Timer, UserFilled } from '@element-plus/icons-vue'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getCurrentUserApi } from '@/api/auth'
import { getMyProjectsApi, getProjectMembersApi } from '@/api/projects'
import { getMyTasksApi } from '@/api/tasks'
import MetricCard from '@/components/MetricCard.vue'
import StatusTag from '@/components/StatusTag.vue'
import type { Project, ProjectRole, Task, UserProfile } from '@/types/api'
import { formatDate, formatDateTime, statusLabel } from '@/utils/format'

const router = useRouter()
const profile = ref<UserProfile>()
const projects = ref<Project[]>([])
const tasks = ref<Task[]>([])
const projectRoles = ref(new Map<number, ProjectRole>())
const loading = ref(false)
const loadFailed = ref(false)

const displayName = computed(() => profile.value?.realName || profile.value?.username || 'FlowDesk 用户')
const inProgressTasks = computed(() => tasks.value.filter((task) => task.status === 'IN_PROGRESS').length)
const pendingTasks = computed(() => tasks.value.filter((task) => task.status === 'TODO').length)
const recentTasks = computed(() => tasks.value.slice().sort((a, b) => +new Date(b.updatedAt) - +new Date(a.updatedAt)).slice(0, 5))
const recentProjects = computed(() => projects.value.slice(0, 5))
const projectNames = computed(() => new Map(projects.value.map((project) => [project.id, project.name])))

async function load() {
  loading.value = true
  loadFailed.value = false
  try {
    const [profileResponse, projectResponse, taskResponse] = await Promise.all([
      getCurrentUserApi(),
      getMyProjectsApi(),
      getMyTasksApi(),
    ])
    profile.value = profileResponse.data.data
    projects.value = projectResponse.data.data
    tasks.value = taskResponse.data.data

    const membershipResults = await Promise.allSettled(
      projects.value.slice(0, 5).map((project) =>
        getProjectMembersApi(project.id).then((response) => ({ projectId: project.id, members: response.data.data })),
      ),
    )
    const roles = new Map<number, ProjectRole>()
    membershipResults.forEach((result) => {
      if (result.status !== 'fulfilled') return
      const membership = result.value.members.find((member) => member.userId === profile.value?.id)
      if (membership) roles.set(result.value.projectId, membership.role)
    })
    projectRoles.value = roles
  } catch {
    loadFailed.value = true
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section v-loading="loading" class="profile-page">
    <el-result v-if="loadFailed && !profile" icon="error" title="个人资料加载失败" sub-title="请检查网络连接后重试">
      <template #extra><el-button type="primary" :icon="Refresh" @click="load">重新加载</el-button></template>
    </el-result>

    <template v-else-if="profile">
      <header class="profile-hero">
        <div class="profile-avatar">{{ displayName.slice(0, 1) }}</div>
        <div class="identity">
          <p>FLOWDESK ACCOUNT</p>
          <h1>{{ displayName }}</h1>
          <span>@{{ profile.username }}</span>
          <div class="identity-tags"><StatusTag :value="profile.systemRole" /><StatusTag :value="profile.status" /></div>
        </div>
        <div class="account-note"><span />账号会话正常</div>
      </header>

      <section class="metrics">
        <MetricCard label="参与项目" :value="projects.length" note="当前有效加入的项目" tone="blue"><FolderOpened /></MetricCard>
        <MetricCard label="我的任务" :value="tasks.length" note="当前分配给我的全部任务" tone="violet"><List /></MetricCard>
        <MetricCard label="进行中" :value="inProgressTasks" note="正在执行的任务" tone="green"><Timer /></MetricCard>
        <MetricCard label="待我处理" :value="pendingTasks" note="尚未开始的任务" tone="amber"><UserFilled /></MetricCard>
      </section>

      <section class="profile-grid">
        <article class="surface-card info-card">
          <div class="section-head"><div><p>ACCOUNT DETAILS</p><h2>基本信息</h2></div><span>只读</span></div>
          <dl>
            <div><dt>用户 ID</dt><dd>#{{ profile.id }}</dd></div>
            <div><dt>用户名</dt><dd>{{ profile.username }}</dd></div>
            <div><dt>姓名</dt><dd>{{ profile.realName }}</dd></div>
            <div><dt>系统角色</dt><dd>{{ statusLabel(profile.systemRole) }}</dd></div>
            <div><dt>账号状态</dt><dd>{{ statusLabel(profile.status) }}</dd></div>
            <div><dt>注册时间</dt><dd>{{ formatDateTime(profile.createdAt) }}</dd></div>
          </dl>
          <p class="security-copy">身份和账号状态由系统统一管理，普通用户无法自行修改。</p>
        </article>

        <article class="surface-card list-card">
          <div class="section-head"><div><p>PROJECTS</p><h2>参与的项目</h2></div><el-button link type="primary" @click="router.push('/projects')">查看全部</el-button></div>
          <el-empty v-if="!recentProjects.length" description="暂未参与项目" :image-size="70" />
          <button v-for="project in recentProjects" :key="project.id" class="list-row" @click="router.push('/projects/' + project.id)">
            <span class="project-mark">{{ project.name.slice(0, 1) }}</span>
            <span class="row-main"><strong>{{ project.name }}</strong><small>{{ statusLabel(projectRoles.get(project.id)) }} · 更新于 {{ formatDate(project.updatedAt) }}</small></span>
            <StatusTag :value="project.status" />
          </button>
        </article>
      </section>

      <article class="surface-card list-card tasks-card">
        <div class="section-head"><div><p>RECENT TASKS</p><h2>最近任务</h2></div><el-button link type="primary" @click="router.push('/tasks')">查看全部</el-button></div>
        <el-empty v-if="!recentTasks.length" description="当前没有分配给你的任务" :image-size="70" />
        <button v-for="task in recentTasks" :key="task.id" class="task-row" @click="router.push('/tasks/' + task.id)">
          <span class="row-main"><strong>{{ task.title }}</strong><small>{{ projectNames.get(task.projectId) || '项目 #' + task.projectId }} · {{ formatDateTime(task.updatedAt) }}</small></span>
          <span class="assignee">{{ task.assigneeName || '待分配' }}</span>
          <StatusTag :value="task.status" />
        </button>
      </article>
    </template>
  </section>
</template>

<style scoped>
.profile-page{display:grid;gap:18px}.profile-hero{display:flex;align-items:center;gap:22px;padding:30px;background:#fff;border:1px solid var(--border);border-radius:20px;box-shadow:var(--shadow-sm)}.profile-avatar{display:grid;place-items:center;width:86px;height:86px;flex:0 0 86px;border-radius:24px;background:var(--brand);color:#fff;font-size:34px;font-weight:800;box-shadow:0 12px 28px rgba(49,91,216,.2)}.identity{min-width:0}.identity>p,.section-head p{margin:0 0 7px;color:var(--brand);font-size:10px;font-weight:800;letter-spacing:.14em}.identity h1{margin:0;color:var(--ink);font-size:29px;letter-spacing:-.04em}.identity>span{display:block;margin-top:5px;color:var(--muted);font-size:13px}.identity-tags{display:flex;gap:8px;margin-top:13px}.account-note{display:flex;align-items:center;gap:9px;margin-left:auto;padding:10px 13px;border-radius:99px;background:#eff9f4;color:#267555;font-size:12px}.account-note span{width:7px;height:7px;border-radius:50%;background:#31a477}.metrics{display:grid;grid-template-columns:repeat(4,1fr);gap:15px}.profile-grid{display:grid;grid-template-columns:.85fr 1.15fr;gap:18px}.info-card,.list-card{padding:24px}.section-head{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:18px}.section-head h2{margin:0;color:var(--ink);font-size:18px}.section-head>span{padding:5px 9px;border-radius:99px;background:#f1f4f9;color:var(--muted);font-size:10px}dl{margin:0}dl>div{display:grid;grid-template-columns:105px 1fr;gap:15px;padding:13px 0;border-bottom:1px solid var(--line)}dt{color:var(--muted);font-size:12px}dd{margin:0;color:var(--text);font-size:13px;font-weight:600}.security-copy{margin:18px 0 0;padding:12px 14px;border-radius:11px;background:#f7f9fd;color:var(--muted);font-size:11px;line-height:1.6}.list-row,.task-row{width:100%;display:flex;align-items:center;gap:12px;padding:14px 2px;border:0;border-bottom:1px solid var(--line);background:transparent;text-align:left;cursor:pointer}.list-row:hover strong,.task-row:hover strong{color:var(--brand)}.project-mark{display:grid;place-items:center;width:36px;height:36px;flex:0 0 36px;border-radius:10px;background:#edf2ff;color:var(--brand);font-weight:800}.row-main{min-width:0;flex:1}.row-main strong,.row-main small{display:block}.row-main strong{overflow:hidden;color:var(--text);font-size:13px;text-overflow:ellipsis;white-space:nowrap}.row-main small{margin-top:5px;color:var(--muted);font-size:11px}.assignee{color:var(--muted);font-size:12px}.tasks-card{margin-bottom:8px}@media(max-width:1100px){.metrics{grid-template-columns:repeat(2,1fr)}.profile-grid{grid-template-columns:1fr}}@media(max-width:680px){.profile-hero{align-items:flex-start;flex-wrap:wrap;padding:22px}.profile-avatar{width:64px;height:64px;flex-basis:64px;border-radius:18px;font-size:25px}.account-note{width:100%;margin-left:0}.metrics{grid-template-columns:1fr}.info-card,.list-card{padding:18px}.assignee{display:none}}
</style>
