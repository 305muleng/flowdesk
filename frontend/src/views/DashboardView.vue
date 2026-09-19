<script setup lang="ts">
import { CircleCheck, FolderOpened, List, Timer } from '@element-plus/icons-vue'
import { computed, onMounted, ref } from 'vue'
import type { EChartsCoreOption } from 'echarts/core'
import DashboardChart from '@/components/DashboardChart.vue'
import MetricCard from '@/components/MetricCard.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useAuthStore } from '@/stores/auth'
import { getMyProjectsApi, getProjectLogsApi } from '@/api/projects'
import { getMyTasksApi } from '@/api/tasks'
import { formatDateTime } from '@/utils/format'
import type { OperationLog, Project, Task } from '@/types/api'

const auth = useAuthStore()
const projects = ref<Project[]>([])
const tasks = ref<Task[]>([])
const activities = ref<OperationLog[]>([])
const loading = ref(true)
const greeting = computed(() => `${auth.displayName}，下午好`)
const activeProjects = computed(() => projects.value.filter((item) => ['PREPARING', 'IN_PROGRESS', 'PENDING_ACCEPTANCE'].includes(item.status)))
const openTasks = computed(() => tasks.value.filter((item) => !['DONE', 'CANCELLED'].includes(item.status)))
const doneTasks = computed(() => tasks.value.filter((item) => item.status === 'DONE'))
const dueSoon = computed(() => tasks.value.filter((item) => !['DONE', 'CANCELLED'].includes(item.status) && new Date(item.deadline).getTime() - Date.now() < 3 * 86400000).length)

const taskOption = computed<EChartsCoreOption>(() => ({
  tooltip: { trigger: 'item' },
  legend: { bottom: 0, icon: 'circle', itemWidth: 8, textStyle: { color: '#7d8799' } },
  series: [{
    type: 'pie', radius: ['58%', '78%'], center: ['50%', '43%'], avoidLabelOverlap: true,
    label: { show: false }, itemStyle: { borderColor: '#fff', borderWidth: 4, borderRadius: 7 },
    data: [
      { value: tasks.value.filter((item) => item.status === 'TODO').length, name: '待开始', itemStyle: { color: '#aab4c5' } },
      { value: tasks.value.filter((item) => item.status === 'IN_PROGRESS').length, name: '进行中', itemStyle: { color: '#315bd8' } },
      { value: tasks.value.filter((item) => item.status === 'REVIEW').length, name: '待审核', itemStyle: { color: '#f2a83b' } },
      { value: doneTasks.value.length, name: '已完成', itemStyle: { color: '#31a477' } },
    ],
  }],
}))

const projectOption = computed<EChartsCoreOption>(() => ({
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
  grid: { left: 10, right: 14, top: 10, bottom: 24, containLabel: true },
  xAxis: { type: 'value', max: 100, splitLine: { lineStyle: { color: '#edf0f5' } }, axisLabel: { color: '#9aa4b4' } },
  yAxis: { type: 'category', data: projects.value.slice(0, 5).map((item) => item.name), axisLine: { show: false }, axisTick: { show: false }, axisLabel: { color: '#667085', width: 90, overflow: 'truncate' } },
  series: [{ type: 'bar', barWidth: 12, data: projects.value.slice(0, 5).map((item) => ({ value: item.status === 'COMPLETED' || item.status === 'ARCHIVED' ? 100 : item.status === 'PENDING_ACCEPTANCE' ? 90 : item.status === 'IN_PROGRESS' ? 58 : 18, itemStyle: { color: '#315bd8', borderRadius: 8 } })) }],
}))

async function load() {
  loading.value = true
  try {
    await auth.verify()
    const [projectResponse, taskResponse] = await Promise.all([getMyProjectsApi(), getMyTasksApi()])
    projects.value = projectResponse.data.data
    tasks.value = taskResponse.data.data
    const logResults = await Promise.allSettled(
      projects.value.slice(0, 4).map((project) =>
        getProjectLogsApi(project.id, 5).then((response) => response.data.data),
      ),
    )
    activities.value = logResults
      .filter((result): result is PromiseFulfilledResult<OperationLog[]> => result.status === 'fulfilled')
      .flatMap((result) => result.value)
      .sort((a, b) => +new Date(b.createdAt) - +new Date(a.createdAt))
      .slice(0, 7)
  } finally { loading.value = false }
}

onMounted(load)
</script>

<template>
  <section v-loading="loading">
    <section class="welcome">
      <div><p>WELCOME TO YOUR WORKSPACE</p><h1>{{ greeting }}</h1><span>聚焦最重要的项目、任务与交付节点。</span></div>
      <el-button type="primary" plain :icon="FolderOpened" @click="$router.push('/projects')">查看全部项目</el-button>
    </section>
    <section class="metrics">
      <MetricCard label="活跃项目" :value="activeProjects.length" note="正在推进或等待验收" tone="blue"><FolderOpened /></MetricCard>
      <MetricCard label="我的待办" :value="openTasks.length" :note="`${dueSoon} 项三天内到期`" tone="amber"><List /></MetricCard>
      <MetricCard label="已完成任务" :value="doneTasks.length" note="累计交付并通过审核" tone="green"><CircleCheck /></MetricCard>
      <MetricCard label="待验收项目" :value="projects.filter((item) => item.status === 'PENDING_ACCEPTANCE').length" note="等待系统管理员确认" tone="violet"><Timer /></MetricCard>
    </section>
    <section class="dashboard-grid">
      <article class="surface-card chart-panel"><div class="panel-head"><div><h3>任务状态分布</h3><p>当前分配给你的任务</p></div><span>{{ tasks.length }} 项</span></div><DashboardChart :option="taskOption" /></article>
      <article class="surface-card chart-panel"><div class="panel-head"><div><h3>项目推进概览</h3><p>最近更新的五个项目</p></div></div><DashboardChart :option="projectOption" /></article>
    </section>
    <section class="lower-grid">
      <article class="surface-card list-panel">
        <div class="panel-head"><div><h3>优先处理</h3><p>按截止时间排列的未完成任务</p></div><el-button link type="primary" @click="$router.push('/tasks')">查看全部</el-button></div>
        <button v-for="task in openTasks.slice().sort((a,b)=>+new Date(a.deadline)-+new Date(b.deadline)).slice(0,5)" :key="task.id" class="task-row" @click="$router.push(`/tasks/${task.id}`)"><span class="task-dot" /><div><strong>{{ task.title }}</strong><small>{{ new Date(task.deadline).toLocaleDateString('zh-CN') }} 截止</small></div><StatusTag :value="task.status" /></button>
        <el-empty v-if="!openTasks.length" description="当前没有待处理任务" :image-size="72" />
      </article>
      <article class="surface-card list-panel">
        <div class="panel-head"><div><h3>近期动态</h3><p>你参与项目的关键操作</p></div><el-button link type="primary" @click="$router.push('/activity')">查看全部</el-button></div>
        <div v-for="activity in activities" :key="activity.id" class="activity-row"><span /><div><strong>{{ activity.actorName }} · {{ activity.description }}</strong><small>{{ formatDateTime(activity.createdAt) }}</small></div></div>
        <el-empty v-if="!activities.length" description="还没有项目动态" :image-size="72" />
      </article>
    </section>
  </section>
</template>

<style scoped>
.welcome{display:flex;justify-content:space-between;gap:24px;align-items:center;margin-bottom:20px;padding:28px 30px;border:1px solid #dfe6f6;border-radius:18px;background:#fff url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='260' height='140' viewBox='0 0 260 140'%3E%3Cpath d='M180 10c-60 30-20 100-100 130M230 0c-70 40-20 105-100 140' fill='none' stroke='%23e8edfb' stroke-width='20'/%3E%3C/svg%3E") no-repeat right center}.welcome p{margin:0 0 7px;color:var(--brand);font-size:10px;font-weight:800;letter-spacing:.14em}.welcome h1{margin:0 0 8px;color:var(--ink);font-size:28px;letter-spacing:-.04em}.welcome span{color:var(--muted);font-size:14px}.metrics{display:grid;grid-template-columns:repeat(4,1fr);gap:15px}.dashboard-grid,.lower-grid{display:grid;grid-template-columns:1fr 1fr;gap:16px;margin-top:16px}.chart-panel,.list-panel{padding:22px}.panel-head{display:flex;align-items:center;justify-content:space-between;gap:16px}.panel-head h3{margin:0;color:var(--ink);font-size:16px}.panel-head p{margin:6px 0 0;color:var(--muted);font-size:12px}.panel-head>span{color:var(--brand);font-size:13px;font-weight:700}.task-row{width:100%;display:flex;align-items:center;gap:12px;padding:14px 2px;border:0;border-bottom:1px solid #f0f2f6;background:transparent;text-align:left;cursor:pointer}.task-row:hover strong{color:var(--brand)}.task-row>div{min-width:0;flex:1}.task-row strong,.activity-row strong{display:block;overflow:hidden;color:#344054;font-size:13px;text-overflow:ellipsis;white-space:nowrap}.task-row small,.activity-row small{display:block;margin-top:5px;color:#98a2b3;font-size:11px}.task-dot{width:8px;height:8px;border-radius:50%;background:var(--brand);box-shadow:0 0 0 4px #edf2ff}.activity-row{position:relative;display:flex;gap:12px;padding:13px 2px}.activity-row>span{width:9px;height:9px;margin-top:3px;border:2px solid #6a87df;border-radius:50%;background:#fff}.activity-row>div{min-width:0;flex:1}.activity-row:not(:last-child):after{content:'';position:absolute;left:5px;top:25px;bottom:-3px;width:1px;background:#e5e9f1}@media(max-width:1100px){.metrics{grid-template-columns:repeat(2,1fr)}}@media(max-width:840px){.dashboard-grid,.lower-grid{grid-template-columns:1fr}.welcome{align-items:flex-start;flex-direction:column}}@media(max-width:560px){.metrics{grid-template-columns:1fr}}
</style>
