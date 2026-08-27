<template>
  <div class="page-card">
    <el-card style="max-width: 560px">
      <template #header>创建受护人</template>
      <KeepAutoForm ref="keepFormRef" keep-auto :model="form" label-width="96px" :exclude="['password']">
        <el-form-item label="登录名" required>
          <el-input v-model="form.loginName" placeholder="受护人登录名（全局唯一）" />
        </el-form-item>
        <el-form-item label="密码" required>
          <el-input v-model="form.password" type="password" show-password placeholder="至少 8 位" />
        </el-form-item>
        <el-form-item label="显示名" required>
          <el-input v-model="form.displayName" placeholder="受护人显示名" />
        </el-form-item>
        <el-form-item label="应急备注">
          <el-input v-model="form.emergencyNote" type="textarea" :rows="3" placeholder="可选：健康情况、紧急联络等" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="handleCreate">创建并建立绑定</el-button>
        </el-form-item>
        <el-alert v-if="result" type="success" :closable="false"
          :title="`创建成功：ID ${result.id}，角色 ${result.roles.join(' / ')}，状态 ${result.status}`" />
      </KeepAutoForm>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { createProtectee } from '@/api/guardian'
import KeepAutoForm from '@shared/keep-auto/KeepAutoForm.vue'
import type { RegistrationResponse } from '@/types/api'

const form = reactive({
  loginName: '',
  password: '',
  displayName: '',
  emergencyNote: '',
})
const loading = ref(false)
const result = ref<RegistrationResponse | null>(null)
const keepFormRef = ref<{ clearCache: () => void } | null>(null)

async function handleCreate() {
  if (!form.loginName || !form.password || !form.displayName) {
    return ElMessage.warning('请填写登录名、密码和显示名')
  }
  loading.value = true
  result.value = null
  try {
    result.value = await createProtectee({
      loginName: form.loginName,
      password: form.password,
      displayName: form.displayName,
      emergencyNote: form.emergencyNote || undefined,
    })
    ElMessage.success('受护人创建成功')
    keepFormRef.value?.clearCache()
    form.loginName = ''
    form.password = ''
    form.displayName = ''
    form.emergencyNote = ''
  } catch (e: any) {
    ElMessage.error(e.response?.data?.error || '创建失败')
  } finally {
    loading.value = false
  }
}
</script>
