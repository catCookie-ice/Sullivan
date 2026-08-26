<template>
  <div class="login-container">
    <el-card class="login-card">
      <h2>Sullivan 公益机构端</h2>
      <el-form @submit.prevent="handleLogin">
        <el-form-item label="账号">
          <el-input v-model="loginName" placeholder="机构登录名" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="password" type="password" placeholder="密码" show-password />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="handleLogin">登录</el-button>
        </el-form-item>
        <el-alert v-if="error" type="error" :closable="false" :title="error" />
        <div class="auth-links">
          <router-link to="/register">机构注册</router-link>
          <router-link to="/forgot-password">忘记密码？</router-link>
        </div>
      </el-form>
    </el-card>

    <!-- 未设置找回联系方式：登录后提醒 -->
    <el-dialog v-model="reminderVisible" title="设置找回联系方式" width="440px"
      :close-on-click-modal="false" :show-close="false">
      <template v-if="reminderMode === 'prompt'">
        <p>你尚未设置找回手机号或邮箱，忘记密码时将无法找回。建议立即设置。</p>
      </template>
      <template v-else>
        <el-form label-width="90px">
          <el-form-item label="找回手机号">
            <el-input v-model="setForm.recoveryPhone" placeholder="可选：11 位手机号" maxlength="11" />
          </el-form-item>
          <el-form-item label="找回邮箱">
            <el-input v-model="setForm.recoveryEmail" placeholder="可选：邮箱地址" maxlength="128" />
          </el-form-item>
          <el-alert type="info" :closable="false" title="手机号与邮箱至少填写一个" />
        </el-form>
      </template>
      <template #footer>
        <template v-if="reminderMode === 'prompt'">
          <el-button @click="onDismiss">不再提醒</el-button>
          <el-button @click="onRemindLater">下次再说</el-button>
          <el-button type="primary" @click="reminderMode = 'set'">去设置</el-button>
        </template>
        <template v-else>
          <el-button @click="reminderMode = 'prompt'">返回</el-button>
          <el-button type="primary" :loading="setting" @click="onSaveSet">保存</el-button>
        </template>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { loginOrganization, recoveryDismiss, recoveryRemindLater, setRecoveryContact } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import type { TokenResponse } from '@/types/api'

const router = useRouter()
const auth = useAuthStore()
const loginName = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')

const reminderVisible = ref(false)
const reminderMode = ref<'prompt' | 'set'>('prompt')
const pendingToken = ref<TokenResponse | null>(null)
const setting = ref(false)
const setForm = reactive({ recoveryPhone: '', recoveryEmail: '' })

async function handleLogin() {
  if (!loginName.value || !password.value) {
    error.value = '请输入账号和密码'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const token = await loginOrganization(loginName.value, password.value)
    auth.setLogin(token)
    ElMessage.success('登录成功')
    if (token.recoveryReminder) {
      pendingToken.value = token
      reminderMode.value = 'prompt'
      setForm.recoveryPhone = ''
      setForm.recoveryEmail = ''
      reminderVisible.value = true
    } else {
      goHome()
    }
  } catch (e: any) {
    error.value = e.response?.data?.error || '登录失败，请检查账号密码'
  } finally {
    loading.value = false
  }
}

function goHome() {
  router.push('/')
}

async function onRemindLater() {
  try {
    await recoveryRemindLater()
  } catch {
    // 忽略失败，仍进入首页
  }
  reminderVisible.value = false
  goHome()
}

async function onDismiss() {
  try {
    await recoveryDismiss()
  } catch {
    // 忽略失败，仍进入首页
  }
  reminderVisible.value = false
  goHome()
}

async function onSaveSet() {
  if (!setForm.recoveryPhone.trim() && !setForm.recoveryEmail.trim()) {
    ElMessage.warning('请至少填写手机号或邮箱')
    return
  }
  setting.value = true
  try {
    await setRecoveryContact({
      recoveryPhone: setForm.recoveryPhone.trim() || undefined,
      recoveryEmail: setForm.recoveryEmail.trim() || undefined,
    })
    ElMessage.success('已设置找回联系方式')
    reminderVisible.value = false
    goHome()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.error || '设置失败')
  } finally {
    setting.value = false
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
.auth-links {
  display: flex;
  justify-content: space-between;
  margin-top: 12px;
  font-size: 13px;
}
</style>
