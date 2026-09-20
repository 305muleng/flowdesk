<script setup lang="ts">
import { Refresh, Search, UserFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import {
  disableAdminUserApi,
  enableAdminUserApi,
  getAdminUsersApi,
  type AdminUser,
  type AdminUserQuery,
} from '@/api/adminUsers'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime, statusLabel } from '@/utils/format'

const auth = useAuthStore()
const users = ref<AdminUser[]>([])
const loading = ref(false)
const error = ref('')
const total = ref(0)
const pages = ref(0)
const processingIds = ref(new Set<number>())
const query = reactive<{
  page: number
  size: number
  keyword: string
  status: '' | 'ACTIVE' | 'DISABLED'
  systemRole: '' | 'USER' | 'SYSTEM_ADMIN'
}>({
  page: 1,
  size: 10,
  keyword: '',
  status: '',
  systemRole: '',
})

function requestParams(): AdminUserQuery {
  return {
    page: query.page,
    size: query.size,
    keyword: query.keyword.trim() || undefined,
    status: query.status || undefined,
    systemRole: query.systemRole || undefined,
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    let result = (await getAdminUsersApi(requestParams())).data.data
    if (!result.records.length && result.total > 0 && query.page > result.pages) {
      query.page = Math.max(1, result.pages)
      result = (await getAdminUsersApi(requestParams())).data.data
    }
    users.value = result.records
    total.value = result.total
    query.page = result.page
    query.size = result.size
    pages.value = result.pages
  } catch {
    error.value = '用户列表加载失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

function search() {
  query.page = 1
  load()
}

function reset() {
  query.keyword = ''
  query.status = ''
  query.systemRole = ''
  query.page = 1
  load()
}

function isCurrentUser(user: AdminUser) {
  return user.id === auth.user?.userId
}

async function changeStatus(user: AdminUser) {
  const disabling = user.status === 'ACTIVE'
  const action = disabling ? '禁用' : '恢复'
  await ElMessageBox.confirm(`确定${action}用户 ${user.username} 吗？`, `${action}用户`, {
    type: disabling ? 'warning' : 'info',
    confirmButtonText: `确认${action}`,
    cancelButtonText: '取消',
  })

  processingIds.value = new Set(processingIds.value).add(user.id)
  try {
    await (disabling ? disableAdminUserApi(user.id) : enableAdminUserApi(user.id))
    ElMessage.success(`用户已${action}`)
    await load()
  } finally {
    const next = new Set(processingIds.value)
    next.delete(user.id)
    processingIds.value = next
  }
}

function changePage(page: number) {
  query.page = page
  load()
}

function changeSize(size: number) {
  query.size = size
  query.page = 1
  load()
}

onMounted(load)
</script>

<template>
  <PageHeader
    eyebrow="SYSTEM ADMIN"
    title="用户管理"
    description="查询系统用户并管理账号可用状态。角色变更不在当前版本范围内。"
  >
    <el-button :icon="Refresh" circle :loading="loading" @click="load" />
  </PageHeader>

  <section class="surface-card filter-panel">
    <el-input
      v-model="query.keyword"
      clearable
      :prefix-icon="Search"
      placeholder="搜索用户名或姓名"
      @keyup.enter="search"
    />
    <el-select v-model="query.status" clearable placeholder="账号状态">
      <el-option label="正常" value="ACTIVE" />
      <el-option label="已禁用" value="DISABLED" />
    </el-select>
    <el-select v-model="query.systemRole" clearable placeholder="系统角色">
      <el-option label="普通用户" value="USER" />
      <el-option label="系统管理员" value="SYSTEM_ADMIN" />
    </el-select>
    <div class="filter-actions">
      <el-button type="primary" :icon="Search" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
    </div>
  </section>

  <el-alert
    v-if="error"
    class="load-error"
    :title="error"
    type="error"
    show-icon
    :closable="false"
  >
    <template #default><el-button link type="primary" @click="load">重新加载</el-button></template>
  </el-alert>

  <section class="table-card">
    <el-table v-loading="loading" :data="users" empty-text="暂无符合条件的用户">
      <el-table-column label="用户" min-width="190">
        <template #default="{ row }">
          <div class="user-cell">
            <span><el-icon><UserFilled /></el-icon></span>
            <div><strong>{{ row.realName || row.username }}</strong><small>@{{ row.username }}</small></div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="系统角色" min-width="130">
        <template #default="{ row }">
          <el-tag :type="row.systemRole === 'SYSTEM_ADMIN' ? 'primary' : 'info'" effect="light">
            {{ statusLabel(row.systemRole) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="账号状态" min-width="110">
        <template #default="{ row }"><StatusTag :value="row.status" /></template>
      </el-table-column>
      <el-table-column label="创建时间" min-width="180">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="更新时间" min-width="180">
        <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" fixed="right" width="110" align="right">
        <template #default="{ row }">
          <el-tooltip
            :disabled="!(row.status === 'ACTIVE' && isCurrentUser(row))"
            content="不能禁用当前账号"
            placement="top"
          >
            <span>
              <el-button
                :type="row.status === 'ACTIVE' ? 'danger' : 'success'"
                link
                :disabled="row.status === 'ACTIVE' && isCurrentUser(row)"
                :loading="processingIds.has(row.id)"
                @click="changeStatus(row)"
              >
                {{ row.status === 'ACTIVE' ? '禁用' : '恢复' }}
              </el-button>
            </span>
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>
    <footer class="pagination-row">
      <span>共 {{ total }} 位用户<span v-if="pages"> · {{ pages }} 页</span></span>
      <el-pagination
        background
        :current-page="query.page"
        :page-size="query.size"
        :page-sizes="[10, 20, 50]"
        layout="sizes, prev, pager, next"
        :total="total"
        @current-change="changePage"
        @size-change="changeSize"
      />
    </footer>
  </section>
</template>

<style scoped>
.filter-panel {
  display: grid;
  grid-template-columns: minmax(220px, 1.5fr) minmax(150px, 0.7fr) minmax(150px, 0.7fr) auto;
  gap: 12px;
  margin-bottom: 18px;
  padding: 18px;
}
.filter-actions {
  display: flex;
  gap: 8px;
}
.load-error {
  margin-bottom: 18px;
}
.user-cell {
  display: flex;
  align-items: center;
  gap: 11px;
}
.user-cell > span {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: #edf2ff;
  color: var(--brand);
}
.user-cell strong,
.user-cell small {
  display: block;
}
.user-cell strong {
  color: var(--ink);
  font-size: 13px;
}
.user-cell small {
  margin-top: 3px;
  color: var(--muted);
  font-size: 11px;
}
.pagination-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 17px 20px;
  border-top: 1px solid var(--line);
}
.pagination-row > span {
  color: var(--muted);
  font-size: 12px;
}
@media (max-width: 900px) {
  .filter-panel {
    grid-template-columns: 1fr 1fr;
  }
}
@media (max-width: 620px) {
  .filter-panel {
    grid-template-columns: 1fr;
  }
  .pagination-row {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
