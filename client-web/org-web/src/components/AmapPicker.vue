<template>
  <div>
    <div v-if="error" class="amap-error">
      <el-alert type="warning" :closable="false" show-icon :title="error" />
    </div>
    <div v-else ref="containerRef" class="amap-box" :style="{ height: height || '320px', width: '100%' }" />
    <div class="amap-tip" v-if="!error">
      在地图上点击或拖拽红色标记选择地点；当前：{{ pickedLabel }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { loadAmap } from '@/composables/useAmap'

interface AmapLocation {
  lng?: number
  lat?: number
  address?: string
}

const props = defineProps<{
  modelValue?: AmapLocation
  height?: string
}>()

const emit = defineEmits<{ (e: 'update:modelValue', value: AmapLocation): void }>()

const containerRef = ref<HTMLDivElement | null>(null)
const error = ref('')

let map: any = null
let marker: any = null
let geocoder: any = null

const pickedLabel = computed(() => {
  const v = props.modelValue
  if (!v || v.lng == null || v.lat == null) return '未选择'
  return v.address || `${v.lng.toFixed(6)}, ${v.lat.toFixed(6)}`
})

onMounted(async () => {
  try {
    await loadAmap()
  } catch (e: any) {
    error.value = e.message || '地图加载失败'
    return
  }
  if (!containerRef.value) return
  const AMap = window.AMap
  const initial = props.modelValue
  const lng = initial?.lng ?? 116.397428
  const lat = initial?.lat ?? 39.90923
  map = new AMap.Map(containerRef.value, { zoom: 14, center: [lng, lat] })

  if (initial?.lng != null && initial?.lat != null) {
    placeMarker(initial.lng, initial.lat)
  }
  map.on('click', (e: any) => {
    placeMarker(e.lnglat.getLng(), e.lnglat.getLat())
  })
  map.on('complete', () => {
    map.setFitView()
  })
})

watch(
  () => props.modelValue,
  (next) => {
    if (!map || next?.lng == null || next?.lat == null) return
    // 防抖动：本组件 emit 回来的坐标与标记当前位置一致时，说明是自反馈，跳过重新定位。
    // （resolveAddress 每次 emit 都是新对象引用，若不判断坐标会无限触发 watch → setCenter 抖动。）
    const pos = marker?.getPosition?.()
    if (pos && Math.abs(pos.getLng() - next.lng) < 1e-9 && Math.abs(pos.getLat() - next.lat) < 1e-9) {
      return
    }
    map.setCenter([next.lng, next.lat])
    placeMarker(next.lng, next.lat)
  },
)

function placeMarker(lng: number, lat: number) {
  ensureMarker(lng, lat)
  resolveAddress(lng, lat)
}

/** 创建/移动标记并居中地图（不含逆地理编码）。 */
function ensureMarker(lng: number, lat: number) {
  const AMap = window.AMap
  if (!marker) {
    marker = new AMap.Marker({
      position: [lng, lat],
      draggable: true,
    })
    marker.on('dragend', (e: any) => {
      const pos = e.target.getPosition()
      resolveAddress(pos.getLng(), pos.getLat())
    })
    map.add(marker)
  } else {
    marker.setPosition([lng, lat])
  }
  map.setCenter([lng, lat])
}

/**
 * 程序化定位：放置标记 + 居中地图。传入 address 时同步 emit（供父组件 v-model 联动）；
 * 不传则只移动地图与标记（用于"输入框→地图"方向，避免反向覆盖输入框内容）。
 */
function setLocation(lng: number, lat: number, address?: string) {
  ensureMarker(lng, lat)
  if (address != null) emit('update:modelValue', { lng, lat, address })
}

function resolveAddress(lng: number, lat: number) {
  if (!geocoder) {
    // Geocoder 插件尚未加载完成（onMounted 异步）；加载后立即用当前坐标编码。
    const AMap = window.AMap
    AMap.plugin('AMap.Geocoder', () => {
      geocoder = new AMap.Geocoder({ city: '全国' })
      resolveAddress(lng, lat)
    })
    return
  }
  geocoder.getAddress([lng, lat], (status: string, result: any) => {
    const address = status === 'complete' && result?.regeocode?.formattedAddress
      ? result.regeocode.formattedAddress
      : undefined
    emit('update:modelValue', { lng, lat, address })
  })
}

onBeforeUnmount(() => {
  map?.destroy()
  map = null
  marker = null
  geocoder = null
})

// 暴露地图实例与定位方法，供父组件（如 ActivityCreate 输入框→地图联动）调用。
defineExpose({ map, setLocation })
</script>

<style scoped>
.amap-box {
  border: 1px solid var(--line-strong);
  border-radius: 10px 4px 12px 5px / 5px 11px 4px 12px;
  overflow: hidden;
  background: var(--paper-card);
}
.amap-tip {
  font-size: 12px;
  color: var(--ink-3);
  margin-top: 6px;
}
.amap-error {
  padding: 12px 0;
}
</style>
