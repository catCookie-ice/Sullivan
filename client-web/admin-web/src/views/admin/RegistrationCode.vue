<template>
  <div class="page-card">
    <el-card style="max-width: 640px">
      <template #header>创建管理员注册码</template>

      <KeepAutoForm ref="keepFormRef" keep-auto :model="form" label-width="120px">
        <el-form-item label="初始角色" required>
          <el-select v-model="form.initialRoleCode" style="width: 100%" placeholder="选择注册码对应角色">
            <el-option
              v-for="role in roles"
              :key="role.roleCode"
              :label="`${role.roleCode}（${role.roleName}）`"
              :value="role.roleCode"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="过期时间" required>
          <el-date-picker
            v-model="form.expiresAt"
            type="datetime"
            placeholder="选择过期时间（yyyy-MM-dd HH:mm:ss）"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="handleCreate">生成注册码</el-button>
        </el-form-item>
      </KeepAutoForm>

      <el-alert
        v-if="result"
        type="success"
        :closable="false"
        title="注册码已生成（明文仅此一次展示）"
      >
        <p style="margin: 8px 0">
          注册码：<code class="code-text">{{ result.code }}</code>
        </p>
        <p>初始角色：{{ result.initialRoleCode }}　过期时间：{{ result.expiresAt }}</p>
      </el-alert>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { createRegistrationCode, listRoles } from '@/api/admin'
import KeepAutoForm from '@shared/keep-auto/KeepAutoForm.vue'
import type { AdminCodeResponse, RoleResponse } from '@/types/api'

import { errorMessage } from '@/utils/error'
const form = reactive<{ initialRoleCode: string; expiresAt: string }>({
  initialRoleCode: '',
  expiresAt: '',
})
const loading = ref(false)
const result = ref<AdminCodeResponse | null>(null)
const roles = ref<RoleResponse[]>([])
const keepFormRef = ref<{ clearCache: () => void } | null>(null)

// 角色清单从服务端取（含自定义角色），不再前端硬编码 —— 新建角色后无需改本页。
onMounted(async () => {
  try {
    roles.value = await listRoles()
  } catch (e: any) {
    ElMessage.error(errorMessage(e, '角色清单加载失败'))
  }
})

async function handleCreate() {
  if (!form.initialRoleCode || !form.expiresAt) {
    return ElMessage.warning('请选择初始角色和过期时间')
  }
  loading.value = true
  result.value = null
  try {
    result.value = await createRegistrationCode({
      initialRoleCode: form.initialRoleCode,
      expiresAt: form.expiresAt,
    })
    ElMessage.success('注册码已生成')
    keepFormRef.value?.clearCache()
  } catch (e: any) {
    ElMessage.error(errorMessage(e, '生成失败'))
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.code-text {
  font-size: 14px;
  font-weight: 600;
  word-break: break-all;
}
</style>
