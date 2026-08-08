<template>
  <div class="page-card">
    <el-card>
      <template #header>机构资料</template>
      <el-skeleton v-if="loading" :rows="5" animated />
      <el-descriptions v-else-if="org" :column="1" border>
        <el-descriptions-item label="机构 ID">{{ org.id }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="statusTagType(org.status)">{{ statusLabel(org.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="登录名">{{ org.loginName }}</el-descriptions-item>
        <el-descriptions-item label="注册全称">{{ org.legalName }}</el-descriptions-item>
        <el-descriptions-item label="对外展示名">{{ org.publicName }}</el-descriptions-item>
      </el-descriptions>
      <el-empty v-else description="机构信息不存在" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getOrganization } from '@/api/org'
import { useAuthStore } from '@/stores/auth'
import type { OrganizationLookupResponse } from '@/types/api'

const auth = useAuthStore()
const org = ref<OrganizationLookupResponse | null>(null)
const loading = ref(true)

function statusLabel(status: string) {
  const map: Record<string, string> = {
    PENDING: '待审核',
    APPROVED: '已通过',
    REJECTED: '已拒绝',
    DISABLED: '已禁用',
  }
  return map[status] || status
}

function statusTagType(status: string): 'info' | 'success' | 'danger' | 'warning' {
  if (status === 'APPROVED') return 'success'
  if (status === 'PENDING') return 'warning'
  if (status === 'DISABLED') return 'danger'
  return 'info'
}

onMounted(async () => {
  try {
    org.value = await getOrganization(auth.principalId)
  } finally {
    loading.value = false
  }
})
</script>
