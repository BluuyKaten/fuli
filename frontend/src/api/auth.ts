import request from '@/utils/request'
import type { LoginVO, UserProfile } from '@/types'

export interface LoginData {
  username: string
  password: string
}

export const loginApi = (data: LoginData) =>
  request.post<any, { code: number; data: LoginVO; message: string }>('/auth/login', data)

export const registerApi = (data: LoginData) =>
  request.post<any, { code: number; data: number; message: string }>('/auth/register', data)

export const getProfile = () =>
  request.get<any, { code: number; data: UserProfile }>('/auth/profile')

export const updateProfile = (data: { nickname?: string; email?: string; phone?: string }) =>
  request.put<any, { code: number; data: boolean; message: string }>('/auth/profile', data)

export const changePassword = (oldPassword: string, newPassword: string) =>
  request.put<any, { code: number; data: boolean; message: string }>('/auth/password', { oldPassword, newPassword })
