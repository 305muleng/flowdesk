<script setup lang="ts">
import { Bell, Check, DataAnalysis, Document, FolderOpened, List, SwitchButton, Timer, User, UserFilled } from '@element-plus/icons-vue'
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'
import { statusLabel } from '@/utils/format'

const router = useRouter()
const auth = useAuthStore()
const notifications = useNotificationStore()
function logout() { notifications.reset(); auth.logout(); router.replace('/login') }
function handleAccountCommand(command: string) {
  if (command === 'profile') router.push('/profile')
  if (command === 'logout') logout()
}
onMounted(() => {
  notifications.reset()
  notifications.loadUnreadCount().catch(() => undefined)
})
</script>

<template>
  <el-container class="app-shell">
    <el-aside width="252px" class="sidebar">
      <RouterLink class="logo" to="/dashboard"><span>F</span><div>FlowDesk<small>TEAM WORKSPACE</small></div></RouterLink>
      <p class="nav-label">工作空间</p>
      <el-menu router :default-active="$route.path" class="nav-menu">
        <el-menu-item index="/dashboard"><el-icon><DataAnalysis /></el-icon><span>工作台</span></el-menu-item>
        <el-menu-item index="/projects"><el-icon><FolderOpened /></el-icon><span>项目中心</span></el-menu-item>
        <el-menu-item index="/tasks"><el-icon><List /></el-icon><span>我的任务</span></el-menu-item>
        <el-menu-item index="/task-requests"><el-icon><Document /></el-icon><span>任务申请</span></el-menu-item>
        <el-menu-item v-if="!auth.isSystemAdmin" index="/invitations"><el-icon><Bell /></el-icon><span>项目邀请</span></el-menu-item>
        <el-menu-item index="/activity"><el-icon><Timer /></el-icon><span>项目动态</span></el-menu-item>
      </el-menu>
      <template v-if="auth.isSystemAdmin">
        <p class="nav-label admin-label">系统管理</p>
        <el-menu router :default-active="$route.path" class="nav-menu">
          <el-menu-item index="/admin/acceptances"><el-icon><Check /></el-icon><span>验收审核</span></el-menu-item>
          <el-menu-item index="/admin/users"><el-icon><UserFilled /></el-icon><span>用户管理</span></el-menu-item>
        </el-menu>
      </template>
      <div class="sidebar-bottom">
        <RouterLink to="/profile" class="avatar" title="个人资料">{{ auth.displayName.slice(0, 1) }}</RouterLink>
        <div class="profile"><strong>{{ auth.displayName }}</strong><small>{{ statusLabel(auth.user?.systemRole) }}</small></div>
        <el-button text :icon="SwitchButton" title="退出登录" @click="logout" />
      </div>
    </el-aside>
    <el-container class="content-shell">
      <el-header class="topbar">
        <div><p>FLOWDESK WORKSPACE</p><h2>{{ ($route.meta.title as string) || '工作台' }}</h2></div>
        <div class="topbar-actions">
          <el-tooltip content="通知中心" placement="bottom">
            <el-badge :value="notifications.unreadCount" :hidden="notifications.unreadCount === 0" :max="99">
              <el-button class="notification-trigger" :icon="Bell" circle aria-label="通知中心" @click="router.push('/notifications')" />
            </el-badge>
          </el-tooltip>
        <el-dropdown trigger="click" placement="bottom-end" @command="handleAccountCommand">
          <button class="account-trigger">
            <span class="session-dot" />
            <span class="account-copy"><strong>{{ auth.displayName }}</strong><small>{{ statusLabel(auth.user?.systemRole) }}</small></span>
            <span class="top-avatar">{{ auth.displayName.slice(0, 1) }}</span>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="profile" :icon="User">个人资料</el-dropdown-item>
              <el-dropdown-item command="logout" :icon="SwitchButton" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        </div>
      </el-header>
      <el-main class="main-content"><RouterView /></el-main>
    </el-container>
  </el-container>
</template>
<style scoped>
.app-shell{min-height:100vh;background:var(--surface)}.sidebar{position:sticky;top:0;height:100vh;background:#fff;border-right:1px solid var(--line);display:flex;flex-direction:column;padding:25px 15px;box-sizing:border-box}.logo{display:flex;align-items:center;gap:11px;margin:0 10px 32px;color:var(--ink);font-size:19px;font-weight:800;text-decoration:none;letter-spacing:-.02em}.logo span,.avatar{width:36px;height:36px;flex:0 0 36px;border-radius:11px;display:grid;place-items:center;background:var(--brand);color:#fff;font-weight:800}.logo small{display:block;margin-top:3px;color:#a1a9b8;font-size:8px;letter-spacing:.16em}.nav-label{margin:0 13px 8px;color:#a4adbd;font-size:10px;font-weight:800;letter-spacing:.14em;text-transform:uppercase}.admin-label{margin-top:24px}.nav-menu{border-right:0}.nav-menu :deep(.el-menu-item){height:46px;margin:4px 0;border-radius:10px;color:#69748a}.nav-menu :deep(.el-menu-item:hover){background:#f7f8fc}.nav-menu :deep(.el-menu-item.is-active){background:#eef3ff;color:var(--brand);font-weight:700}.sidebar-bottom{margin-top:auto;padding:16px 7px 0;border-top:1px solid var(--line);display:flex;gap:10px;align-items:center}.profile{min-width:0}.profile strong{display:block;overflow:hidden;color:var(--ink);font-size:13px;text-overflow:ellipsis;white-space:nowrap}.profile small{display:block;color:#9aa3b5;margin-top:4px;font-size:11px}.sidebar-bottom .el-button{margin-left:auto}.content-shell{min-width:0}.topbar{height:auto;padding:27px 42px 20px;display:flex;align-items:center;justify-content:space-between;background:var(--surface)}.topbar p{margin:0 0 5px;font-size:10px;font-weight:800;letter-spacing:.16em;color:#929db3}.topbar h2{margin:0;font-size:25px;color:var(--ink);letter-spacing:-.035em}.session{display:flex;align-items:center;gap:8px;color:#708077;font-size:12px}.session span{width:7px;height:7px;border-radius:50%;background:#35a66f;box-shadow:0 0 0 4px #e3f6ed}.main-content{padding:0 42px 42px}@media(max-width:760px){.sidebar{width:70px!important;padding:20px 8px}.logo{margin:0 auto 30px;font-size:0}.logo div,.nav-label,.nav-menu span,.sidebar-bottom .profile,.sidebar-bottom .el-button{display:none}.nav-menu :deep(.el-menu-item){justify-content:center;padding:0!important}.topbar,.main-content{padding-left:20px;padding-right:20px}.session{display:none}}
.avatar{border:0;cursor:pointer;text-decoration:none}.account-trigger{display:flex;align-items:center;gap:10px;padding:6px 7px 6px 11px;border:1px solid var(--border);border-radius:13px;background:#fff;cursor:pointer;transition:.2s}.account-trigger:hover{border-color:#bccaea;box-shadow:var(--shadow-sm)}.session-dot{width:7px;height:7px;border-radius:50%;background:#35a66f;box-shadow:0 0 0 4px #e3f6ed}.account-copy{display:grid;gap:2px;text-align:right}.account-copy strong{color:var(--ink);font-size:12px}.account-copy small{color:var(--muted);font-size:10px}.top-avatar{display:grid;place-items:center;width:34px;height:34px;border-radius:10px;background:var(--brand);color:#fff;font-weight:800}@media(max-width:760px){.account-copy,.session-dot{display:none}}
.topbar-actions{display:flex;align-items:center;gap:14px}.notification-trigger{width:40px;height:40px;border-color:var(--border);background:#fff;color:#63708a}.notification-trigger:hover{border-color:#bccaea;color:var(--brand)}
</style>
