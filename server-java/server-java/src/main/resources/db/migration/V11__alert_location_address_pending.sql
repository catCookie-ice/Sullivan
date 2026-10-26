-- =============================================================
-- SullivanMINI V11 — 报警地名的「解析超时」标识
--
-- 背景：地名解析（高德逆地理）在报警创建线程里同步执行。给它配了硬时限
-- （默认 1 秒，AMAP_GEOCODE_DEADLINE_MS 可调）——到点还没解析完，
-- 报警**立即**照常转发，绝不能让「查个地名」拖慢「把求助送出去」。
--
-- 超时的报警带 location_address_pending = 1 下发：接收方（监护人端）看到
-- 标识就知道服务端没来得及解析，于是调用自己内置的解析（Android Key）兜底。
-- 解析成功 = 0 且有 location_address；确定失败（无 Key / 服务异常）= 0 且地址为空，
-- 客户端同样会兜底，但语义上不算「超时」。
-- =============================================================

ALTER TABLE alert
  ADD COLUMN location_address_pending TINYINT(1) NOT NULL DEFAULT 0
  COMMENT '1 = 地名解析超时未完成，接收方应使用本端解析兜底'
  AFTER location_address;
