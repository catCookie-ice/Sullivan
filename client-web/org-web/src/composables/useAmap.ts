// 高德地图 JS API 动态加载（v2.0）。可复用于实时位置视图（后续需求）。
let loadPromise: Promise<void> | null = null

/** 是否已配置 securityJsCode（JS API 2.0 强制要求；未配置时 Geocoder 等服务会失效）。 */
export function hasAmapSecurityCode(): boolean {
  return Boolean(import.meta.env.VITE_AMAP_SECURITY_CODE)
}

/**
 * 加载高德地图脚本（幂等）。未配置 VITE_AMAP_KEY 时 reject。
 * 调用方需在组件卸载后释放地图实例；本函数只负责脚本加载。
 *
 * 注意：JS API 2.0 自 2021-12-02 起强制要求安全密钥（securityJsCode），
 * 必须在脚本加载前设置 window._AMapSecurityConfig，否则报 INVALID_USER_SCODE。
 * 未配置 VITE_AMAP_SECURITY_CODE 时保持原行为（让调用方看到加载失败的提示）。
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
    const securityCode = import.meta.env.VITE_AMAP_SECURITY_CODE
    if (securityCode) {
      ;(window as any)._AMapSecurityConfig = { securityJsCode: securityCode }
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
