import request from './request'
import type { ApiResult, LoginPayload, LoginUser, UserProfile } from '@/types/api'
export const loginApi = (payload: LoginPayload) =>
  request.post<ApiResult<LoginUser>>('/users/login', payload)
export const getCurrentUserApi = () => request.get<ApiResult<UserProfile>>('/users/me')
export const registerApi = (payload: { username: string; password: string; realName: string }) =>
  request.post<ApiResult<number>>('/users/register', payload)
