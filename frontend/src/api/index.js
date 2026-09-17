import axios from 'axios'
import { ElMessage } from 'element-plus'

const http = axios.create({
  baseURL: '/api',
  // 简历分析已改为服务端后台任务：上传只负责建任务，结果靠 /resume/{id} 轮询，
  // 所以不再需要 5 分钟的超时，30 秒足以覆盖网络抖动
  timeout: 30000
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

function redirectToLogin() {
  localStorage.removeItem('token')
  localStorage.removeItem('user')
  if (!window.location.pathname.startsWith('/login')) {
    window.location.href = '/login'
  }
}

http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && typeof body.code === 'number') {
      if (body.code === 0) {
        return body.data
      }
      if (body.code === 401) {
        redirectToLogin()
        return Promise.reject(new Error(body.msg))
      }
      ElMessage.error(body.msg || '请求失败')
      return Promise.reject(new Error(body.msg || '请求失败'))
    }
    return body
  },
  (err) => {
    if (err.response?.status === 401) {
      ElMessage.error('登录已过期，请重新登录')
      redirectToLogin()
      return Promise.reject(err)
    }
    const msg = err.response?.data?.msg
      || (err.code === 'ECONNABORTED' ? '请求超时，大模型响应较慢时请稍候重试' : err.message)
      || '网络异常'
    ElMessage.error(msg)
    return Promise.reject(err)
  }
)

export const authApi = {
  register: (data) => http.post('/auth/register', data),
  login: (data) => http.post('/auth/login', data),
  me: () => http.get('/auth/me'),
  updateProfile: (data) => http.put('/auth/profile', data)
}

export const questionApi = {
  categories: () => http.get('/categories'),
  page: (params) => http.get('/questions', { params }),
  detail: (id) => http.get(`/questions/${id}`),
  // 传 JSON 文本而不是文件：粘贴和上传两种交互共用同一个接口
  importQuestions: (data) => http.post('/questions/import', data, { timeout: 60000 })
}

export const practiceApi = {
  today: () => http.get('/practice/today'),
  submit: (data) => http.post('/practice/submit', data),
  stats: () => http.get('/practice/stats'),
  wrongBook: (params) => http.get('/practice/wrong-book', { params }),
  history: (questionId) => http.get(`/practice/history/${questionId}`),
  append: (questionId) => http.post('/practice/append', { questionId }),
  // JD 定向题单一键导入：一次加一批，避免前端循环发 N 个请求
  appendBatch: (questionIds) => http.post('/practice/append-batch', { questionIds })
}

export const jdApi = {
  // 抽关键词要等模型跑完，默认 30 秒不够，单独放宽
  analyze: (data) => http.post('/jd/analyze', data, { timeout: 120000 })
}

export const interviewApi = {
  // 每一轮问答都要等模型，超时统一放宽
  start: (data) => http.post('/interview/session', data, { timeout: 120000 }),
  answer: (id, answer) => http.post(`/interview/session/${id}/answer`, { answer }, { timeout: 120000 }),
  finish: (id) => http.post(`/interview/session/${id}/finish`, {}, { timeout: 180000 }),
  detail: (id) => http.get(`/interview/session/${id}`),
  list: (limit = 20) => http.get('/interview/sessions', { params: { limit } }),
  remove: (id) => http.delete(`/interview/session/${id}`)
}

export const resumeApi = {
  analyze: (file) => {
    const form = new FormData()
    form.append('file', file)
    return http.post('/resume/analyze', form, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },
  list: (limit = 20) => http.get('/resume/list', { params: { limit } }),
  detail: (id) => http.get(`/resume/${id}`),
  remove: (id) => http.delete(`/resume/${id}`)
}

export const llmApi = {
  get: () => http.get('/llm/setting'),
  save: (data) => http.put('/llm/setting', data),
  test: () => http.post('/llm/test')
}

export default http
