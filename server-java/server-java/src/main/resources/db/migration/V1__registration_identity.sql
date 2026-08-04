-- Registration and identity extensions for the Java modular monolith.
-- This migration is safe to run after docs/final/db/schema.sql was applied.

CREATE TABLE IF NOT EXISTS user_account (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  login_name VARCHAR(64) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  role_code VARCHAR(16) NOT NULL,
  phone_ciphertext VARBINARY(512) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_user_account_login (login_name),
  CONSTRAINT ck_registration_user_status CHECK (status IN ('ENABLED', 'DISABLED')),
  CONSTRAINT ck_registration_user_role CHECK (role_code IN ('PROTECTEE', 'GUARDIAN', 'VOLUNTEER'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS user_profile (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  display_name VARCHAR(80) NOT NULL,
  emergency_note VARCHAR(500) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_registration_user_profile_user (user_id),
  CONSTRAINT fk_registration_profile_user FOREIGN KEY (user_id) REFERENCES user_account(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS user_role (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  role_code VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_user_role (user_id, role_code),
  KEY ix_user_role_user_status (user_id, status),
  CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES user_account(id),
  CONSTRAINT ck_user_role_code CHECK (role_code IN ('PROTECTEE', 'GUARDIAN', 'VOLUNTEER')),
  CONSTRAINT ck_user_role_status CHECK (status IN ('ACTIVE', 'REVOKED'))
) ENGINE=InnoDB COMMENT='自然人角色关系；支持监护人兼任志愿者';

CREATE TABLE IF NOT EXISTS guardian_binding (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  guardian_uid CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  protectee_uid CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  is_primary BOOLEAN NOT NULL DEFAULT FALSE,
  permanent_flag BOOLEAN NOT NULL DEFAULT FALSE,
  requested_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  confirmed_at DATETIME(3) NULL,
  expires_at DATETIME(3) NULL,
  decision_note VARCHAR(500) NULL,
  PRIMARY KEY (id),
  KEY ix_registration_binding_guardian (guardian_uid, status),
  KEY ix_registration_binding_protectee (protectee_uid, status),
  CONSTRAINT fk_registration_binding_guardian FOREIGN KEY (guardian_uid) REFERENCES user_account(id),
  CONSTRAINT fk_registration_binding_protectee FOREIGN KEY (protectee_uid) REFERENCES user_account(id),
  CONSTRAINT ck_registration_binding_status CHECK (status IN ('PENDING', 'ACTIVE', 'REJECTED', 'CANCELLED', 'EXPIRED', 'TERMINATED')),
  CONSTRAINT ck_registration_binding_distinct CHECK (guardian_uid <> protectee_uid),
  CONSTRAINT ck_registration_binding_primary CHECK (NOT is_primary OR status = 'ACTIVE')
) ENGINE=InnoDB;

SET @guardian_binding_permanent_flag_sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE guardian_binding ADD COLUMN permanent_flag BOOLEAN NOT NULL DEFAULT FALSE',
    'SELECT 1'
  )
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'guardian_binding'
    AND column_name = 'permanent_flag'
);
PREPARE guardian_binding_permanent_flag_stmt FROM @guardian_binding_permanent_flag_sql;
EXECUTE guardian_binding_permanent_flag_stmt;
DEALLOCATE PREPARE guardian_binding_permanent_flag_stmt;

SET @guardian_binding_expires_at_sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE guardian_binding ADD COLUMN expires_at DATETIME(3) NULL',
    'SELECT 1'
  )
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'guardian_binding'
    AND column_name = 'expires_at'
);
PREPARE guardian_binding_expires_at_stmt FROM @guardian_binding_expires_at_sql;
EXECUTE guardian_binding_expires_at_stmt;
DEALLOCATE PREPARE guardian_binding_expires_at_stmt;

CREATE TABLE IF NOT EXISTS organization (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  login_name VARCHAR(64) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  legal_name VARCHAR(160) NOT NULL,
  public_name VARCHAR(160) NOT NULL,
  contact_ciphertext VARBINARY(1024) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_registration_organization_login (login_name),
  CONSTRAINT ck_registration_organization_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'DISABLED'))
) ENGINE=InnoDB;

SET @organization_login_name_sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE organization ADD COLUMN login_name VARCHAR(64) NULL',
    'SELECT 1'
  )
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'organization'
    AND column_name = 'login_name'
);
PREPARE organization_login_name_stmt FROM @organization_login_name_sql;
EXECUTE organization_login_name_stmt;
DEALLOCATE PREPARE organization_login_name_stmt;

SET @organization_password_hash_sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE organization ADD COLUMN password_hash VARCHAR(255) NULL',
    'SELECT 1'
  )
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'organization'
    AND column_name = 'password_hash'
);
PREPARE organization_password_hash_stmt FROM @organization_password_hash_sql;
EXECUTE organization_password_hash_stmt;
DEALLOCATE PREPARE organization_password_hash_stmt;

CREATE TABLE IF NOT EXISTS admin_account (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  login_name VARCHAR(64) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  display_name VARCHAR(80) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_registration_admin_login (login_name),
  CONSTRAINT ck_registration_admin_status CHECK (status IN ('ENABLED', 'DISABLED'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS admin_role (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  role_code VARCHAR(64) NOT NULL,
  role_name VARCHAR(100) NOT NULL,
  is_system BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (id),
  UNIQUE KEY uq_registration_admin_role_code (role_code)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS admin_permission (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  permission_code VARCHAR(64) NOT NULL,
  permission_name VARCHAR(120) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_registration_admin_permission_code (permission_code)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS admin_role_permission (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  role_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  permission_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  PRIMARY KEY (id),
  UNIQUE KEY uq_registration_role_permission (role_id, permission_id),
  CONSTRAINT fk_registration_role_permission_role FOREIGN KEY (role_id) REFERENCES admin_role(id),
  CONSTRAINT fk_registration_role_permission_permission FOREIGN KEY (permission_id) REFERENCES admin_permission(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS admin_account_role (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  role_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  granted_by_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
  expires_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_registration_admin_role (admin_id, role_id),
  CONSTRAINT fk_registration_admin_role_admin FOREIGN KEY (admin_id) REFERENCES admin_account(id),
  CONSTRAINT fk_registration_admin_role_role FOREIGN KEY (role_id) REFERENCES admin_role(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS admin_registration_code (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  code_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  role_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  expires_at DATETIME(3) NULL,
  created_by_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  used_by_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
  used_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uq_admin_registration_code_hash (code_hash),
  KEY ix_admin_registration_code_status (status, expires_at),
  CONSTRAINT fk_registration_code_role FOREIGN KEY (role_id) REFERENCES admin_role(id),
  CONSTRAINT fk_registration_code_creator FOREIGN KEY (created_by_admin_id) REFERENCES admin_account(id),
  CONSTRAINT fk_registration_code_user FOREIGN KEY (used_by_admin_id) REFERENCES admin_account(id),
  CONSTRAINT ck_registration_code_status CHECK (status IN ('ACTIVE', 'USED', 'REVOKED', 'EXPIRED'))
) ENGINE=InnoDB COMMENT='管理员注册码；注册码映射初始管理员角色';

INSERT INTO admin_permission (id, permission_code, permission_name)
SELECT 'P0000000000000000000000009', 'ADMIN_REGISTRATION_CODE_CREATE', '创建管理员注册码'
WHERE NOT EXISTS (SELECT 1 FROM admin_permission WHERE permission_code = 'ADMIN_REGISTRATION_CODE_CREATE');

INSERT INTO admin_role (id, role_code, role_name, is_system)
SELECT 'R0000000000000000000000003', 'HR_ADMIN', 'HR管理员', TRUE
WHERE NOT EXISTS (SELECT 1 FROM admin_role WHERE role_code = 'HR_ADMIN');

INSERT INTO admin_role_permission (id, role_id, permission_id)
SELECT 'RP000000000000000000000010', r.id, p.id
FROM admin_role r CROSS JOIN admin_permission p
WHERE r.role_code = 'HR_ADMIN'
  AND p.permission_code = 'ADMIN_REGISTRATION_CODE_CREATE'
  AND NOT EXISTS (
    SELECT 1 FROM admin_role_permission x WHERE x.role_id = r.id AND x.permission_id = p.id);
