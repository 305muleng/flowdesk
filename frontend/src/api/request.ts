import axios from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResult } from '@/types/api'

const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 10_000,
})
request.interceptors.request.use((config) => {
  const token = localStorage.getItem('flowdesk_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})
request.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResult<unknown>
    return body.code === 200 ? response : Promise.reject(new Error(body.message || '请求失败'))
  },
  (error) => {
    const status = error.response?.status
    const fallback =
      status === 401
        ? '登录已失效，请重新登录'
        : status === 403
          ? '你没有执行此操作的权限'
          : '网络请求失败，请稍后重试'
    const message = error.response?.data?.message || fallback
    if (status === 401) {
      localStorage.removeItem('flowdesk_token')
      localStorage.removeItem('flowdesk_user')
      if (location.pathname !== '/login') location.replace('/login')
    }
    ElMessage.error(message)
    return Promise.reject(error)
  },
)
export default request
