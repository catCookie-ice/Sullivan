-- SullivanMINI V1 Baseline — sullivan_admin

CREATE TABLE admin_account (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  login_name VARCHAR(64) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  display_name VARCHAR(80) NOT NULL,
  CONSTRAINT pk_admin_account PRIMARY KEY (id),
  CONSTRAINT uq_admin_account_login UNIQUE (login_name),
  CONSTRAINT ck_admin_account_status CHECK (status IN ('ENABLED', 'DISABLED'))
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
) ENGINE=InnoDB COMMENT='管理员 RBAC 角色';

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
) ENGINE=InnoDB COMMENT='权限代码字典';

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
  CONSTRAINT ck_admin_account_role_status CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED'))
) ENGINE=InnoDB COMMENT='管理员角色授权';
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
  CONSTRAINT ck_admin_audit_log_target CHECK (target_principal_type IN ('USER','ORGANIZATION','ADMIN','SYSTEM')),
  CONSTRAINT ck_admin_audit_log_before CHECK (before_json IS NULL OR JSON_TYPE(before_json) = 'OBJECT'),
  CONSTRAINT ck_admin_audit_log_after CHECK (after_json IS NULL OR JSON_TYPE(after_json) = 'OBJECT')
) ENGINE=InnoDB COMMENT='管理员不可变审计记录';
CREATE INDEX ix_admin_audit_log_target_time ON admin_audit_log (target_principal_type, target_principal_id, created_at);
CREATE INDEX ix_admin_audit_log_actor_time ON admin_audit_log (actor_admin_id, created_at);

-- =============================================================
-- 种子数据
-- =============================================================
INSERT INTO admin_permission (id, permission_code, permission_name) VALUES
  ('P00000000000000000000000001', 'ADMIN_MANAGE', '管理员账号管理'),
  ('P00000000000000000000000002', 'USER_READ', '查看用户资料'),
  ('P00000000000000000000000003', 'USER_GOVERN', '用户治理'),
  ('P00000000000000000000000004', 'ORG_REVIEW', '机构审核'),
  ('P00000000000000000000000005', 'ORG_GOVERN', '机构治理'),
  ('P00000000000000000000000006', 'BUSINESS_AUDIT', '业务审计'),
  ('P00000000000000000000000007', 'OPERATION_STATS', '运营统计'),
  ('P00000000000000000000000008', 'SYSTEM_NOTICE', '发布系统通知');

INSERT INTO admin_role (id, role_code, role_name, is_system) VALUES
  ('R00000000000000000000000001', 'SUPER_ADMIN', '超级管理员', TRUE),
  ('R00000000000000000000000002', 'OPERATIONS', '运营管理员', TRUE);

-- SUPER_ADMIN 拥有全部权限
INSERT INTO admin_role_permission (id, role_id, permission_id)
SELECT
  CONCAT('RP', LPAD(ROW_NUMBER() OVER(), 22, '0')),
  'R00000000000000000000000001', p.id
FROM admin_permission p;

-- OPERATIONS 仅拥有运营统计权限
INSERT INTO admin_role_permission (id, role_id, permission_id) VALUES
  ('RP000000000000000000000009', 'R00000000000000000000000002',
   'P00000000000000000000000007');
