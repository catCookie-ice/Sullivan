<template>
  <div class="not-found" :style="pos">
    <el-result icon="warning" title="404" sub-title="页面不存在">
      <template #extra>
        <el-button type="primary" @click="$router.push('/')">返回首页</el-button>
      </template>
    </el-result>

    <div class="hint">🕵️ 移动鼠标，用放大镜查看磨砂下的内容</div>

    <!-- 磨砂遮罩：高斯模糊 + 放大镜圆孔镂空（镜内内容清晰） -->
    <div class="frost" aria-hidden="true"></div>

    <!-- 侦探放大镜 -->
    <div class="detective-glass" aria-hidden="true">
      <div class="glass-lens"></div>
      <div class="glass-handle"></div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

const mx = ref(0)
const my = ref(0)

const pos = computed(() => ({
  '--mx': `${mx.value}px`,
  '--my': `${my.value}px`,
}))

function onMove(e: MouseEvent) {
  mx.value = e.clientX
  my.value = e.clientY
}

function onTouch(e: TouchEvent) {
  const t = e.touches[0]
  if (t) {
    mx.value = t.clientX
    my.value = t.clientY
  }
}

onMounted(() => {
  mx.value = window.innerWidth / 2
  my.value = window.innerHeight / 2
  window.addEventListener('mousemove', onMove)
  window.addEventListener('touchmove', onTouch)
})

onBeforeUnmount(() => {
  window.removeEventListener('mousemove', onMove)
  window.removeEventListener('touchmove', onTouch)
})
</script>

<style scoped>
.not-found {
  position: relative;
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100vh;
  background: transparent;
}

/* 404 标题醒目，手写体放大 */
.not-found :deep(.el-result__icon svg) {
  width: 84px;
  height: 84px;
}
.not-found :deep(.el-result__title p) {
  font-family: var(--font-hand);
  font-size: 76px;
  font-weight: 400;
  color: var(--ink);
  line-height: 1.1;
  letter-spacing: 4px;
}
.not-found :deep(.el-result__subtitle p) {
  font-family: var(--font-hand);
  font-size: 18px;
  color: var(--ink-3);
}

.hint {
  position: fixed;
  left: 50%;
  bottom: 30px;
  transform: translateX(-50%);
  z-index: 5;
  font-family: var(--font-hand);
  font-size: 14px;
  color: var(--ink-3);
  white-space: nowrap;
}

/* 磨砂遮罩：高斯模糊营造磨砂质感，放大镜圆孔处镂空 */
.frost {
  position: fixed;
  inset: 0;
  z-index: 10;
  pointer-events: none;
  -webkit-backdrop-filter: blur(10px) saturate(1.15);
  backdrop-filter: blur(10px) saturate(1.15);
  background: rgba(255, 253, 248, 0.16);
  /* 圆孔与放大镜外缘对齐（半径 130px），内圈镂空、外圈渐隐为磨砂 */
  -webkit-mask-image: radial-gradient(circle 130px at var(--mx) var(--my), transparent 123px, black 128px);
  mask-image: radial-gradient(circle 130px at var(--mx) var(--my), transparent 123px, black 128px);
}

/* 侦探放大镜：跟随光标 */
.detective-glass {
  position: fixed;
  left: var(--mx);
  top: var(--my);
  z-index: 20;
  width: 260px;
  height: 260px;
  transform: translate(-50%, -50%);
  pointer-events: none;
}
.glass-lens {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  border: 6px solid var(--ink);
  box-shadow:
    0 0 0 3px rgba(63, 58, 50, 0.15),
    inset 0 0 14px rgba(63, 58, 50, 0.12),
    inset 0 0 0 2px rgba(255, 255, 255, 0.28);
}
.glass-lens::before {
  content: "";
  position: absolute;
  inset: 10px;
  border-radius: 50%;
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.35), transparent 48%);
  pointer-events: none;
}
.glass-lens::after {
  content: "";
  position: absolute;
  left: 50%;
  top: 50%;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--terra);
  transform: translate(-50%, -50%);
  box-shadow: 0 0 0 2px rgba(156, 79, 63, 0.22);
}
.glass-handle {
  position: absolute;
  top: 220px;
  left: 220px;
  width: 118px;
  height: 15px;
  border-radius: 9px;
  background: linear-gradient(180deg, #6e5f4c, #3f3a32);
  box-shadow: 1px 2px 0 rgba(63, 58, 50, 0.35);
  transform: rotate(45deg);
  transform-origin: 0 50%;
}
.glass-handle::after {
  content: "";
  position: absolute;
  right: -6px;
  top: 50%;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--ink);
  transform: translateY(-50%);
  box-shadow: 1px 2px 0 rgba(63, 58, 50, 0.3);
}
</style>
