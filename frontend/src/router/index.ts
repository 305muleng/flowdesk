import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', redirect: '/dashboard' },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { guestOnly: true },
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/views/RegisterView.vue'),
      meta: { guestOnly: true },
    },
    {
      path: '/',
      component: () => import('@/layouts/AppLayout.vue'),
      meta: { requiresAuth: true },
      children: [
        {
          path: 'dashboard',
          name: 'dashboard',
          component: () => import('@/views/DashboardView.vue'),
          meta: { title: '工作台' },
        },
        {
          path: 'profile',
          name: 'profile',
          component: () => import('@/views/ProfileView.vue'),
          meta: { title: '个人资料' },
        },
        {
          path: 'projects',
          name: 'projects',
          component: () => import('@/views/ProjectListView.vue'),
          meta: { title: '项目中心', ordinaryUser: true },
        },
        {
          path: 'projects/:projectId',
          name: 'project-detail',
          component: () => import('@/views/ProjectDetailView.vue'),
          meta: { title: '项目详情', ordinaryUser: true },
        },
        {
          path: 'tasks/:taskId',
          name: 'task-detail',
          component: () => import('@/views/TaskDetailView.vue'),
          meta: { title: '任务详情', ordinaryUser: true },
        },
        {
          path: 'invitations',
          name: 'invitations',
          component: () => import('@/views/InvitationView.vue'),
          meta: { title: '项目邀请', ordinaryUser: true },
        },
        {
          path: 'notifications',
          name: 'notifications',
          component: () => import('@/views/NotificationsView.vue'),
          meta: { title: '通知中心' },
        },
        {
          path: 'tasks',
          name: 'tasks',
          component: () => import('@/views/MyTasksView.vue'),
          meta: { title: '我的任务', ordinaryUser: true },
        },
        {
          path: 'task-requests',
          name: 'task-requests',
          component: () => import('@/views/TaskRequestsView.vue'),
          meta: { title: '任务申请', ordinaryUser: true },
        },
        {
          path: 'activity',
          name: 'activity',
          component: () => import('@/views/ActivityView.vue'),
          meta: { title: '项目动态', ordinaryUser: true },
        },
        {
          path: 'admin/acceptances',
          name: 'admin-acceptances',
          component: () => import('@/views/AdminAcceptancesView.vue'),
          meta: { title: '验收审核', systemAdmin: true },
        },
        {
          path: 'admin/users',
          name: 'admin-users',
          component: () => import('@/views/AdminUsersView.vue'),
          meta: { title: '用户管理', systemAdmin: true },
        },
        { path: 'operations', redirect: '/activity' },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
  ],
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.requiresAuth && !auth.token)
    return { name: 'login', query: { redirect: to.fullPath } }
  if (to.meta.guestOnly && auth.token) return { name: 'dashboard' }
  if (to.meta.systemAdmin && !auth.isSystemAdmin) return { name: 'dashboard' }
  if (to.meta.ordinaryUser && auth.isSystemAdmin) return { name: 'admin-acceptances' }
  return true
})

export default router
