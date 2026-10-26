<template>
  <div class="forgot-container">
    <el-card class="forgot-card">
      <h2>找回密码</h2>
      <el-steps :active="stepActive" simple finish-status="success" style="margin-bottom: 20px">
        <el-step title="输入登录名" />
        <el-step title="选择找回方式并补全" />
        <el-step title="验证结果" />
      </el-steps>

      <!-- Step 1: 输入登录名 -->
      <template v-if="step === 'login'">
        <el-form label-width="90px" @submit.prevent>
          <el-form-item label="登录名" required>
            <el-input v-model="loginForm.loginName" placeholder="请输入机构登录名" maxlength="64" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="starting" @click="handleStart">下一步</el-button>
            <el-button @click="router.push('/login')">返回登录</el-button>
          </el-form-item>
        </el-form>
        <el-alert v-if="startError" type="error" :closable="false" :title="startError" />
      </template>

      <!-- Step 2: 选择找回方式并补全 -->
      <template v-else-if="step === 'methods'">
        <el-alert v-if="!methods.length" type="warning" :closable="false"
          title="该机构未设置找回手机号或邮箱，无法通过此方式找回密码。" />
        <template v-else>
          <el-form label-width="90px" @submit.prevent>
            <el-form-item label="找回方式">
              <el-radio-group v-model="selectedType">
                <el-radio v-for="m in methods" :key="m.type" :value="m.type">
                  {{ m.type === 'PHONE' ? '手机号' : '邮箱' }}（{{ m.maskedPrefix }}）
                </el-radio>
              </el-radio-group>
            </el-form-item>
            <el-alert type="info" :closable="false"
              :title="`请根据不加密前缀「${selectedPrefix}」补全完整${selectedType === 'PHONE' ? '手机号' : '邮箱'}`" />
            <el-form-item :label="selectedType === 'PHONE' ? '手机号' : '邮箱'" required>
              <el-input v-model="fullContact"
                :placeholder="selectedType === 'PHONE' ? '补全 11 位手机号' : '补全完整邮箱'" maxlength="128" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="verifying" @click="handleVerify">验证</el-button>
              <el-button @click="step = 'login'">重新输入登录名</el-button>
            </el-form-item>
          </el-form>
          <el-alert v-if="verifyError" type="error" :closable="false" :title="verifyError" />
        </template>
      </template>

      <!-- Step 3: 验证结果（发送验证码与重置密码后续留空） -->
      <template v-else>
        <el-alert type="success" :closable="false"
          title="验证通过！重置密码功能正在开发中，后续将开放。" />
        <el-button type="primary" style="margin-top: 16px" @click="router.push('/login')">返回登录</el-button>
      </template>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { passwordRecoveryStart, passwordRecoveryVerify } from '@/api/auth'
import type { RecoveryMethod } from '@/types/api'

import { errorMessage } from '@/utils/error'
const router = useRouter()
const step = ref<'login' | 'methods' | 'result'>('login')
const stepActive = computed(() => (step.value === 'login' ? 0 : step.value === 'methods' ? 1 : 2))
const loginForm = reactive({ loginName: '' })
const starting = ref(false)
const startError = ref('')
const methods = ref<RecoveryMethod[]>([])
const selectedType = ref('')
const fullContact = ref('')
const verifying = ref(false)
const verifyError = ref('')

const selectedPrefix = computed(
  () => methods.value.find((m) => m.type === selectedType.value)?.maskedPrefix || '',
)

async function handleStart() {
  startError.value = ''
  if (!loginForm.loginName.trim()) {
    startError.value = '请输入登录名'
    return
  }
  starting.value = true
  try {
    const res = await passwordRecoveryStart('ORGANIZATION', loginForm.loginName.trim())
    methods.value = res.methods
    selectedType.value = res.methods[0]?.type || ''
    fullContact.value = ''
    verifyError.value = ''
    step.value = 'methods'
  } catch (e: any) {
    startError.value = errorMessage(e, '查找失败')
  } finally {
    starting.value = false
  }
}

async function handleVerify() {
  verifyError.value = ''
  if (!selectedType.value) {
    verifyError.value = '请先选择找回方式'
    return
  }
  if (!fullContact.value.trim()) {
    verifyError.value = '请补全完整手机号或邮箱'
    return
  }
  verifying.value = true
  try {
    const res = await passwordRecoveryVerify(
      'ORGANIZATION',
      loginForm.loginName.trim(),
      selectedType.value,
      fullContact.value.trim(),
    )
    if (res.verified) {
      step.value = 'result'
    } else {
      verifyError.value = '联系方式不匹配，请检查后重试'
    }
  } catch (e: any) {
    verifyError.value = errorMessage(e, '验证失败')
  } finally {
    verifying.value = false
  }
}
</script>

<style scoped>
.forgot-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  background: transparent;
  padding: 24px;
}
.forgot-card {
  width: 520px;
}
</style>
