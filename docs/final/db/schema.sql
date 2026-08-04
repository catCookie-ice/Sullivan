-- SullivanMINI v1.0 logical model. Requires MySQL 8.0.16+.
-- All timestamps are UTC. Application connections must also set time_zone = '+00:00'.
SET NAMES utf8mb4;
SET time_zone = '+00:00';

CREATE DATABASE IF NOT EXISTS sullivan CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

USE sullivan;

CREATE TABLE user_account (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '应用生成的 ULID',
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED 或 DISABLED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  tenant_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'FULL-RESERVED：未来租户逻辑外键',
  external_ref VARCHAR(128) NULL COMMENT 'FULL-RESERVED：外部身份稳定引用',
  extension JSON NULL COMMENT '受约束的扩展对象，不能承载权限、状态或金额',
  login_name VARCHAR(64) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  role_code VARCHAR(16) NOT NULL COMMENT 'PROTECTEE、GUARDIAN 或 VOLUNTEER',
  phone_ciphertext VARBINARY(512) NULL COMMENT '加密手机号，不在普通接口返回',
  CONSTRAINT pk_user_account PRIMARY KEY (id),
  CONSTRAINT uq_user_account_login UNIQUE (login_name),
  CONSTRAINT ck_user_account_status CHECK (status IN ('ENABLED', 'DISABLED')),
  CONSTRAINT ck_user_account_role CHECK (role_code IN ('PROTECTEE', 'GUARDIAN', 'VOLUNTEER')),
  CONSTRAINT ck_user_account_extension CHECK (extension IS NULL OR JSON_TYPE(extension) = 'OBJECT')
) ENGINE=InnoDB COMMENT='自然人账号；不包含主监护人字段';

CREATE TABLE user_profile (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  tenant_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'FULL-RESERVED',
  external_ref VARCHAR(128) NULL COMMENT 'FULL-RESERVED',
  extension JSON NULL,
  user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  display_name VARCHAR(80) NOT NULL,
  emergency_note VARCHAR(500) NULL,
  CONSTRAINT pk_user_profile PRIMARY KEY (id),
  CONSTRAINT uq_user_profile_user UNIQUE (user_id),
  CONSTRAINT fk_user_profile_account FOREIGN KEY (user_id) REFERENCES user_account(id),
  CONSTRAINT ck_user_profile_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
  CONSTRAINT ck_user_profile_extension CHECK (extension IS NULL OR JSON_TYPE(extension) = 'OBJECT')
) ENGINE=InnoDB COMMENT='自然人资料';

CREATE TABLE auth_refresh_token (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  terminated_at DATETIME(3) NULL,
  device_label VARCHAR(120) NULL,
  CONSTRAINT pk_auth_refresh_token PRIMARY KEY (id),
  CONSTRAINT uq_auth_refresh_token_hash UNIQUE (token_hash),
  CONSTRAINT fk_auth_refresh_token_user FOREIGN KEY (user_id) REFERENCES user_account(id),
  CONSTRAINT ck_auth_refresh_token_status CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED'))
) ENGINE=InnoDB COMMENT='仅保存刷新令牌哈希';
CREATE INDEX ix_auth_refresh_token_user_status ON auth_refresh_token (user_id, status, expires_at);

CREATE TABLE guardian_binding (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  guardian_uid CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  protectee_uid CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  is_primary BOOLEAN NOT NULL DEFAULT FALSE,
  requested_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  confirmed_at DATETIME(3) NULL,
  revoked_at DATETIME(3) NULL,
  decision_note VARCHAR(500) NULL,
  active_pair_key VARCHAR(53) GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL AND status IN ('PENDING','ACTIVE') THEN CONCAT(guardian_uid, ':', protectee_uid) ELSE NULL END) STORED,
  primary_protectee_key CHAR(26) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL AND status = 'ACTIVE' AND is_primary THEN protectee_uid ELSE NULL END) STORED,
  CONSTRAINT pk_guardian_binding PRIMARY KEY (id),
  CONSTRAINT uq_guardian_binding_active_pair UNIQUE (active_pair_key),
  CONSTRAINT uq_guardian_binding_primary UNIQUE (primary_protectee_key),
  CONSTRAINT fk_guardian_binding_guardian FOREIGN KEY (guardian_uid) REFERENCES user_account(id),
  CONSTRAINT fk_guardian_binding_protectee FOREIGN KEY (protectee_uid) REFERENCES user_account(id),
  CONSTRAINT ck_guardian_binding_status CHECK (status IN ('PENDING', 'ACTIVE', 'REJECTED', 'CANCELLED', 'EXPIRED', 'TERMINATED')),
  CONSTRAINT ck_guardian_binding_distinct CHECK (guardian_uid <> protectee_uid),
  CONSTRAINT ck_guardian_binding_primary CHECK (NOT is_primary OR status = 'ACTIVE')
) ENGINE=InnoDB COMMENT='确认后才生效的监护绑定';
CREATE INDEX ix_guardian_binding_protectee_status ON guardian_binding (protectee_uid, status, created_at);
CREATE INDEX ix_guardian_binding_guardian_status ON guardian_binding (guardian_uid, status, created_at);

CREATE TABLE location_authorization (
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
  CONSTRAINT pk_location_authorization PRIMARY KEY (id),
  CONSTRAINT uq_location_authorization_user_purpose UNIQUE (user_id, purpose),
  CONSTRAINT fk_location_authorization_user FOREIGN KEY (user_id) REFERENCES user_account(id),
  CONSTRAINT ck_location_authorization_status CHECK (status IN ('GRANTED', 'DENIED', 'REVOKED')),
  CONSTRAINT ck_location_authorization_purpose CHECK (purpose IN ('PROTECTEE_SHARING', 'VOLUNTEER_ALERT_MATCHING', 'NAVIGATION'))
) ENGINE=InnoDB COMMENT='位置用途授权留痕；绑定本身仍是监护位置读取的必要条件';

CREATE TABLE latest_location (
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
  CONSTRAINT pk_latest_location PRIMARY KEY (id),
  CONSTRAINT uq_latest_location_user UNIQUE (user_id),
  CONSTRAINT fk_latest_location_user FOREIGN KEY (user_id) REFERENCES user_account(id),
  CONSTRAINT ck_latest_location_status CHECK (status IN ('AVAILABLE', 'UNAVAILABLE', 'REVOKED')),
  CONSTRAINT ck_latest_location_latitude CHECK (latitude BETWEEN -90 AND 90),
  CONSTRAINT ck_latest_location_longitude CHECK (longitude BETWEEN -180 AND 180),
  CONSTRAINT ck_latest_location_accuracy CHECK (accuracy_m IS NULL OR accuracy_m >= 0)
) ENGINE=InnoDB COMMENT='仅存最新位置；位置新鲜度由 captured_at 与当前时间计算';
CREATE INDEX ix_latest_location_captured ON latest_location (captured_at);

CREATE TABLE volunteer_availability (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'OFFLINE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  volunteer_uid CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  is_available BOOLEAN NOT NULL DEFAULT FALSE,
  enabled_at DATETIME(3) NULL,
  disabled_at DATETIME(3) NULL,
  CONSTRAINT pk_volunteer_availability PRIMARY KEY (id),
  CONSTRAINT uq_volunteer_availability_user UNIQUE (volunteer_uid),
  CONSTRAINT fk_volunteer_availability_user FOREIGN KEY (volunteer_uid) REFERENCES user_account(id),
  CONSTRAINT ck_volunteer_availability_status CHECK (status IN ('ONLINE', 'OFFLINE', 'SUSPENDED')),
  CONSTRAINT ck_volunteer_availability_flag CHECK ((status = 'ONLINE' AND is_available) OR (status <> 'ONLINE' AND NOT is_available))
) ENGINE=InnoDB COMMENT='志愿者本人显式开关；报警匹配还需位置与授权新鲜';

USE sullivan;

CREATE TABLE model_version (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  tenant_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'FULL-RESERVED',
  external_ref VARCHAR(128) NULL COMMENT '模型制品外部引用',
  extension JSON NULL,
  model_name VARCHAR(100) NOT NULL,
  model_revision VARCHAR(100) NOT NULL,
  artifact_uri VARCHAR(500) NOT NULL,
  checksum_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  activated_at DATETIME(3) NULL,
  retired_at DATETIME(3) NULL,
  CONSTRAINT pk_model_version PRIMARY KEY (id),
  CONSTRAINT uq_model_version_name_revision UNIQUE (model_name, model_revision),
  CONSTRAINT ck_model_version_status CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
  CONSTRAINT ck_model_version_extension CHECK (extension IS NULL OR JSON_TYPE(extension) = 'OBJECT')
) ENGINE=InnoDB COMMENT='导航模型登记，不承载客服模型或计费';

CREATE TABLE navigation_session (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  protectee_uid CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联 user_account.id；Python 以 JWT 主体校验归属',
  model_version_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
  started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  ended_at DATETIME(3) NULL,
  failure_code VARCHAR(64) NULL,
  CONSTRAINT pk_navigation_session PRIMARY KEY (id),
  CONSTRAINT fk_navigation_session_model FOREIGN KEY (model_version_id) REFERENCES model_version(id),
  CONSTRAINT ck_navigation_session_status CHECK (status IN ('ACTIVE', 'STOPPED', 'FAILED', 'EXPIRED'))
) ENGINE=InnoDB COMMENT='导航会话；受护人 ID 以 JWT 主体校验归属';
CREATE INDEX ix_navigation_session_protectee_status ON navigation_session (protectee_uid, status, started_at);

CREATE TABLE navigation_sample_event (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  session_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  model_version_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
  sample_seq BIGINT UNSIGNED NOT NULL,
  captured_at DATETIME(3) NULL,
  image_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '只存摘要，禁止存原始帧',
  width_px INT UNSIGNED NULL,
  height_px INT UNSIGNED NULL,
  risk_level VARCHAR(16) NULL,
  guide_text VARCHAR(280) NULL,
  inference_ms INT UNSIGNED NULL,
  result_json JSON NULL COMMENT '采样后的最小推理结果',
  CONSTRAINT pk_navigation_sample_event PRIMARY KEY (id),
  CONSTRAINT uq_navigation_sample_sequence UNIQUE (session_id, sample_seq),
  CONSTRAINT fk_navigation_sample_session FOREIGN KEY (session_id) REFERENCES navigation_session(id),
  CONSTRAINT fk_navigation_sample_model FOREIGN KEY (model_version_id) REFERENCES model_version(id),
  CONSTRAINT ck_navigation_sample_status CHECK (status IN ('PROCESSED', 'DROPPED', 'FAILED')),
  CONSTRAINT ck_navigation_sample_risk CHECK (risk_level IS NULL OR risk_level IN ('SAFE', 'CAUTION', 'DANGER', 'UNKNOWN')),
  CONSTRAINT ck_navigation_sample_size CHECK ((width_px IS NULL AND height_px IS NULL) OR (width_px > 0 AND height_px > 0)),
  CONSTRAINT ck_navigation_sample_json CHECK (result_json IS NULL OR JSON_TYPE(result_json) = 'OBJECT')
) ENGINE=InnoDB COMMENT='采样事件，非逐帧日志；不得保存原始图像或 TTS 音频';
CREATE INDEX ix_navigation_sample_created ON navigation_sample_event (created_at);

USE sullivan;

CREATE TABLE organization (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  tenant_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'FULL-RESERVED：未来运营主体隔离',
  external_ref VARCHAR(128) NULL COMMENT 'FULL-RESERVED：外部注册主体引用',
  extension JSON NULL,
  legal_name VARCHAR(160) NOT NULL,
  public_name VARCHAR(160) NOT NULL,
  contact_ciphertext VARBINARY(1024) NULL,
  commercial_subject_ref VARCHAR(128) NULL COMMENT 'FULL-RESERVED：未来商业主体引用，MINI 必须为空',
  commercial_verification_status VARCHAR(32) NULL COMMENT 'FULL-RESERVED：未来商业认证状态，MINI 必须为空',
  CONSTRAINT pk_organization PRIMARY KEY (id),
  CONSTRAINT ck_organization_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'DISABLED')),
  CONSTRAINT ck_organization_extension CHECK (extension IS NULL OR JSON_TYPE(extension) = 'OBJECT'),
  CONSTRAINT ck_organization_commercial_unused CHECK (commercial_subject_ref IS NULL AND commercial_verification_status IS NULL)
) ENGINE=InnoDB COMMENT='MINI 公益机构；商业认证字段只作受禁用预留';
CREATE INDEX ix_organization_status ON organization (status, created_at);

CREATE TABLE organization_review (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'FINAL',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  organization_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reviewer_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '关联 admin_account.id，由 Java 权限与事务校验',
  action VARCHAR(16) NOT NULL,
  from_status VARCHAR(16) NULL,
  to_status VARCHAR(16) NOT NULL,
  reason VARCHAR(1000) NULL,
  trace_id VARCHAR(64) NULL,
  CONSTRAINT pk_organization_review PRIMARY KEY (id),
  CONSTRAINT fk_organization_review_org FOREIGN KEY (organization_id) REFERENCES organization(id),
  CONSTRAINT ck_organization_review_status CHECK (status = 'FINAL'),
  CONSTRAINT ck_organization_review_action CHECK (action IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'DISABLED', 'RESUBMITTED')),
  CONSTRAINT ck_organization_review_to_status CHECK (to_status IN ('PENDING', 'APPROVED', 'REJECTED', 'DISABLED'))
) ENGINE=InnoDB COMMENT='机构审核不可覆盖历史；管理员 ID 由 Java 权限校验';
CREATE INDEX ix_organization_review_org_time ON organization_review (organization_id, created_at);

CREATE TABLE welfare_service (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  tenant_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'FULL-RESERVED',
  external_ref VARCHAR(128) NULL COMMENT 'FULL-RESERVED',
  extension JSON NULL,
  organization_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  title VARCHAR(160) NOT NULL,
  description TEXT NOT NULL,
  is_free BOOLEAN NOT NULL DEFAULT TRUE,
  opened_at DATETIME(3) NULL,
  closed_at DATETIME(3) NULL,
  CONSTRAINT pk_welfare_service PRIMARY KEY (id),
  CONSTRAINT fk_welfare_service_org FOREIGN KEY (organization_id) REFERENCES organization(id),
  CONSTRAINT ck_welfare_service_status CHECK (status IN ('OPEN', 'PAUSED', 'CLOSED')),
  CONSTRAINT ck_welfare_service_free CHECK (is_free),
  CONSTRAINT ck_welfare_service_extension CHECK (extension IS NULL OR JSON_TYPE(extension) = 'OBJECT')
) ENGINE=InnoDB COMMENT='免费公益服务；禁止添加金额、币种、订单或支付字段';
CREATE INDEX ix_welfare_service_org_status ON welfare_service (organization_id, status, created_at);
CREATE INDEX ix_welfare_service_public ON welfare_service (status, created_at);

CREATE TABLE service_quota (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  welfare_service_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  capacity INT UNSIGNED NULL COMMENT 'NULL 表示不限名额',
  approved_count INT UNSIGNED NOT NULL DEFAULT 0,
  CONSTRAINT pk_service_quota PRIMARY KEY (id),
  CONSTRAINT uq_service_quota_service UNIQUE (welfare_service_id),
  CONSTRAINT fk_service_quota_service FOREIGN KEY (welfare_service_id) REFERENCES welfare_service(id),
  CONSTRAINT ck_service_quota_status CHECK (status IN ('ACTIVE', 'DISABLED')),
  CONSTRAINT ck_service_quota_capacity CHECK (capacity IS NULL OR approved_count <= capacity)
) ENGINE=InnoDB COMMENT='服务审批并发锁点；审批必须锁行或条件更新';

CREATE TABLE service_application (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  welfare_service_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  applicant_uid CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联自然人账号，自身或监护人',
  beneficiary_protectee_uid CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联受护人账号',
  submitted_by_uid CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联提交人账号',
  application_note VARCHAR(2000) NULL,
  decision_reason VARCHAR(1000) NULL,
  decided_at DATETIME(3) NULL,
  completed_at DATETIME(3) NULL,
  effective_application_key VARCHAR(53) GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL AND status IN ('PENDING','APPROVED') THEN CONCAT(welfare_service_id, ':', beneficiary_protectee_uid) ELSE NULL END) STORED,
  CONSTRAINT pk_service_application PRIMARY KEY (id),
  CONSTRAINT uq_service_application_effective UNIQUE (effective_application_key),
  CONSTRAINT fk_service_application_service FOREIGN KEY (welfare_service_id) REFERENCES welfare_service(id),
  CONSTRAINT ck_service_application_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'COMPLETED', 'CANCELLED'))
) ENGINE=InnoDB COMMENT='服务申请；关联主体由 Java 事务校验角色与绑定';
CREATE INDEX ix_service_application_service_status ON service_application (welfare_service_id, status, created_at);
CREATE INDEX ix_service_application_beneficiary_status ON service_application (beneficiary_protectee_uid, status, created_at);
CREATE INDEX ix_service_application_submitter ON service_application (submitted_by_uid, created_at);

CREATE TABLE volunteer_activity (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'RECRUITING',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  tenant_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'FULL-RESERVED',
  external_ref VARCHAR(128) NULL COMMENT 'FULL-RESERVED',
  extension JSON NULL,
  organization_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  title VARCHAR(160) NOT NULL,
  description TEXT NOT NULL,
  location_text VARCHAR(300) NOT NULL,
  starts_at DATETIME(3) NOT NULL,
  ends_at DATETIME(3) NOT NULL,
  max_volunteers INT UNSIGNED NOT NULL,
  approved_count INT UNSIGNED NOT NULL DEFAULT 0,
  is_free BOOLEAN NOT NULL DEFAULT TRUE,
  CONSTRAINT pk_volunteer_activity PRIMARY KEY (id),
  CONSTRAINT fk_volunteer_activity_org FOREIGN KEY (organization_id) REFERENCES organization(id),
  CONSTRAINT ck_volunteer_activity_status CHECK (status IN ('RECRUITING', 'IN_PROGRESS', 'ENDED', 'CANCELLED')),
  CONSTRAINT ck_volunteer_activity_time CHECK (starts_at < ends_at),
  CONSTRAINT ck_volunteer_activity_quota CHECK (approved_count <= max_volunteers AND max_volunteers > 0),
  CONSTRAINT ck_volunteer_activity_free CHECK (is_free),
  CONSTRAINT ck_volunteer_activity_extension CHECK (extension IS NULL OR JSON_TYPE(extension) = 'OBJECT')
) ENGINE=InnoDB COMMENT='免费志愿活动；活动行是报名并发审批锁点';
CREATE INDEX ix_volunteer_activity_org_status ON volunteer_activity (organization_id, status, starts_at);
CREATE INDEX ix_volunteer_activity_public ON volunteer_activity (status, starts_at);

CREATE TABLE activity_enrollment (
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
  CONSTRAINT pk_activity_enrollment PRIMARY KEY (id),
  CONSTRAINT uq_activity_enrollment_effective UNIQUE (effective_enrollment_key),
  CONSTRAINT fk_activity_enrollment_activity FOREIGN KEY (activity_id) REFERENCES volunteer_activity(id),
  CONSTRAINT ck_activity_enrollment_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'))
) ENGINE=InnoDB COMMENT='活动报名；Java 事务校验志愿者角色与活动归属';
CREATE INDEX ix_activity_enrollment_activity_status ON activity_enrollment (activity_id, status, created_at);
CREATE INDEX ix_activity_enrollment_volunteer ON activity_enrollment (volunteer_uid, created_at);

CREATE TABLE alert (
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
  CONSTRAINT pk_alert PRIMARY KEY (id),
  CONSTRAINT uq_alert_idempotency UNIQUE (protectee_uid, idempotency_key),
  CONSTRAINT ck_alert_status CHECK (status IN ('CREATED', 'DISPATCHING', 'DISPATCHED', 'ACKNOWLEDGED', 'RESOLVED', 'NO_RECIPIENT', 'DISPATCH_FAILED')),
  CONSTRAINT ck_alert_type CHECK (alert_type IN ('MANUAL', 'FALL_DETECTED', 'VOICE_HELP')),
  CONSTRAINT ck_alert_route_type CHECK (route_type IS NULL OR route_type IN ('ALL_GUARDIANS', 'NEARBY_VOLUNTEERS', 'NONE')),
  CONSTRAINT ck_alert_route_consistency CHECK (status IN ('CREATED', 'DISPATCHING') OR route_type IS NOT NULL),
  CONSTRAINT ck_alert_location CHECK ((latitude IS NULL AND longitude IS NULL) OR (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180))
) ENGINE=InnoDB COMMENT='报警先持久化；位置快照可为空';
CREATE INDEX ix_alert_protectee_created ON alert (protectee_uid, created_at);
CREATE INDEX ix_alert_status_created ON alert (status, created_at);

CREATE TABLE alert_delivery (
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
  CONSTRAINT pk_alert_delivery PRIMARY KEY (id),
  CONSTRAINT uq_alert_delivery_receiver UNIQUE (alert_id, receiver_principal_type, receiver_principal_id),
  CONSTRAINT fk_alert_delivery_alert FOREIGN KEY (alert_id) REFERENCES alert(id),
  CONSTRAINT ck_alert_delivery_status CHECK (status IN ('PENDING', 'SENT', 'DELIVERED', 'ACKNOWLEDGED', 'FAILED')),
  CONSTRAINT ck_alert_delivery_receiver CHECK (receiver_principal_type = 'USER'),
  CONSTRAINT ck_alert_delivery_route CHECK (route_type IN ('ALL_GUARDIANS', 'NEARBY_VOLUNTEERS'))
) ENGINE=InnoDB COMMENT='一条报警对一个接收主体；接收人由 Java 路由事务固化';
CREATE INDEX ix_alert_delivery_receiver_status ON alert_delivery (receiver_principal_type, receiver_principal_id, status, created_at);

CREATE TABLE alert_broadcast (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  alert_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  radius_m INT UNSIGNED NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  closed_at DATETIME(3) NULL,
  CONSTRAINT pk_alert_broadcast PRIMARY KEY (id),
  CONSTRAINT uq_alert_broadcast_alert UNIQUE (alert_id),
  CONSTRAINT fk_alert_broadcast_alert FOREIGN KEY (alert_id) REFERENCES alert(id),
  CONSTRAINT ck_alert_broadcast_status CHECK (status IN ('OPEN', 'CLOSED', 'EXPIRED', 'CANCELLED')),
  CONSTRAINT ck_alert_broadcast_radius CHECK (radius_m > 0)
) ENGINE=InnoDB COMMENT='仅在报警创建时不存在生效监护人时创建';

CREATE TABLE alert_broadcast_response (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  broadcast_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  alert_delivery_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  volunteer_uid CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联志愿者账号',
  responded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT pk_alert_broadcast_response PRIMARY KEY (id),
  CONSTRAINT uq_alert_broadcast_response_volunteer UNIQUE (broadcast_id, volunteer_uid),
  CONSTRAINT uq_alert_broadcast_response_delivery UNIQUE (alert_delivery_id),
  CONSTRAINT fk_alert_broadcast_response_broadcast FOREIGN KEY (broadcast_id) REFERENCES alert_broadcast(id),
  CONSTRAINT fk_alert_broadcast_response_delivery FOREIGN KEY (alert_delivery_id) REFERENCES alert_delivery(id),
  CONSTRAINT ck_alert_broadcast_response_status CHECK (status IN ('ACCEPTED', 'DECLINED', 'EXPIRED', 'CANCELLED'))
) ENGINE=InnoDB COMMENT='志愿者响应；服务须校验投递属于该广播且收件人为该志愿者';
CREATE INDEX ix_alert_broadcast_response_volunteer ON alert_broadcast_response (volunteer_uid, created_at);

CREATE TABLE notification (
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
  CONSTRAINT pk_notification PRIMARY KEY (id),
  CONSTRAINT ck_notification_status CHECK (status IN ('UNREAD', 'READ')),
  CONSTRAINT ck_notification_principal CHECK (receiver_principal_type IN ('USER', 'ORGANIZATION', 'ADMIN')),
  CONSTRAINT ck_notification_type CHECK (notification_type IN ('BINDING_REQUEST', 'BINDING_RESULT', 'ALERT', 'APPLICATION_RESULT', 'ENROLLMENT_RESULT', 'ACTIVITY_CHANGE', 'ORG_REVIEW', 'SYSTEM')),
  CONSTRAINT ck_notification_payload CHECK (payload_json IS NULL OR JSON_TYPE(payload_json) = 'OBJECT')
) ENGINE=InnoDB COMMENT='多接收主体通知；不作为聊天消息表';
CREATE INDEX ix_notification_receiver_unread ON notification (receiver_principal_type, receiver_principal_id, status, created_at);

CREATE TABLE notification_delivery_attempt (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  notification_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  channel VARCHAR(16) NOT NULL,
  attempt_no INT UNSIGNED NOT NULL,
  provider_message_id VARCHAR(160) NULL,
  failure_reason VARCHAR(500) NULL,
  attempted_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT pk_notification_delivery_attempt PRIMARY KEY (id),
  CONSTRAINT uq_notification_delivery_attempt UNIQUE (notification_id, channel, attempt_no),
  CONSTRAINT fk_notification_delivery_attempt_notification FOREIGN KEY (notification_id) REFERENCES notification(id),
  CONSTRAINT ck_notification_delivery_attempt_status CHECK (status IN ('PENDING', 'SENT', 'DELIVERED', 'FAILED')),
  CONSTRAINT ck_notification_delivery_attempt_channel CHECK (channel IN ('PUSH', 'IN_APP'))
) ENGINE=InnoDB COMMENT='通知渠道重试记录，不含推送凭据';
CREATE INDEX ix_notification_delivery_attempt_pending ON notification_delivery_attempt (status, attempted_at);

CREATE TABLE outbox_message (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  message_type VARCHAR(48) NOT NULL,
  business_ref CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload JSON NOT NULL,
  retry_count INT UNSIGNED NOT NULL DEFAULT 0,
  next_retry_at DATETIME(3) NULL,
  last_error VARCHAR(500) NULL,
  trace_id VARCHAR(64) NULL,
  CONSTRAINT pk_outbox_message PRIMARY KEY (id),
  CONSTRAINT uq_outbox_business UNIQUE (message_type, business_ref),
  CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'DONE', 'FAILED')),
  CONSTRAINT ck_outbox_payload CHECK (JSON_TYPE(payload) = 'OBJECT')
) ENGINE=InnoDB COMMENT='Java 本地可靠任务表，替代消息队列';
CREATE INDEX ix_outbox_claim ON outbox_message (status, next_retry_at, created_at);

USE sullivan;

CREATE TABLE admin_account (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  tenant_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'FULL-RESERVED',
  external_ref VARCHAR(128) NULL COMMENT 'FULL-RESERVED',
  extension JSON NULL,
  login_name VARCHAR(64) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  display_name VARCHAR(80) NOT NULL,
  CONSTRAINT pk_admin_account PRIMARY KEY (id),
  CONSTRAINT uq_admin_account_login UNIQUE (login_name),
  CONSTRAINT ck_admin_account_status CHECK (status IN ('ENABLED', 'DISABLED')),
  CONSTRAINT ck_admin_account_extension CHECK (extension IS NULL OR JSON_TYPE(extension) = 'OBJECT')
) ENGINE=InnoDB COMMENT='平台管理员账号';

CREATE TABLE admin_role (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  role_code VARCHAR(64) NOT NULL,
  role_name VARCHAR(100) NOT NULL,
  is_system BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT pk_admin_role PRIMARY KEY (id),
  CONSTRAINT uq_admin_role_code UNIQUE (role_code),
  CONSTRAINT ck_admin_role_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB COMMENT='管理员 RBAC 角色；初始角色可为 SUPER_ADMIN、OPERATIONS';

CREATE TABLE admin_permission (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  permission_code VARCHAR(64) NOT NULL,
  permission_name VARCHAR(120) NOT NULL,
  CONSTRAINT pk_admin_permission PRIMARY KEY (id),
  CONSTRAINT uq_admin_permission_code UNIQUE (permission_code),
  CONSTRAINT ck_admin_permission_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB COMMENT='权限代码，如 ADMIN_MANAGE、ORG_REVIEW、BUSINESS_AUDIT';

CREATE TABLE admin_role_permission (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  role_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  permission_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  CONSTRAINT pk_admin_role_permission PRIMARY KEY (id),
  CONSTRAINT uq_admin_role_permission UNIQUE (role_id, permission_id),
  CONSTRAINT fk_admin_role_permission_role FOREIGN KEY (role_id) REFERENCES admin_role(id),
  CONSTRAINT fk_admin_role_permission_permission FOREIGN KEY (permission_id) REFERENCES admin_permission(id),
  CONSTRAINT ck_admin_role_permission_status CHECK (status IN ('ACTIVE', 'REVOKED'))
) ENGINE=InnoDB COMMENT='角色权限授权';

CREATE TABLE admin_account_role (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  role_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  granted_by_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
  expires_at DATETIME(3) NULL,
  CONSTRAINT pk_admin_account_role PRIMARY KEY (id),
  CONSTRAINT uq_admin_account_role UNIQUE (admin_id, role_id),
  CONSTRAINT fk_admin_account_role_admin FOREIGN KEY (admin_id) REFERENCES admin_account(id),
  CONSTRAINT fk_admin_account_role_role FOREIGN KEY (role_id) REFERENCES admin_role(id),
  CONSTRAINT fk_admin_account_role_granter FOREIGN KEY (granted_by_admin_id) REFERENCES admin_account(id),
  CONSTRAINT ck_admin_account_role_status CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED'))
) ENGINE=InnoDB COMMENT='管理员角色授权及授予人';
CREATE INDEX ix_admin_account_role_active ON admin_account_role (admin_id, status, expires_at);

CREATE TABLE admin_audit_log (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'FINAL',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  actor_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
  action_code VARCHAR(80) NOT NULL,
  target_principal_type VARCHAR(16) NOT NULL,
  target_principal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reason VARCHAR(1000) NULL,
  before_json JSON NULL,
  after_json JSON NULL,
  trace_id VARCHAR(64) NULL,
  CONSTRAINT pk_admin_audit_log PRIMARY KEY (id),
  CONSTRAINT fk_admin_audit_log_actor FOREIGN KEY (actor_admin_id) REFERENCES admin_account(id),
  CONSTRAINT ck_admin_audit_log_status CHECK (status = 'FINAL'),
  CONSTRAINT ck_admin_audit_log_target CHECK (target_principal_type IN ('USER', 'ORGANIZATION', 'ADMIN', 'SYSTEM')),
  CONSTRAINT ck_admin_audit_log_before CHECK (before_json IS NULL OR JSON_TYPE(before_json) = 'OBJECT'),
  CONSTRAINT ck_admin_audit_log_after CHECK (after_json IS NULL OR JSON_TYPE(after_json) = 'OBJECT')
) ENGINE=InnoDB COMMENT='管理员不可变审计；应用角色不得更新或删除';
CREATE INDEX ix_admin_audit_log_target_time ON admin_audit_log (target_principal_type, target_principal_id, created_at);
CREATE INDEX ix_admin_audit_log_actor_time ON admin_audit_log (actor_admin_id, created_at);
