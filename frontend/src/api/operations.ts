import request from './request'
import type { ApiResult } from '@/types/api'
export const apiGet = <T>(url: string, params?: object) =>
  request.get<ApiResult<T>>(url, { params })
export const apiPost = <T>(url: string, data?: object) => request.post<ApiResult<T>>(url, data)
export const apiPatch = <T>(url: string, data?: object) => request.patch<ApiResult<T>>(url, data)
