import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref(localStorage.getItem('access_token') || '')
  const role = ref(localStorage.getItem('role') || '')
  const uid = ref(localStorage.getItem('uid') || '')

  function setToken(token: string, userRole: string, userId: string) {
    accessToken.value = token
    role.value = userRole
    uid.value = userId
    localStorage.setItem('access_token', token)
    localStorage.setItem('role', userRole)
    localStorage.setItem('uid', userId)
  }

  function logout() {
    accessToken.value = ''
    role.value = ''
    uid.value = ''
    localStorage.clear()
  }

  return { accessToken, role, uid, setToken, logout }
})
