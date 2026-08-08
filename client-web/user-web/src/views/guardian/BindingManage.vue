<template>
  <div class="page-card">
    <el-card>
      <template #header>绑定管理</template>

      <el-tabs v-model="tab">
        <el-tab-pane label="发起绑定" name="request">
          <el-form :inline="true">
            <el-form-item label="受护人 ID">
              <el-input v-model="protecteeId" placeholder="输入受护人账号 ID" style="width: 280px" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="loading" @click="handleRequestBinding">发起绑定</el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="接受绑定" name="accept">
          <el-form :inline="true">
            <el-form-item label="绑定请求 ID">
              <el-input v-model="bindingId" placeholder="输入待确认的绑定 ID" style="width: 280px" />
            </el-form-item>
            <el-form-item>
              <el-button type="success" :loading="loading" @click="handleAcceptBinding">确认绑定</el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="用户查找" name="lookup">
          <el-form :inline="true">
            <el-form-item label="账号 ID">
              <el-input v-model="lookupUserId" placeholder="按唯一标识查找用户" style="width: 280px" />
            </el-form-item>
            <el-form-item>
              <el-button :loading="loading" @click="handleLookup">查找</el-button>
            </el-form-item>
          </el-form>
          <el-descriptions v-if="looked" :column="1" border>
            <el-descriptions-item label="ID">{{ looked.id }}</el-descriptions-item>
            <el-descriptions-item label="显示名">{{ looked.displayName }}</el-descriptions-item>
            <el-descriptions-item label="主角色">{{ looked.roleCode }}</el-descriptions-item>
            <el-descriptions-item label="角色集合">{{ looked.roles.join(' / ') }}</el-descriptions-item>
            <el-descriptions-item label="兼任志愿者">{{ looked.alsoVolunteer ? '是' : '否' }}</el-descriptions-item>
          </el-descriptions>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { requestBinding, acceptBinding } from '@/api/guardian'
import { getUser } from '@/api/lookup'
import type { UserLookupResponse } from '@/types/api'

const tab = ref('request')
const protecteeId = ref('')
const bindingId = ref('')
const lookupUserId = ref('')
const loading = ref(false)
const looked = ref<UserLookupResponse | null>(null)

async function handleRequestBinding() {
  if (!protecteeId.value) return ElMessage.warning('请输入受护人 ID')
  loading.value = true
  try {
    const res = await requestBinding(protecteeId.value)
    ElMessage.success(`绑定请求已发起，状态：${res.status}，绑定 ID：${res.id}`)
  } catch (e: any) {
    ElMessage.error(e.response?.data?.error || '发起失败')
  } finally {
    loading.value = false
  }
}

async function handleAcceptBinding() {
  if (!bindingId.value) return ElMessage.warning('请输入绑定 ID')
  loading.value = true
  try {
    const res = await acceptBinding(bindingId.value)
    ElMessage.success(`绑定已确认，状态：${res.status}，主绑定：${res.primary}`)
  } catch (e: any) {
    ElMessage.error(e.response?.data?.error || '确认失败')
  } finally {
    loading.value = false
  }
}

async function handleLookup() {
  if (!lookupUserId.value) return ElMessage.warning('请输入账号 ID')
  loading.value = true
  try {
    looked.value = await getUser(lookupUserId.value)
  } catch (e: any) {
    looked.value = null
    ElMessage.error(e.response?.data?.error || '查找失败')
  } finally {
    loading.value = false
  }
}
</script>
