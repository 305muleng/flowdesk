import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { getCurrentUserApi, loginApi } from '@/api/auth'
import type { LoginPayload, LoginUser } from '@/types/api'
const TOKEN_KEY = 'flowdesk_token'
const USER_KEY = 'flowdesk_user'

function savedUser(): LoginUser | null {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null') as LoginUser | null
  } catch {
    localStorage.removeItem(USER_KEY)
    return null
  }
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')
  const user = ref<LoginUser | null>(savedUser())
  const displayName = computed(
    () => user.value?.realName || user.value?.username || 'FlowDesk 用户',
  )
  const isSystemAdmin = computed(() => user.value?.systemRole === 'SYSTEM_ADMIN')
  async function login(payload: LoginPayload) {
    const { data } = await loginApi(payload)
    token.value = data.data.token
    user.value = data.data
    localStorage.setItem(TOKEN_KEY, token.value)
    localStorage.setItem(USER_KEY, JSON.stringify(user.value))
  }
  async function verify() {
    if (!token.value) return false
    const { data } = await getCurrentUserApi()
    if (user.value) {
      user.value = {
        ...user.value,
        userId: data.data.id,
        username: data.data.username,
        realName: data.data.realName,
        systemRole: data.data.systemRole,
      }
      localStorage.setItem(USER_KEY, JSON.stringify(user.value))
    }
    return true
  }
  function logout() {
    token.value = ''
    user.value = null
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  }
  return { token, user, displayName, isSystemAdmin, login, verify, logout }
})
