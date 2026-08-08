import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { TokenResponse } from '@/types/api'

export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref(localStorage.getItem('access_token') || '')
  const principalId = ref(localStorage.getItem('principal_id') || '')
  const principalType = ref(localStorage.getItem('principal_type') || '')
  const roles = ref<string[]>(JSON.parse(localStorage.getItem('roles') || '[]'))
  const permissions = ref<string[]>(JSON.parse(localStorage.getItem('permissions') || '[]'))

  function setLogin(token: TokenResponse) {
    accessToken.value = token.accessToken
    principalId.value = token.principalId
    principalType.value = token.principalType
    roles.value = token.roles
    permissions.value = token.permissions
    localStorage.setItem('access_token', token.accessToken)
    localStorage.setItem('principal_id', token.principalId)
    localStorage.setItem('principal_type', token.principalType)
    localStorage.setItem('roles', JSON.stringify(token.roles))
    localStorage.setItem('permissions', JSON.stringify(token.permissions))
  }

  function hasPermission(code: string) {
    return permissions.value.includes(code)
  }

  function logout() {
    accessToken.value = ''
    principalId.value = ''
    principalType.value = ''
    roles.value = []
    permissions.value = []
    localStorage.clear()
  }

  return { accessToken, principalId, principalType, roles, permissions, setLogin, hasPermission, logout }
})
