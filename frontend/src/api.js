import axios from 'axios'
import { reactive } from 'vue'

export const session = reactive({ token: '', role: '', username: '', mustChangePassword: false })
export const api = axios.create({ baseURL: import.meta.env.DEV ? '/api' : '/hospital/api', timeout: 12000 })
api.interceptors.request.use(config => {
  if (session.token) config.headers.Authorization = `Bearer ${session.token}`
  return config
})
api.interceptors.response.use(response => response.data, error => {
  if (error.response?.status === 401) { session.token = ''; session.role = '' }
  return Promise.reject(new Error(error.response?.data?.message || error.response?.data?.error || error.message))
})
export const key = () => crypto.randomUUID()
