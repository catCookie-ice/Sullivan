// 高德地图 JS API 动态加载（v2.0）。可复用于实时位置视图（后续需求）。
let loadPromise: Promise<void> | null = null

/**
 * 加载高德地图脚本（幂等）。未配置 VITE_AMAP_KEY 时 reject。
 * 调用方需在组件卸载后释放地图实例；本函数只负责脚本加载。
 */
export function loadAmap(): Promise<void> {
  if (window.AMap) return Promise.resolve()
  if (loadPromise) return loadPromise
  loadPromise = new Promise((resolve, reject) => {
    const key = import.meta.env.VITE_AMAP_KEY
    if (!key) {
      loadPromise = null
      reject(new Error('VITE_AMAP_KEY 未配置，无法加载高德地图'))
      return
    }
    const script = document.createElement('script')
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(key)}`
    script.async = true
    script.onload = () => resolve()
    script.onerror = () => {
      loadPromise = null
      reject(new Error('高德地图脚本加载失败'))
    }
    document.head.appendChild(script)
  })
  return loadPromise
}
