-- =============================================================
-- SullivanMINI V6 — 二维码：扫码绑定受护人 + 扫码授权登录
--
-- 两张表都沿用 guardian_binding 已使用的「生成列做唯一约束」写法：
-- 生成列只是把「业务上唯一的条件」表达给数据库，而不是靠应用层自觉。
--
--   protectee_bind_code  受护人出示的绑定码（登录后展示；一次只有一张有效）
--   qr_login_ticket      受护人登录页出示的登录票据（未登录即可展示；一次性消费）
--
-- 两张表的码都由服务端签发，客户端只负责把码放进二维码。
-- =============================================================

CREATE TABLE IF NOT EXISTS protectee_bind_code (
  code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '绑定码明文（主键）；取自去易混字符表',
  protectee_uid CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '出示绑定码的受护人',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 待扫 / REVOKED 被新码替换 / EXPIRED 超时',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  expires_at DATETIME(3) NOT NULL COMMENT '过期时间；TTL 由 app.qr.bind-code-ttl-seconds 控制',
  revoked_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  active_code_key CHAR(26) CHARACTER SET ascii COLLATE ascii_bin
    GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN protectee_uid ELSE NULL END) STORED,
  CONSTRAINT pk_protectee_bind_code PRIMARY KEY (code),
  CONSTRAINT uq_protectee_bind_code_active UNIQUE (active_code_key),
  CONSTRAINT fk_protectee_bind_code_user FOREIGN KEY (protectee_uid) REFERENCES user_account(id),
  CONSTRAINT ck_protectee_bind_code_status CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED'))
) ENGINE=InnoDB COMMENT='受护人出示的扫码绑定码；每位受护人同时最多一张 ACTIVE';
CREATE INDEX ix_protectee_bind_code_user_status ON protectee_bind_code (protectee_uid, status, expires_at);

CREATE TABLE IF NOT EXISTS qr_login_ticket (
  ticket VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '登录票据明文（主键，一次性）',
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
    COMMENT 'PENDING 待扫 / SCANNED 已扫待选 / CONFIRMED 已授权 / CONSUMED 已换令牌 / EXPIRED 超时',
  secret_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    COMMENT '出示设备本地生成的 clientSecret 的 SHA-256；明文只留在设备上，服务端不保存',
  guardian_uid CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '扫描该票据的监护人',
  protectee_uid CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '监护人选定并授权的受护人',
  exchange_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
    COMMENT 'CONFIRMED 后下发给轮询方的换令牌凭据；与 clientSecret 同时校验',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  expires_at DATETIME(3) NOT NULL COMMENT '仅约束 PENDING/SCANNED；CONFIRMED 后不再受 TTL 影响',
  scanned_at DATETIME(3) NULL,
  confirmed_at DATETIME(3) NULL,
  consumed_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  CONSTRAINT pk_qr_login_ticket PRIMARY KEY (ticket),
  CONSTRAINT fk_qr_login_ticket_guardian FOREIGN KEY (guardian_uid) REFERENCES user_account(id),
  CONSTRAINT fk_qr_login_ticket_protectee FOREIGN KEY (protectee_uid) REFERENCES user_account(id),
  CONSTRAINT ck_qr_login_ticket_status CHECK (status IN ('PENDING', 'SCANNED', 'CONFIRMED', 'CONSUMED', 'EXPIRED'))
) ENGINE=InnoDB COMMENT='扫码登录票据；监护人确认后由出示设备换取受护人令牌';
CREATE INDEX ix_qr_login_ticket_status ON qr_login_ticket (status, expires_at);
