<template>
  <div class="page-card">
    <el-card>
      <template #header>
        <div class="card-head">
          <span>角色与权限集（ADMIN_GOVERN）</span>
          <div>
            <el-button size="small" :loading="loading" @click="loadAll">刷新</el-button>
            <el-button v-if="canGovern" type="primary" size="small" @click="openCreate">新建角色</el-button>
          </div>
        </div>
      </template>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="角色 = 权限集的具名打包（HR_ADMIN、OPERATIONS 为出厂示例）。调整权限集后，持有者对其下一个请求立即按新权限执行。"
        style="margin-bottom: 12px"
      />

      <el-table :data="roles" border v-loading="loading">
        <el-table-column prop="roleCode" label="角色代码" min-width="140" />
        <el-table-column prop="roleName" label="显示名" min-width="120" />
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag :type="row.system ? 'warning' : 'info'" size="small">
              {{ row.system ? '系统内置' : '自定义' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="memberCount" label="成员数" width="80" />
        <el-table-column label="权限集" min-width="280">
          <template #default="{ row }">
            <el-tag
              v-for="perm in row.permissions.slice(0, 4)"
              :key="perm"
              size="small"
              type="info"
              style="margin-right: 4px"
            >
              {{ perm }}
            </el-tag>
            <span v-if="row.permissions.length > 4" class="muted">+{{ row.permissions.length - 4 }}</span>
            <span v-if="!row.permissions.length" class="muted">空权限集</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-tooltip
              v-if="row.roleCode === 'SUPER_ADMIN'"
              content="最高管理员权限集固定为全量，不可调整"
              placement="top"
            >
              <el-button size="small" disabled>调整权限</el-button>
            </el-tooltip>
            <el-button v-else-if="canGovern" size="small" @click="openEdit(row)">调整权限</el-button>
            <el-button
              v-if="canGovern && !row.system"
              size="small"
              type="danger"
              :disabled="row.memberCount > 0"
              @click="handleDisable(row)"
            >
              停用
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建 / 编辑角色：同一个权限勾选矩阵。 -->
    <el-dialog v-model="dialog.visible" :title="dialog.title" width="620px">
      <el-form v-if="dialog.mode === 'create'" :model="dialog" label-width="90px">
        <el-form-item label="角色代码" required>
          <el-input
            v-model="dialog.roleCode"
            placeholder="大写字母开头，大写字母/数字/下划线，如 AUDITOR"
            :disabled="dialog.saving"
          />
        </el-form-item>
        <el-form-item label="显示名" required>
          <el-input v-model="dialog.roleName" placeholder="如：审计员" :disabled="dialog.saving" />
        </el-form-item>
      </el-form>
      <p v-else class="dialog-role-line">
        角色：<strong>{{ dialog.roleCode }}</strong>
        <span class="muted">（全量覆盖式保存：未勾选的权限会被撤销）</span>
      </p>

      <el-divider content-position="left">权限集</el-divider>
      <el-checkbox-group v-model="dialog.permissionCodes" :disabled="dialog.saving">
        <el-checkbox
          v-for="perm in permissions"
          :key="perm.code"
          :value="perm.code"
          class="perm-checkbox"
        >
          <span class="perm-code">{{ perm.code }}</span>
          <span class="perm-name">{{ perm.name }}</span>
        </el-checkbox>
      </el-checkbox-group>

      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listRoles, listPermissions, createRole, updateRolePermissions, disableRole } from '@/api/admin'
import type { RoleResponse, PermissionResponse } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/utils/error'

const auth = useAuthStore()
const canGovern = computed(() => auth.hasPermission('ADMIN_GOVERN'))

const roles = ref<RoleResponse[]>([])
const permissions = ref<PermissionResponse[]>([])
const loading = ref(false)

const dialog = reactive<{
  visible: boolean
  mode: 'create' | 'edit'
  title: string
  roleCode: string
  roleName: string
  permissionCodes: string[]
  saving: boolean
}>({
  visible: false,
  mode: 'create',
  title: '新建角色',
  roleCode: '',
  roleName: '',
  permissionCodes: [],
  saving: false,
})

onMounted(loadAll)

async function loadAll() {
  loading.value = true
  try {
    const [roleList, permList] = await Promise.all([listRoles(), listPermissions()])
    roles.value = roleList
    permissions.value = permList
  } catch (e: any) {
    ElMessage.error(errorMessage(e, '加载失败'))
  } finally {
    loading.value = false
  }
}

function openCreate() {
  dialog.mode = 'create'
  dialog.title = '新建角色'
  dialog.roleCode = ''
  dialog.roleName = ''
  dialog.permissionCodes = []
  dialog.visible = true
}

function openEdit(row: RoleResponse) {
  dialog.mode = 'edit'
  dialog.title = `调整权限集 — ${row.roleCode}`
  dialog.roleCode = row.roleCode
  dialog.roleName = row.roleName
  dialog.permissionCodes = [...row.permissions]
  dialog.visible = true
}

async function handleSave() {
  if (dialog.mode === 'create' && (!dialog.roleCode || !dialog.roleName)) {
    return ElMessage.warning('请填写角色代码与显示名')
  }
  dialog.saving = true
  try {
    if (dialog.mode === 'create') {
      await createRole({
        roleCode: dialog.roleCode.trim(),
        roleName: dialog.roleName.trim(),
        permissionCodes: dialog.permissionCodes,
      })
      ElMessage.success('角色已创建')
    } else {
      await updateRolePermissions(dialog.roleCode, dialog.permissionCodes)
      ElMessage.success('权限集已更新（立即生效）')
    }
    dialog.visible = false
    await loadAll()
  } catch (e: any) {
    ElMessage.error(errorMessage(e, '保存失败'))
  } finally {
    dialog.saving = false
  }
}

async function handleDisable(row: RoleResponse) {
  try {
    await ElMessageBox.confirm(
      `确定停用角色「${row.roleCode}」？停用后持有者立即失去该角色的全部权限；停用前须先撤销全部成员与可用注册码。`,
      '停用角色',
      { type: 'warning' },
    )
    await disableRole(row.roleCode)
    ElMessage.success('角色已停用')
    await loadAll()
  } catch (e: any) {
    if (e !== 'cancel' && e?.message !== 'cancel') {
      ElMessage.error(errorMessage(e, '停用失败'))
    }
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
.dialog-role-line {
  margin: 0 0 4px;
  font-size: 14px;
}
.perm-checkbox {
  display: flex;
  align-items: baseline;
  margin-right: 16px;
}
.perm-code {
  font-weight: 600;
  margin-right: 6px;
}
.perm-name {
  color: var(--ink-3, #666);
  font-size: 12px;
}
</style>
