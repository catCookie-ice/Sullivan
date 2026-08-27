<template>
  <div class="page-card">
    <el-card>
      <template #header>志愿者兼任申请</template>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="通过管理员审核后，需要重新登录才能刷新 VOLUNTEER 角色。"
        style="margin-bottom: 16px"
      />

      <KeepAutoForm ref="keepFormRef" :model="form" label-width="100px" style="max-width: 520px">
        <KeepAutoFormItem keep-auto prop="reason" label="申请理由">
          <el-input v-model="form.reason" type="textarea" :rows="3" maxlength="1000" show-word-limit
                    placeholder="选填，说明你希望参与志愿活动的原因" />
        </KeepAutoFormItem>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="submit">提交申请</el-button>
        </el-form-item>
      </KeepAutoForm>

      <el-divider>我的申请记录</el-divider>
      <el-table :data="applications" v-loading="loading" border>
        <el-table-column prop="createdAt" label="申请时间" width="200" />
        <el-table-column prop="reason" label="理由" show-overflow-tooltip />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button v-if="row.status === 'PENDING'" size="small" type="danger" @click="cancel(row.id)">
              撤销
            </el-button>
            <span v-else>—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  submitVolunteerApplication,
  getMyVolunteerApplications,
  cancelVolunteerApplication,
} from '@/api/auth'
import KeepAutoForm from '@shared/keep-auto/KeepAutoForm.vue'
import KeepAutoFormItem from '@shared/keep-auto/KeepAutoFormItem.vue'
import type { VolunteerApplicationResponse } from '@/types/api'

const form = reactive({ reason: '' })
const applications = ref<VolunteerApplicationResponse[]>([])
const loading = ref(false)
const keepFormRef = ref<{ clearCache: () => void } | null>(null)

onMounted(load)

async function load() {
  loading.value = true
  try {
    applications.value = await getMyVolunteerApplications()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.error || '加载失败')
  } finally {
    loading.value = false
  }
}

async function submit() {
  loading.value = true
  try {
    const res = await submitVolunteerApplication(form.reason)
    ElMessage.success(`申请已提交，状态：${res.status}`)
    keepFormRef.value?.clearCache()
    form.reason = ''
    await load()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.error || '提交失败')
  } finally {
    loading.value = false
  }
}

async function cancel(applicationId: string) {
  loading.value = true
  try {
    await cancelVolunteerApplication(applicationId)
    ElMessage.success('已撤销')
    await load()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.error || '撤销失败')
  } finally {
    loading.value = false
  }
}

function statusTag(status: string) {
  const map: Record<string, string> = {
    PENDING: 'warning',
    APPROVED: 'success',
    REJECTED: 'danger',
    CANCELLED: 'info',
  }
  return map[status] || 'info'
}

function statusText(status: string) {
  const map: Record<string, string> = {
    PENDING: '待审核',
    APPROVED: '已通过',
    REJECTED: '已拒绝',
    CANCELLED: '已撤销',
  }
  return map[status] || status
}
</script>
