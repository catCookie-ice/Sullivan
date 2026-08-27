<template>
  <el-form v-bind="$attrs" :model="model" @submit.prevent>
    <slot />
  </el-form>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, provide, ref, toRaw, watch } from 'vue'
import { ElForm, ElMessage, ElMessageBox } from 'element-plus'
import { useRoute } from 'vue-router'
import {
  KEEP_AUTO_FORM_KEY,
  debounce,
  getPath,
  isDraftEmpty,
  omitPaths,
  readDraft,
  removeDraft,
  setPath,
  writeDraft,
} from './useKeepAuto'
import type { KeepAutoFormContext } from './useKeepAuto'

/**
 * 模块级去重锁：防止同一保存键的恢复弹窗叠加。
 * 原因：组件可能被重复挂载（HMR、路由重进、同一页面多个 KeepAutoForm 检测到同一草稿），
 * 若每次都 ElMessageBox.confirm 会叠加多个弹窗。这里以 saveKey 为粒度，
 * 弹窗期间置位、弹窗结束（确认/丢弃）后释放。
 */
const pendingPrompts = new Set<string>()

const props = withDefaults(
  defineProps<{
    /** 表单数据对象（与 el-form 的 :model 一致，必填）。 */
    model: Record<string, any>
    /** keep_auto 标识：标记整个表单自动保存。 */
    keepAuto?: boolean
    /** 自定义存储键；缺省时回退到当前路由路径。同一页面存在多个表单时应显式传值以隔离。 */
    saveKey?: string
    /** 保存草稿时剔除的字段（点分路径），用于排除 password 等敏感字段。 */
    exclude?: string[]
  }>(),
  {
    keepAuto: false,
    saveKey: undefined,
    exclude: () => [],
  },
)

defineOptions({ inheritAttrs: false })

// 路由路径在表单生命周期内稳定，setup 时读取一次即可。
let routePath = ''
try {
  routePath = useRoute().path
} catch {
  routePath = ''
}
const saveKey = computed(() => props.saveKey || routePath || 'form')

/** 子组件注册的字段（字段级 keep_auto 模式）。 */
interface RegisteredField {
  prop: string
  keepAuto: boolean
}
const registeredFields = ref<RegisteredField[]>([])

const formMode = computed(() => props.keepAuto)
const active = computed(() => formMode.value || registeredFields.value.some((f) => f.keepAuto))

/** 是否正在执行恢复（抑制 deep watcher 对恢复赋值的响应）。 */
const muted = ref(false)

/**
 * "干净状态"快照：挂载时的原始值（或 clearCache 后的当前值）。
 * 只要草稿与此一致，就认为没有可保存的内容，应移除存储。
 */
let cleanJson = ''

/** 是否存在尚未落盘的改动（决定 onBeforeUnmount 是否需要 flush）。 */
let hasPending = false

/** 计算当前应保存的草稿数据；没有可保存内容时返回 null。 */
function currentDraft(): Record<string, any> | null {
  const raw = toRaw(props.model)
  if (formMode.value) {
    const d = omitPaths(raw, props.exclude)
    return isDraftEmpty(d) ? null : d
  }
  // 字段级模式：合并所有标记了 keep_auto 的字段。
  const d: Record<string, any> = {}
  for (const f of registeredFields.value) {
    if (!f.keepAuto) continue
    const v = getPath(raw, f.prop)
    if (v != null && v !== '') setPath(d, f.prop, v)
  }
  return isDraftEmpty(d) ? null : d
}

/** 写入或清除草稿：与"干净状态"一致或为空 → 移除；否则写入。 */
function flushSave() {
  if (!active.value) return
  hasPending = false
  const cur = currentDraft()
  if (cur == null || JSON.stringify(cur) === cleanJson) {
    if (readDraft(saveKey.value)) removeDraft(saveKey.value)
    return
  }
  writeDraft(saveKey.value, cur)
}

const saveDebounced = debounce(flushSave, 500)

/** 暴露给父级：表单提交成功后调用，清除草稿并把当前内容视为"干净状态"，防止残留/回写。 */
function clearCache() {
  if (!active.value) return
  saveDebounced.cancel()
  hasPending = false
  removeDraft(saveKey.value)
  cleanJson = JSON.stringify(currentDraft())
}
defineExpose({ clearCache })

/** 页面级兜底：关标签页 / 刷新 / 切后台时立即落盘。 */
function flushOnExit() {
  if (hasPending) saveDebounced.flush()
}
function onVisibilityChange() {
  if (document.visibilityState === 'hidden' && hasPending) saveDebounced.flush()
}
window.addEventListener('beforeunload', flushOnExit)
window.addEventListener('pagehide', flushOnExit)
document.addEventListener('visibilitychange', onVisibilityChange)

watch(
  () => props.model,
  () => {
    if (!muted.value) {
      hasPending = true
      saveDebounced()
    }
  },
  { deep: true },
)

/** 字段级注册接口，供 KeepAutoFormItem 调用。 */
function registerField(f: { prop: string; keepAuto: boolean }) {
  registeredFields.value.push(f)
}
function unregisterField(prop: string) {
  registeredFields.value = registeredFields.value.filter((x) => x.prop !== prop)
}
provide<KeepAutoFormContext>(KEEP_AUTO_FORM_KEY, { registerField, unregisterField })

onMounted(async () => {
  // 子组件（KeepAutoFormItem）的 onMounted 先于父级执行，此处 registeredFields 已齐备。
  cleanJson = JSON.stringify(currentDraft())
  if (!active.value) return

  const draft = readDraft(saveKey.value)
  if (draft == null || isDraftEmpty(draft)) return

  // 同 key 已有一个恢复弹窗在等待时，本实例不再弹，避免叠加。
  if (pendingPrompts.has(saveKey.value)) return
  pendingPrompts.add(saveKey.value)
  try {
    await ElMessageBox.confirm(
      '检测到该表单有未完成的草稿，是否恢复上次填写的内容？',
      '恢复草稿',
      { confirmButtonText: '恢复', cancelButtonText: '丢弃', type: 'info' },
    )
    muted.value = true
    if (formMode.value) {
      for (const k of Object.keys(draft)) {
        ;(props.model as Record<string, any>)[k] = draft[k]
      }
    } else {
      for (const k of Object.keys(draft)) setPath(props.model, k, draft[k])
    }
    // 等待 deep watcher 的 pre-flush 执行完（此时 muted 仍为 true，跳过保存），
    // 再解除静音。cleanJson 保持不变（= 挂载时原状），
    // 因此"恢复后不做任何改动就离开"时草稿会保留，而非被当作回退到干净状态而删除。
    await nextTick()
    muted.value = false
    hasPending = false
    ElMessage.success('已恢复上次填写的草稿')
  } catch {
    // 用户选择"丢弃" → 清除草稿缓存；当前表单内容不动，且不再回写。
    removeDraft(saveKey.value)
  } finally {
    pendingPrompts.delete(saveKey.value)
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', flushOnExit)
  window.removeEventListener('pagehide', flushOnExit)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  if (hasPending) saveDebounced.flush()
})
</script>
