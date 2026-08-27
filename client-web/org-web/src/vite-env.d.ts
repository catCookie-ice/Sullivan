/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 高德地图 JS API Key（创建志愿活动选点） */
  readonly VITE_AMAP_KEY?: string
  /** 高德地图 JS API 安全密钥（securityJsCode，JS API 2.0 强制要求） */
  readonly VITE_AMAP_SECURITY_CODE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

// 高德地图 JS API 全局对象（未安装类型声明，按需窄化）
interface Window {
  AMap?: any
}
