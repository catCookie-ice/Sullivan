-- =============================================================
-- SullivanMINI V3 — 公益活动 / 志愿者兼任 / 双向绑定 / 实时会话
-- 全部语句幂等：CREATE TABLE IF NOT EXISTS + information_schema 判断追加列，
-- 保证已执行 docs/final/db/schema.sql 的库可平滑升级。
-- =============================================================

-- ---------- 1. guardian_binding：绑定发起方 requested_by ----------
-- 发起方为监护人或受护人账号 ID；方向由与 guardian_uid/protectee_uid 比较得出。
SET @guardian_binding_requested_by_sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE guardian_binding ADD COLUMN requested_by CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT ''发起方账号ID（监护人=guardian_uid，受护人=protectee_uid）''',
    'SELECT 1'
  )
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'guardian_binding'
    AND column_name = 'requested_by'
);
PREPARE guardian_binding_requested_by_stmt FROM @guardian_binding_requested_by_sql;
EXECUTE guardian_binding_requested_by_stmt;
DEALLOCATE PREPARE guardian_binding_requested_by_stmt;

-- 历史行均由监护人发起，回填 requested_by = guardian_uid
UPDATE guardian_binding SET requested_by = guardian_uid WHERE requested_by IS NULL;

-- ---------- 2. volunteer_activity：志愿活动（含报名窗口与高德经纬度） ----------
CREATE TABLE IF NOT EXISTS volunteer_activity (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'RECRUITING',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  tenant_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'FULL-RESERVED：未来运营主体隔离',
  external_ref VARCHAR(128) NULL COMMENT 'FULL-RESERVED：外部活动引用',
  extension JSON NULL,
  organization_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  title VARCHAR(160) NOT NULL,
  description TEXT NOT NULL,
  location_text VARCHAR(300) NOT NULL,
  location_lng DECIMAL(10,6) NULL,
  location_lat DECIMAL(10,6) NULL,
  starts_at DATETIME(3) NOT NULL,
  ends_at DATETIME(3) NOT NULL,
  registration_starts_at DATETIME(3) NOT NULL,
  registration_ends_at DATETIME(3) NOT NULL,
  max_volunteers INT UNSIGNED NOT NULL,
  approved_count INT UNSIGNED NOT NULL DEFAULT 0,
  is_free BOOLEAN NOT NULL DEFAULT TRUE,
  PRIMARY KEY (id),
  KEY ix_activity_org_status (organization_id, status, starts_at),
  KEY ix_activity_public (status, starts_at),
  CONSTRAINT fk_activity_org FOREIGN KEY (organization_id) REFERENCES organization(id),
  CONSTRAINT ck_activity_status CHECK (status IN ('RECRUITING', 'IN_PROGRESS', 'ENDED', 'CANCELLED')),
  CONSTRAINT ck_activity_time CHECK (starts_at < ends_at),
  CONSTRAINT ck_activity_reg CHECK (registration_starts_at <= registration_ends_at AND registration_ends_at <= starts_at),
  CONSTRAINT ck_activity_quota CHECK (approved_count <= max_volunteers AND max_volunteers > 0),
  CONSTRAINT ck_activity_free CHECK (is_free = TRUE),
  CONSTRAINT ck_activity_extension CHECK (extension IS NULL OR JSON_TYPE(extension) = 'OBJECT')
) ENGINE=InnoDB COMMENT='免费志愿活动；含报名窗口与高德经纬度';

-- 已存在表（曾执行 schema.sql）补充新列；存量表新列加为 NULL，应用层必填校验
SET @activity_reg_starts_sql = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE volunteer_activity ADD COLUMN registration_starts_at DATETIME(3) NULL COMMENT ''报名开始时间''',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'volunteer_activity' AND column_name = 'registration_starts_at');
PREPARE activity_reg_starts_stmt FROM @activity_reg_starts_sql;
EXECUTE activity_reg_starts_stmt;
DEALLOCATE PREPARE activity_reg_starts_stmt;

SET @activity_reg_ends_sql = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE volunteer_activity ADD COLUMN registration_ends_at DATETIME(3) NULL COMMENT ''报名结束时间''',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'volunteer_activity' AND column_name = 'registration_ends_at');
PREPARE activity_reg_ends_stmt FROM @activity_reg_ends_sql;
EXECUTE activity_reg_ends_stmt;
DEALLOCATE PREPARE activity_reg_ends_stmt;

SET @activity_lng_sql = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE volunteer_activity ADD COLUMN location_lng DECIMAL(10,6) NULL COMMENT ''高德经度''',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'volunteer_activity' AND column_name = 'location_lng');
PREPARE activity_lng_stmt FROM @activity_lng_sql;
EXECUTE activity_lng_stmt;
DEALLOCATE PREPARE activity_lng_stmt;

SET @activity_lat_sql = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE volunteer_activity ADD COLUMN location_lat DECIMAL(10,6) NULL COMMENT ''高德纬度''',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'volunteer_activity' AND column_name = 'location_lat');
PREPARE activity_lat_stmt FROM @activity_lat_sql;
EXECUTE activity_lat_stmt;
DEALLOCATE PREPARE activity_lat_stmt;

-- ---------- 3. activity_enrollment：活动报名 ----------
CREATE TABLE IF NOT EXISTS activity_enrollment (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  activity_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  volunteer_uid CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联志愿者账号',
  decision_reason VARCHAR(1000) NULL,
  decided_at DATETIME(3) NULL,
  effective_enrollment_key VARCHAR(53) GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL AND status IN ('PENDING','APPROVED','REJECTED') THEN CONCAT(activity_id, ':', volunteer_uid) ELSE NULL END) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uq_activity_enrollment_effective (effective_enrollment_key),
  KEY ix_activity_enrollment_activity_status (activity_id, status, created_at),
  KEY ix_activity_enrollment_volunteer (volunteer_uid, created_at),
  CONSTRAINT fk_activity_enrollment_activity FOREIGN KEY (activity_id) REFERENCES volunteer_activity(id),
  CONSTRAINT ck_activity_enrollment_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'))
) ENGINE=InnoDB COMMENT='活动报名；Java 事务校验志愿者角色与活动归属';

-- ---------- 4. volunteer_application：志愿者兼任申请 ----------
CREATE TABLE IF NOT EXISTS volunteer_application (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  applicant_uid CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '申请人（监护人）账号ID',
  reason VARCHAR(1000) NULL COMMENT '申请理由',
  decided_by_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '审核管理员ID',
  decided_at DATETIME(3) NULL,
  decision_note VARCHAR(1000) NULL COMMENT '审核意见/拒绝原因',
  effective_key VARCHAR(40) GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL AND status = 'PENDING' THEN CONCAT('V', applicant_uid) ELSE NULL END) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uq_volunteer_app_effective (effective_key),
  KEY ix_vol_app_user_status (applicant_uid, status, created_at),
  KEY ix_vol_app_admin_status (decided_by_admin_id, status, created_at),
  CONSTRAINT fk_vol_app_user FOREIGN KEY (applicant_uid) REFERENCES user_account(id),
  CONSTRAINT fk_vol_app_admin FOREIGN KEY (decided_by_admin_id) REFERENCES admin_account(id),
  CONSTRAINT ck_vol_app_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'))
) ENGINE=InnoDB COMMENT='监护人兼任志愿者申请（提交→管理员审核）';

-- ---------- 5. realtime_session：实时会话（长连接） ----------
-- session_type 仅允许 BASIC；VOICE/CAMERA/LOCATION 为 [FULL] 未来扩展，不得加入可达值。
CREATE TABLE IF NOT EXISTS realtime_session (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'REQUESTED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  initiator_uid CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '发起方（监护人或受护人）',
  target_uid CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '接收方',
  session_type VARCHAR(16) NOT NULL DEFAULT 'BASIC' COMMENT '会话类型：BASIC；VOICE/CAMERA/LOCATION 为 FULL-RESERVED',
  accepted_at DATETIME(3) NULL,
  ended_at DATETIME(3) NULL,
  ended_by_uid CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
  end_reason VARCHAR(500) NULL,
  PRIMARY KEY (id),
  KEY ix_realtime_initiator (initiator_uid, status, created_at),
  KEY ix_realtime_target (target_uid, status, created_at),
  CONSTRAINT fk_realtime_initiator FOREIGN KEY (initiator_uid) REFERENCES user_account(id),
  CONSTRAINT fk_realtime_target FOREIGN KEY (target_uid) REFERENCES user_account(id),
  CONSTRAINT ck_realtime_distinct CHECK (initiator_uid <> target_uid),
  CONSTRAINT ck_realtime_status CHECK (status IN ('REQUESTED', 'ACTIVE', 'REJECTED', 'ENDED')),
  CONSTRAINT ck_realtime_type CHECK (session_type IN ('BASIC'))
) ENGINE=InnoDB COMMENT='监护-受护实时会话（长连接消息中继）';
