<template>
  <div class="page-card">
    <el-card>
      <template #header>机构管理（ORG_MANAGE）</template>

      <el-form :inline="true">
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 150px" @change="load(1)">
            <el-option label="全部" value="" />
            <el-option label="待审核" value="PENDING" />
            <el-option label="已通过" value="APPROVED" />
            <el-option label="已拒绝" value="REJECTED" />
            <el-option label="已禁用" value="DISABLED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load(1)">查询</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="rows" border v-loading="loading">
        <el-table-column prop="loginName" label="登录名" min-width="140" />
        <el-table-column prop="legalName" label="注册全称" min-width="200" />
        <el-table-column prop="publicName" label="展示名" min-width="140" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="注册时间" width="170" />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button type="danger" size="small" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        style="margin-top: 12px; justify-content: flex-end"
        layout="total, prev, pager, next, sizes"
        :total="total"
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :page-sizes="[10, 20, 50]"
        @current-change="load()"
        @size-change="load(1)"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listOrganizations, deleteOrganization } from '@/api/admin'
import type { AdminOrgResponse } from '@/types/api'

const rows = ref<AdminOrgResponse[]>([])
const total = ref(0)
const loading = ref(false)
const query = reactive<{ status: string; page: number; size: number }>({ status: '', page: 1, size: 20 })

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

async function load(page?: number) {
  if (page) query.page = page
  loading.value = true
  try {
    const res = await listOrganizations({
      status: query.status || undefined,
      page: query.page,
      size: query.size,
    })
    rows.value = res.records
    total.value = res.total
  } catch (e: any) {
    ElMessage.error(e.response?.data?.error || '加载失败')
  } finally {
    loading.value = false
  }
}

async function handleDelete(row: AdminOrgResponse) {
  try {
    await ElMessageBox.confirm(`确定删除机构「${row.publicName || row.loginName}」？删除后不可恢复。`, '删除确认', {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await deleteOrganization(row.id)
    ElMessage.success('机构已删除')
    load()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.error || '删除失败')
  }
}

onMounted(() => load(1))
</script>
