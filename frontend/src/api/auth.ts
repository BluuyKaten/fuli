import request from '@/utils/request'

export const loginApi = (data: { username: string; password: string }) =>
  request.post<any, { code: number; data: any; message: string }>('/auth/login', data)

export const registerApi = (data: { username: string; password: string }) =>
  request.post<any, { code: number; data: number; message: string }>('/auth/register', data)

export const getProfile = () =>
  request.get<any, { code: number; data: any }>('/auth/profile')

export const updateProfile = (data: { nickname?: string; email?: string; phone?: string }) =>
  request.put<any, { code: number; data: boolean; message: string }>('/auth/profile', data)

export const changePassword = (oldPassword: string, newPassword: string) =>
  request.put<any, { code: number; data: boolean; message: string }>('/auth/password', { oldPassword, newPassword })
