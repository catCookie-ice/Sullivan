<template>
  <div class="page-card">
    <el-card>
      <template #header>实时会话（长连接演示）</template>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="仅支持与存在 ACTIVE 绑定的受护人/监护人建立会话；中继每条消息都会复查绑定。"
        style="margin-bottom: 16px"
      />

      <el-form :inline="true">
        <el-form-item label="目标账号 ID">
          <el-input v-model="targetUid" placeholder="已绑定受护人/监护人 ID" style="width: 240px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="createSession">发起会话</el-button>
        </el-form-item>
        <el-form-item>
          <el-button :loading="loading" @click="loadSessions">刷新</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="sessions" v-loading="loading" border>
        <el-table-column prop="id" label="会话 ID" width="210" />
        <el-table-column label="角色">
          <template #default="{ row }">
            {{ row.initiatorUid === myUid ? '发起方' : '接收方' }}
          </template>
        </el-table-column>
        <el-table-column label="对方">
          <template #default="{ row }">
            {{ row.initiatorUid === myUid ? row.targetUid : row.initiatorUid }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="220">
          <template #default="{ row }">
            <template v-if="row.status === 'REQUESTED' && row.targetUid === myUid">
              <el-button size="small" type="success" @click="decide(row, 'accept')">接受</el-button>
              <el-button size="small" type="danger" @click="decide(row, 'reject')">拒绝</el-button>
            </template>
            <template v-if="row.status === 'ACTIVE'">
              <el-button size="small" type="primary" @click="connect(row.id)">连接</el-button>
              <el-button size="small" type="warning" @click="decide(row, 'end')">结束</el-button>
            </template>
            <span v-if="['REJECTED', 'ENDED'].includes(row.status)">—</span>
          </template>
        </el-table-column>
      </el-table>

      <el-divider>WebSocket 连接（选择 ACTIVE 会话后点击"连接"）</el-divider>
      <el-descriptions :column="1" border style="margin-bottom: 12px">
        <el-descriptions-item label="连接状态">
          <el-tag :type="wsConnected ? 'success' : 'info'">
            {{ wsConnected ? '已连接' : '未连接' }}
          </el-tag>
          <span v-if="activeSessionId" style="margin-left: 12px">会话：{{ activeSessionId }}</span>
        </el-descriptions-item>
      </el-descriptions>

      <el-form :inline="true" :disabled="!wsConnected">
        <el-form-item label="发送给（对方 UID）">
          <el-input v-model="relayTo" placeholder="对方账号 ID" style="width: 200px" />
        </el-form-item>
        <el-form-item label="消息">
          <el-input v-model="relayText" placeholder="中继消息内容" style="width: 260px" />
        </el-form-item>
        <el-form-item>
          <el-button type="success" @click="sendRelay">发送中继</el-button>
          <el-button @click="sendPing">PING</el-button>
          <el-button type="danger" @click="disconnect">断开</el-button>
        </el-form-item>
      </el-form>

      <el-card shadow="never" style="background: #0d1117; color: #c9d1d9; margin-top: 12px">
        <pre class="log">{{ logText || '暂无消息…' }}</pre>
      </el-card>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  createRealtimeSession,
  listMyRealtimeSessions,
  acceptRealtimeSession,
  rejectRealtimeSession,
  endRealtimeSession,
} from '@/api/realtime'
import type { RealtimeSessionResponse } from '@/types/api'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const myUid = auth.principalId

const targetUid = ref('')
const sessions = ref<RealtimeSessionResponse[]>([])
const loading = ref(false)
const activeSessionId = ref('')
const wsConnected = ref(false)
const relayTo = ref('')
const relayText = ref('')
const logEntries = ref<string[]>([])
const logText = computed(() => logEntries.value.join('\n'))

let ws: WebSocket | null = null

onMounted(loadSessions)
onUnmounted(() => ws?.close())

async function loadSessions() {
  loading.value = true
  try {
    sessions.value = await listMyRealtimeSessions()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.error || '加载失败')
  } finally {
    loading.value = false
  }
}

async function createSession() {
  if (!targetUid.value) return ElMessage.warning('请输入目标账号 ID')
  loading.value = true
  try {
    const res = await createRealtimeSession(targetUid.value)
    ElMessage.success(`会话已发起：${res.id}（状态 ${res.status}）`)
    targetUid.value = ''
    await loadSessions()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.error || '发起失败')
  } finally {
    loading.value = false
  }
}

async function decide(row: RealtimeSessionResponse, action: 'accept' | 'reject' | 'end') {
  try {
    const res =
      action === 'accept'
        ? await acceptRealtimeSession(row.id)
        : action === 'reject'
          ? await rejectRealtimeSession(row.id)
          : await endRealtimeSession(row.id)
    ElMessage.success(`会话状态：${res.status}`)
    await loadSessions()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.error || '操作失败')
  }
}

function connect(sessionId: string) {
  disconnect()
  const token = auth.accessToken
  if (!token) return ElMessage.warning('未登录')
  const url = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws/realtime?token=${encodeURIComponent(token)}&sessionId=${encodeURIComponent(sessionId)}`
  ws = new WebSocket(url)
  ws.onopen = () => {
    wsConnected.value = true
    activeSessionId.value = sessionId
    log(`[连接] 已建立：${sessionId}`)
  }
  ws.onmessage = (event) => {
    try {
      const envelope = JSON.parse(event.data)
      log(`[接收] ${envelope.type}${envelope.payload ? ' ' + JSON.stringify(envelope.payload) : ''}`)
      if (envelope.type === 'error') {
        ElMessage.error(String(envelope.payload))
      }
    } catch {
      log(`[接收] ${event.data}`)
    }
  }
  ws.onerror = () => {
    log('[错误] WebSocket 连接失败')
    wsConnected.value = false
  }
  ws.onclose = () => {
    log('[断开] 连接已关闭')
    wsConnected.value = false
  }
}

function sendRelay() {
  if (!ws || ws.readyState !== WebSocket.OPEN) return ElMessage.warning('未连接')
  ws.send(
    JSON.stringify({
      type: 'relay',
      sessionId: activeSessionId.value,
      fromUid: myUid,
      toUid: relayTo.value,
      messageId: crypto.randomUUID(),
      ts: Date.now(),
      payload: { text: relayText.value },
    }),
  )
  log(`[发送-relay] -> ${relayTo.value}: ${relayText.value}`)
}

function sendPing() {
  if (!ws || ws.readyState !== WebSocket.OPEN) return ElMessage.warning('未连接')
  ws.send(
    JSON.stringify({
      type: 'ping',
      sessionId: activeSessionId.value,
      fromUid: myUid,
      toUid: myUid,
      messageId: crypto.randomUUID(),
      ts: Date.now(),
      payload: null,
    }),
  )
  log('[发送-ping]')
}

function disconnect() {
  ws?.close()
  ws = null
  wsConnected.value = false
}

function log(line: string) {
  const time = new Date().toLocaleTimeString()
  logEntries.value.push(`[${time}] ${line}`)
  if (logEntries.value.length > 200) logEntries.value.shift()
}

function statusTag(status: string) {
  const map: Record<string, string> = {
    REQUESTED: 'warning',
    ACTIVE: 'success',
    REJECTED: 'danger',
    ENDED: 'info',
  }
  return map[status] || 'info'
}

function statusText(status: string) {
  const map: Record<string, string> = {
    REQUESTED: '待接受',
    ACTIVE: '进行中',
    REJECTED: '已拒绝',
    ENDED: '已结束',
  }
  return map[status] || status
}
</script>

<style scoped>
.log {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  max-height: 300px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
