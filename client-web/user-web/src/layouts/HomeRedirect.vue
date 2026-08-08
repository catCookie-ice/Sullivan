<template>
  <div />
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

// 根据角色跳转到对应首页（无需挂载，交给路由守卫处理）
if (auth.accessToken) {
  const isVolunteer = auth.roles.includes('VOLUNTEER')
  const isGuardian = auth.roles.includes('GUARDIAN')
  router.replace(isVolunteer && !isGuardian ? '/volunteer' : '/guardian')
}
</script>
