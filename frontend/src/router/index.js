import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/Login.vue'), meta: { public: true } },
  {
    path: '/',
    component: () => import('../views/Layout.vue'),
    children: [
      { path: '', redirect: '/dashboard' },
      { path: 'dashboard', name: 'dashboard', component: () => import('../views/Dashboard.vue'), meta: { title: '今日刷题' } },
      { path: 'questions', name: 'questions', component: () => import('../views/Questions.vue'), meta: { title: '题库' } },
      { path: 'import', name: 'import', component: () => import('../views/Import.vue'), meta: { title: '导入题库' } },
      { path: 'wrong-book', name: 'wrongBook', component: () => import('../views/WrongBook.vue'), meta: { title: '错题本' } },
      { path: 'resume', name: 'resume', component: () => import('../views/Resume.vue'), meta: { title: '简历分析' } },
      { path: 'jd-match', name: 'jdMatch', component: () => import('../views/JdMatch.vue'), meta: { title: 'JD 定向题单' } },
      { path: 'interview', name: 'mockInterview', component: () => import('../views/MockInterview.vue'), meta: { title: '模拟面试' } },
      { path: 'settings', name: 'settings', component: () => import('../views/Settings.vue'), meta: { title: '设置' } }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  const token = localStorage.getItem('token')
  if (to.meta.public) {
    return token && to.name === 'login' ? { name: 'dashboard' } : true
  }
  if (!token) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · SelfFace` : 'SelfFace'
})

export default router
