-- =============================================================
-- SullivanMINI V7 — 报警闭环：位置快照 + 报警 + 路由投递 + 5 秒自动会话
--
-- 这一版补齐的是 FR-11…FR-14（报警路由）与 FR-06（位置）里**报警必需**的那一段：
--
--   latest_location          受护人最新位置（只存最新一条，不存轨迹）
--   location_authorization   位置用途授权留痕
--   alert                    报警先落库，再路由
--   alert_delivery           一条报警对一个接收人
--   notification             站内通知（与报警投递分开：投递是「有没有送到」，
--                            通知是「用户看不看得到」）
--   protectee_alert_setting  报警后自动发起会话的开关（默认开启）
--
-- 与 docs/final/db/schema.sql 的差异，累计如下（都已登记在 16 §1 的 AL-01~AL-04）：
--
--   1. alert.alert_type 增加了 'COLLISION'。schema.sql 只列了 MANUAL /
--      FALL_DETECTED / VOICE_HELP，而本版要让**端侧碰撞预警达到 DANGER** 时也能
--      自动触发报警（用户明确要求「两者都要」）。不属于原有枚举的任何一个，
--      也不能塞进 MANUAL（那会让「用户按的」和「算法判的」在统计数据里无法区分）。
--
--   2. 新增 protectee_alert_setting 表。schema.sql 没有承载「报警后自动发起会话」
--      这个开关的地方；它是用户可关闭的产品功能，必须持久化，且必须**服务端**为准
--      （换设备、重装 App 后行为要一致，不能只在某一台手机上关掉）。
--
--   3. alert 增加了 auto_session_due_at / auto_session_started_at /
--      auto_session_state 三列。schema.sql 把「5 秒后自动发起会话」这件事
--      含在 alert.status 里表达不了 —— 它们会交错：监护人可能在 t=3s 主动回拨
--      把自动呼叫取消，也可能报警已 RESOLVED 而那通电话还在响。所以拆成
--      两条独立的进度线。
--
--   其余表结构照抄 schema.sql，只补了 InnoDB 引擎声明与索引。
-- 全部 IF NOT EXISTS：V3 已经用过同样的写法，允许在手工执行过 schema.sql 的库上重复跑。
-- =============================================================

-- ---------- 1. location_authorization：位置用途授权 ----------
-- 「绑定本身仍是监护位置读取的必要条件」：这张表只证明用户点过同意，
-- 不代替 guardian_binding 的 ACTIVE 校验。两者都要过。
CREATE TABLE IF NOT EXISTS location_authorization (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  purpose VARCHAR(40) NOT NULL COMMENT 'PROTECTEE_SHARING、VOLUNTEER_ALERT_MATCHING 或 NAVIGATION',
  granted_at DATETIME(3) NULL,
  revoked_at DATETIME(3) NULL,
  source VARCHAR(32) NOT NULL DEFAULT 'CLIENT_SYSTEM_PERMISSION',
  PRIMARY KEY (id),
  UNIQUE KEY uq_location_authorization_user_purpose (user_id, purpose),
  CONSTRAINT fk_location_authorization_user FOREIGN KEY (user_id) REFERENCES user_account(id),
  CONSTRAINT ck_location_authorization_status CHECK (status IN ('GRANTED', 'DENIED', 'REVOKED')),
  CONSTRAINT ck_location_authorization_purpose CHECK (purpose IN ('PROTECTEE_SHARING', 'VOLUNTEER_ALERT_MATCHING', 'NAVIGATION'))
) ENGINE=InnoDB COMMENT='位置用途授权留痕；绑定本身仍是监护位置读取的必要条件';

-- ---------- 2. latest_location：只存最新位置 ----------
-- 不做轨迹表：本版只需要「报警那一刻他在哪」，存轨迹既超范围又要额外处理保留期。
CREATE TABLE IF NOT EXISTS latest_location (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  latitude DECIMAL(9,6) NOT NULL,
  longitude DECIMAL(9,6) NOT NULL,
  accuracy_m DECIMAL(8,2) NULL,
  captured_at DATETIME(3) NOT NULL,
  received_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_latest_location_user (user_id),
  CONSTRAINT fk_latest_location_user FOREIGN KEY (user_id) REFERENCES user_account(id),
  CONSTRAINT ck_latest_location_status CHECK (status IN ('AVAILABLE', 'UNAVAILABLE', 'REVOKED')),
  CONSTRAINT ck_latest_location_latitude CHECK (latitude BETWEEN -90 AND 90),
  CONSTRAINT ck_latest_location_longitude CHECK (longitude BETWEEN -180 AND 180),
  CONSTRAINT ck_latest_location_accuracy CHECK (accuracy_m IS NULL OR accuracy_m >= 0),
  KEY ix_latest_location_captured (captured_at)
) ENGINE=InnoDB COMMENT='仅存最新位置；位置新鲜度由 captured_at 与当前时间计算';

-- ---------- 3. alert：报警先持久化 ----------
-- 位置快照冗余在 alert 上（latitude/longitude/location_captured_at），不是只靠
-- latest_location 的当下值：报警详情要能还原「当时报的位置」，而 latest_location
-- 会被后续上报覆盖。这是 schema.sql 已有的设计，这里照抄。
CREATE TABLE IF NOT EXISTS alert (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  protectee_uid CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联受护人账号',
  idempotency_key VARCHAR(100) NOT NULL,
  alert_type VARCHAR(24) NOT NULL,
  route_type VARCHAR(24) NULL COMMENT 'NULL 表示尚未路由',
  route_note VARCHAR(200) NULL,
  latitude DECIMAL(9,6) NULL,
  longitude DECIMAL(9,6) NULL,
  location_captured_at DATETIME(3) NULL,
  resolved_at DATETIME(3) NULL,
  -- 自动会话的调度状态。与 status 分开：status 描述报警本身的生命周期，
  -- 这两列描述「5 秒后要不要替他呼人」这条副线，两者可以各自推进。
  auto_session_due_at DATETIME(3) NULL COMMENT '计划自动发起会话的时刻；NULL 表示未安排',
  auto_session_started_at DATETIME(3) NULL COMMENT '实际开始依次发起的时刻',
  auto_session_state VARCHAR(16) NOT NULL DEFAULT 'NONE' COMMENT 'NONE 未安排 / SCHEDULED 已排期 / RUNNING 已发起 / SKIPPED 被用户关闭 / DONE 已完成',
  PRIMARY KEY (id),
  UNIQUE KEY uq_alert_idempotency (protectee_uid, idempotency_key),
  CONSTRAINT ck_alert_status CHECK (status IN ('CREATED', 'DISPATCHING', 'DISPATCHED', 'ACKNOWLEDGED', 'RESOLVED', 'NO_RECIPIENT', 'DISPATCH_FAILED')),
  -- 'COLLISION' 是本版新增，理由见文件头。
  CONSTRAINT ck_alert_type CHECK (alert_type IN ('MANUAL', 'FALL_DETECTED', 'VOICE_HELP', 'COLLISION')),
  CONSTRAINT ck_alert_route_type CHECK (route_type IS NULL OR route_type IN ('ALL_GUARDIANS', 'NEARBY_VOLUNTEERS', 'NONE')),
  CONSTRAINT ck_alert_route_consistency CHECK (status IN ('CREATED', 'DISPATCHING') OR route_type IS NOT NULL),
  CONSTRAINT ck_alert_location CHECK ((latitude IS NULL AND longitude IS NULL) OR (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180)),
  CONSTRAINT ck_alert_auto_session CHECK (auto_session_state IN ('NONE', 'SCHEDULED', 'RUNNING', 'SKIPPED', 'DONE')),
  KEY ix_alert_protectee_created (protectee_uid, created_at),
  KEY ix_alert_status_created (status, created_at),
  -- 扫描「到点该发起会话」的报警；只索引需要被扫到的那些行。
  KEY ix_alert_auto_session_due (auto_session_state, auto_session_due_at)
) ENGINE=InnoDB COMMENT='报警先持久化；位置快照可为空';

-- ---------- 4. alert_delivery：一条报警对一个接收人 ----------
CREATE TABLE IF NOT EXISTS alert_delivery (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  alert_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  receiver_principal_type VARCHAR(16) NOT NULL,
  receiver_principal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联接收主体，由类型与 ID 共同标识',
  route_type VARCHAR(24) NOT NULL,
  acknowledged_at DATETIME(3) NULL,
  failure_reason VARCHAR(500) NULL,
  retry_count INT UNSIGNED NOT NULL DEFAULT 0,
  -- 依次发起会话时的顺序与结果。顺序固定下来，重试才不会打乱「依次」的语义。
  dispatch_order INT UNSIGNED NULL COMMENT '依次发起时的序号，从 1 开始',
  session_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '为该投递创建的实时会话',
  PRIMARY KEY (id),
  UNIQUE KEY uq_alert_delivery_receiver (alert_id, receiver_principal_type, receiver_principal_id),
  CONSTRAINT fk_alert_delivery_alert FOREIGN KEY (alert_id) REFERENCES alert(id),
  CONSTRAINT ck_alert_delivery_status CHECK (status IN ('PENDING', 'SENT', 'DELIVERED', 'ACKNOWLEDGED', 'FAILED')),
  CONSTRAINT ck_alert_delivery_receiver CHECK (receiver_principal_type = 'USER'),
  CONSTRAINT ck_alert_delivery_route CHECK (route_type IN ('ALL_GUARDIANS', 'NEARBY_VOLUNTEERS')),
  KEY ix_alert_delivery_receiver_status (receiver_principal_type, receiver_principal_id, status, created_at),
  KEY ix_alert_delivery_order (alert_id, dispatch_order)
) ENGINE=InnoDB COMMENT='一条报警对一个接收主体；接收人由 Java 路由事务固化';

-- ---------- 5. notification：站内通知 ----------
CREATE TABLE IF NOT EXISTS notification (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'UNREAD',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  receiver_principal_type VARCHAR(16) NOT NULL,
  receiver_principal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '按主体类型加 ID 标识接收人',
  notification_type VARCHAR(32) NOT NULL,
  title VARCHAR(160) NOT NULL,
  body VARCHAR(1000) NOT NULL,
  payload_json JSON NULL,
  read_at DATETIME(3) NULL,
  source_type VARCHAR(32) NULL,
  source_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
  PRIMARY KEY (id),
  CONSTRAINT ck_notification_status CHECK (status IN ('UNREAD', 'READ')),
  CONSTRAINT ck_notification_principal CHECK (receiver_principal_type IN ('USER', 'ORGANIZATION', 'ADMIN')),
  CONSTRAINT ck_notification_type CHECK (notification_type IN ('BINDING_REQUEST', 'BINDING_RESULT', 'ALERT', 'APPLICATION_RESULT', 'ENROLLMENT_RESULT', 'ACTIVITY_CHANGE', 'ORG_REVIEW', 'SYSTEM')),
  CONSTRAINT ck_notification_payload CHECK (payload_json IS NULL OR JSON_TYPE(payload_json) = 'OBJECT'),
  KEY ix_notification_receiver_unread (receiver_principal_type, receiver_principal_id, status, created_at)
) ENGINE=InnoDB COMMENT='多接收主体通知；不作为聊天消息表';

-- ---------- 6. protectee_alert_setting：报警后自动发起会话的开关 ----------
-- 默认 TRUE：这是产品的默认行为，新用户不必先找开关才算「有守护」。
-- 未建行的受护人按默认值处理 —— 服务端读不到就取 TRUE，绝不因为「没有配置行」
-- 而静默关掉求助功能。
CREATE TABLE IF NOT EXISTS protectee_alert_setting (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  protectee_uid CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  auto_session_enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '报警后是否自动向监护人依次发起会话；默认开启',
  auto_session_delay_seconds INT UNSIGNED NOT NULL DEFAULT 5 COMMENT '报警到发起会话的延迟秒数；本版固定 5，留列以便后续可调',
  PRIMARY KEY (id),
  UNIQUE KEY uq_protectee_alert_setting_user (protectee_uid),
  CONSTRAINT fk_protectee_alert_setting_user FOREIGN KEY (protectee_uid) REFERENCES user_account(id),
  CONSTRAINT ck_protectee_alert_setting_status CHECK (status IN ('ACTIVE', 'DISABLED')),
  CONSTRAINT ck_protectee_alert_setting_delay CHECK (auto_session_delay_seconds BETWEEN 0 AND 300)
) ENGINE=InnoDB COMMENT='受护人的报警偏好；缺行按默认（开启、5 秒）处理';
