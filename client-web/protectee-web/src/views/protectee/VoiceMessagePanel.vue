<template>
  <div class="page-card">
    <el-card>
      <template #header>语音留言</template>

      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 16px">
        <template #title>
          录音通过浏览器直接采集并上传，播放时才临时取回音频。
          界面只显示双方登录名与显示名，账号内部编号不对外显示。
        </template>
      </el-alert>

      <!-- 环境不支持时的说明：不假装能录，直接告诉用户换浏览器/换地址 -->
      <el-alert
        v-if="!recorderSupported"
        type="error"
        :closable="false"
        show-icon
        title="当前浏览器不支持录音"
        :description="unsupportedReason"
      />

      <template v-else>
        <!-- 录音区 -->
        <div class="record-row">
          <el-form :inline="true" :disabled="recording || uploading">
            <el-form-item label="发给">
              <el-select
                v-model="receiverLoginName"
                filterable
                allow-create
                default-first-option
                placeholder="选择已绑定联系人，或直接输入登录名"
                style="width: 240px"
              >
                <el-option
                  v-for="option in peerOptions"
                  :key="option.loginName"
                  :label="option.label"
                  :value="option.loginName"
                />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button
                v-if="!recording"
                type="primary"
                :disabled="uploading"
                @click="startRecording"
              >
                开始录音
              </el-button>
              <el-button v-else type="danger" @click="stopRecording">停止录音</el-button>
            </el-form-item>
          </el-form>

          <div v-if="recording" class="recording-bar">
            <span class="dot" />
            <span>录音中 {{ formatDuration(elapsedMs) }}</span>
            <span class="sub-text">最多 {{ Math.round(maxDurationMs / 1000) }} 秒</span>
          </div>
          <div v-if="uploading" class="sub-text">正在上传…</div>
          <div v-if="recordedBlob" class="preview-row">
            <audio ref="previewAudio" controls :src="previewUrl || undefined" />
            <span class="sub-text">
              {{ formatDuration(recordedDurationMs) }} · {{ formatBytes(recordedBlob.size) }}
            </span>
            <el-button type="primary" :loading="uploading" @click="upload">发送</el-button>
            <el-button @click="discardRecording">丢弃</el-button>
          </div>
        </div>

        <el-divider />

        <!-- 收件箱 -->
        <div class="list-header">
          <span class="list-title">我的留言</span>
          <el-button size="small" :loading="loading" @click="load">刷新</el-button>
        </div>

        <el-radio-group v-model="filter" size="small" style="margin-bottom: 12px">
          <el-radio-button value="all">全部</el-radio-button>
          <el-radio-button value="received">收到的</el-radio-button>
          <el-radio-button value="sent">发出的</el-radio-button>
        </el-radio-group>

        <el-table :data="filteredMessages" v-loading="loading" border>
          <el-table-column label="方向" width="90">
            <template #default="{ row }">
              <el-tag :type="row.myRole === 'RECEIVER' ? 'success' : 'info'" size="small">
                {{ row.myRole === 'RECEIVER' ? '收到' : '发出' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="对方">
            <template #default="{ row }">
              <div>{{ counterpartLabel(row) }}</div>
              <div class="sub-text">
                {{ row.myRole === 'RECEIVER' ? '来自' : '发给' }}{{ counterpartSubLabel(row) }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="时长" width="90">
            <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="180">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" min-width="260">
            <template #default="{ row }">
              <el-button
                size="small"
                type="primary"
                :loading="playingId === row.id"
                @click="togglePlay(row)"
              >
                {{ playingId === row.id ? '停止' : '播放' }}
              </el-button>
              <el-button
                v-if="row.myRole === 'RECEIVER' && row.status !== 'LISTENED'"
                size="small"
                @click="markListened(row)"
              >
                标记已听
              </el-button>
              <el-button size="small" type="danger" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!loading && filteredMessages.length === 0" description="还没有语音留言" />
      </template>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createVoiceMessage,
  deleteVoiceMessage,
  fetchVoiceMessageAudio,
  listVoiceMessages,
  markVoiceMessageListened,
} from '@/api/voice'
import { listMyBindings } from '@/api/binding'
import { getUserByLoginName } from '@/api/lookup'
import type { BindingResponse, VoiceMessageResponse } from '@/types/api'
import {
  formatBytes,
  formatDateTime,
  formatDuration,
  personLabel,
  personSubLabel,
} from '@/utils/display'

const myUid = localStorage.getItem('principal_id') || ''

/**
 * 服务端只接受这几种音频类型（VoiceMessageService 的白名单）。
 * Chrome/Edge 默认录出的是 audio/webm，**不在白名单里**，所以必须先按顺序探测能录的格式，
 * 录不了就明确告诉用户不支持，而不是录完再上传失败。
 */
const ALLOWED_MIME_TYPES = [
  'audio/mp4',
  'audio/aac',
  'audio/3gpp',
  'audio/amr',
  'audio/ogg',
] as const

/** 录音时长上限：60 秒。留言不是通话，太长既不便收听也占存储。与服务端默认值一致。 */
const maxDurationMs = 60_000
/** 体积上限 1 MB，与服务端 app.voice-message.max-size-bytes 的默认值一致。 */
const MAX_SIZE_BYTES = 1024 * 1024

const loading = ref(false)
const uploading = ref(false)
const messages = ref<VoiceMessageResponse[]>([])
const filter = ref<'all' | 'received' | 'sent'>('all')
const bindings = ref<BindingResponse[]>([])

const receiverLoginName = ref('')
const recording = ref(false)
const elapsedMs = ref(0)
const recordedBlob = ref<Blob | null>(null)
const recordedDurationMs = ref(0)
const previewUrl = ref('')
const previewAudio = ref<HTMLAudioElement | null>(null)
const playingId = ref('')

let recorder: MediaRecorder | null = null
let recorderStream: MediaStream | null = null
let chunks: Blob[] = []
let recordStartAt = 0
let elapsedTicker: number | null = null
let autoStopTimer: number | null = null
/** 正在播放的音频元素与其 blob URL：切换/停止时必须撤销，否则 blob 会一直占内存 */
let playingAudio: HTMLAudioElement | null = null
let playingUrl = ''

// 环境能力探测：非 https / 非 localhost 时 mediaDevices 直接不存在
const hasMediaDevices = typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getUserMedia
const supportedMimeType = pickMimeType()
const recorderSupported = computed(() => hasMediaDevices && !!supportedMimeType)
const unsupportedReason = computed(() => {
  if (!hasMediaDevices) {
    return '浏览器只在 https 或 localhost 下开放麦克风。请用 http://localhost 或 https 地址打开本页。'
  }
  return `浏览器不支持服务端接受的音频格式（${ALLOWED_MIME_TYPES.join(' / ')}）。请改用 Chrome 或 Edge 的较新版本。`
})

/** 绑定列表只用于下拉建议，取不到不影响手动输入登录名 */
const peerOptions = computed(() =>
  bindings.value
    .filter((binding) => binding.status === 'ACTIVE')
    .map((binding) => {
      // 我是监护人就发给受护人，反之发给监护人
      const iAmGuardian = binding.guardianId === myUid
      const loginName = iAmGuardian ? binding.protecteeLoginName : binding.guardianLoginName
      const displayName = iAmGuardian ? binding.protecteeName : binding.guardianName
      const label = personLabel(displayName, loginName)
      return {
        loginName: loginName || '',
        label: loginName && label !== loginName ? `${label}（${loginName}）` : label,
      }
    })
    .filter((option) => !!option.loginName),
)

const filteredMessages = computed(() => {
  if (filter.value === 'received') return messages.value.filter((m) => m.myRole === 'RECEIVER')
  if (filter.value === 'sent') return messages.value.filter((m) => m.myRole === 'SENDER')
  return messages.value
})

/** 对方的展示名：显示名 > 登录名 > 未知用户，绝不回落到 uid（响应里本来也没有 uid）。 */
function counterpartLabel(row: VoiceMessageResponse): string {
  return row.myRole === 'RECEIVER'
    ? personLabel(row.senderName, row.senderLoginName)
    : personLabel(row.receiverName, row.receiverLoginName)
}

function counterpartSubLabel(row: VoiceMessageResponse): string {
  return row.myRole === 'RECEIVER'
    ? personSubLabel(row.senderName, row.senderLoginName)
    : personSubLabel(row.receiverName, row.receiverLoginName)
}

onMounted(async () => {
  await Promise.all([load(), loadBindings()])
})

onBeforeUnmount(() => {
  stopTicker()
  stopPlayback()
  if (recorder && recorder.state !== 'inactive') recorder.stop()
  recorderStream?.getTracks().forEach((track) => track.stop())
  releaseRecorderStream()
  if (previewUrl.value) {
    URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = ''
  }
})

async function loadBindings() {
  try {
    bindings.value = await listMyBindings()
  } catch {
    bindings.value = []
  }
}

async function load() {
  loading.value = true
  try {
    messages.value = await listVoiceMessages()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.error || '加载留言失败')
  } finally {
    loading.value = false
  }
}

/** 按顺序挑一个浏览器确实能录、且服务端也接受的 MIME 类型。 */
function pickMimeType(): string {
  if (typeof MediaRecorder === 'undefined') return ''
  for (const type of ALLOWED_MIME_TYPES) {
    if (MediaRecorder.isTypeSupported(type)) return type
  }
  return ''
}

function stopTicker() {
  if (elapsedTicker !== null) {
    window.clearInterval(elapsedTicker)
    elapsedTicker = null
  }
  if (autoStopTimer !== null) {
    window.clearTimeout(autoStopTimer)
    autoStopTimer = null
  }
}

async function startRecording() {
  if (!recorderSupported.value) return ElMessage.error(unsupportedReason.value)
  try {
    recorderStream = await navigator.mediaDevices.getUserMedia({ audio: true })
  } catch (error) {
    const name = error instanceof DOMException ? error.name : ''
    if (name === 'NotAllowedError' || name === 'SecurityError') {
      ElMessage.error('麦克风权限被拒绝，请在浏览器的站点权限里允许后重试')
    } else if (name === 'NotFoundError') {
      ElMessage.error('没有找到可用的麦克风设备')
    } else if (name === 'NotReadableError') {
      ElMessage.error('麦克风被其它程序占用，暂时无法录音')
    } else {
      ElMessage.error('无法打开麦克风')
    }
    return
  }
  discardRecording()
  chunks = []
  try {
    recorder = new MediaRecorder(recorderStream, { mimeType: supportedMimeType })
  } catch {
    ElMessage.error('无法创建录音器，请更换浏览器重试')
    releaseRecorderStream()
    return
  }
  recorder.ondataavailable = (event) => {
    if (event.data && event.data.size > 0) chunks.push(event.data)
  }
  recorder.onstop = () => {
    // 用探测到的类型收尾，保证 Blob 的 type 与上传时声明的 contentType 一致
    const blob = new Blob(chunks, { type: supportedMimeType })
    const duration = Math.min(Date.now() - recordStartAt, maxDurationMs)
    releaseRecorderStream()
    // 服务端要求时长 ≥ 1 毫秒：手抖点两下产生的空录音直接丢掉，别让它变成一次必然失败的上传
    if (duration < 1 || blob.size === 0) {
      ElMessage.warning('录音太短，没有内容可发送')
      return
    }
    if (blob.size > MAX_SIZE_BYTES) {
      // 服务端上限 1 MB；超出时如实告知并丢弃，而不是等上传完再看 400
      ElMessage.error(
        `录音体积 ${formatBytes(blob.size)} 超过上限 ${formatBytes(MAX_SIZE_BYTES)}，请缩短录音后重试`,
      )
      return
    }
    recordedBlob.value = blob
    recordedDurationMs.value = duration
    previewUrl.value = URL.createObjectURL(blob)
  }
  recorder.start()
  recording.value = true
  recordStartAt = Date.now()
  elapsedMs.value = 0
  elapsedTicker = window.setInterval(() => {
    elapsedMs.value = Date.now() - recordStartAt
  }, 200)
  // 到点自动停：避免用户忘记按停止，录出一个超大文件
  autoStopTimer = window.setTimeout(() => {
    if (recording.value) {
      stopRecording()
      ElMessage.info(`已达到最长录音时长 ${Math.round(maxDurationMs / 1000)} 秒`)
    }
  }, maxDurationMs)
}

function stopRecording() {
  stopTicker()
  recording.value = false
  if (recorder && recorder.state !== 'inactive') recorder.stop()
  recorder = null
}

function releaseRecorderStream() {
  recorderStream?.getTracks().forEach((track) => track.stop())
  recorderStream = null
}

function discardRecording() {
  if (previewUrl.value) {
    URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = ''
  }
  recordedBlob.value = null
  recordedDurationMs.value = 0
  elapsedMs.value = 0
  chunks = []
}

async function upload() {
  const blob = recordedBlob.value
  if (!blob) return
  const login = receiverLoginName.value.trim()
  if (!login) return ElMessage.warning('请选择或填写接收人的登录名')
  uploading.value = true
  try {
    // 界面收登录名，这里换成 uid 再上传——uid 是内部标识，不摆到界面上
    let target
    try {
      target = await getUserByLoginName(login)
    } catch (e: any) {
      ElMessage.error(e.response?.data?.error || `没有找到登录名为「${login}」的账号`)
      return
    }
    if (target.id === myUid) {
      ElMessage.warning('不能给自己发送语音留言')
      return
    }
    await createVoiceMessage({
      receiverUid: target.id,
      durationMs: recordedDurationMs.value,
      sizeBytes: blob.size,
      file: blob,
      contentType: supportedMimeType,
    })
    ElMessage.success(`已发送给 ${personLabel(target.displayName, target.loginName)}`)
    discardRecording()
    await load()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.error || '上传失败')
  } finally {
    uploading.value = false
  }
}

/**
 * 播放 / 停止。
 *
 * 服务端的 /audio 需要 Bearer 令牌，所以**不能**把地址塞进 <audio src>：
 * 那样请求不会带 Authorization，只会得到 401。这里先 fetch 成 blob，
 * 用完立刻 revokeObjectURL。
 */
async function togglePlay(row: VoiceMessageResponse) {
  if (playingId.value === row.id) {
    stopPlayback()
    return
  }
  stopPlayback()
  playingId.value = row.id
  try {
    const blob = await fetchVoiceMessageAudio(row.id)
    const url = URL.createObjectURL(blob)
    playingUrl = url
    const audio = new Audio(url)
    playingAudio = audio
    audio.onended = () => stopPlayback()
    audio.onerror = () => {
      stopPlayback()
      ElMessage.error('音频播放失败')
    }
    await audio.play()
  } catch (e: any) {
    ElMessage.error(e?.message || '无法取回音频')
    stopPlayback()
  }
}

function stopPlayback() {
  if (playingAudio) {
    playingAudio.onended = null
    playingAudio.onerror = null
    playingAudio.pause()
    playingAudio = null
  }
  if (playingUrl) {
    URL.revokeObjectURL(playingUrl)
    playingUrl = ''
  }
  playingId.value = ''
}

async function markListened(row: VoiceMessageResponse) {
  try {
    await markVoiceMessageListened(row.id)
    ElMessage.success('已标记为已听')
    await load()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.error || '标记失败')
  }
}

async function remove(row: VoiceMessageResponse) {
  const confirmed = await ElMessageBox.confirm('删除后音频无法恢复，确认删除？', '删除留言', {
    type: 'warning',
  }).catch(() => false)
  if (!confirmed) return
  try {
    if (playingId.value === row.id) stopPlayback()
    await deleteVoiceMessage(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.error || '删除失败')
  }
}

function statusTag(status: string) {
  const map: Record<string, 'success' | 'warning' | 'info' | 'danger'> = {
    SENT: 'warning',
    LISTENED: 'success',
    DELETED: 'info',
  }
  return map[status] || 'info'
}

function statusText(status: string) {
  const map: Record<string, string> = {
    SENT: '未听',
    LISTENED: '已听',
    DELETED: '已删除',
  }
  return map[status] || status
}
</script>

<style scoped>
.record-row {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.recording-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #b3462f;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #b3462f;
  animation: pulse 1s infinite;
}
@keyframes pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.25;
  }
}
.preview-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.preview-row audio {
  height: 34px;
}
.list-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 0 0 10px;
}
.list-title {
  font-weight: 600;
}
.sub-text {
  font-size: 12px;
  color: var(--ink-soft, #7a7264);
}
</style>
