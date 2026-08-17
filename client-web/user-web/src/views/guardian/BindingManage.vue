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
                {{ row.guardianId === myUid ? row.protecteeId + '（受护人）' : row.guardianId + '（监护人）' }}
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

        <!-- 发起绑定 -->
        <el-tab-pane label="发起绑定" name="request">
          <el-form label-width="140px" style="max-width: 480px">
            <el-form-item label="作为监护人绑定受护人">
              <el-input v-model="protecteeId" placeholder="输入受护人账号 ID" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="loading" @click="handleRequest('guardian')">
                监护人发起绑定
              </el-button>
            </el-form-item>
            <el-form-item label="作为受护人申请监护人">
              <el-input v-model="guardianId" placeholder="输入监护人账号 ID" />
            </el-form-item>
            <el-form-item>
              <el-button :loading="loading" @click="handleRequest('protectee')">
                受护人申请绑定
              </el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <!-- 用户查找 -->
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
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  requestBinding,
  requestBindingByProtectee,
  acceptBinding,
  rejectBinding,
  cancelBinding,
  terminateBinding,
  listMyBindings,
} from '@/api/guardian'
import { getUser } from '@/api/lookup'
import type { BindingResponse, UserLookupResponse } from '@/types/api'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const myUid = auth.principalId

const tab = ref('list')
const bindings = ref<BindingResponse[]>([])
const protecteeId = ref('')
const guardianId = ref('')
const lookupUserId = ref('')
const loading = ref(false)
const looked = ref<UserLookupResponse | null>(null)

onMounted(load)

async function load() {
  loading.value = true
  try {
    bindings.value = await listMyBindings()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.error || '加载失败')
  } finally {
    loading.value = false
  }
}

function isReceiver(row: BindingResponse) {
  return row.requestedBy !== myUid && (row.guardianId === myUid || row.protecteeId === myUid)
}

async function handle(row: BindingResponse, action: 'accept' | 'reject' | 'cancel' | 'terminate') {
  if (action === 'terminate' || action === 'reject') {
    const label = action === 'terminate' ? '解除绑定' : '拒绝绑定请求'
    await ElMessageBox.confirm(`确认${label}？`, '确认操作', { type: 'warning' }).catch(() => 'cancel')
    if (action === 'terminate') {
      await terminateBinding(row.id)
      ElMessage.success('已解除绑定')
    } else {
      await rejectBinding(row.id)
      ElMessage.success('已拒绝')
    }
  } else if (action === 'accept') {
    const res = await acceptBinding(row.id)
    ElMessage.success(`已确认，状态：${res.status}，主绑定：${res.primary}`)
  } else {
    await cancelBinding(row.id)
    ElMessage.success('已取消')
  }
  await load()
}

async function handleRequest(kind: 'guardian' | 'protectee') {
  const id = kind === 'guardian' ? protecteeId.value : guardianId.value
  if (!id) return ElMessage.warning(kind === 'guardian' ? '请输入受护人 ID' : '请输入监护人 ID')
  loading.value = true
  try {
    const res =
      kind === 'guardian' ? await requestBinding(id) : await requestBindingByProtectee(id)
    ElMessage.success(`绑定请求已发起，状态：${res.status}，绑定 ID：${res.id}`)
  } catch (e: any) {
    ElMessage.error(e.response?.data?.error || '发起失败')
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
