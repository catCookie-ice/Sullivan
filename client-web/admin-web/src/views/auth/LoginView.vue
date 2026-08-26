<template>
  <div class="login-container">
    <el-card class="login-card">
      <h2>Sullivan 管理端</h2>
      <el-form @submit.prevent="handleLogin">
        <el-form-item label="账号">
          <el-input v-model="loginName" placeholder="管理员登录名" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="password" type="password" placeholder="密码" show-password />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="handleLogin">登录</el-button>
        </el-form-item>
        <el-alert v-if="error" type="error" :closable="false" :title="error" />
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { loginAdmin } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()
const loginName = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')

async function handleLogin() {
  if (!loginName.value || !password.value) {
    error.value = '请输入账号和密码'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const token = await loginAdmin(loginName.value, password.value)
    auth.setLogin(token)
    ElMessage.success('登录成功')
    router.push('/')
  } catch (e: any) {
    error.value = e.response?.data?.error || '登录失败，请检查账号密码'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-container {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100vh;
  background: transparent;
}
.login-card {
  width: 400px;
}
</style>
