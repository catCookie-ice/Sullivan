<template>
  <div class="page-card">
    <el-card>
      <template #header>
        <div class="card-head">
          <span>管理员管理（ADMIN_MANAGE）</span>
          <el-button size="small" :loading="loading" @click="load(1)">刷新</el-button>
        </div>
      </template>

      <el-form :inline="true">
        <el-form-item label="关键字">
          <el-input
            v-model="query.keyword"
            placeholder="登录名 / 显示名"
            clearable
            style="width: 200px"
            @keyup.enter="load(1)"
            @clear="load(1)"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load(1)">查询</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="rows" border v-loading="loading">
        <el-table-column prop="loginName" label="登录名" min-width="140" />
        <el-table-column prop="displayName" label="显示名" min-width="120" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ENABLED' ? 'success' : 'danger'">
              {{ row.status === 'ENABLED' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="角色" min-width="160">
          <template #default="{ row }">
            <el-tag v-for="role in row.roles" :key="role" size="small" style="margin-right: 4px">
              {{ role }}
            </el-tag>
            <span v-if="!row.roles.length" class="muted">无角色</span>
          </template>
        </el-table-column>
        <el-table-column label="权限" min-width="220">
          <template #default="{ row }">
            <el-tag
              v-for="perm in row.permissions.slice(0, 3)"
              :key="perm"
              size="small"
              type="info"
              style="margin-right: 4px"
            >
              {{ perm }}
            </el-tag>
            <span v-if="row.permissions.length > 3" class="muted">+{{ row.permissions.length - 3 }}</span>
            <span v-if="!row.permissions.length" class="muted">无权限</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="canGovern"
              size="small"
              :type="row.status === 'ENABLED' ? 'danger' : 'success'"
              :disabled="row.id === auth.principalId && row.status === 'ENABLED'"
              @click="handleToggleStatus(row)"
            >
              {{ row.status === 'ENABLED' ? '停用' : '启用' }}
            </el-button>
            <el-button v-if="canGovern" size="small" @click="openRoleDialog(row)">分配角色</el-button>
            <span v-if="!canGovern" class="muted">只读（需 ADMIN_GOVERN）</span>
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

    <!-- 分配角色：每行一个角色开关；开启时可选择过期时间（空 = 永久）。 -->
    <el-dialog v-model="roleDialog.visible" :title="`分配角色 — ${roleDialog.loginName}`" width="560px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="角色是权限集的具名打包；变更对被操作管理员的下一个请求立即生效。"
        style="margin-bottom: 12px"
      />
      <el-table :data="roleDialog.rows" size="small" v-loading="roleDialog.loading">
        <el-table-column prop="roleCode" label="角色" min-width="120" />
        <el-table-column prop="roleName" label="说明" min-width="110" />
        <el-table-column label="持有" width="70">
          <template #default="{ row }">
            <el-switch
              :model-value="hasRole(row)"
              :loading="row.busy"
              @change="(value: string | number | boolean) => handleRoleToggle(row, Boolean(value))"
            />
          </template>
        </el-table-column>
        <el-table-column label="过期时间（可选）" width="180">
          <template #default="{ row }">
            <el-date-picker
              v-if="!hasRole(row)"
              v-model="row.expiresAt"
              type="datetime"
              placeholder="空 = 永久"
              value-format="YYYY-MM-DDTHH:mm:ss"
              style="width: 100%"
            />
            <span v-else class="muted">生效中</span>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listAdmins,
  listRoles,
  setAdminStatus,
  grantAdminRole,
  revokeAdminRole,
} from '@/api/admin'
import type { AdminAccountResponse, RoleResponse } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/error'

const auth = useAuthStore()
const canGovern = computed(() => auth.hasPermission('ADMIN_GOVERN'))

const rows = ref<AdminAccountResponse[]>([])
const total = ref(0)
const loading = ref(false)
const query = reactive<{ keyword: string; page: number; size: number }>({
  keyword: '',
  page: 1,
  size: 20,
})

interface DialogRole extends RoleResponse {
  busy?: boolean
  expiresAt?: string
}

const roleDialog = reactive<{ visible: boolean; adminId: string; loginName: string; loading: boolean; rows: DialogRole[] }>({
  visible: false,
  adminId: '',
  loginName: '',
  loading: false,
  rows: [],
})

onMounted(() => load(1))

async function load(page?: number) {
  if (page) query.page = page
  loading.value = true
  try {
    const res = await listAdmins({
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

async function handleToggleStatus(row: AdminAccountResponse) {
  const disabling = row.status === 'ENABLED'
  const name = row.displayName || row.loginName
  try {
    if (disabling) {
      await ElMessageBox.confirm(
        `确定停用管理员「${name}」？停用立即生效，其正在使用的登录状态将在下一个请求被拒绝。`,
        '停用管理员',
        { type: 'warning' },
      )
    }
    await setAdminStatus(row.id, disabling ? 'DISABLED' : 'ENABLED')
    ElMessage.success(disabling ? '已停用（立即生效）' : '已启用')
    load()
  } catch (e: any) {
    if (e !== 'cancel' && e?.message !== 'cancel') {
      ElMessage.error(errorMessage(e, '操作失败'))
    }
  }
}

async function openRoleDialog(row: AdminAccountResponse) {
  roleDialog.visible = true
  roleDialog.adminId = row.id
  roleDialog.loginName = row.displayName || row.loginName
  roleDialog.loading = true
  try {
    const roles = await listRoles()
    roleDialog.rows = roles.map((role) => ({ ...role, expiresAt: undefined }))
  } catch (e: any) {
    ElMessage.error(errorMessage(e, '角色清单加载失败'))
  } finally {
    roleDialog.loading = false
  }
}

function hasRole(row: DialogRole): boolean {
  const admin = rows.value.find((a) => a.id === roleDialog.adminId)
  return admin?.roles.includes(row.roleCode) ?? false
}

async function handleRoleToggle(row: DialogRole, grant: boolean) {
  row.busy = true
  try {
    if (grant) {
      await grantAdminRole(roleDialog.adminId, { roleCode: row.roleCode, expiresAt: row.expiresAt })
      ElMessage.success(`已授予 ${row.roleCode}`)
    } else {
      await revokeAdminRole(roleDialog.adminId, row.roleCode)
      ElMessage.success(`已撤销 ${row.roleCode}`)
    }
    await load()
  } catch (e: any) {
    ElMessage.error(errorMessage(e, '角色变更失败'))
  } finally {
    row.busy = false
  }
}
</script>

<style scoped>
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.muted {
  color: var(--ink-4, #999);
  font-size: 12px;
}
</style>
