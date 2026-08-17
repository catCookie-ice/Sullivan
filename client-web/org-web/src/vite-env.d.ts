/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 高德地图 JS API Key（创建志愿活动选点） */
  readonly VITE_AMAP_KEY?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

// 高德地图 JS API 全局对象（未安装类型声明，按需窄化）
interface Window {
  AMap?: any
}
