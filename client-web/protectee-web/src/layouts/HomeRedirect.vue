<template>
  <div />
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

// 纯受护人端，登录后直接回绑定管理首页（无需角色分流）
if (auth.accessToken) {
  router.replace('/bindings')
}
</script>
