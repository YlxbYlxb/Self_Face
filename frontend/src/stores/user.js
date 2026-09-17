import { defineStore } from 'pinia'
import { authApi } from '../api'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('token') || '',
    user: JSON.parse(localStorage.getItem('user') || 'null')
  }),
  getters: {
    isLoggedIn: (state) => !!state.token,
    displayName: (state) => state.user?.nickname || state.user?.username || ''
  },
  actions: {
    setAuth({ token, user }) {
      this.token = token
      this.user = user
      localStorage.setItem('token', token)
      localStorage.setItem('user', JSON.stringify(user))
    },
    async login(payload) {
      this.setAuth(await authApi.login(payload))
    },
    async register(payload) {
      this.setAuth(await authApi.register(payload))
    },
    async refresh() {
      const user = await authApi.me()
      this.user = user
      localStorage.setItem('user', JSON.stringify(user))
      return user
    },
    logout() {
      this.token = ''
      this.user = null
      localStorage.removeItem('token')
      localStorage.removeItem('user')
    }
  }
})
