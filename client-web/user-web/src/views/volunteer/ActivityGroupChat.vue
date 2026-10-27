<template>
  <div class="page-card">
    <el-card>
      <template #header>
        <div class="card-head">
          <span>活动群聊</span>
          <span class="card-head__actions">
            <el-button size="small" :disabled="!activeGroup" @click="openGovernReport">举报群聊/机构</el-button>
            <el-button size="small" :loading="loading" @click="reload">刷新</el-button>
          </span>
        </div>
      </template>

      <!-- 举报群聊/机构（提交给举报治理管理员） -->
      <el-dialog v-model="governReportVisible" title="举报群聊 / 机构" width="440px">
        <el-form label-width="70px">
          <el-form-item label="对象">
            <el-radio-group v-model="governReport.category">
              <el-radio value="GROUP">群聊</el-radio>
              <el-radio value="ORGANIZATION">机构</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="理由">
            <el-input
              v-model="governReport.reason"
              type="textarea"
              :rows="3"
              maxlength="500"
              placeholder="请说明违规情况（500 字以内）"
            />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="governReportVisible = false">取消</el-button>
          <el-button type="danger" :loading="governReporting" @click="submitGovernReport">提交举报</el-button>
        </template>
      </el-dialog>

      <el-skeleton v-if="loading && !groups.length" :rows="4" animated />
      <el-empty v-else-if="!groups.length"
                description="还没有群聊：报名成功（机构审核通过）后自动加入活动群" />

      <div v-else class="groups-layout">
        <aside class="group-list">
          <button v-for="group in groups" :key="group.id" type="button"
                  class="group-item" :class="{ 'is-active': group.id === activeGroupId }"
                  @click="selectGroup(group)">
            <span class="group-title">{{ group.activityTitle || '未命名活动' }}</span>
            <span class="group-meta">
              <el-tag :type="group.status === 'OPEN' ? 'success' : (group.status === 'SUSPENDED' ? 'danger' : 'info')" size="small">
                {{ group.status === 'OPEN' ? '开放中' : (group.status === 'SUSPENDED' ? '暂时封禁' : '已封闭') }}
              </el-tag>
              <span>{{ group.memberCount }} 人</span>
            </span>
          </button>
        </aside>

        <section v-if="activeGroup" class="group-panel">
          <div class="panel-head">
            <span>{{ activeGroup.activityTitle }}</span>
            <span class="panel-org">{{ activeGroup.organizationPublicName }}</span>
          </div>

          <div ref="chatBoxRef" class="chat-box">
            <div v-if="hasMore" class="chat-more">
              <el-button size="small" text :loading="loadingMessages" @click="loadOlder">加载更早的消息</el-button>
            </div>
            <template v-for="message in messages" :key="message.id">
              <!-- 已撤回：只显示墓碑，原文不下发 -->
              <div v-if="message.withdrawn" :key="`t-${message.id}`" class="chat-message is-tombstone">
                <p class="message-content is-tombstone-text">
                  {{ message.senderType === 'ORGANIZATION'
                    ? '机构撤回了一条消息'
                    : `「${message.senderName || '成员'}」撤回了一条消息` }}
                </p>
              </div>
              <div v-else class="chat-message"
                   :class="{ 'is-mine': message.senderUid === myUid, 'is-org': message.senderType === 'ORGANIZATION' }">
                <div class="message-head">
                  <span class="message-sender">
                    {{ message.senderType === 'ORGANIZATION' ? '机构 · ' : '' }}{{ message.senderName || '成员' }}
                  </span>
                  <span class="message-time">{{ formatDateTime(message.createdAt) }}</span>
                  <el-button
                    v-if="canWithdraw(message)"
                    class="message-withdraw" size="small" text
                    @click="withdrawOwn(message)"
                  >撤回</el-button>
                  <el-button
                    v-else-if="message.senderUid !== myUid && message.senderType !== 'ORGANIZATION'
                      && activeGroup.status === 'OPEN'"
                    class="message-report" size="small" text type="danger"
                    @click="report(message)"
                  >举报</el-button>
                </div>
                <p class="message-content">{{ message.content }}</p>
              </div>
            </template>
            <el-empty v-if="!messages.length && !loadingMessages" description="还没有消息，打个招呼吧"
                      :image-size="60" />
          </div>

          <div class="chat-input">
            <el-input v-model="draft" type="textarea" :rows="2" maxlength="2000" show-word-limit
                      :disabled="activeGroup.status !== 'OPEN'"
                      :placeholder="activeGroup.status === 'OPEN' ? '输入文字消息，Ctrl+Enter 发送'
                          : (activeGroup.status === 'SUSPENDED'
                              ? '群聊已被暂时封禁（处罚中），只能查看历史消息'
                              : '群聊已封闭（活动结束），只能查看历史消息')"
                      @keydown.ctrl.enter.prevent="send" />
            <div class="chat-actions">
              <span class="chat-hint">仅支持文字；发布转账、刷单等信息会被拦截</span>
              <el-button type="primary" :disabled="activeGroup.status !== 'OPEN' || !draft.trim()"
                         :loading="sending" @click="send">发送</el-button>
            </div>
          </div>
        </section>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  listGroupMessages,
  listMyActivityGroups,
  reportGroupMessage,
  sendGroupMessage,
  withdrawOwnGroupMessage,
} from '@/api/activity'
import { createAbuseReport } from '@/api/abuse'
import type { ActivityGroupResponse, GroupMessageResponse } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/display'
import { errorMessage } from '@/utils/error'

const POLL_MS = 5000

const route = useRoute()
const auth = useAuthStore()
const myUid = computed(() => auth.principalId ?? '')

const groups = ref<ActivityGroupResponse[]>([])
const activeGroupId = ref('')
const messages = ref<GroupMessageResponse[]>([])
const hasMore = ref(false)
const draft = ref('')

// ---- 举报群聊/机构（治理通道：提交给举报治理管理员） ----
const governReportVisible = ref(false)
const governReporting = ref(false)
const governReport = ref<{ category: 'GROUP' | 'ORGANIZATION'; reason: string }>({
  category: 'GROUP',
  reason: '',
})

function openGovernReport() {
  governReport.value = { category: 'GROUP', reason: '' }
  governReportVisible.value = true
}

async function submitGovernReport() {
  const group = activeGroup.value
  if (!group) return
  if (!governReport.value.reason.trim()) {
    ElMessage.warning('请填写举报理由')
    return
  }
  governReporting.value = true
  try {
    await createAbuseReport({
      category: governReport.value.category,
      targetId: governReport.value.category === 'GROUP' ? group.id : group.organizationId,
      reason: governReport.value.reason.trim(),
    })
    ElMessage.success('举报已提交，感谢你的反馈')
    governReportVisible.value = false
  } catch (e) {
    ElMessage.error(errorMessage(e, '举报失败'))
  } finally {
    governReporting.value = false
  }
}
const loading = ref(false)
const loadingMessages = ref(false)
const sending = ref(false)
let pollTimer: number | undefined
/** 是否已向更早翻页；没翻过页时轮询可整页替换，翻过则按 id 去重合并。 */
let loadedOlder = false

const activeGroup = computed(() => groups.value.find((group) => group.id === activeGroupId.value) ?? null)

onMounted(async () => {
  await reload()
  pollTimer = window.setInterval(pollMessages, POLL_MS)
})

onBeforeUnmount(() => {
  if (pollTimer !== undefined) {
    window.clearInterval(pollTimer)
  }
})

async function reload() {
  loading.value = true
  try {
    groups.value = await listMyActivityGroups()
    if (!activeGroupId.value && groups.value.length) {
      // 从「我的报名」跳转过来时带着 activityId，优先选中对应群。
      const wanted = String(route.query.activityId ?? '')
      const target = groups.value.find((group) => group.activityId === wanted) ?? groups.value[0]
      selectGroup(target)
    } else if (activeGroupId.value) {
      await loadMessages()
    }
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

function selectGroup(group: ActivityGroupResponse) {
  if (group.id === activeGroupId.value) return
  activeGroupId.value = group.id
  messages.value = []
  hasMore.value = false
  loadedOlder = false
  loadMessages()
}

async function loadMessages() {
  if (!activeGroupId.value) return
  loadingMessages.value = true
  try {
    const result = await listGroupMessages(activeGroupId.value, { limit: 50 })
    messages.value = result.records
    hasMore.value = result.hasMore
    loadedOlder = false
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loadingMessages.value = false
  }
}

async function loadOlder() {
  if (!activeGroupId.value || !messages.value.length) return
  loadingMessages.value = true
  try {
    const oldest = messages.value[0]
    const result = await listGroupMessages(activeGroupId.value, {
      before: oldest.createdAt,
      limit: 50,
    })
    messages.value = dedupeMerge(result.records, messages.value)
    hasMore.value = result.hasMore
    loadedOlder = true
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loadingMessages.value = false
  }
}

/** 轮询：拿最新一页合并；群已封闭后不再轮询。 */
async function pollMessages() {
  if (!activeGroupId.value || activeGroup.value?.status !== 'OPEN' || loadingMessages.value) return
  try {
    const result = await listGroupMessages(activeGroupId.value, { limit: 50 })
    const merged = loadedOlder ? dedupeMerge(result.records, messages.value) : result.records
    if (merged.length !== messages.value.length
      || merged[merged.length - 1]?.id !== messages.value[messages.value.length - 1]?.id) {
      messages.value = merged
    }
    hasMore.value = result.hasMore
  } catch {
    // 轮询失败静默，下一次会再试。
  }
}

function dedupeMerge(olderAsc: GroupMessageResponse[], newerAsc: GroupMessageResponse[]) {
  const seen = new Set(olderAsc.map((message) => message.id))
  return [...olderAsc, ...newerAsc.filter((message) => !seen.has(message.id))]
}

async function send() {
  const content = draft.value.trim()
  if (!content || activeGroup.value?.status !== 'OPEN') return
  sending.value = true
  try {
    await sendGroupMessage(activeGroupId.value, content)
    draft.value = ''
    const result = await listGroupMessages(activeGroupId.value, { limit: 50 })
    messages.value = loadedOlder ? dedupeMerge(result.records, messages.value) : result.records
  } catch (error) {
    // 违禁词拦截（400）等信息在这里展示
    ElMessage.error(errorMessage(error))
  } finally {
    sending.value = false
  }
}

/** 后端撤回时限默认 30 分钟（app.activity-group.message-withdraw-window-minutes）；
 *  前端镜像同一窗口只用于隐藏按钮，超窗的后端仍会明确报 409，这里不是裁决方。 */
const WITHDRAW_WINDOW_MS = 30 * 60 * 1000

/** 自己发的志愿者消息可自撤：群开放、未撤回、还在时限内。 */
function canWithdraw(message: GroupMessageResponse): boolean {
  if (activeGroup.value?.status !== 'OPEN' || message.withdrawn) return false
  if (message.senderUid !== myUid.value || message.senderType !== 'VOLUNTEER') return false
  const sentAt = new Date(message.createdAt.replace(' ', 'T')).getTime()
  return !Number.isNaN(sentAt) && Date.now() - sentAt < WITHDRAW_WINDOW_MS
}

async function withdrawOwn(message: GroupMessageResponse) {
  if (!window.confirm('撤回这条消息？撤回后群里只显示「你撤回了一条消息」。')) return
  try {
    await withdrawOwnGroupMessage(message.id)
    await loadMessages()
  } catch (error) {
    // 超窗（409）等信息由后端给出明确文案
    ElMessage.error(errorMessage(error))
    await loadMessages()
  }
}

async function report(message: GroupMessageResponse) {
  const reason = window.prompt(`举报这条消息？\n「${message.content.slice(0, 80)}」\n请填写举报理由：`)
  if (reason == null) return
  const trimmed = reason.trim()
  if (!trimmed) {
    ElMessage.warning('请填写举报理由')
    return
  }
  try {
    await reportGroupMessage(message.id, trimmed)
    ElMessage.success('已提交举报，机构会尽快处理')
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}
</script>

<style scoped>
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.card-head__actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.groups-layout {
  display: flex;
  gap: 16px;
  align-items: stretch;
  min-height: 480px;
}
.group-list {
  flex: 0 0 240px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  border-right: 1px solid var(--line);
  padding-right: 12px;
}
.group-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
  text-align: left;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: transparent;
  cursor: pointer;
}
.group-item.is-active {
  border-color: var(--line-strong);
  background: var(--paper-tint);
}
.group-title {
  font-size: 14px;
  color: var(--ink-1);
}
.group-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--ink-3);
}
.group-panel {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.panel-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--line);
  color: var(--ink-1);
  font-weight: 600;
}
.panel-org {
  font-size: 12px;
  font-weight: 400;
  color: var(--ink-3);
}
.chat-box {
  height: 380px;
  overflow-y: auto;
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin: 12px 0;
}
.chat-more {
  text-align: center;
}
.chat-message {
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 8px 10px;
  max-width: 78%;
}
.chat-message.is-mine {
  align-self: flex-end;
  background: var(--paper-tint);
}
.chat-message.is-org {
  border-color: var(--line-strong);
}
.message-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 12px;
  color: var(--ink-3);
}
.message-sender {
  color: var(--ink-2);
  font-weight: 600;
}
.message-report {
  margin-left: auto;
  padding: 0;
  height: auto;
}
.message-content {
  margin: 4px 0 0;
  font-size: 14px;
  color: var(--ink-1);
  white-space: pre-wrap;
  word-break: break-word;
}
.chat-message.is-tombstone {
  align-self: center;
  max-width: 90%;
  border-style: dashed;
  background: transparent;
}
.is-tombstone-text {
  font-size: 12px;
  color: var(--ink-4);
  margin: 0;
  white-space: normal;
}
.chat-input {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.chat-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.chat-hint {
  font-size: 12px;
  color: var(--ink-4);
}
</style>
