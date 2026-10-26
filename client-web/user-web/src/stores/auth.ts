import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { TokenResponse } from '@/types/api'
import { clearAuthStorage } from '@/utils/session'

export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref(localStorage.getItem('access_token') || '')
  const principalId = ref(localStorage.getItem('principal_id') || '')
  const principalType = ref(localStorage.getItem('principal_type') || '')
  const roles = ref<string[]>(JSON.parse(localStorage.getItem('roles') || '[]'))

  function setLogin(token: TokenResponse) {
    accessToken.value = token.accessToken
    principalId.value = token.principalId
    principalType.value = token.principalType
    roles.value = token.roles
    localStorage.setItem('access_token', token.accessToken)
    localStorage.setItem('principal_id', token.principalId)
    localStorage.setItem('principal_type', token.principalType)
    localStorage.setItem('roles', JSON.stringify(token.roles))
  }

  function logout() {
    accessToken.value = ''
    principalId.value = ''
    principalType.value = ''
    roles.value = []
    // 只清登录态；localStorage.clear() 会连 @shared/keep-auto 的表单草稿一起抹掉。
    clearAuthStorage()
  }

  return { accessToken, principalId, principalType, roles, setLogin, logout }
})
