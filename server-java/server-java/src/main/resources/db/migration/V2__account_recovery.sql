-- =============================================================
-- SullivanMINI V2 — 找回联系方式（半加密两段存储）与提醒状态
-- 新增两张表，不修改已有表。
-- 联系方式: 明文 prefix（手机号后四位 / 邮箱 local 前1/4 段，最多 4 字符）
--           + 单向加密 cipher_hash（HMAC-SHA256，key=prefix）。
-- =============================================================

CREATE TABLE account_recovery (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  principal_type VARCHAR(16) NOT NULL COMMENT '主体类型：USER / ORGANIZATION',
  principal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'user_account.id 或 organization.id（多态，无外键）',
  recovery_type VARCHAR(8) NOT NULL COMMENT '找回方式：PHONE / EMAIL',
  prefix VARCHAR(16) NOT NULL COMMENT '明文不加密段：手机号后四位 / 邮箱 local 前1/4(≤4)',
  cipher_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '剩余段 HMAC-SHA256(key=prefix) hex，单向不可逆',
  CONSTRAINT pk_account_recovery PRIMARY KEY (id),
  CONSTRAINT uq_account_recovery_principal UNIQUE (principal_type, principal_id, recovery_type),
  CONSTRAINT ck_account_recovery_status CHECK (status IN ('ACTIVE', 'DISABLED')),
  CONSTRAINT ck_account_recovery_type CHECK (recovery_type IN ('PHONE', 'EMAIL'))
) ENGINE=InnoDB COMMENT='找回联系方式（半加密两段存储）';

CREATE TABLE account_recovery_reminder (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  principal_type VARCHAR(16) NOT NULL COMMENT '主体类型：USER / ORGANIZATION',
  principal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'user_account.id 或 organization.id',
  remind_never BOOLEAN NOT NULL DEFAULT FALSE COMMENT '不再提醒',
  last_reminded_at DATETIME(3) NULL COMMENT '上次点击"下次再说"的时间',
  CONSTRAINT pk_account_recovery_reminder PRIMARY KEY (id),
  CONSTRAINT uq_account_recovery_reminder_principal UNIQUE (principal_type, principal_id),
  CONSTRAINT ck_account_recovery_reminder_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB COMMENT='找回联系方式提醒状态（下次再说 / 不再提醒）';
