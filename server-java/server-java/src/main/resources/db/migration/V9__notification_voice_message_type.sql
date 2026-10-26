-- =============================================================
-- SullivanMINI V9 — 修正 ck_notification_type，允许 VOICE_MESSAGE
--
-- 为什么会有这一版（V8 的漏项）：
--   V8 新增了语音留言，并在 service 里投递一条 notification_type='VOICE_MESSAGE'
--   的通知（让接收方知道「有一条留言」）。但 V8 **忘了同步扩展
--   ck_notification_type 的 CHECK 约束** —— 那个约束是 V3 建的，只认得
--   当时那七个值。于是真实上传会以 error 3819 失败：
--
--       Check constraint 'ck_notification_type' is violated.
--
--   单元测试没有抓到它，因为它们全部 mock 了 mapper：没有一条 INSERT 真正
--   落到 MySQL 上，而 CHECK 约束恰恰只在真实库里生效。这条坑值得留在
--   开发日记里：**凡是新增枚举值，都必须同时改 CHECK 约束并跑一次真实库**。
--
-- 为什么单独一版而不是改进 V8：
--   V8 已经在真实库上应用过（flyway_schema_history 有记录），改它会触发
--   校验和不匹配、让所有已部署的库起不来。迁移只能往后追加。
--
-- 值域来源：02 §9.5 NotificationType。
-- =============================================================

ALTER TABLE notification DROP CHECK ck_notification_type;

ALTER TABLE notification
  ADD CONSTRAINT ck_notification_type
  CHECK (notification_type IN (
    'BINDING_REQUEST',
    'BINDING_RESULT',
    'ALERT',
    'APPLICATION_RESULT',
    'ENROLLMENT_RESULT',
    'ACTIVITY_CHANGE',
    'ORG_REVIEW',
    'SYSTEM',
    'VOICE_MESSAGE'
  ));

ALTER TABLE notification
  MODIFY COLUMN notification_type VARCHAR(32) NOT NULL
  COMMENT '通知类型：BINDING_REQUEST/BINDING_RESULT/ALERT/APPLICATION_RESULT/ENROLLMENT_RESULT/ACTIVITY_CHANGE/ORG_REVIEW/SYSTEM/VOICE_MESSAGE';
