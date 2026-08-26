<template>
  <div class="register-container">
    <el-card class="register-card">
      <h2>Sullivan 监护与志愿者端 · 注册</h2>
      <el-form :model="form" label-width="110px" @submit.prevent>
        <el-form-item label="登录名" required>
          <el-input v-model="form.loginName" placeholder="4-64 位，全局唯一" maxlength="64" />
        </el-form-item>
        <el-form-item label="密码" required>
          <el-input v-model="form.password" type="password" show-password placeholder="至少 8 位" maxlength="72" />
        </el-form-item>
        <el-form-item label="确认密码" required>
          <el-input v-model="form.confirmPassword" type="password" show-password placeholder="再次输入密码" maxlength="72" />
        </el-form-item>
        <el-form-item label="显示名" required>
          <el-input v-model="form.displayName" placeholder="昵称" maxlength="80" />
        </el-form-item>
        <el-form-item label="注册角色" required>
          <el-radio-group v-model="form.role">
            <el-radio value="GUARDIAN">监护人</el-radio>
            <el-radio value="VOLUNTEER">志愿者</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-divider />
        <el-form-item label="找回手机号">
          <el-input v-model="form.recoveryPhone" placeholder="可选：11 位手机号" maxlength="11" />
        </el-form-item>
        <el-form-item label="找回邮箱">
          <el-input v-model="form.recoveryEmail" placeholder="可选：邮箱地址" maxlength="128" />
        </el-form-item>
        <el-alert type="info" :closable="false"
          title="找回手机号/邮箱用于找回密码；留空将导致密码无法找回（登录后仍可设置）。" />
        <el-form-item style="margin-top: 16px">
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
import { registerUser } from '@/api/auth'

const router = useRouter()
const form = reactive({
  loginName: '',
  password: '',
  confirmPassword: '',
  displayName: '',
  role: 'GUARDIAN',
  recoveryPhone: '',
  recoveryEmail: '',
})
const loading = ref(false)
const error = ref('')

async function handleRegister() {
  error.value = ''
  if (!form.loginName || !form.password || !form.confirmPassword || !form.displayName) {
    error.value = '请填写登录名、密码、确认密码和显示名'
    return
  }
  if (form.password.length < 8) {
    error.value = '密码至少 8 位'
    return
  }
  if (form.password !== form.confirmPassword) {
    error.value = '两次输入的密码不一致'
    return
  }
  loading.value = true
  try {
    await registerUser({
      loginName: form.loginName.trim(),
      password: form.password,
      displayName: form.displayName.trim(),
      role: form.role as 'GUARDIAN' | 'VOLUNTEER',
      recoveryPhone: form.recoveryPhone.trim() || undefined,
      recoveryEmail: form.recoveryEmail.trim() || undefined,
    })
    ElMessage.success('注册成功，请登录')
    router.push('/login')
  } catch (e: any) {
    error.value = e.response?.data?.error || '注册失败'
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
  min-height: 100vh;
  background: transparent;
  padding: 24px;
}
.register-card {
  width: 460px;
}
</style>
