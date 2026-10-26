/**
 * 一次「实时会话」的完整客户端流程：发起 → 等待接听 → 建立媒体 → 结束。
 *
 * 放在 composable 里而不是 view 里，是因为两个 Web 应用（监护人端 / 受护人端）的
 * 流程完全一致，只有「我是哪一方」不同（决定 CAMERA 会话里谁上行视频）。
 *
 * 三条硬性约束（都是服务端实际行为，不是设计偏好）：
 * 1. 接受即进入：接收方点一次「接受」→ accept 接口把会话置 ACTIVE → 本端立刻建 PeerConnection，
 *    不需要发起方再点第二次，也不存在「已接受但未连接」的中间态。
 * 2. 发起方必须走「等待」通道：服务端不会把「已接受」推给发起方（长连接只转发 relay/signal），
 *    所以只能轮询会话列表；超过 60 秒没变 ACTIVE 就主动结束会话并如实显示「对方未接听」。
 * 3. 信令只在 ACTIVE 时被转发：因此建 PeerConnection 必须发生在确认 ACTIVE 之后，
 *    提前发 offer 只会得到一条永远连不通的通路。
 */
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { ElMessage } from 'element-plus'
import {
  acceptRealtimeSession,
  createRealtimeSession,
  endRealtimeSession,
  getIceServers,
  listMyRealtimeSessions,
  rejectRealtimeSession,
} from '@/api/realtime'
import { getUserByLoginName } from '@/api/lookup'
import { PeerCall, RealtimeSignalSocket, hasTurnServer, toMediaShape } from '@/utils/webrtc'
import type { CallPhase, MediaSide, SignalPayload } from '@/utils/webrtc'
import { personLabel } from '@/utils/display'
import type { ClientRealtimeSessionType, RealtimeSessionResponse } from '@/types/api'

/** 界面阶段：CallPhase 之外还有「没有通话」「纯文本会话」两种。 */
export type UiPhase = CallPhase | 'idle' | 'texting'

/** 发起方等待接听的时限。60 秒足够一个正在找手机的人接起来，又不至于让人干等。 */
const CALL_TIMEOUT_MS = 60_000
/** 等待期内轮询会话状态的间隔：2 秒是「点完几乎立刻有反应」的体感上限。 */
const WAIT_POLL_MS = 2_000
/** 空闲时刷新会话列表的间隔，用于发现别人发来的会话请求。 */
const IDLE_POLL_MS = 5_000

export interface RelayMessage {
  from: 'me' | 'peer'
  text: string
  at: string
}

export function useCallSession(side: MediaSide) {
  const sessions = ref<RealtimeSessionResponse[]>([])
  const loading = ref(false)
  const creating = ref(false)

  const phase = ref<UiPhase>('idle')
  const phaseDetail = ref('')
  const activeSession = ref<RealtimeSessionResponse | null>(null)
  const localStream = shallowRef<MediaStream | null>(null)
  const remoteStream = shallowRef<MediaStream | null>(null)
  const microphoneOn = ref(true)
  const cameraOn = ref(false)
  const elapsedSeconds = ref(0)
  const relayMessages = ref<RelayMessage[]>([])

  /** 发起后等待接听中的会话（用于显示倒计时）。 */
  const waiting = ref<{ sessionId: string; peerName: string; remaining: number } | null>(null)
  /** 服务端下发的 ICE 摘要，用于提前告诉用户「没有 TURN 时跨网可能连不上」。 */
  const iceSummary = ref<{ configured: boolean; turn: boolean } | null>(null)

  const myUid = localStorage.getItem('principal_id') || ''

  let socket: RealtimeSignalSocket | null = null
  let call: PeerCall | null = null
  /**
   * 建连之前收到的信号先存着。
   *
   * 真实竞态：对方的 offer 可能在「长连接已开、ICE 配置还没取回」的几百毫秒内到达，
   * 此时 PeerCall 还不存在。直接丢弃就等于这次通话永远连不上，所以必须先排队。
   */
  let pendingSignals: SignalPayload[] = []
  let waitDeadline = 0
  let waitTicker: number | null = null
  let statusPoller: number | null = null
  let idlePoller: number | null = null
  let elapsedTicker: number | null = null

  const inCall = computed(() =>
    ['connecting-ws', 'waiting-peer', 'negotiating', 'connected', 'texting'].includes(phase.value),
  )

  /** 阶段文案：只说客观状态，不提前宣布成功。 */
  const phaseText = computed(() => {
    switch (phase.value) {
      case 'idle':
        return '未在会话中'
      case 'connecting-ws':
        return '正在建立信令连接…'
      case 'waiting-peer':
        // 发起方视角：会话已建立但对方还没点「接受」。服务端不会推送接受事件，
        // 所以这句话会一直保留到轮询到 ACTIVE 或 60 秒超时为止。
        return '已呼叫，等待对方接听'
      case 'negotiating':
        return '正在协商媒体连接…'
      case 'connected':
        return '通话中'
      case 'texting':
        return '文本会话中'
      case 'peer-left':
        return '对方已挂断'
      case 'failed':
        return phaseDetail.value || '通话未能建立'
      case 'closed':
        return '通话已结束'
      default:
        return ''
    }
  })

  const activePeerName = computed(() => {
    const session = activeSession.value
    if (!session) return ''
    return personLabel(peerName(session), peerLoginName(session))
  })

  onMounted(() => {
    refresh()
    startIdlePolling()
  })

  onBeforeUnmount(() => {
    cleanup()
    // 必须摘掉全局监听：路由切换后组件已卸载，残留的监听会让旧实例的闭包一直被 window 持有
    if (typeof window !== 'undefined') {
      window.removeEventListener('beforeunload', cleanup)
    }
  })
  // 关标签页 / 刷新时必须释放摄像头与麦克风，否则设备指示灯会一直亮
  if (typeof window !== 'undefined') {
    window.addEventListener('beforeunload', cleanup)
  }
  // ---------------------------------------------------------------- 列表

  async function refresh() {
    loading.value = true
    try {
      sessions.value = await listMyRealtimeSessions()
    } catch (e: any) {
      ElMessage.error(e.response?.data?.error || '加载会话列表失败')
    } finally {
      loading.value = false
    }
  }

  /** 会话里「对方」的 uid：只是内部用，展示一律走 peerName/peerLoginName。 */
  function peerUid(session: RealtimeSessionResponse): string {
    return session.initiatorUid === myUid ? session.targetUid : session.initiatorUid
  }

  function peerName(session: RealtimeSessionResponse): string {
    return (session.initiatorUid === myUid ? session.targetName : session.initiatorName) || ''
  }

  function peerLoginName(session: RealtimeSessionResponse): string {
    return (session.initiatorUid === myUid ? session.targetLoginName : session.initiatorLoginName) || ''
  }

  function isInitiator(session: RealtimeSessionResponse): boolean {
    return session.initiatorUid === myUid
  }

  /** 我作为接收方、且还没处理过的会话请求。 */
  const incomingRequests = computed(() =>
    sessions.value.filter((s) => s.status === 'REQUESTED' && s.targetUid === myUid),
  )

  /** 我发出去、还没被处理的会话。 */
  const outgoingRequests = computed(() =>
    sessions.value.filter((s) => s.status === 'REQUESTED' && s.initiatorUid === myUid),
  )

  // ---------------------------------------------------------------- 发起

  /**
   * 按登录名发起会话。
   *
   * 界面只让用户输入登录名；这里查成 uid 再提交——uid 是服务端内部标识，不该被用户看见或抄写。
   */
  async function startSession(loginName: string, sessionType: ClientRealtimeSessionType) {
    const login = loginName.trim()
    if (!login) {
      ElMessage.warning('请填写对方的登录名')
      return
    }
    if (inCall.value || waiting.value) {
      ElMessage.warning('请先结束当前会话')
      return
    }
    creating.value = true
    try {
      let target
      try {
        target = await getUserByLoginName(login)
      } catch (e: any) {
        ElMessage.error(e.response?.data?.error || `没有找到登录名为「${login}」的账号`)
        return
      }
      if (target.id === myUid) {
        ElMessage.warning('不能和自己建立会话')
        return
      }
      // 同一对人只允许一条 REQUESTED/ACTIVE 会话：先在本地挡一次，省掉必然失败的请求；
      // 真正的唯一性仍由服务端保证（重复时返回 409）。
      const existed = sessions.value.find(
        (s) =>
          (s.status === 'REQUESTED' || s.status === 'ACTIVE') && peerUid(s) === target.id,
      )
      if (existed) {
        ElMessage.warning('与该用户的会话已在进行中，请先在列表中处理')
        return
      }
      const displayName = personLabel(target.displayName, target.loginName)
      const session = await createRealtimeSession(target.id, sessionType)
      await refresh()
      if (session.status === 'ACTIVE') {
        // 理论上不会发生（创建后必为 REQUESTED），但真发生了就直接进会话，别让用户困惑
        await joinSession(session)
        return
      }
      beginWaiting(session, displayName)
    } catch (e: any) {
      ElMessage.error(e.response?.data?.error || '发起会话失败')
    } finally {
      creating.value = false
    }
  }

  function beginWaiting(session: RealtimeSessionResponse, peerDisplayName: string) {
    phase.value = 'waiting-peer'
    phaseDetail.value = '已呼叫，等待对方接听'
    activeSession.value = session
    waiting.value = {
      sessionId: session.id,
      peerName: peerDisplayName,
      remaining: Math.floor(CALL_TIMEOUT_MS / 1000),
    }
    waitDeadline = Date.now() + CALL_TIMEOUT_MS
    stopWaitTimers()
    waitTicker = window.setInterval(() => {
      if (!waiting.value) return
      const remaining = Math.max(0, Math.ceil((waitDeadline - Date.now()) / 1000))
      waiting.value = { ...waiting.value, remaining }
      if (remaining <= 0) void cancelUnanswered(session.id)
    }, 1000)
    // 服务端不推送「已接受」，只能轮询；一旦变 ACTIVE 就立刻建媒体连接
    statusPoller = window.setInterval(() => void pollWaiting(session.id), WAIT_POLL_MS)
  }

  async function pollWaiting(sessionId: string) {
    if (!waiting.value) return
    let list: RealtimeSessionResponse[] = []
    try {
      list = await listMyRealtimeSessions()
    } catch {
      return
    }
    sessions.value = list
    const current = list.find((s) => s.id === sessionId)
    if (!current || !waiting.value) {
      await stopWaiting()
      return
    }
    if (current.status === 'ACTIVE') {
      await stopWaiting()
      await joinSession(current)
      return
    }
    if (current.status === 'REJECTED') {
      await stopWaiting()
      phase.value = 'failed'
      phaseDetail.value = '对方已拒绝本次会话'
      activeSession.value = null
      ElMessage.info('对方已拒绝本次会话')
      return
    }
    if (current.status === 'ENDED') {
      await stopWaiting()
      phase.value = 'failed'
      phaseDetail.value = '会话已结束'
      activeSession.value = null
    }
  }

  /** 超时未接听：主动结束会话，并如实说明原因（而不是静默留在「呼叫中」）。 */
  async function cancelUnanswered(sessionId: string) {
    await stopWaiting()
    closeMedia()
    try {
      await endRealtimeSession(sessionId)
    } catch {
      // 对方可能刚好同时结束，409 无所谓
    }
    activeSession.value = null
    phase.value = 'failed'
    phaseDetail.value = '对方未接听'
    ElMessage.warning('对方未接听')
    await refresh()
  }

  async function stopWaiting() {
    waiting.value = null
    stopWaitTimers()
    await Promise.resolve()
  }

  function stopWaitTimers() {
    if (waitTicker !== null) {
      window.clearInterval(waitTicker)
      waitTicker = null
    }
    if (statusPoller !== null) {
      window.clearInterval(statusPoller)
      statusPoller = null
    }
  }

  /** 发起方撤回自己还没被接听的呼叫。 */
  async function cancelOutgoing(session: RealtimeSessionResponse) {
    if (waiting.value?.sessionId === session.id) await stopWaiting()
    closeMedia()
    try {
      await endRealtimeSession(session.id)
      ElMessage.success('已取消呼叫')
    } catch (e: any) {
      ElMessage.error(e.response?.data?.error || '取消失败')
    }
    phase.value = 'idle'
    activeSession.value = null
    await refresh()
  }

  // ---------------------------------------------------------------- 接收方：一次点击即进入

  async function acceptAndJoin(session: RealtimeSessionResponse) {
    try {
      const accepted = await acceptRealtimeSession(session.id)
      // 接受成功即视为进入会话：不再弹二次确认，也不要求发起方再操作
      await joinSession(accepted)
      await refresh()
    } catch (e: any) {
      ElMessage.error(e.response?.data?.error || '接受会话失败')
      await refresh()
    }
  }

  async function rejectIncoming(session: RealtimeSessionResponse) {
    try {
      await rejectRealtimeSession(session.id)
      ElMessage.success('已拒绝')
      await refresh()
    } catch (e: any) {
      ElMessage.error(e.response?.data?.error || '拒绝失败')
    }
  }

  /** 对已 ACTIVE 的会话点「进入」：用于刷新页面后重新接回自己正在进行的会话。 */
  async function rejoinActive(session: RealtimeSessionResponse) {
    await joinSession(session)
  }

  // ---------------------------------------------------------------- 建立媒体

  async function joinSession(session: RealtimeSessionResponse) {
    activeSession.value = session
    closeMedia()
    relayMessages.value = []
    phase.value = 'connecting-ws'
    phaseDetail.value = ''

    const type = session.sessionType
    // 信令通道对纯文本会话也有用：文本中继走同一条长连接
    const shape = toMediaShape(type)
    socket = new RealtimeSignalSocket(session.id, myUid, peerUid(session), {
      onOpen: () => {
        if (!shape) {
          phase.value = 'texting'
        } else if (phase.value === 'connecting-ws') {
          phase.value = 'negotiating'
        }
      },
      onSignal: (payload) => void handleSignal(payload),
      onJoined: () => {
        // 对端也连上来了；真正的连接状态以 PeerConnection 为准
      },
      onRelay: (text) => {
        relayMessages.value.push({ from: 'peer', text, at: new Date().toLocaleTimeString() })
      },
      onError: (message) => {
        phaseDetail.value = message
        ElMessage.error(message)
      },
      onClose: () => {
        if (inCall.value) phaseDetail.value = '信令连接已断开，正在重连…'
      },
    })
    socket.connect()

    if (!shape) return

    const initiator = isInitiator(session)
    const peerDisplayName = personLabel(peerName(session), peerLoginName(session))

    try {
      const ice = await getIceServers()
      iceSummary.value = { configured: ice.urls.length > 0, turn: hasTurnServer(ice) }
      if (ice.urls.length === 0) {
        ElMessage.warning('服务端未配置 STUN/TURN，只有同一局域网内才可能连通')
      } else if (!hasTurnServer(ice)) {
        ElMessage.warning('服务端未配置 TURN 中继，跨网络时可能无法接通')
      }

      call = new PeerCall(shape, side, {
        onRemoteStream: (stream) => {
          remoteStream.value = stream
        },
        onPhase: (next, detail) => {
          if (detail) phaseDetail.value = detail
          phase.value = next
          if (next === 'connected') {
            startElapsedTicker()
          } else if (next === 'failed' || next === 'peer-left' || next === 'closed') {
            stopElapsedTicker()
            if (next === 'failed' && detail) ElMessage.error(detail)
          }
        },
      })
      if (socket) {
        call.onLocalCandidate = (candidate) => socket?.sendSignal({ kind: 'candidate', candidate })
        call.onLocalDescription = (payload) => socket?.sendSignal(payload)
      }

      const local = await call.prepareLocalMedia()
      localStream.value = local
      microphoneOn.value = true
      cameraOn.value = (local?.getVideoTracks().length ?? 0) > 0

      await call.start(initiator, peerDisplayName, ice)
      await flushPendingSignals()
    } catch (e: any) {
      // 媒体权限/设备失败：如实说清是哪一种，并结束这次会话，绝不假装在通话
      const message = e?.message || '无法建立媒体连接'
      phase.value = 'failed'
      phaseDetail.value = message
      ElMessage.error(message)
      closeMedia()
      try {
        await endRealtimeSession(session.id)
      } catch {
        // 已经结束了就忽略
      }
      await refresh()
    }
  }

  async function handleSignal(payload: SignalPayload) {
    if (payload.kind === 'hangup') {
      stopElapsedTicker()
      closeMedia()
      phase.value = 'peer-left'
      phaseDetail.value = '对方已挂断'
      activeSession.value = null
      await refresh()
      return
    }
    if (!call) {
      // PeerCall 还在准备（取 ICE 配置、申请权限），先排队，就绪后按序补处理
      if (pendingSignals.length < 100) pendingSignals.push(payload)
      return
    }
    await call.handleSignal(payload)
  }

  /** PeerCall 建好后，把排队中的信号按到达顺序补处理。 */
  async function flushPendingSignals() {
    const queued = pendingSignals
    pendingSignals = []
    for (const payload of queued) {
      await call?.handleSignal(payload)
    }
  }

  // ---------------------------------------------------------------- 结束

  /** 挂断：先告诉对端（让它立刻停掉采集），再结束服务端会话，最后释放本地资源。 */
  async function hangup() {
    const session = activeSession.value
    stopElapsedTicker()
    stopWaiting()
    if (session) {
      socket?.sendSignal({ kind: 'hangup' })
      try {
        await endRealtimeSession(session.id)
      } catch {
        // 对端可能已经结束；结束会话是幂等的，报错不影响本端收尾
      }
    }
    closeMedia()
    phase.value = 'closed'
    phaseDetail.value = '会话已结束'
    activeSession.value = null
    await refresh()
  }

  function toggleMicrophone() {
    microphoneOn.value = !microphoneOn.value
    call?.toggleAudio(microphoneOn.value)
  }

  function toggleCamera() {
    cameraOn.value = !cameraOn.value
    call?.toggleVideo(cameraOn.value)
  }

  function sendRelay(text: string): boolean {
    const content = text.trim()
    if (!content) return false
    if (!socket?.sendRelay(content)) {
      ElMessage.warning('信令连接尚未就绪，消息未发出')
      return false
    }
    relayMessages.value.push({ from: 'me', text: content, at: new Date().toLocaleTimeString() })
    return true
  }

  /** 释放一切媒体与长连接。幂等。 */
  function closeMedia() {
    if (call) {
      call.close()
      call = null
    }
    if (socket) {
      socket.close()
      socket = null
    }
    pendingSignals = []
    localStream.value = null
    remoteStream.value = null
    stopElapsedTicker()
  }

  function cleanup() {
    stopWaitTimers()
    stopIdlePolling()
    closeMedia()
  }

  // ---------------------------------------------------------------- 轮询与计时

  function startIdlePolling() {
    stopIdlePolling()
    idlePoller = window.setInterval(() => {
      // 等待接听由 pollWaiting 自己轮询（2 秒一次，要尽快感知 ACTIVE）；
      // 通话进行中也不刷新列表，避免表格抖动
      if (phase.value === 'waiting-peer') return
      if (phase.value === 'connecting-ws' || phase.value === 'negotiating' || phase.value === 'connected') {
        return
      }
      void listMyRealtimeSessions()
        .then((list) => {
          sessions.value = list
        })
        .catch(() => undefined)
    }, IDLE_POLL_MS)
  }

  function stopIdlePolling() {
    if (idlePoller !== null) {
      window.clearInterval(idlePoller)
      idlePoller = null
    }
  }

  function startElapsedTicker() {
    stopElapsedTicker()
    const startedAt = Date.now()
    elapsedSeconds.value = 0
    elapsedTicker = window.setInterval(() => {
      elapsedSeconds.value = Math.floor((Date.now() - startedAt) / 1000)
    }, 1000)
  }

  function stopElapsedTicker() {
    if (elapsedTicker !== null) {
      window.clearInterval(elapsedTicker)
      elapsedTicker = null
    }
    elapsedSeconds.value = 0
  }

  return {
    // 列表
    sessions,
    loading,
    creating,
    incomingRequests,
    outgoingRequests,
    refresh,
    // 会话动作
    startSession,
    acceptAndJoin,
    rejectIncoming,
    rejoinActive,
    cancelOutgoing,
    hangup,
    // 通话状态
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
    // 展示辅助
    peerName,
    peerLoginName,
    isInitiator,
  }
}
