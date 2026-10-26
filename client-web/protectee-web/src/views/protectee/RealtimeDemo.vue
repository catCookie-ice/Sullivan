<template>
  <div class="page-card">
    <el-card>
      <template #header>实时会话</template>

      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 16px">
        <template #title>
          只能与存在「生效绑定」的监护人通话；一旦解除绑定，通话会立刻中断。
          界面上的「对方」一律按登录名查找，账号内部编号不对外显示。
        </template>
      </el-alert>

      <!-- 发起会话 -->
      <el-form :inline="true" :disabled="inCall || !!waiting">
        <el-form-item label="对方登录名">
          <el-select
            v-model="targetLoginName"
            filterable
            allow-create
            default-first-option
            placeholder="选择已绑定监护人，或直接输入登录名"
            style="width: 260px"
          >
            <el-option
              v-for="option in peerOptions"
              :key="option.loginName"
              :label="option.label"
              :value="option.loginName"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="会话类型">
          <el-radio-group v-model="sessionType">
            <el-radio v-for="option in sessionTypeOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="creating" @click="onStart">发起会话</el-button>
        </el-form-item>
      </el-form>
      <div class="type-hint">{{ currentTypeHint }}</div>

      <!-- 呼叫中 / 通话中 -->
      <el-card v-if="inCall || waiting" shadow="never" class="call-card">
        <div class="call-header">
          <div>
            <span class="call-peer">{{ activePeerName || waiting?.peerName || '对方' }}</span>
            <el-tag :type="phaseTagType" size="small" style="margin-left: 8px">{{ phaseText }}</el-tag>
          </div>
          <div class="call-meta">
            <template v-if="phase === 'connected'">{{ formatDuration(elapsedSeconds * 1000) }}</template>
            <template v-else-if="waiting">剩余 {{ waiting.remaining }} 秒</template>
          </div>
        </div>

        <el-alert
          v-if="phase === 'waiting-peer'"
          type="warning"
          :closable="false"
          show-icon
          title="已呼叫，等待对方接听"
          :description="`超过 ${callTimeoutSeconds} 秒未接听将自动结束本次呼叫。`"
          style="margin-bottom: 12px"
        />
        <el-alert
          v-if="phase === 'failed'"
          type="error"
          :closable="false"
          show-icon
          :title="phaseText"
          :description="phaseDetail && phaseDetail !== phaseText ? phaseDetail : ''"
          style="margin-bottom: 12px"
        />
        <el-alert
          v-if="iceSummary && !iceSummary.turn && showMedia"
          type="warning"
          :closable="false"
          show-icon
          title="服务端未配置 TURN 中继：双方不在同一网络时可能无法接通"
          style="margin-bottom: 12px"
        />

        <!-- 媒体区域：没有真正 connected 就不显示「通话中」 -->
        <div v-if="showMedia && hasVideo" class="media-grid">
          <div class="media-box">
            <video ref="remoteVideo" autoplay playsinline class="media-el"></video>
            <span class="media-label">对方画面</span>
            <div v-if="!remoteStream" class="media-placeholder">{{ mediaPlaceholder }}</div>
          </div>
          <div v-if="showLocalPreview" class="media-box">
            <video ref="localVideo" autoplay playsinline muted class="media-el"></video>
            <span class="media-label">我的画面</span>
          </div>
        </div>

        <!-- 语音通话没有画面，只说明音频状态，避免摆一个永远空着的视频框 -->
        <div v-else-if="showMedia && !hasVideo" class="audio-note">
          语音通话：{{ microphoneOn ? '麦克风已开启' : '麦克风已静音' }}
        </div>

        <!-- 纯文本会话 -->
        <div v-if="phase === 'texting'" class="relay-box">
          <div class="relay-log">
            <div v-for="(message, index) in relayMessages" :key="index" class="relay-line">
              <span class="relay-time">{{ message.at }}</span>
              <span :class="message.from === 'me' ? 'relay-me' : 'relay-peer'">
                {{ message.from === 'me' ? '我' : activePeerName }}：{{ message.text }}
              </span>
            </div>
            <div v-if="relayMessages.length === 0" class="relay-empty">还没有消息</div>
          </div>
          <div class="relay-input">
            <el-input v-model="relayDraft" placeholder="输入一句话" @keyup.enter="onSendRelay" />
            <el-button type="primary" @click="onSendRelay">发送</el-button>
          </div>
        </div>

        <div class="call-actions">
          <template v-if="showMedia">
            <el-button @click="toggleMicrophone">{{ microphoneOn ? '静音' : '取消静音' }}</el-button>
            <el-button v-if="canToggleCamera" @click="toggleCamera">
              {{ cameraOn ? '关闭摄像头' : '打开摄像头' }}
            </el-button>
          </template>
          <el-button v-if="waiting" type="warning" @click="onCancelWait">取消呼叫</el-button>
          <el-button v-else-if="activeSession" type="danger" @click="hangup">结束会话</el-button>
        </div>
      </el-card>

      <!-- 会话请求横幅：一次点击即进入会话 -->
      <el-alert
        v-for="request in incomingRequests"
        :key="request.id"
        type="success"
        :closable="false"
        style="margin-bottom: 12px"
      >
        <template #title>
          <span>{{ displayOf(request) }} 邀请你进行「{{ sessionTypeLabel(request.sessionType) }}」</span>
          <el-button
            type="success"
            size="small"
            style="margin-left: 12px"
            :loading="acceptingId === request.id"
            @click="onAccept(request)"
          >
            接受
          </el-button>
          <el-button size="small" @click="onReject(request)">拒绝</el-button>
        </template>
      </el-alert>

      <!-- 会话列表 -->
      <div class="list-header">
        <span class="list-title">我的会话</span>
        <el-button size="small" :loading="loading" @click="refresh">刷新</el-button>
      </div>
      <el-table :data="sessions" v-loading="loading" border>
        <el-table-column label="对方">
          <template #default="{ row }">
            <div>{{ displayOf(row) }}</div>
            <div class="sub-text">{{ row.initiatorUid === myUid ? '监护人' : '受护人' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="120">
          <template #default="{ row }">
            <el-tag :type="sessionTypeTagType(row.sessionType)" size="small">
              {{ sessionTypeLabel(row.sessionType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="发起时间" width="180">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="200">
          <template #default="{ row }">
            <template v-if="row.status === 'REQUESTED' && row.targetUid === myUid">
              <el-button size="small" type="success" :loading="acceptingId === row.id" @click="onAccept(row)">
                接受
              </el-button>
              <el-button size="small" @click="onReject(row)">拒绝</el-button>
            </template>
            <template v-else-if="row.status === 'REQUESTED' && row.initiatorUid === myUid">
              <span class="sub-text">等待接听</span>
              <el-button size="small" @click="cancelOutgoing(row)">撤回</el-button>
            </template>
            <template v-else-if="row.status === 'ACTIVE'">
              <el-button size="small" type="primary" @click="onRejoin(row)">进入会话</el-button>
              <el-button size="small" type="danger" @click="onEndRow(row)">结束</el-button>
            </template>
            <span v-else class="sub-text">—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useCallSession } from '@/composables/useCallSession'
import { listMyBindings } from '@/api/binding'
import { endRealtimeSession } from '@/api/realtime'
import type { BindingResponse, ClientRealtimeSessionType, RealtimeSessionResponse } from '@/types/api'
import {
  formatDateTime,
  formatDuration,
  personLabel,
  sessionTypeLabel,
  sessionTypeTagType,
} from '@/utils/display'

// 受护人端永远是「受护人」视角：CAMERA 会话里由受护人上传摄像头画面与声音
const {
  sessions,
  loading,
  creating,
  incomingRequests,
  refresh,
  startSession,
  acceptAndJoin,
  rejectIncoming,
  rejoinActive,
  cancelOutgoing,
  hangup,
  phase,
  phaseDetail,
  phaseText,
  activeSession,
  activePeerName,
  localStream,
  remoteStream,
  microphoneOn,
  cameraOn,
  elapsedSeconds,
  waiting,
  iceSummary,
  relayMessages,
  inCall,
  sendRelay,
  toggleMicrophone,
  toggleCamera,
} = useCallSession('protectee')

/** 与服务端/组合式函数里的超时保持一致（60 秒） */
const callTimeoutSeconds = 60

const myUid = localStorage.getItem('principal_id') || ''

const targetLoginName = ref('')
const sessionType = ref<ClientRealtimeSessionType>('VOICE')
const acceptingId = ref('')
const relayDraft = ref('')
const remoteVideo = ref<HTMLVideoElement | null>(null)
const localVideo = ref<HTMLVideoElement | null>(null)
const bindings = ref<BindingResponse[]>([])

/**
 * 会话类型选项。
 * 刻意不提供「紧急会话」：服务端只允许报警自动发起，客户端选了也会被 403 拒绝。
 */
const sessionTypeOptions: Array<{ value: ClientRealtimeSessionType; label: string; hint: string }> = [
  { value: 'BASIC', label: '文本会话', hint: '只发送文字，不打开麦克风与摄像头。' },
  { value: 'VOICE', label: '语音通话', hint: '双方建立双向语音，只使用麦克风。' },
  { value: 'VIDEO', label: '视频通话', hint: '双方建立双向语音与视频，双方都会打开摄像头。' },
  {
    value: 'CAMERA',
    label: '共享摄像头',
    hint: '由你上传摄像头画面与声音，监护人只接收、不会打开自己的摄像头。',
  },
]

const currentTypeHint = computed(
  () => sessionTypeOptions.find((option) => option.value === sessionType.value)?.hint || '',
)

/** 已绑定的监护人：优先从列表里选，避免手抄登录名抄错。 */
const peerOptions = computed(() =>
  bindings.value
    .filter((binding) => binding.status === 'ACTIVE' && binding.protecteeId === myUid)
    .map((binding) => {
      const loginName = binding.guardianLoginName || ''
      const label = personLabel(binding.guardianName, loginName)
      return { loginName, label: loginName && label !== loginName ? `${label}（${loginName}）` : label }
    })
    .filter((option) => !!option.loginName),
)

/**
 * VOICE/VIDEO/CAMERA/EMERGENCY 才需要媒体面板；纯文本会话只有文本。
 * 等待接听阶段还没建连接，也不该显示画面。
 */
const showMedia = computed(
  () =>
    !!activeSession.value &&
    activeSession.value.sessionType !== 'BASIC' &&
    phase.value !== 'waiting-peer',
)
/** 本地预览只在真的采集了视频时出现。 */
const showLocalPreview = computed(() => !!localStream.value?.getVideoTracks().length)
/** 摄像头开关只对本端有摄像头轨道时才有意义。 */
const canToggleCamera = computed(() => !!localStream.value?.getVideoTracks().length)
/**
 * 这次会话是否涉及画面：VIDEO 双方都有，CAMERA 受护人上行、监护人接收，
 * EMERGENCY 是报警自动发起的视频通话，同样有画面。
 * VOICE 完全没有视频轨道，摆一个黑框只会让人以为画面坏了。
 */
const hasVideo = computed(() => {
  const type = activeSession.value?.sessionType
  return type === 'VIDEO' || type === 'CAMERA' || type === 'EMERGENCY'
})

const phaseTagType = computed(() => {
  switch (phase.value) {
    case 'connected':
      return 'success'
    case 'failed':
      return 'danger'
    case 'waiting-peer':
    case 'negotiating':
    case 'connecting-ws':
      return 'warning'
    default:
      return 'info'
  }
})

const mediaPlaceholder = computed(() => {
  if (phase.value === 'connected') return '等待对方画面…'
  if (phase.value === 'failed') return '未接通'
  return '正在连接…'
})

/** 会话里的对方展示名：显示名优先，回落登录名；绝不回落到 uid。 */
function displayOf(row: RealtimeSessionResponse): string {
  const isMe = row.initiatorUid === myUid
  const name = isMe ? row.targetName : row.initiatorName
  const loginName = isMe ? row.targetLoginName : row.initiatorLoginName
  return personLabel(name, loginName)
}

async function loadBindings() {
  try {
    bindings.value = await listMyBindings()
  } catch {
    // 绑定列表只用于下拉建议，取不到不影响手动输入登录名
    bindings.value = []
  }
}

void loadBindings()

// 远端视频流到达后绑定到 <video>：必须显式赋值，不能靠 src 字符串
watch(remoteStream, async (stream) => {
  await nextTick()
  if (remoteVideo.value && remoteVideo.value.srcObject !== stream) {
    remoteVideo.value.srcObject = stream
  }
})

watch(localStream, async (stream) => {
  await nextTick()
  if (localVideo.value && localVideo.value.srcObject !== stream) {
    localVideo.value.srcObject = stream
  }
})

async function onStart() {
  await startSession(targetLoginName.value, sessionType.value)
  await refresh()
}

async function onAccept(row: RealtimeSessionResponse) {
  acceptingId.value = row.id
  try {
    // 一次点击直接进入会话：不弹确认框，也不要求发起方再点一次
    await acceptAndJoin(row)
  } finally {
    acceptingId.value = ''
  }
}

async function onReject(row: RealtimeSessionResponse) {
  await rejectIncoming(row)
}

async function onRejoin(row: RealtimeSessionResponse) {
  await rejoinActive(row)
}

async function onCancelWait() {
  // 优先用列表里的那条（状态最新）；列表还没刷到时退回 activeSession，
  // 否则「取消呼叫」会静默失效，用户只能干等 60 秒超时。
  const target =
    sessions.value.find((item) => item.id === waiting.value?.sessionId) ||
    (activeSession.value?.id === waiting.value?.sessionId ? activeSession.value : null)
  if (target) await cancelOutgoing(target)
}

async function onEndRow(row: RealtimeSessionResponse) {
  try {
    await ElMessageBox.confirm('确认结束该会话？', '结束会话', { type: 'warning' })
  } catch {
    return
  }
  try {
    await endRealtimeSession(row.id)
    ElMessage.success('已结束')
  } catch (e: any) {
    ElMessage.error(e.response?.data?.error || '结束失败')
  }
  await refresh()
}

function onSendRelay() {
  if (sendRelay(relayDraft.value)) relayDraft.value = ''
}

/** 会话状态的中文名与标签色（REQUESTED / ACTIVE / REJECTED / ENDED 四态）。 */
function statusText(status: string) {
  const map: Record<string, string> = {
    REQUESTED: '待接听',
    ACTIVE: '进行中',
    REJECTED: '已拒绝',
    ENDED: '已结束',
  }
  return map[status] || status
}

function statusTag(status: string) {
  const map: Record<string, 'success' | 'warning' | 'danger' | 'info'> = {
    REQUESTED: 'warning',
    ACTIVE: 'success',
    REJECTED: 'danger',
    ENDED: 'info',
  }
  return map[status] || 'info'
}
</script>

<style scoped>
.type-hint {
  margin: -6px 0 16px;
  font-size: 12px;
  color: var(--ink-soft, #7a7264);
}
.call-card {
  margin-bottom: 16px;
  border: 1px dashed rgba(63, 58, 50, 0.35);
}
.call-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.call-peer {
  font-size: 16px;
  font-weight: 600;
}
.call-meta {
  font-size: 13px;
  color: var(--ink-soft, #7a7264);
}
.media-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px;
  margin-bottom: 12px;
}
.media-box {
  position: relative;
  background: #2c2822;
  border-radius: 8px;
  overflow: hidden;
  min-height: 180px;
}
.media-el {
  width: 100%;
  height: 220px;
  object-fit: contain;
  display: block;
}
.media-label {
  position: absolute;
  left: 8px;
  bottom: 6px;
  font-size: 12px;
  color: #f0e9d8;
}
.media-placeholder {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #cfc5ae;
  font-size: 13px;
}
.call-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.audio-note {
  margin-bottom: 12px;
  padding: 12px;
  background: rgba(63, 58, 50, 0.06);
  border-radius: 8px;
  font-size: 13px;
}
.relay-box {
  margin-bottom: 12px;
}
.relay-log {
  max-height: 220px;
  overflow-y: auto;
  padding: 8px;
  background: rgba(63, 58, 50, 0.05);
  border-radius: 8px;
  margin-bottom: 8px;
}
.relay-line {
  font-size: 13px;
  line-height: 1.7;
}
.relay-time {
  color: var(--ink-soft, #7a7264);
  margin-right: 8px;
  font-size: 12px;
}
.relay-me {
  color: #266b3f;
}
.relay-peer {
  color: #1f4d78;
}
.relay-empty {
  font-size: 12px;
  color: var(--ink-soft, #7a7264);
}
.relay-input {
  display: flex;
  gap: 8px;
}
.list-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 20px 0 10px;
}
.list-title {
  font-weight: 600;
}
.sub-text {
  font-size: 12px;
  color: var(--ink-soft, #7a7264);
}
</style>
