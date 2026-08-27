-- =============================================================
-- SullivanMINI V5 — 管理员注册码改为明文存储
--
-- 之前注册码以 SHA-256 哈希存于 admin_registration_code.code_hash，
-- 现按需求改为明文存储：明文注册码 code 即主键，去掉 id 与 code_hash。
-- 原哈希无法还原明文，且本库管理员数据已清空（admin_registration_code 无存量），
-- 故直接重建表。
-- =============================================================

DROP TABLE IF EXISTS admin_registration_code;

CREATE TABLE admin_registration_code (
  code VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '注册码明文（主键，一次性）',
  role_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  expires_at DATETIME(3) NULL,
  created_by_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  used_by_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
  used_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (code),
  KEY ix_admin_registration_code_status (status, expires_at),
  CONSTRAINT fk_registration_code_role FOREIGN KEY (role_id) REFERENCES admin_role(id),
  CONSTRAINT fk_registration_code_creator FOREIGN KEY (created_by_admin_id) REFERENCES admin_account(id),
  CONSTRAINT fk_registration_code_user FOREIGN KEY (used_by_admin_id) REFERENCES admin_account(id),
  CONSTRAINT ck_registration_code_status CHECK (status IN ('ACTIVE', 'USED', 'REVOKED', 'EXPIRED'))
) ENGINE=InnoDB COMMENT='管理员注册码（明文存储）；注册码即主键，使用后核销';
