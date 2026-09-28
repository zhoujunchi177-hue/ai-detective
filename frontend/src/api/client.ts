import axios from 'axios'
import type { ApiResponse } from '@/types'

const client = axios.create({
  baseURL: '/api',
  timeout: 90000,
})

client.interceptors.request.use((config) => {
  const token = localStorage.getItem('mindtrace_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

client.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error.response?.status
    const message = error.response?.data?.message || error.message || '请求失败'
    if (status === 401 && !location.pathname.startsWith('/login')) {
      localStorage.removeItem('mindtrace_token')
      localStorage.removeItem('mindtrace_profile')
      location.href = '/login?expired=1'
    }
    return Promise.reject(new Error(message))
  },
)

export async function unwrap<T>(request: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  const response = await request
  if (!response.data.success) {
    throw new Error(response.data.message)
  }
  return response.data.data
}

export default client

