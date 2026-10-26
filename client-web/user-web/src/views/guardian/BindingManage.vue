<template>
  <div class="page-card">
    <el-card>
      <template #header>绑定管理</template>

      <el-tabs v-model="tab">
        <!-- 我的绑定 / 收到的请求 -->
        <el-tab-pane label="我的绑定" name="list">
          <el-button type="primary" :loading="loading" style="margin-bottom: 12px" @click="load">
            刷新
          </el-button>
          <el-table :data="bindings" v-loading="loading" border>
            <el-table-column prop="status" label="状态" width="110">
              <template #default="{ row }">
                <el-tag :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="对方">
              <template #default="{ row }">
                <div>{{ peerLabel(row) }}</div>
                <div class="sub-text">{{ peerRoleLabel(row) }}</div>
              </template>
            </el-table-column>
            <el-table-column label="发起方" width="120">
              <template #default="{ row }">
                {{ row.requestedBy === myUid ? '我' : '对方' }}
              </template>
            </el-table-column>
            <el-table-column prop="permanent" label="永久" width="80">
              <template #default="{ row }">{{ row.permanent ? '是' : '否' }}</template>
            </el-table-column>
            <el-table-column label="操作" min-width="260">
              <template #default="{ row }">
                <template v-if="row.status === 'PENDING'">
                  <el-button v-if="isReceiver(row)" size="small" type="success" @click="handle(row, 'accept')">
                    确认
                  </el-button>
                  <el-button v-if="isReceiver(row)" size="small" type="danger" @click="handle(row, 'reject')">
                    拒绝
                  </el-button>
                  <el-button v-if="!isReceiver(row)" size="small" @click="handle(row, 'cancel')">取消</el-button>
                </template>
                <el-button
                  v-else-if="row.status === 'ACTIVE' && !row.permanent"
                  size="small"
                  type="warning"
                  @click="handle(row, 'terminate')"
                >
                  解除
                </el-button>
                <span v-else>—</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- 发起绑定：监护人端只保留「以监护人身份绑定受护人」这一个方向。
             反向的「受护人申请监护人」由受护人端发起——同一个人在同一个应用里
             两种身份并列，只会让人点错那个自己没有权限的方向（服务端会返回 404）。 -->
        <el-tab-pane label="发起绑定" name="request">
          <el-form label-width="140px" style="max-width: 520px">
            <el-form-item label="受护人登录名">
              <el-input
                v-model="protecteeLoginName"
                placeholder="输入受护人的登录名"
                clearable
                @keyup.enter="handleRequest"
              />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="loading" @click="handleRequest">
                以监护人身份绑定受护人
              </el-button>
            </el-form-item>
            <el-form-item>
              <span class="sub-text">
                绑定发起后需要对方在自己的应用里确认；若双方已有待确认或已生效的绑定，服务端会拒绝重复发起。
              </span>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <!-- 用户查找 -->
        <el-tab-pane label="用户查找" name="lookup">
          <el-form :inline="true">
            <el-form-item label="登录名">
              <el-input
                v-model="lookupLoginName"
                placeholder="按登录名查找用户"
                style="width: 280px"
                clearable
                @keyup.enter="handleLookup"
              />
            </el-form-item>
            <el-form-item>
              <el-button :loading="loading" @click="handleLookup">查找</el-button>
            </el-form-item>
          </el-form>
          <el-descriptions v-if="looked" :column="1" border>
            <el-descriptions-item label="显示名">{{ looked.displayName || '—' }}</el-descriptions-item>
            <el-descriptions-item label="登录名">{{ looked.loginName }}</el-descriptions-item>
            <el-descriptions-item label="主角色">{{ roleText(looked.roleCode) }}</el-descriptions-item>
            <el-descriptions-item label="角色集合">
              {{ looked.roles.map(roleText).join(' / ') }}
            </el-descriptions-item>
            <el-descriptions-item label="兼任志愿者">{{ looked.alsoVolunteer ? '是' : '否' }}</el-descriptions-item>
          </el-descriptions>
          <el-empty v-else description="输入登录名后查询账号" />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  acceptBinding,
  cancelBinding,
  listMyBindings,
  rejectBinding,
  requestBindingByGuardian,
  terminateBinding,
} from '@/api/binding'
import { getUserByLoginName } from '@/api/lookup'
import type { BindingResponse, UserLookupResponse } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import { personLabel, personSubLabel } from '@/utils/display'

import { errorMessage } from '@/utils/error'
const auth = useAuthStore()
// uid 只用于判断「我是这条绑定的哪一方」，不出现在界面上
const myUid = auth.principalId

const tab = ref('list')
const bindings = ref<BindingResponse[]>([])
const protecteeLoginName = ref('')
const lookupLoginName = ref('')
const loading = ref(false)
const looked = ref<UserLookupResponse | null>(null)

onMounted(load)

async function load() {
  loading.value = true
  try {
    bindings.value = await listMyBindings()
  } catch (e: any) {
    ElMessage.error(errorMessage(e, '加载失败'))
  } finally {
    loading.value = false
  }
}

function isReceiver(row: BindingResponse) {
  return row.requestedBy !== myUid && (row.guardianId === myUid || row.protecteeId === myUid)
}

/** 我是监护人时对方是受护人，反之亦然。 */
function iAmGuardian(row: BindingResponse): boolean {
  return row.guardianId === myUid
}

/** 对方的展示名：显示名 > 登录名 > 未知用户。uid 永不展示。 */
function peerLabel(row: BindingResponse): string {
  return iAmGuardian(row)
    ? personLabel(row.protecteeName, row.protecteeLoginName)
    : personLabel(row.guardianName, row.guardianLoginName)
}

function peerRoleLabel(row: BindingResponse): string {
  const role = iAmGuardian(row) ? '受护人' : '监护人'
  const sub = iAmGuardian(row)
    ? personSubLabel(row.protecteeName, row.protecteeLoginName)
    : personSubLabel(row.guardianName, row.guardianLoginName)
  return sub ? `${role}${sub}` : role
}

/** 角色码转中文，避免把 GUARDIAN 这类内部代码直接摆给用户。 */
function roleText(code: string): string {
  const map: Record<string, string> = {
    GUARDIAN: '监护人',
    PROTECTEE: '受护人',
    VOLUNTEER: '志愿者',
    ORG_ADMIN: '机构管理员',
    PLATFORM_ADMIN: '平台管理员',
  }
  return map[code] || code
}

async function handle(row: BindingResponse, action: 'accept' | 'reject' | 'cancel' | 'terminate') {
  if (action === 'terminate' || action === 'reject') {
    const label = action === 'terminate' ? '解除绑定' : '拒绝绑定请求'
    const confirmed = await ElMessageBox.confirm(`确认${label}？`, '确认操作', {
      type: 'warning',
    }).catch(() => false)
    if (!confirmed) return
    try {
      if (action === 'terminate') {
        await terminateBinding(row.id)
        ElMessage.success('已解除绑定')
      } else {
        await rejectBinding(row.id)
        ElMessage.success('已拒绝')
      }
    } catch (e: any) {
      ElMessage.error(errorMessage(e, '操作失败'))
    }
  } else if (action === 'accept') {
    try {
      const res = await acceptBinding(row.id)
      ElMessage.success(`已确认，状态：${statusText(res.status)}，主绑定：${res.primary ? '是' : '否'}`)
    } catch (e: any) {
      ElMessage.error(errorMessage(e, '确认失败'))
    }
  } else {
    try {
      await cancelBinding(row.id)
      ElMessage.success('已取消')
    } catch (e: any) {
      ElMessage.error(errorMessage(e, '取消失败'))
    }
  }
  await load()
}

/**
 * 发起绑定：界面收登录名，先查成 uid 再提交。
 *
 * 为什么不直接提交登录名：绑定接口的 targetId 是账号主键，
 * 服务端不接受登录名——转换必须发生在这里。
 */
async function handleRequest() {
  const login = protecteeLoginName.value.trim()
  if (!login) return ElMessage.warning('请输入受护人的登录名')
  loading.value = true
  try {
    let target: UserLookupResponse
    try {
      target = await getUserByLoginName(login)
    } catch (e: any) {
      ElMessage.error(e.response?.data?.error || `没有找到登录名为「${login}」的账号`)
      return
    }
    if (target.id === myUid) {
      ElMessage.warning('不能绑定自己')
      return
    }
    const res = await requestBindingByGuardian(target.id)
    ElMessage.success(
      `已向 ${personLabel(target.displayName, target.loginName)} 发起绑定，等待对方确认`,
    )
    protecteeLoginName.value = ''
    if (res) await load()
    tab.value = 'list'
  } catch (e: any) {
    ElMessage.error(errorMessage(e, '发起失败'))
  } finally {
    loading.value = false
  }
}

async function handleLookup() {
  const login = lookupLoginName.value.trim()
  if (!login) return ElMessage.warning('请输入登录名')
  loading.value = true
  try {
    looked.value = await getUserByLoginName(login)
  } catch (e: any) {
    looked.value = null
    ElMessage.error(errorMessage(e, '查找失败'))
  } finally {
    loading.value = false
  }
}

function statusTag(status: string) {
  const map: Record<string, string> = {
    PENDING: 'warning',
    ACTIVE: 'success',
    REJECTED: 'danger',
    CANCELLED: 'info',
    EXPIRED: 'info',
    TERMINATED: 'info',
  }
  return map[status] || 'info'
}

function statusText(status: string) {
  const map: Record<string, string> = {
    PENDING: '待确认',
    ACTIVE: '已生效',
    REJECTED: '已拒绝',
    CANCELLED: '已取消',
    EXPIRED: '已过期',
    TERMINATED: '已解除',
  }
  return map[status] || status
}
</script>

<style scoped>
.sub-text {
  font-size: 12px;
  color: var(--ink-soft, #7a7264);
}
</style>
