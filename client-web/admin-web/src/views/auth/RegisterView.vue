<template>
  <div class="register-container">
    <el-card class="register-card">
      <h2>Sullivan 管理端 · 管理员注册</h2>
      <el-alert
        type="info"
        :closable="false"
        title="需要由最高管理员或 HR 管理员签发的有效注册码（SV- 开头）。"
        style="margin-bottom: 16px"
      />
      <el-form :model="form" label-width="110px" @submit.prevent>
        <el-form-item label="登录名" required>
          <el-input v-model="form.loginName" placeholder="4-64 位，全局唯一" maxlength="64" />
        </el-form-item>
        <el-form-item label="密码" required>
          <el-input v-model="form.password" type="password" show-password placeholder="至少 8 位" maxlength="72" />
        </el-form-item>
        <el-form-item label="显示名" required>
          <el-input v-model="form.displayName" placeholder="管理员显示名" maxlength="80" />
        </el-form-item>
        <el-form-item label="注册码" required>
          <el-input v-model="form.registrationCode" placeholder="SV-xxxxxxxxxx（一次性）" maxlength="128" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="handleRegister">注册</el-button>
          <el-button @click="router.push('/login')">返回登录</el-button>
        </el-form-item>
        <el-alert v-if="error" type="error" :closable="false" :title="error" />
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { registerAdmin } from '@/api/auth'

const router = useRouter()
const form = reactive({
  loginName: '',
  password: '',
  displayName: '',
  registrationCode: '',
})
const loading = ref(false)
const error = ref('')

async function handleRegister() {
  error.value = ''
  if (!form.loginName || !form.password || !form.displayName || !form.registrationCode) {
    error.value = '请填写登录名、密码、显示名和注册码'
    return
  }
  if (form.password.length < 8) {
    error.value = '密码至少 8 位'
    return
  }
  loading.value = true
  try {
    await registerAdmin({
      loginName: form.loginName.trim(),
      password: form.password,
      displayName: form.displayName.trim(),
      registrationCode: form.registrationCode.trim(),
    })
    ElMessage.success('管理员注册成功，请登录')
    router.push('/login')
  } catch (e: any) {
    error.value = e.response?.data?.error || '注册失败，请检查注册码'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.register-container {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100vh;
  background: transparent;
}
.register-card {
  width: 440px;
}
</style>
