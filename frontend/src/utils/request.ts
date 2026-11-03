import axios, { type AxiosInstance, type AxiosResponse } from 'axios'
import { message } from 'ant-design-vue'
import router from '@/router'
import { BizError, BIZ_CODE_MESSAGE } from './bizError'


/**
 * 错误提示去重：并发请求同时失败时（例如后端未启动），
 * 3 秒内相同文案只弹一次，避免满屏重复 toast。
 */
let lastError = { text: '', at: 0 }
const notifyError = (text: string): void => {
  const now = Date.now()
  if (text === lastError.text && now - lastError.at < 3000) return
  lastError = { text, at: now }
  message.error(text)
}

const request: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 10000
})

request.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

request.interceptors.response.use(
  (response: AxiosResponse) => {
    const res = response.data
    if (res.code !== 200) {
      // 业务错误：保留 code，让下游可按 BizCode 做差异化处理（如余额不足跳转）
      notifyError(res.message || BIZ_CODE_MESSAGE[res.code] || '请求失败')
      if (res.code === 401) {
        localStorage.removeItem('token')
        router.push('/login')
      }
      return Promise.reject(new BizError(res.code, res.message || BIZ_CODE_MESSAGE[res.code] || '请求失败'))
    }
    return res
  },
  (error) => {
    if (error.response) {
      // 服务器返回了错误状态码
      const status = error.response.status
      if (status === 401) {
        localStorage.removeItem('token')
        router.push('/login')
        notifyError('登录已过期，请重新登录')
      } else if (status === 403) {
        notifyError('没有权限访问')
      } else if (status === 404) {
        notifyError('请求的资源不存在')
      } else if (status >= 500) {
        notifyError('服务器内部错误，请稍后重试')
      } else {
        notifyError(error.response.data?.message || '请求失败')
      }
    } else if (error.request) {
      // 请求已发出但没有收到响应（网络错误）
      notifyError('网络连接失败，请检查网络')
    } else {
      // 请求配置出错
      notifyError(error.message || '请求失败')
    }
    return Promise.reject(error)
  }
)

export default request
