<template>
  <div class="page-card">
    <el-card>
      <template #header>用户管理（USER_MANAGE）</template>

      <el-form :inline="true">
        <el-form-item label="角色">
          <el-select v-model="query.role" placeholder="全部" clearable style="width: 140px" @change="load(1)">
            <el-option label="全部" value="" />
            <el-option label="监护人" value="GUARDIAN" />
            <el-option label="受护人" value="PROTECTEE" />
            <el-option label="志愿者" value="VOLUNTEER" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="登录名 / 显示名" clearable style="width: 220px" @keyup.enter="load(1)" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load(1)">查询</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="rows" border v-loading="loading">
        <el-table-column prop="loginName" label="登录名" min-width="140" />
        <el-table-column prop="displayName" label="显示名" min-width="120" />
        <el-table-column prop="roleCode" label="主角色" width="110" />
        <el-table-column label="角色集合" min-width="180">
          <template #default="{ row }">{{ (row.roles || []).join(' / ') }}</template>
        </el-table-column>
        <el-table-column label="兼任志愿者" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.alsoVolunteer" type="warning">是</el-tag>
            <el-tag v-else type="info">否</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <!-- 不能直接显示 row.status：那是 ENABLED / DISABLED 之类的后端枚举，
                 对使用者没有意义，而且在窄列里会被截断成 "ENABLED …"。 -->
            <el-tag :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
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
import { listUsers, deleteUser } from '@/api/admin'
import type { AdminUserResponse } from '@/types/api'

import { errorMessage } from '@/utils/error'
const rows = ref<AdminUserResponse[]>([])
const total = ref(0)
const loading = ref(false)
const query = reactive<{ role: string; keyword: string; page: number; size: number }>({
  role: '',
  keyword: '',
  page: 1,
  size: 20,
})

async function load(page?: number) {
  if (page) query.page = page
  loading.value = true
  try {
    const res = await listUsers({
      role: query.role || undefined,
      keyword: query.keyword || undefined,
      page: query.page,
      size: query.size,
    })
    rows.value = res.records
    total.value = res.total
  } catch (e: any) {
    ElMessage.error(errorMessage(e, '加载失败'))
  } finally {
    loading.value = false
  }
}

async function handleDelete(row: AdminUserResponse) {
  try {
    await ElMessageBox.confirm(
      `确定删除用户「${row.displayName || row.loginName}」？兼任志愿者将仅取消兼任。`,
      '删除确认',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    const res = await deleteUser(row.id)
    ElMessage.success(res.message)
    load()
  } catch (e: any) {
    ElMessage.error(errorMessage(e, '删除失败'))
  }
}

onMounted(() => load(1))

/** 账号状态：后端只下发 ENABLED / DISABLED，界面用中文说明。 */
function statusText(status: string): string {
  const map: Record<string, string> = {
    ENABLED: '正常',
    DISABLED: '已禁用',
  }
  return map[status] || status
}

function statusTag(status: string): 'success' | 'danger' | 'info' {
  if (status === 'ENABLED') return 'success'
  if (status === 'DISABLED') return 'danger'
  return 'info'
}
</script>
