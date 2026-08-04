-- =============================================================
-- SullivanMINI V1 Baseline — sullivan_user
-- 对应 docs/final/05 §2、14 §1 修正版
-- =============================================================

CREATE TABLE user_account (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '应用生成的 ULID',
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
  role_code VARCHAR(16) NOT NULL COMMENT 'PROTECTEE / GUARDIAN / VOLUNTEER',
  phone_ciphertext VARBINARY(512) NULL,
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
  tenant_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
  external_ref VARCHAR(128) NULL,
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
  revoked_at DATETIME(3) NULL,
  device_label VARCHAR(120) NULL,
  CONSTRAINT pk_auth_refresh_token PRIMARY KEY (id),
  CONSTRAINT uq_auth_refresh_token_hash UNIQUE (token_hash),
  CONSTRAINT fk_auth_refresh_token_user FOREIGN KEY (user_id) REFERENCES user_account(id),
  CONSTRAINT ck_auth_refresh_token_status CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED'))
) ENGINE=InnoDB COMMENT='仅保存刷新令牌哈希';
CREATE INDEX ix_auth_refresh_token_user_status ON auth_refresh_token (user_id, status, expires_at);

-- 修正: 使用 02 §4.1 的 6 值状态 (14 §1.3)
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
  expires_at DATETIME(3) NULL COMMENT 'PENDING 后 7 天过期',
  decision_note VARCHAR(500) NULL,
  active_pair_key VARCHAR(53) GENERATED ALWAYS AS (
    CASE WHEN deleted_at IS NULL AND status IN ('PENDING','ACTIVE')
    THEN CONCAT(guardian_uid, ':', protectee_uid) ELSE NULL END) STORED,
  primary_protectee_key CHAR(26) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS (
    CASE WHEN deleted_at IS NULL AND status = 'ACTIVE' AND is_primary
    THEN protectee_uid ELSE NULL END) STORED,
  CONSTRAINT pk_guardian_binding PRIMARY KEY (id),
  CONSTRAINT uq_guardian_binding_active_pair UNIQUE (active_pair_key),
  CONSTRAINT uq_guardian_binding_primary UNIQUE (primary_protectee_key),
  CONSTRAINT fk_guardian_binding_guardian FOREIGN KEY (guardian_uid) REFERENCES user_account(id),
  CONSTRAINT fk_guardian_binding_protectee FOREIGN KEY (protectee_uid) REFERENCES user_account(id),
  CONSTRAINT ck_guardian_binding_status CHECK (status IN
    ('PENDING','ACTIVE','REJECTED','CANCELLED','EXPIRED','TERMINATED')),
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
  purpose VARCHAR(40) NOT NULL,
  granted_at DATETIME(3) NULL,
  revoked_at DATETIME(3) NULL,
  source VARCHAR(32) NOT NULL DEFAULT 'CLIENT_SYSTEM_PERMISSION',
  CONSTRAINT pk_location_authorization PRIMARY KEY (id),
  CONSTRAINT uq_location_authorization_user_purpose UNIQUE (user_id, purpose),
  CONSTRAINT fk_location_authorization_user FOREIGN KEY (user_id) REFERENCES user_account(id),
  CONSTRAINT ck_location_authorization_status CHECK (status IN ('GRANTED', 'DENIED', 'REVOKED')),
  CONSTRAINT ck_location_authorization_purpose CHECK (purpose IN ('PROTECTEE_SHARING','VOLUNTEER_ALERT_MATCHING','NAVIGATION'))
) ENGINE=InnoDB COMMENT='位置用途授权留痕';

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
  CONSTRAINT ck_latest_location_longitude CHECK (longitude BETWEEN -180 AND 180)
) ENGINE=InnoDB COMMENT='仅存最新位置';
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
) ENGINE=InnoDB COMMENT='志愿者显式开关可服务状态';
