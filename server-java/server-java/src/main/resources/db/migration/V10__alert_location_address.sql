-- =============================================================
-- SullivanMINI V10 — 报警位置的服务端逆地理编码（坐标 → 地名）
--
-- 为什么在服务端做这件事：
--   监护人端此前用高德 Android SDK 自己解析坐标（PlaceNameResolver），
--   但一条报警的地名会被**三处**展示（来电通知、报警弹窗、会话页位置条），
--   客户端解析意味着三处各解析一次或各自缓存；在服务端解析一次、
--   随位置快照一起下发，客户端只剩「有就显示」。
--
--   解析在报警创建时进行一次并随 alert 行持久化：报警是历史证据，
--   「当时解析出的地名」应当与坐标一起定格，而不是每次查看都重新解析
--   （高德可能改地址库，也可能那天 Key 配额用完了）。
--
-- 解析失败（无网 / Key 未配 / 配额耗尽 / 服务区外）不影响报警：
--   location_address 保持 NULL，客户端回落到坐标展示或自己的解析。
-- =============================================================

-- MySQL 的 ALTER TABLE ADD COLUMN 没有 IF NOT EXISTS，重复执行会报
-- Duplicate column name——迁移由 Flyway 记账，正常流程不会重放。
ALTER TABLE alert
  ADD COLUMN location_address VARCHAR(255) NULL
  COMMENT '报警位置的逆地理编码地名（服务端解析）；解析失败或未配置 Key 时为 NULL'
  AFTER location_captured_at;
