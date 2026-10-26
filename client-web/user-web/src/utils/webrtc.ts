/**
 * 实时会话的 WebRTC 部分：长连接信令通道 + 原生 RTCPeerConnection + 诚实的状态机。
 *
 * 为什么自己写而不用第三方 SDK：需求是「浏览器原生 RTCPeerConnection，不引依赖」。
 * 代价是状态机得自己兜住——尤其是「连不上」这件事必须如实上报，见 CallPhase.failed。
 *
 * 与服务端的约定（只读确认过 RealtimeWebSocketHandler）：
 * - 握手：/ws/realtime?token=<jwt>&sessionId=<会话 ID>，非参与人或会话已结束会被拒绝；
 * - 发：{ type: 'signal', sessionId, fromUid, toUid, payload: { kind, sdp?, candidate? } }
 *   其中 kind 只能是 offer / answer / candidate / hangup / renegotiate；
 * - signal 只有在会话 ACTIVE 时才会被转发：会话还停在 REQUESTED 时发出去会被丢弃，
 *   所以「先等 ACTIVE 再发 offer」是必须的，否则会建出一条永远连不上的 PeerConnection；
 * - 服务端不解析 SDP，原样转发；fromUid 必须等于登录者本人，toUid 必须是另一位参与者。
 */

export type SignalKind = 'offer' | 'answer' | 'candidate' | 'hangup' | 'renegotiate'

/** 服务端 SIGNAL_KINDS 只认这五个值，其余一律拒绝，所以类型也收窄到这个集合。 */
export interface SignalPayload {
  kind: SignalKind
  sdp?: string
  candidate?: RTCIceCandidateInit
}

/** 会话的媒体形态，对应 sessionType 的媒体子集（BASIC 不建 PeerConnection）。 */
export type MediaShape = 'VOICE' | 'VIDEO' | 'CAMERA'
/** CAMERA 里受护人上行、监护人只看——监护人这一侧绝不允许采集摄像头。 */
export type MediaSide = 'protectee' | 'guardian'

/**
 * 会话类型 → 媒体形态。
 *
 * EMERGENCY 客户端不能发起，但**可能作为接收方收到**（服务端由报警自动派发）。
 * 紧急会话就是视频通话（需求原文：EMERGENCY = video call），所以这里映射到 VIDEO，
 * 绝不能因为「不在可选项里」就退化成纯语音——那会在最需要看到现场时丢掉画面。
 */
export function toMediaShape(sessionType: string): MediaShape | null {
  switch (sessionType) {
    case 'VOICE':
      return 'VOICE'
    case 'VIDEO':
    case 'EMERGENCY':
      return 'VIDEO'
    case 'CAMERA':
      return 'CAMERA'
    default:
      // BASIC 以及未来可能新增的纯信令类型：不建媒体连接
      return null
  }
}

/** 对外暴露的连接阶段；UI 只按这个值说人话，不做二次推断。 */
export type CallPhase =
  | 'connecting-ws'
  | 'waiting-peer'
  | 'negotiating'
  | 'connected'
  | 'peer-left'
  | 'failed'
  | 'closed'

export interface RealtimeSignalSocketHandlers {
  onSignal: (payload: SignalPayload, fromUid: string) => void
  /** joinedUid：对端也连上来了（服务端转发的 session.joined） */
  onJoined: (joinedUid: string) => void
  onRelay: (text: string, fromUid: string) => void
  onError: (message: string) => void
  onClose: () => void
  onOpen: () => void
}

/** 长连接信封（字段与 server-java 的 RealtimeEnvelope 一一对应）。 */
interface Envelope {
  type: string
  sessionId: string
  fromUid: string
  toUid: string
  messageId: string | null
  ts: number
  payload: unknown
}

/**
 * 会话级信令通道。
 *
 * 只做「连上 / 收发 / 断开」，不含 PeerConnection：
 * 信令跟着页面走，PeerConnection 跟着一次通话走，混在一起会导致重连时把正常通话一起丢掉。
 */
export class RealtimeSignalSocket {
  private socket: WebSocket | null = null
  private closedByUs = false
  private retryTimer: number | null = null
  private retryDelay = 1000
  private heartbeat: number | null = null
  /** 连上之前产生的信号先排队：ICE 候选往往比 WebSocket 握手先到，丢了就打不通 */
  private pendingSignals: Envelope[] = []

  constructor(
    private readonly sessionId: string,
    private readonly myUid: string,
    private readonly peerUid: string,
    private readonly handlers: RealtimeSignalSocketHandlers,
  ) {}

  get connected(): boolean {
    return this.socket?.readyState === WebSocket.OPEN
  }

  connect() {
    this.closedByUs = false
    this.disposeSocket()
    const token = localStorage.getItem('access_token')
    if (!token) {
      this.handlers.onError('登录状态已失效，请重新登录')
      return
    }
    // 同源的 /ws 由 vite 代理到后端；token 走 query 是服务端握手拦截器支持的两种方式之一
    const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const url = `${scheme}://${window.location.host}/ws/realtime?token=${encodeURIComponent(token)}&sessionId=${encodeURIComponent(this.sessionId)}`
    let socket: WebSocket
    try {
      socket = new WebSocket(url)
    } catch {
      this.handlers.onError('无法建立信令长连接')
      return
    }
    this.socket = socket
    socket.onopen = () => {
      this.retryDelay = 1000
      this.startHeartbeat()
      this.handlers.onOpen()
      this.flushPending()
    }
    socket.onmessage = (event) => this.handleMessage(event.data)
    // onerror 之后一定会有 onclose，重连逻辑统一放在 onclose，避免两条路径各连一次
    socket.onerror = () => undefined
    socket.onclose = () => {
      this.stopHeartbeat()
      if (this.closedByUs) return
      this.handlers.onClose()
      // 会话仍在进行时自动重连：移动网络下长连接被掐断是常态，直接判死刑太脆
      this.scheduleRetry()
    }
  }

  /** 发送一条信号。返回 false 表示此刻没连上（已排队，连上后自动补发）。 */
  sendSignal(payload: SignalPayload): boolean {
    const envelope: Envelope = {
      type: 'signal',
      sessionId: this.sessionId,
      fromUid: this.myUid,
      toUid: this.peerUid,
      messageId: null,
      ts: Date.now(),
      payload,
    }
    if (!this.connected) {
      // 只缓存最近 50 条：候选人再多也有上限，避免离线圈被无限撑大
      if (this.pendingSignals.length < 50) this.pendingSignals.push(envelope)
      return false
    }
    return this.send(envelope)
  }

  /** 文本中继（BASIC 会话、以及通话前的「先说一句」用）。不排队：过时的文本没有补发价值。 */
  sendRelay(text: string): boolean {
    if (!this.connected) return false
    return this.send({
      type: 'relay',
      sessionId: this.sessionId,
      fromUid: this.myUid,
      toUid: this.peerUid,
      messageId: randomId(),
      ts: Date.now(),
      payload: text,
    })
  }

  /** 主动断开：调用后不再自动重连。 */
  close() {
    this.closedByUs = true
    this.stopHeartbeat()
    if (this.retryTimer !== null) {
      window.clearTimeout(this.retryTimer)
      this.retryTimer = null
    }
    this.pendingSignals = []
    this.disposeSocket()
  }

  private flushPending() {
    const queued = this.pendingSignals
    this.pendingSignals = []
    for (const envelope of queued) this.send(envelope)
  }

  private send(envelope: Envelope): boolean {
    if (!this.connected) return false
    try {
      this.socket!.send(JSON.stringify(envelope))
      return true
    } catch {
      return false
    }
  }

  private handleMessage(raw: unknown) {
    if (typeof raw !== 'string') return
    let envelope: Envelope
    try {
      envelope = JSON.parse(raw) as Envelope
    } catch {
      return
    }
    if (!envelope || typeof envelope.type !== 'string') return
    switch (envelope.type) {
      case 'signal': {
        const payload = envelope.payload as SignalPayload | null
        if (!payload || typeof payload.kind !== 'string') return
        this.handlers.onSignal(payload, envelope.fromUid)
        return
      }
      case 'session.joined':
        this.handlers.onJoined(envelope.fromUid)
        return
      case 'relay':
        if (typeof envelope.payload === 'string') {
          this.handlers.onRelay(envelope.payload, envelope.fromUid)
        }
        return
      case 'error':
        this.handlers.onError(typeof envelope.payload === 'string' ? envelope.payload : '信令通道报错')
        return
      default:
        // pong 等其余控制帧无需处理
        return
    }
  }

  private startHeartbeat() {
    this.stopHeartbeat()
    // 浏览器看不到协议层心跳，只能自己发应用层 ping 探活；间隔取 20s，比常见代理的 60s 空闲阈值更短
    this.heartbeat = window.setInterval(() => {
      this.send({
        type: 'ping',
        sessionId: this.sessionId,
        fromUid: this.myUid,
        toUid: this.myUid,
        messageId: null,
        ts: Date.now(),
        payload: null,
      })
    }, 20000)
  }

  private stopHeartbeat() {
    if (this.heartbeat !== null) {
      window.clearInterval(this.heartbeat)
      this.heartbeat = null
    }
  }

  /** 退避重连，封顶 15 秒。只重连长连接，不重连 PeerConnection（后者已随断线关闭）。 */
  private scheduleRetry() {
    if (this.retryTimer !== null || this.closedByUs) return
    this.retryTimer = window.setTimeout(() => {
      this.retryTimer = null
      this.connect()
    }, this.retryDelay)
    this.retryDelay = Math.min(this.retryDelay * 2, 15000)
  }

  private disposeSocket() {
    const socket = this.socket
    this.socket = null
    if (!socket) return
    socket.onopen = null
    socket.onmessage = null
    socket.onerror = null
    socket.onclose = null
    try {
      socket.close()
    } catch {
      // 已关闭的 socket 再关一次会抛 InvalidStateError，无意义
    }
  }
}

export interface IceServerConfig {
  urls: string[]
  username: string | null
  credential: string | null
  ttlSeconds: number
}

  /** 发出 offer 后等 answer 的时限。超过就如实判定「未接通」，而不是无限转圈。 */
const ANSWER_TIMEOUT_MS = 12_000
/**
 * 重发 offer 的次数上限。
 *
 * 为什么要重发：发起方的 offer 可能在接收方的长连接还没注册到服务端时就发出去了，
 * 服务端找不到连接会静默丢弃（不会回错误）。再发一次就能覆盖这个窗口，
 * 所以第一次超时先重发，三次都无响应才判定失败。
 */
const OFFER_RETRY_LIMIT = 2

export interface PeerCallHandlers {
  onRemoteStream: (stream: MediaStream) => void
  onPhase: (phase: CallPhase, detail?: string) => void
}

/**
 * 一次通话的 PeerConnection 包装。
 *
 * 职责边界：本机媒体采集、SDP 交换、ICE 候选、以及**如实报告连不上**。
 * 不负责长连接（由 RealtimeSignalSocket 持有），也不负责会话状态（由 REST 接口决定）。
 */
export class PeerCall {
  private pc: RTCPeerConnection | null = null
  private localStream: MediaStream | null = null
  private remoteStream: MediaStream | null = null
  private pendingCandidates: RTCIceCandidateInit[] = []
  /** glare 时的让步方：只有建立者发 offer，另一方等 answer */
  private polite = false
  private closed = false
  private connectedOnce = false
  private peerName = '对方'
  /**
   * 等 answer 的定时器。
   *
   * 为什么需要它：offer 或 answer 走的是长连接转发，如果对端的长连接还没在服务端注册，
   * 这条信令会被静默丢掉。此时 ICE 根本不会启动，`connectionState` 永远停在 new，
   * 也就没有任何回调能告诉我们「已经失败了」——不设这个超时就会一直停在「协商中」。
   */
  private answerTimer: number | null = null
  /** 已经重发过几次 offer（只在发起方累计）。 */
  private offerRetries = 0

  /** 由外部注入「把候选 / 描述发出去」的实现，避免本类直接依赖长连接。 */
  onLocalCandidate: ((candidate: RTCIceCandidateInit) => void) | null = null
  onLocalDescription: ((payload: SignalPayload) => void) | null = null

  constructor(
    private readonly shape: MediaShape,
    private readonly side: MediaSide,
    private readonly handlers: PeerCallHandlers,
  ) {}

  get mediaStream(): MediaStream | null {
    return this.localStream
  }

  get audioEnabled(): boolean {
    return this.localStream?.getAudioTracks().every((track) => track.enabled) ?? false
  }

  get videoEnabled(): boolean {
    return this.localStream?.getVideoTracks().every((track) => track.enabled) ?? false
  }

  toggleAudio(enabled: boolean) {
    this.localStream?.getAudioTracks().forEach((track) => (track.enabled = enabled))
  }

  toggleVideo(enabled: boolean) {
    this.localStream?.getVideoTracks().forEach((track) => (track.enabled = enabled))
  }

  /**
   * 采集本机媒体。
   *
   * CAMERA 的监护人侧只收视频，但音频是双向的（术语枚举：CAMERA = 双向音频 + 受护人单向上行视频），
   * 所以这里仍然要麦克风，只是绝不能采集摄像头。
   */
  async prepareLocalMedia(): Promise<MediaStream | null> {
    const wantsVideo = this.shape === 'VIDEO' || (this.shape === 'CAMERA' && this.side === 'protectee')
    const constraints: MediaStreamConstraints = {
      audio: true,
      video: wantsVideo
        ? { width: { ideal: 640 }, height: { ideal: 480 }, facingMode: 'user' }
        : false,
    }
    try {
      this.localStream = await navigator.mediaDevices.getUserMedia(constraints)
      return this.localStream
    } catch (error) {
      throw new Error(describeMediaError(error, wantsVideo))
    }
  }

  /**
   * 建 PeerConnection 并挂上本地轨道。
   *
   * @param initiator 本端是否负责发 offer（只有建立者有这个权利，避免双方同时 offer 撞成 glare）
   * @param peerName  对端显示名，用于把「连不上」说成「与 X 的通话未接通」
   */
  async start(initiator: boolean, peerName: string, ice: IceServerConfig) {
    if (this.closed) throw new Error('通话已结束')
    this.polite = !initiator
    this.peerName = peerName
    const pc = new RTCPeerConnection({
      // 只信服务端下发的 ICE 配置，前端不硬编码 STUN/TURN。
      // 没有 TURN 时会话只能直连或靠 STUN 打洞，跨网失败必须在 UI 上说明，而不是假装成功。
      iceServers: buildIceServers(ice),
      bundlePolicy: 'max-bundle',
    })
    this.pc = pc

    if (this.localStream) {
      for (const track of this.localStream.getTracks()) {
        pc.addTrack(track, this.localStream)
      }
    }
    // 自己没有采集的媒体，也要显式声明想收，否则 SDP 里没有对应的 m-line，对端送不过来
    const sendsVideo = (this.localStream?.getVideoTracks().length ?? 0) > 0
    if (!sendsVideo && (this.shape === 'VIDEO' || this.shape === 'CAMERA')) {
      pc.addTransceiver('video', { direction: 'recvonly' })
    }

    this.remoteStream = new MediaStream()
    pc.ontrack = (event) => {
      const stream = event.streams[0] ?? this.remoteStream
      if (!stream) return
      this.remoteStream = stream
      this.handlers.onRemoteStream(stream)
    }

    pc.onicecandidate = (event) => {
      if (event.candidate) this.onLocalCandidate?.(event.candidate.toJSON())
    }

    pc.onconnectionstatechange = () => this.handleConnectionState()
    pc.oniceconnectionstatechange = () => this.handleConnectionState()

    if (initiator) await this.createAndSendOffer()
  }

  async createAndSendOffer() {
    const pc = this.pc
    if (!pc || this.closed) return
    this.handlers.onPhase('negotiating')
    try {
      const offer = await pc.createOffer()
      await pc.setLocalDescription(offer)
      this.onLocalDescription?.({ kind: 'offer', sdp: offer.sdp })
      this.armAnswerTimeout()
    } catch {
      this.handlers.onPhase('failed', '无法生成本地会话描述（SDP）')
    }
  }

  /** 只有发起方需要等 answer；收到 answer 或连接成功后立刻解除。 */
  private armAnswerTimeout() {
    this.clearAnswerTimeout()
    this.answerTimer = window.setTimeout(() => {
      this.answerTimer = null
      // connectedOnce 为真说明已经连上过，超时判断不再适用
      if (this.closed || this.connectedOnce) return
      const pc = this.pc
      if (pc && (pc.connectionState === 'connected' || pc.connectionState === 'connecting')) return
      // 首次超时先重发一次 offer：接收方的长连接可能比信令晚到，这是最常见的偶发失败
      if (this.offerRetries < OFFER_RETRY_LIMIT) {
        this.offerRetries += 1
        void this.createAndSendOffer()
        return
      }
      this.handlers.onPhase(
        'failed',
        `与${this.peerName}的通话未接通：对方未响应媒体协商请求`,
      )
    }, ANSWER_TIMEOUT_MS)
  }

  private clearAnswerTimeout() {
    if (this.answerTimer !== null) {
      window.clearTimeout(this.answerTimer)
      this.answerTimer = null
    }
  }

  /** 处理对端信号。ICE 候选可能早于远程描述到达，先排队再补。 */
  async handleSignal(payload: SignalPayload) {
    const pc = this.pc
    if (!pc || this.closed) return
    try {
      if (payload.kind === 'offer' && payload.sdp) {
        // glare 处理：双方同时 offer 时让 polite 一方回滚，非 polite 一方忽略对方 offer
        if (pc.signalingState !== 'stable') {
          if (!this.polite) return
          await pc.setLocalDescription({ type: 'rollback' })
        }
        await pc.setRemoteDescription({ type: 'offer', sdp: payload.sdp })
        await this.flushCandidates()
        const answer = await pc.createAnswer()
        await pc.setLocalDescription(answer)
        this.onLocalDescription?.({ kind: 'answer', sdp: answer.sdp })
      } else if (payload.kind === 'answer' && payload.sdp) {
        if (pc.signalingState !== 'have-local-offer') return
        await pc.setRemoteDescription({ type: 'answer', sdp: payload.sdp })
        // answer 已到，等它的定时器必须立刻解除，否则协商正常也会被判成超时
        this.clearAnswerTimeout()
        await this.flushCandidates()
      } else if (payload.kind === 'candidate' && payload.candidate) {
        if (pc.remoteDescription) {
          await pc.addIceCandidate(payload.candidate)
        } else {
          this.pendingCandidates.push(payload.candidate)
        }
      } else if (payload.kind === 'renegotiate') {
        if (this.polite) return
        await this.createAndSendOffer()
      } else if (payload.kind === 'hangup') {
        this.handlers.onPhase('peer-left')
      }
    } catch {
      // SDP / 候选解析失败几乎总是网络中间层改写了内容；如实降级为「未接通」
      this.handlers.onPhase('failed', '与对方的媒体协商失败')
    }
  }

  private async flushCandidates() {
    const pc = this.pc
    if (!pc) return
    const queued = this.pendingCandidates
    this.pendingCandidates = []
    for (const candidate of queued) {
      try {
        await pc.addIceCandidate(candidate)
      } catch {
        // 单条候选失败不影响其余候选
      }
    }
  }

  private handleConnectionState() {
    const pc = this.pc
    if (!pc || this.closed) return
    const state = pc.connectionState
    if (state === 'connected') {
      this.connectedOnce = true
      this.clearAnswerTimeout()
      this.handlers.onPhase('connected')
      return
    }
    if (state === 'connecting' || state === 'new') {
      this.handlers.onPhase('negotiating')
      return
    }
    if (state === 'disconnected') {
      // disconnected 有可能是暂时换网，先如实说「正在恢复」，别急着宣布断了
      this.handlers.onPhase('negotiating', '网络中断，正在尝试恢复')
      return
    }
    if (state === 'failed') {
      this.handlers.onPhase('failed', this.connectedOnce ? '通话中断' : this.failureHint())
      return
    }
    if (state === 'closed') this.handlers.onPhase('closed')
  }

  /** 没连上时的原因猜测——把「没有 TURN」这个最常见原因说出来，而不是只丢一句失败。 */
  failureHint(): string {
    return `与${this.peerName}的通话未接通：ICE 打洞失败。若双方不在同一网络，通常是服务端未配置 TURN 中继所致`
  }

  /** 释放一切媒体资源。幂等：页面卸载与手动挂断都会调。 */
  close() {
    if (this.closed) return
    this.closed = true
    this.clearAnswerTimeout()
    if (this.pc) {
      this.pc.onicecandidate = null
      this.pc.ontrack = null
      this.pc.onconnectionstatechange = null
      this.pc.oniceconnectionstatechange = null
      try {
        this.pc.close()
      } catch {
        // 已关闭的 PC 再 close 会抛，忽略
      }
      this.pc = null
    }
    // 必须停掉每一条轨道：否则摄像头指示灯会一直亮着、麦克风也仍被占用
    if (this.localStream) {
      for (const track of this.localStream.getTracks()) track.stop()
      this.localStream = null
    }
    this.remoteStream = null
    this.pendingCandidates = []
  }
}

/** 把服务端下发的 ICE 配置翻译成 RTCConfiguration.iceServers。 */
export function buildIceServers(ice: IceServerConfig): RTCIceServer[] {
  if (!ice.urls || ice.urls.length === 0) return []
  const entry: RTCIceServer = { urls: ice.urls }
  // 只有 TURN 才需要凭据；给 stun: 地址配上用户名会被部分浏览器直接判为非法配置
  if (ice.username && ice.credential) {
    entry.username = ice.username
    entry.credential = ice.credential
  }
  return [entry]
}

/** 是否配了 TURN。没有 TURN 时要提前在界面上说明跨网可能连不上。 */
export function hasTurnServer(ice: IceServerConfig | null): boolean {
  if (!ice) return false
  return ice.urls.some((url) => url.startsWith('turn:') || url.startsWith('turns:'))
}

/** getUserMedia 的失败原因要分开说：拒绝授权、没有设备、被其它程序占用，处理方式完全不同。 */
export function describeMediaError(error: unknown, needsVideo: boolean): string {
  const device = needsVideo ? '摄像头/麦克风' : '麦克风'
  const name = error instanceof DOMException ? error.name : ''
  if (name === 'NotAllowedError' || name === 'SecurityError') {
    return `${device}权限被拒绝。请在浏览器地址栏的站点权限里允许后重试`
  }
  if (name === 'NotFoundError' || name === 'OverconstrainedError') {
    return `没有找到可用的${device}设备`
  }
  if (name === 'NotReadableError' || name === 'AbortError') {
    return `${device}被其它程序占用，暂时无法采集`
  }
  return `${device}采集失败${name ? `（${name}）` : ''}`
}

function randomId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `m_${Date.now()}_${Math.random().toString(16).slice(2)}`
}
