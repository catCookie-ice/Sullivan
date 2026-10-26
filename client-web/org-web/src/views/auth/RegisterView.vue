<template>
  <div class="register-container">
    <el-card class="register-card">
      <h2>Sullivan 公益机构端 · 注册</h2>
      <el-alert type="info" :closable="false" style="margin-bottom: 16px"
        title="机构注册后需管理员审核，审核通过前无法登录。" />
      <KeepAutoForm ref="keepFormRef" keep-auto :model="form" label-width="110px"
        :exclude="['password', 'confirmPassword']" @submit.prevent>
        <el-form-item label="登录名" required>
          <el-input v-model="form.loginName" placeholder="4-64 位，全局唯一" maxlength="64" />
        </el-form-item>
        <el-form-item label="密码" required>
          <el-input v-model="form.password" type="password" show-password placeholder="至少 8 位" maxlength="72" />
        </el-form-item>
        <el-form-item label="确认密码" required>
          <el-input v-model="form.confirmPassword" type="password" show-password placeholder="再次输入密码" maxlength="72" />
        </el-form-item>
        <el-form-item label="机构全称" required>
          <el-input v-model="form.legalName" placeholder="注册全称（法律名称）" maxlength="160" />
        </el-form-item>
        <el-form-item label="展示名称" required>
          <el-input v-model="form.publicName" placeholder="对外展示名称" maxlength="160" />
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
      </KeepAutoForm>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { registerOrganization } from '@/api/auth'
import KeepAutoForm from '@shared/keep-auto/KeepAutoForm.vue'

import { errorMessage } from '@/utils/error'
const router = useRouter()
const keepFormRef = ref<{ clearCache: () => void } | null>(null)
const form = reactive({
  loginName: '',
  password: '',
  confirmPassword: '',
  legalName: '',
  publicName: '',
  recoveryPhone: '',
  recoveryEmail: '',
})
const loading = ref(false)
const error = ref('')

async function handleRegister() {
  error.value = ''
  if (!form.loginName || !form.password || !form.confirmPassword || !form.legalName || !form.publicName) {
    error.value = '请填写登录名、密码、确认密码、机构全称和展示名称'
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
    await registerOrganization({
      loginName: form.loginName.trim(),
      password: form.password,
      legalName: form.legalName.trim(),
      publicName: form.publicName.trim(),
      recoveryPhone: form.recoveryPhone.trim() || undefined,
      recoveryEmail: form.recoveryEmail.trim() || undefined,
    })
    ElMessage.success('注册成功，待管理员审核后即可登录')
    keepFormRef.value?.clearCache()
    router.push('/login')
  } catch (e: any) {
    error.value = errorMessage(e, '注册失败')
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
  width: 480px;
}
</style>
