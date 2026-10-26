/**
 * 共享导航的类型定义。
 *
 * 单独一个文件是因为 `<script setup>` 里**不能写 `export`**——组件只能导出自身，
 * 类型必须放在普通模块里才能被其它文件 import。
 */

export interface NavItem {
  /** 路由路径，同时用作 el-menu 的 index。 */
  index: string
  /** 菜单显示文案。 */
  label: string
  /**
   * 为 false 时不显示（按角色/权限裁剪入口）。缺省视为显示。
   *
   * 存在的意义：某些页面的后端接口还没实现（见 docs/final/api/openapi.yaml 与
   * 运行中的 /v3/api-docs 的差异），按 00 §6.2 第 3 条「MINI UI 不展示不可用入口」，
   * 应当把入口隐藏而不是让用户点进去看占位页。后端就绪后把 show 改回 true 即可。
   */
  show?: boolean
}
