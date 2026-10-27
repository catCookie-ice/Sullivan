<template>
  <div class="page-card">
    <el-card>
      <template #header>
        <div class="card-head">
          <span>活动群聊</span>
          <el-button size="small" :loading="loading" @click="reload">刷新</el-button>
        </div>
      </template>

      <el-skeleton v-if="loading && !groups.length" :rows="4" animated />
      <el-empty v-else-if="!groups.length"
                description="还没有活动群聊：志愿者报名通过后会自动建群并入群" />

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
          <el-tabs v-model="activeTab">
            <el-tab-pane label="群聊" name="chat">
              <div ref="chatBoxRef" class="chat-box">
                <div v-if="hasMore" class="chat-more">
                  <el-button size="small" text :loading="loadingMessages" @click="loadOlder">加载更早的消息</el-button>
                </div>
                <template v-for="message in messages" :key="message.id">
                  <!-- 已撤回：只显示墓碑，原文不下发 -->
                  <div v-if="message.withdrawn" :key="`t-${message.id}`"
                       class="chat-message is-tombstone">
                    <p class="message-content is-tombstone-text">
                      {{ message.senderType === 'ORGANIZATION'
                        ? '机构撤回了一条消息'
                        : `「${message.senderName || '成员'}」撤回了一条消息` }}
                    </p>
                  </div>
                  <div v-else class="chat-message"
                       :class="{ 'is-org': message.senderType === 'ORGANIZATION' }">
                    <div class="message-head">
                      <span class="message-sender">{{ message.senderName || '成员' }}</span>
                      <span class="message-time">{{ formatDateTime(message.createdAt) }}</span>
                      <el-button v-if="canWithdraw(message)" class="message-withdraw"
                                 size="small" text type="danger"
                                 @click="withdraw(message)">撤回</el-button>
                    </div>
                    <p class="message-content">{{ message.content }}</p>
                  </div>
                </template>
                <el-empty v-if="!messages.length && !loadingMessages" description="还没有消息"
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
                  <span class="chat-hint">仅支持文字；违禁词会被拦截</span>
                  <el-button type="primary" :disabled="activeGroup.status !== 'OPEN' || !draft.trim()"
                             :loading="sending" @click="send">发送</el-button>
                </div>
              </div>
            </el-tab-pane>

            <el-tab-pane :label="`举报（${pendingReportCount} 待处理）`" name="reports">
              <el-empty v-if="!reports.length" description="没有举报记录" />
              <el-table v-else :data="reports" style="width: 100%">
                <el-table-column label="被举报内容" min-width="220" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.messageContent }}</template>
                </el-table-column>
                <el-table-column label="举报理由" min-width="160" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.reason }}</template>
                </el-table-column>
                <el-table-column label="举报人" width="120">
                  <template #default="{ row }">{{ row.reporterName || row.reporterUid }}</template>
                </el-table-column>
                <el-table-column label="状态" width="100">
                  <template #default="{ row }">
                    <el-tag :type="reportTag(row.status)" size="small">{{ reportText(row.status) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="190" fixed="right">
                  <template #default="{ row }">
                    <template v-if="row.status === 'PENDING'">
                      <el-button size="small" type="danger" :loading="handlingReport === row.id"
                                 @click="decideReport(row, true)">受理并撤回</el-button>
                      <el-button size="small" :loading="handlingReport === row.id"
                                 @click="decideReport(row, false)">驳回</el-button>
                    </template>
                    <span v-else class="done-hint">已处理</span>
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>
          </el-tabs>
        </section>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  decideGroupReport,
  listOrgActivityGroups,
  listOrgGroupMessages,
  listOrgGroupReports,
  sendOrgGroupMessage,
  withdrawOrgGroupMessage,
} from '@/api/org'
import type { ActivityGroupResponse, GroupMessageResponse, GroupReportResponse } from '@/types/api'
import { formatDateTime } from '@/utils/display'
import { errorMessage } from '@/utils/error'

const POLL_MS = 5000

const groups = ref<ActivityGroupResponse[]>([])
const activeGroupId = ref('')
const activeTab = ref('chat')
const messages = ref<GroupMessageResponse[]>([])
const reports = ref<GroupReportResponse[]>([])
const hasMore = ref(false)
const draft = ref('')
const loading = ref(false)
const loadingMessages = ref(false)
const sending = ref(false)
const handlingReport = ref('')
let pollTimer: number | undefined
/** 是否已向更早翻页；没翻过页时轮询可直接整页替换，翻过则按 id 去重合并。 */
let loadedOlder = false

const activeGroup = computed(() => groups.value.find((group) => group.id === activeGroupId.value) ?? null)
const pendingReportCount = computed(() => reports.value.filter((report) => report.status === 'PENDING').length)

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
    groups.value = await listOrgActivityGroups()
    if (!activeGroupId.value && groups.value.length) {
      selectGroup(groups.value[0])
    } else if (activeGroupId.value) {
      await Promise.all([loadMessages(), loadReports()])
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
  reports.value = []
  hasMore.value = false
  loadedOlder = false
  loadMessages()
  loadReports()
}

async function loadMessages() {
  if (!activeGroupId.value) return
  loadingMessages.value = true
  try {
    const result = await listOrgGroupMessages(activeGroupId.value, { limit: 50 })
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
    const result = await listOrgGroupMessages(activeGroupId.value, {
      before: toCursor(oldest.createdAt),
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

/** 轮询：拿最新一页，与已加载数据按 id 去重合并（已翻历史时不丢旧消息）。 */
async function pollMessages() {
  if (!activeGroupId.value || activeGroup.value?.status !== 'OPEN' || loadingMessages.value) return
  try {
    const result = await listOrgGroupMessages(activeGroupId.value, { limit: 50 })
    const merged = loadedOlder ? dedupeMerge(result.records, messages.value) : result.records
    if (merged.length !== messages.value.length
      || merged[merged.length - 1]?.id !== messages.value[messages.value.length - 1]?.id) {
      messages.value = merged
    }
    hasMore.value = result.hasMore
  } catch {
    // 轮询失败静默，不打断阅读；下一次轮询会再试。
  }
}

function dedupeMerge(olderAsc: GroupMessageResponse[], newerAsc: GroupMessageResponse[]) {
  const seen = new Set(olderAsc.map((message) => message.id))
  return [...olderAsc, ...newerAsc.filter((message) => !seen.has(message.id))]
}

/** 后端游标接受 yyyy-MM-dd HH:mm:ss 或 ISO；直接用序列化回来的字符串。 */
function toCursor(createdAt: string) {
  return createdAt
}

async function send() {
  const content = draft.value.trim()
  if (!content || activeGroup.value?.status !== 'OPEN') return
  sending.value = true
  try {
    await sendOrgGroupMessage(activeGroupId.value, content)
    draft.value = ''
    const result = await listOrgGroupMessages(activeGroupId.value, { limit: 50 })
    messages.value = loadedOlder ? dedupeMerge(result.records, messages.value) : result.records
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    sending.value = false
  }
}

/** 后端撤回时限默认 30 分钟（app.activity-group.message-withdraw-window-minutes）；
 *  前端镜像同一窗口只用于隐藏按钮，超窗的后端仍会明确报 409，这里不是裁决方。 */
const WITHDRAW_WINDOW_MS = 30 * 60 * 1000

/** 机构可撤回 = 群开放、消息未撤、还在时限内。 */
function canWithdraw(message: GroupMessageResponse): boolean {
  if (activeGroup.value?.status !== 'OPEN' || message.withdrawn) return false
  const sentAt = new Date(message.createdAt.replace(' ', 'T')).getTime()
  return !Number.isNaN(sentAt) && Date.now() - sentAt < WITHDRAW_WINDOW_MS
}

async function withdraw(message: GroupMessageResponse) {
  if (!window.confirm('确定撤回这条消息？撤回后所有人只看到「已撤回」墓碑。')) return
  try {
    await withdrawOrgGroupMessage(message.id)
    ElMessage.success('已撤回')
    await loadMessages()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function loadReports() {
  if (!activeGroupId.value) return
  try {
    reports.value = await listOrgGroupReports(activeGroupId.value, {})
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function decideReport(report: GroupReportResponse, withdrawMessage: boolean) {
  let note: string | undefined
  if (withdrawMessage) {
    if (!window.confirm('受理该举报并撤回被举报消息？')) return
  } else {
    const input = window.prompt('驳回意见（可留空）') ?? ''
    note = input.trim() || undefined
  }
  handlingReport.value = report.id
  try {
    await decideGroupReport(report.id, { withdrawMessage, note })
    ElMessage.success(withdrawMessage ? '已受理并撤回消息' : '已驳回')
    await Promise.all([loadReports(), loadMessages()])
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    handlingReport.value = ''
  }
}

function reportText(status: string) {
  const map: Record<string, string> = { PENDING: '待处理', RESOLVED: '已受理', DISMISSED: '已驳回' }
  return map[status] ?? status
}

function reportTag(status: string) {
  const map: Record<string, string> = { PENDING: 'warning', RESOLVED: 'success', DISMISSED: 'info' }
  return (map[status] ?? 'info') as 'warning' | 'success' | 'info'
}
</script>

<style scoped>
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.groups-layout {
  display: flex;
  gap: 16px;
  align-items: stretch;
  min-height: 480px;
}
.group-list {
  flex: 0 0 260px;
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
  margin-bottom: 12px;
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
.chat-message.is-org {
  align-self: flex-end;
  background: var(--paper-tint);
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
.message-withdraw {
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
.done-hint {
  font-size: 12px;
  color: var(--ink-4);
}
</style>
