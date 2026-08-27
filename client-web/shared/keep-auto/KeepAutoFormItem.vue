<template>
  <el-form-item v-bind="$attrs" :prop="prop">
    <slot />
  </el-form-item>
</template>

<script setup lang="ts">
import { inject, onBeforeUnmount, onMounted } from 'vue'
import { ElFormItem } from 'element-plus'
import { KEEP_AUTO_FORM_KEY } from './useKeepAuto'
import type { KeepAutoFormContext } from './useKeepAuto'

const props = withDefaults(
  defineProps<{
    /** 对应 form model 中的字段路径（与 el-form-item 的 prop 一致，必填）。 */
    prop: string
    /** keep_auto 标识：仅当父级 KeepAutoForm 未启用表单级保存时，标记该字段自动保存。 */
    keepAuto?: boolean
  }>(),
  { keepAuto: false },
)

defineOptions({ inheritAttrs: false })

const ctx = inject<KeepAutoFormContext | null>(KEEP_AUTO_FORM_KEY, null)

onMounted(() => {
  ctx?.registerField({ prop: props.prop, keepAuto: props.keepAuto })
})

onBeforeUnmount(() => {
  ctx?.unregisterField(props.prop)
})
</script>
