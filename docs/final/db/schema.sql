-- ============================================================================
-- SullivanMINI 数据模型参考（重构版）— 与 server-java Flyway V1__baseline.sql 同步
--
-- 本文件是数据库文档参考，与 `server-java/server-java/src/main/resources/db/migration/V1__baseline.sql`
-- 保持一致（该文件为唯一权威来源，改表先改 Flyway 再同步本文）。
-- 手动建库可执行本文件；常规部署由 Flyway 在应用启动时自动建表。
--
-- 要求 MySQL 8.0.16+（CHECK 约束在此版本起真正生效）。
--
-- 范围说明：
--   * 仅包含 Java 服务实际使用的 26 张表。Python 导航端已放弃，导航三表不建；
--     设计文档中的未实现表（welfare_service / alert_broadcast / outbox_message /
--     admin_audit_log 等）不再建空表。
--   * 权限与角色种子不在 SQL 中，由 AdminSeedInitializer 启动时幂等播种。
--   * 升级旧库：数据不保留，DROP DATABASE 后重建（或删除 deploy/data/mysql 卷）。
--
-- 全库统一约定：
--   主键      业务表 id CHAR(26) ascii_bin（应用层 ULID）；凭据类表以凭据为主键。
--   审计列    status / created_at / updated_at / deleted_at / version 五件套。
--   软删除    deleted_at IS NULL = 未删除。
--   约束命名  pk_/uq_/fk_/ck_<表名>_<含义>；索引 ix_<表名>_<含义>。
--   唯一性    「业务上只允许一条有效行」用生成列 + UNIQUE 由数据库兜底。
--   状态机    时间戳与状态一致性用单向 CHECK 表达（推进必填，回退不强清）。
-- ============================================================================

SET NAMES utf8mb4;
USE sullivan;


-- ============================================================================
-- §1 用户与身份
-- ============================================================================

CREATE TABLE user_account (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '应用生成的 ULID',
  status          VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED 正常 / DISABLED 禁用',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  tenant_id       CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'FULL-RESERVED：未来租户逻辑外键',
  external_ref    VARCHAR(128) NULL COMMENT 'FULL-RESERVED：外部身份稳定引用',
  extension       JSON NULL COMMENT '受约束的扩展对象；不得承载权限、状态或金额',
  login_name      VARCHAR(64) NOT NULL COMMENT '登录名；全局唯一',
  password_hash   VARCHAR(255) NOT NULL COMMENT '密码哈希（BCrypt）',
  role_code       VARCHAR(16) NOT NULL COMMENT '主角色：PROTECTEE 受护人 / GUARDIAN 监护人 / VOLUNTEER 志愿者',
  phone_ciphertext VARBINARY(512) NULL COMMENT '加密手机号；不在普通接口返回',
  CONSTRAINT pk_user_account PRIMARY KEY (id),
  CONSTRAINT uq_user_account_login UNIQUE (login_name),
  CONSTRAINT ck_user_account_status CHECK (status IN ('ENABLED', 'DISABLED')),
  CONSTRAINT ck_user_account_role CHECK (role_code IN ('PROTECTEE', 'GUARDIAN', 'VOLUNTEER')),
  CONSTRAINT ck_user_account_extension CHECK (extension IS NULL OR JSON_TYPE(extension) = 'OBJECT')
) ENGINE=InnoDB COMMENT='自然人账号；主角色在本表，兼任角色在 user_role';

CREATE TABLE user_profile (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 启用 / INACTIVE 停用',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  tenant_id       CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'FULL-RESERVED',
  external_ref    VARCHAR(128) NULL COMMENT 'FULL-RESERVED',
  extension       JSON NULL,
  user_id         CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账号；一人一档',
  display_name    VARCHAR(80) NOT NULL COMMENT '展示昵称',
  emergency_note  VARCHAR(500) NULL COMMENT '紧急备注（监护人可见）',
  CONSTRAINT pk_user_profile PRIMARY KEY (id),
  CONSTRAINT uq_user_profile_user UNIQUE (user_id),
  CONSTRAINT fk_user_profile_account FOREIGN KEY (user_id) REFERENCES user_account(id),
  CONSTRAINT ck_user_profile_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
  CONSTRAINT ck_user_profile_extension CHECK (extension IS NULL OR JSON_TYPE(extension) = 'OBJECT')
) ENGINE=InnoDB COMMENT='自然人资料';

CREATE TABLE user_role (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 生效 / REVOKED 已撤销',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  user_id         CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账号',
  role_code       VARCHAR(16) NOT NULL COMMENT 'PROTECTEE / GUARDIAN / VOLUNTEER（兼任角色）',
  created_by_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '创建者账号ID（注册时为本人或其监护人）',
  CONSTRAINT pk_user_role PRIMARY KEY (id),
  CONSTRAINT uq_user_role UNIQUE (user_id, role_code),
  CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES user_account(id),
  CONSTRAINT fk_user_role_creator FOREIGN KEY (created_by_user_id) REFERENCES user_account(id),
  CONSTRAINT ck_user_role_code CHECK (role_code IN ('PROTECTEE', 'GUARDIAN', 'VOLUNTEER')),
  CONSTRAINT ck_user_role_status CHECK (status IN ('ACTIVE', 'REVOKED'))
) ENGINE=InnoDB COMMENT='自然人角色关系；支持监护人兼任志愿者。同账号同角色至多一行';
CREATE INDEX ix_user_role_user_status ON user_role (user_id, status);

-- ★ 唯一配对 / 唯一主监护人由生成列 + UNIQUE 由数据库兜底（活库旧版缺失，本版恢复）。
CREATE TABLE guardian_binding (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待确认 / ACTIVE 生效 / REJECTED 已拒绝 / CANCELLED 已取消 / EXPIRED 已过期 / TERMINATED 已解除',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  guardian_uid    CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '监护人账号',
  protectee_uid   CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '受护人账号',
  is_primary      BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否主监护人；仅 ACTIVE 绑定可为 TRUE',
  permanent_flag  BOOLEAN NOT NULL DEFAULT FALSE COMMENT '永久绑定标记（长期照护关系）',
  requested_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  confirmed_at    DATETIME(3) NULL COMMENT '受护人确认时间；进入 ACTIVE 时必填',
  expires_at      DATETIME(3) NULL COMMENT 'PENDING 后 7 天过期',
  decision_note   VARCHAR(500) NULL COMMENT '拒绝/解除原因',
  requested_by    CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '发起方账号ID（= guardian_uid 或 protectee_uid，方向由比较得出）',
  active_pair_key VARCHAR(53) GENERATED ALWAYS AS (
    CASE WHEN deleted_at IS NULL AND status IN ('PENDING', 'ACTIVE')
    THEN CONCAT(guardian_uid, ':', protectee_uid) ELSE NULL END) STORED,
  primary_protectee_key CHAR(26) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS (
    CASE WHEN deleted_at IS NULL AND status = 'ACTIVE' AND is_primary
    THEN protectee_uid ELSE NULL END) STORED,
  CONSTRAINT pk_guardian_binding PRIMARY KEY (id),
  CONSTRAINT uq_guardian_binding_active_pair UNIQUE (active_pair_key),
  CONSTRAINT uq_guardian_binding_primary UNIQUE (primary_protectee_key),
  CONSTRAINT fk_guardian_binding_guardian FOREIGN KEY (guardian_uid) REFERENCES user_account(id),
  CONSTRAINT fk_guardian_binding_protectee FOREIGN KEY (protectee_uid) REFERENCES user_account(id),
  CONSTRAINT fk_guardian_binding_requester FOREIGN KEY (requested_by) REFERENCES user_account(id),
  CONSTRAINT ck_guardian_binding_status CHECK (status IN
    ('PENDING', 'ACTIVE', 'REJECTED', 'CANCELLED', 'EXPIRED', 'TERMINATED')),
  CONSTRAINT ck_guardian_binding_distinct CHECK (guardian_uid <> protectee_uid),
  CONSTRAINT ck_guardian_binding_primary CHECK (NOT is_primary OR status = 'ACTIVE'),
  CONSTRAINT ck_guardian_binding_confirmed CHECK (status <> 'ACTIVE' OR confirmed_at IS NOT NULL)
) ENGINE=InnoDB COMMENT='监护绑定；确认后才生效。唯一配对与唯一主监护人由数据库生成列兜底';
CREATE INDEX ix_guardian_binding_protectee_status ON guardian_binding (protectee_uid, status, created_at);
CREATE INDEX ix_guardian_binding_guardian_status ON guardian_binding (guardian_uid, status, created_at);


-- ============================================================================
-- §2 管理员与 RBAC
-- ============================================================================

CREATE TABLE admin_account (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED 正常 / DISABLED 禁用',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  login_name      VARCHAR(64) NOT NULL COMMENT '管理员登录名；全局唯一',
  password_hash   VARCHAR(255) NOT NULL COMMENT '密码哈希（BCrypt）',
  display_name    VARCHAR(80) NOT NULL COMMENT '展示昵称',
  CONSTRAINT pk_admin_account PRIMARY KEY (id),
  CONSTRAINT uq_admin_account_login UNIQUE (login_name),
  CONSTRAINT ck_admin_account_status CHECK (status IN ('ENABLED', 'DISABLED'))
) ENGINE=InnoDB COMMENT='平台管理员账号；首个账号由启动引导（HighestAdminBootstrap）创建';

CREATE TABLE admin_role (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 启用 / DISABLED 停用',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  role_code       VARCHAR(64) NOT NULL COMMENT '角色代码；系统角色 SUPER_ADMIN / HR_ADMIN / OPERATIONS',
  role_name       VARCHAR(100) NOT NULL COMMENT '角色显示名',
  is_system       BOOLEAN NOT NULL DEFAULT FALSE COMMENT '系统内置角色不可删除',
  CONSTRAINT pk_admin_role PRIMARY KEY (id),
  CONSTRAINT uq_admin_role_code UNIQUE (role_code),
  CONSTRAINT ck_admin_role_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB COMMENT='管理员 RBAC 角色；种子由 AdminSeedInitializer 播种';

CREATE TABLE admin_permission (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 启用 / DISABLED 停用',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  permission_code VARCHAR(64) NOT NULL COMMENT '权限代码，如 ADMIN_MANAGE / ORG_REVIEW',
  permission_name VARCHAR(120) NOT NULL COMMENT '权限显示名',
  CONSTRAINT pk_admin_permission PRIMARY KEY (id),
  CONSTRAINT uq_admin_permission_code UNIQUE (permission_code),
  CONSTRAINT ck_admin_permission_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB COMMENT='权限代码字典；种子由 AdminSeedInitializer 播种';

CREATE TABLE admin_role_permission (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 授予 / REVOKED 撤销',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  role_id         CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '角色',
  permission_id   CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '权限',
  CONSTRAINT pk_admin_role_permission PRIMARY KEY (id),
  CONSTRAINT uq_admin_role_permission UNIQUE (role_id, permission_id),
  CONSTRAINT fk_admin_role_permission_role FOREIGN KEY (role_id) REFERENCES admin_role(id),
  CONSTRAINT fk_admin_role_permission_permission FOREIGN KEY (permission_id) REFERENCES admin_permission(id),
  CONSTRAINT ck_admin_role_permission_status CHECK (status IN ('ACTIVE', 'REVOKED'))
) ENGINE=InnoDB COMMENT='角色-权限授权；同角色同权限至多一行，撤销后再授予 = 更新状态';

CREATE TABLE admin_account_role (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 生效 / REVOKED 撤销 / EXPIRED 过期',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  admin_id        CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '管理员账号',
  role_id         CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '角色',
  granted_by_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '授予人；首管自举时为 NULL',
  expires_at      DATETIME(3) NULL COMMENT '过期时间；NULL 永久',
  CONSTRAINT pk_admin_account_role PRIMARY KEY (id),
  CONSTRAINT uq_admin_account_role UNIQUE (admin_id, role_id),
  CONSTRAINT fk_admin_account_role_admin FOREIGN KEY (admin_id) REFERENCES admin_account(id),
  CONSTRAINT fk_admin_account_role_role FOREIGN KEY (role_id) REFERENCES admin_role(id),
  CONSTRAINT fk_admin_account_role_granter FOREIGN KEY (granted_by_admin_id) REFERENCES admin_account(id),
  CONSTRAINT ck_admin_account_role_status CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED'))
) ENGINE=InnoDB COMMENT='管理员-角色授权；同管理员同角色至多一行';
CREATE INDEX ix_admin_account_role_active ON admin_account_role (admin_id, status, expires_at);

-- 核销回填存在中间态（先置 USED 后回填使用人），故只约束非 USED 状态不得带使用痕迹。
CREATE TABLE admin_registration_code (
  code             VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '注册码明文（主键，一次性）',
  role_id          CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '注册后授予的角色',
  status           VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 可用 / USED 已用 / REVOKED 已作废 / EXPIRED 已过期',
  created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  expires_at       DATETIME(3) NULL COMMENT '过期时间；NULL 不过期',
  created_by_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '签发人',
  used_by_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '使用人；核销后回填',
  used_at          DATETIME(3) NULL COMMENT '核销时间；核销后回填',
  version          BIGINT UNSIGNED NOT NULL DEFAULT 0,
  CONSTRAINT pk_admin_registration_code PRIMARY KEY (code),
  CONSTRAINT fk_registration_code_role FOREIGN KEY (role_id) REFERENCES admin_role(id),
  CONSTRAINT fk_registration_code_creator FOREIGN KEY (created_by_admin_id) REFERENCES admin_account(id),
  CONSTRAINT fk_registration_code_user FOREIGN KEY (used_by_admin_id) REFERENCES admin_account(id),
  CONSTRAINT ck_registration_code_status CHECK (status IN ('ACTIVE', 'USED', 'REVOKED', 'EXPIRED')),
  CONSTRAINT ck_registration_code_usage CHECK (status = 'USED' OR (used_by_admin_id IS NULL AND used_at IS NULL))
) ENGINE=InnoDB COMMENT='管理员注册码（明文）；核销留痕，非 USED 不得带使用痕迹';
CREATE INDEX ix_admin_registration_code_status ON admin_registration_code (status, expires_at);


-- ============================================================================
-- §3 机构与账号找回
-- ============================================================================

CREATE TABLE organization (
  id                 CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status             VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待审 / APPROVED 已通过 / REJECTED 已驳回 / DISABLED 已停用',
  created_at         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at         DATETIME(3) NULL,
  version            BIGINT UNSIGNED NOT NULL DEFAULT 0,
  login_name         VARCHAR(64) NOT NULL COMMENT '机构登录名；全局唯一',
  password_hash      VARCHAR(255) NOT NULL COMMENT '密码哈希（BCrypt）',
  legal_name         VARCHAR(160) NOT NULL COMMENT '机构法定名称',
  public_name        VARCHAR(160) NOT NULL COMMENT '对外展示名称',
  contact_ciphertext VARBINARY(1024) NULL COMMENT '加密联系方式',
  reviewed_by_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '审核管理员；与 reviewed_at 成对出现',
  reviewed_at        DATETIME(3) NULL COMMENT '审核时间；与 reviewed_by_admin_id 成对出现',
  review_note        VARCHAR(500) NULL COMMENT '审核意见/驳回原因',
  CONSTRAINT pk_organization PRIMARY KEY (id),
  CONSTRAINT uq_organization_login UNIQUE (login_name),
  CONSTRAINT fk_organization_reviewer FOREIGN KEY (reviewed_by_admin_id) REFERENCES admin_account(id),
  CONSTRAINT ck_organization_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'DISABLED')),
  CONSTRAINT ck_organization_review_pair CHECK
    ((reviewed_by_admin_id IS NULL AND reviewed_at IS NULL)
     OR (reviewed_by_admin_id IS NOT NULL AND reviewed_at IS NOT NULL))
) ENGINE=InnoDB COMMENT='公益机构；审核三件套（人/时/意见）成对落库';
CREATE INDEX ix_organization_status ON organization (status, created_at);

CREATE TABLE account_recovery (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 启用 / DISABLED 停用',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  principal_type  VARCHAR(16) NOT NULL COMMENT '主体类型：USER / ORGANIZATION',
  principal_id    CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'user_account.id 或 organization.id（多态，无外键）',
  recovery_type   VARCHAR(8) NOT NULL COMMENT '找回方式：PHONE 手机 / EMAIL 邮箱',
  prefix          VARCHAR(16) NOT NULL COMMENT '明文不加密段：手机号后四位 / 邮箱 local 前 1/4（≤4 字符）',
  cipher_hash     VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '剩余段 HMAC-SHA256(key=prefix) hex；单向不可逆',
  CONSTRAINT pk_account_recovery PRIMARY KEY (id),
  CONSTRAINT uq_account_recovery_principal UNIQUE (principal_type, principal_id, recovery_type),
  CONSTRAINT ck_account_recovery_status CHECK (status IN ('ACTIVE', 'DISABLED')),
  CONSTRAINT ck_account_recovery_type CHECK (recovery_type IN ('PHONE', 'EMAIL')),
  CONSTRAINT ck_account_recovery_prefix CHECK (CHAR_LENGTH(prefix) BETWEEN 1 AND 4)
) ENGINE=InnoDB COMMENT='找回联系方式（半加密两段存储）';

CREATE TABLE account_recovery_reminder (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 启用 / DISABLED 停用',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  principal_type  VARCHAR(16) NOT NULL COMMENT '主体类型：USER / ORGANIZATION',
  principal_id    CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'user_account.id 或 organization.id',
  remind_never    BOOLEAN NOT NULL DEFAULT FALSE COMMENT '不再提醒',
  last_reminded_at DATETIME(3) NULL COMMENT '上次点击「下次再说」的时间',
  CONSTRAINT pk_account_recovery_reminder PRIMARY KEY (id),
  CONSTRAINT uq_account_recovery_reminder_principal UNIQUE (principal_type, principal_id),
  CONSTRAINT ck_account_recovery_reminder_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB COMMENT='找回联系方式提醒状态（下次再说 / 不再提醒）';


-- ============================================================================
-- §4 实时会话与语音留言
-- ============================================================================

CREATE TABLE realtime_session (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'REQUESTED' COMMENT 'REQUESTED 呼叫中 / ACTIVE 通话中 / REJECTED 被拒 / ENDED 已结束',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  initiator_uid   CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '发起方（监护人或受护人）',
  target_uid      CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '接收方',
  session_type    VARCHAR(16) NOT NULL DEFAULT 'BASIC' COMMENT '会话类型：BASIC/VOICE/VIDEO/CAMERA/EMERGENCY（LOCATION 为 FULL 预留）',
  accepted_at     DATETIME(3) NULL COMMENT '接通时间；进入 ACTIVE 时必填（REQUESTED 被直接结束时可不填）',
  ended_at        DATETIME(3) NULL COMMENT '结束时间；进入 ENDED 时必填',
  ended_by_uid    CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '结束操作人；系统回收时为 NULL',
  end_reason      VARCHAR(500) NULL COMMENT '结束原因',
  CONSTRAINT pk_realtime_session PRIMARY KEY (id),
  CONSTRAINT fk_realtime_initiator FOREIGN KEY (initiator_uid) REFERENCES user_account(id),
  CONSTRAINT fk_realtime_target FOREIGN KEY (target_uid) REFERENCES user_account(id),
  CONSTRAINT fk_realtime_ender FOREIGN KEY (ended_by_uid) REFERENCES user_account(id),
  CONSTRAINT ck_realtime_distinct CHECK (initiator_uid <> target_uid),
  CONSTRAINT ck_realtime_status CHECK (status IN ('REQUESTED', 'ACTIVE', 'REJECTED', 'ENDED')),
  CONSTRAINT ck_realtime_type CHECK (session_type IN ('BASIC', 'VOICE', 'VIDEO', 'CAMERA', 'EMERGENCY')),
  CONSTRAINT ck_realtime_accepted CHECK (status <> 'ACTIVE' OR accepted_at IS NOT NULL),
  CONSTRAINT ck_realtime_ended CHECK (status <> 'ENDED' OR ended_at IS NOT NULL)
) ENGINE=InnoDB COMMENT='监护-受护实时会话；语音/视频媒体走 WebRTC，媒体不入库';
CREATE INDEX ix_realtime_initiator ON realtime_session (initiator_uid, status, created_at);
CREATE INDEX ix_realtime_target ON realtime_session (target_uid, status, created_at);

CREATE TABLE voice_message (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  sender_uid      CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '发送方',
  receiver_uid    CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '接收方',
  session_id      CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '来源会话（可选；会话结束后仍保留该值）',
  status          VARCHAR(16) NOT NULL DEFAULT 'SENT' COMMENT 'SENT 待收听 / LISTENED 已收听 / DELETED 已删除',
  object_key      VARCHAR(255) NOT NULL COMMENT '对象存储键；客户端不可见，只能经鉴权端点取回',
  duration_ms     INT NOT NULL COMMENT '时长（毫秒）；上限由 app.voice-message.max-duration-ms 控制',
  size_bytes      BIGINT UNSIGNED NOT NULL COMMENT '音频字节数；上限由 app.voice-message.max-size-bytes 控制',
  content_type    VARCHAR(64) NOT NULL DEFAULT 'audio/mp4',
  listened_at     DATETIME(3) NULL COMMENT '收听时间；进入 LISTENED 时必填',
  deleted_at      DATETIME(3) NULL,
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  CONSTRAINT pk_voice_message PRIMARY KEY (id),
  CONSTRAINT fk_voice_message_sender FOREIGN KEY (sender_uid) REFERENCES user_account(id),
  CONSTRAINT fk_voice_message_receiver FOREIGN KEY (receiver_uid) REFERENCES user_account(id),
  CONSTRAINT fk_voice_message_session FOREIGN KEY (session_id) REFERENCES realtime_session(id),
  CONSTRAINT ck_voice_message_status CHECK (status IN ('SENT', 'LISTENED', 'DELETED')),
  CONSTRAINT ck_voice_message_distinct CHECK (sender_uid <> receiver_uid),
  CONSTRAINT ck_voice_message_duration CHECK (duration_ms > 0),
  CONSTRAINT ck_voice_message_size CHECK (size_bytes > 0),
  CONSTRAINT ck_voice_message_listened CHECK (status <> 'LISTENED' OR listened_at IS NOT NULL)
) ENGINE=InnoDB COMMENT='语音留言元数据；音频本体在对象存储，媒体不入库';
CREATE INDEX ix_voice_message_receiver ON voice_message (receiver_uid, status, created_at);
CREATE INDEX ix_voice_message_sender ON voice_message (sender_uid, created_at);


-- ============================================================================
-- §5 扫码：绑定码与登录票据
-- ============================================================================

CREATE TABLE protectee_bind_code (
  code             VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '绑定码明文（主键）；取自去易混字符表',
  protectee_uid    CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '出示绑定码的受护人',
  status           VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 待扫 / REVOKED 被新码替换 / EXPIRED 超时',
  created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  expires_at       DATETIME(3) NOT NULL COMMENT '过期时间；TTL 由 app.qr.bind-code-ttl-seconds 控制',
  revoked_at       DATETIME(3) NULL COMMENT '作废时间；进入 REVOKED 时必填',
  version          BIGINT UNSIGNED NOT NULL DEFAULT 0,
  active_code_key  CHAR(26) CHARACTER SET ascii COLLATE ascii_bin
    GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN protectee_uid ELSE NULL END) STORED,
  CONSTRAINT pk_protectee_bind_code PRIMARY KEY (code),
  CONSTRAINT uq_protectee_bind_code_active UNIQUE (active_code_key),
  CONSTRAINT fk_protectee_bind_code_user FOREIGN KEY (protectee_uid) REFERENCES user_account(id),
  CONSTRAINT ck_protectee_bind_code_status CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
  CONSTRAINT ck_protectee_bind_code_revoked CHECK (status <> 'REVOKED' OR revoked_at IS NOT NULL)
) ENGINE=InnoDB COMMENT='受护人出示的扫码绑定码；每位受护人同时最多一张 ACTIVE';
CREATE INDEX ix_protectee_bind_code_user_status ON protectee_bind_code (protectee_uid, status, expires_at);

CREATE TABLE qr_login_ticket (
  ticket           VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '登录票据明文（主键，一次性）',
  status           VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待扫 / SCANNED 已扫待选 / CONFIRMED 已授权 / CONSUMED 已换令牌 / EXPIRED 超时',
  secret_hash      CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '出示设备 clientSecret 的 SHA-256；明文只留在设备上',
  guardian_uid     CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '扫描该票据的监护人',
  protectee_uid    CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '监护人选定并授权的受护人',
  exchange_code    VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'CONFIRMED 后下发给轮询方的换令牌凭据；与 clientSecret 同时校验',
  created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  expires_at       DATETIME(3) NOT NULL COMMENT '仅约束 PENDING/SCANNED；CONFIRMED 后不再受 TTL 影响',
  scanned_at       DATETIME(3) NULL COMMENT '扫描时间',
  confirmed_at     DATETIME(3) NULL COMMENT '确认授权时间',
  consumed_at      DATETIME(3) NULL COMMENT '换取令牌时间',
  version          BIGINT UNSIGNED NOT NULL DEFAULT 0,
  CONSTRAINT pk_qr_login_ticket PRIMARY KEY (ticket),
  CONSTRAINT fk_qr_login_ticket_guardian FOREIGN KEY (guardian_uid) REFERENCES user_account(id),
  CONSTRAINT fk_qr_login_ticket_protectee FOREIGN KEY (protectee_uid) REFERENCES user_account(id),
  CONSTRAINT ck_qr_login_ticket_status CHECK (status IN ('PENDING', 'SCANNED', 'CONFIRMED', 'CONSUMED', 'EXPIRED')),
  CONSTRAINT ck_qr_login_ticket_scanned CHECK (status NOT IN ('SCANNED', 'CONFIRMED', 'CONSUMED')
    OR (scanned_at IS NOT NULL AND guardian_uid IS NOT NULL)),
  CONSTRAINT ck_qr_login_ticket_confirmed CHECK (status NOT IN ('CONFIRMED', 'CONSUMED')
    OR (confirmed_at IS NOT NULL AND protectee_uid IS NOT NULL AND exchange_code IS NOT NULL)),
  CONSTRAINT ck_qr_login_ticket_consumed CHECK (status <> 'CONSUMED' OR consumed_at IS NOT NULL)
) ENGINE=InnoDB COMMENT='扫码登录票据；监护人确认后由出示设备换取受护人令牌';
CREATE INDEX ix_qr_login_ticket_status ON qr_login_ticket (status, expires_at);


-- ============================================================================
-- §6 位置与报警
-- ============================================================================

CREATE TABLE location_authorization (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'GRANTED' COMMENT 'GRANTED 已授权 / DENIED 已拒绝 / REVOKED 已撤回',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  user_id         CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '授权用户',
  purpose         VARCHAR(40) NOT NULL COMMENT '授权用途：PROTECTEE_SHARING 位置共享 / VOLUNTEER_ALERT_MATCHING 志愿者匹配',
  granted_at      DATETIME(3) NULL COMMENT '授权时间；进入 GRANTED 时必填',
  revoked_at      DATETIME(3) NULL COMMENT '撤回时间；进入 REVOKED 时必填',
  source          VARCHAR(32) NOT NULL DEFAULT 'CLIENT_SYSTEM_PERMISSION' COMMENT '授权来源',
  CONSTRAINT pk_location_authorization PRIMARY KEY (id),
  CONSTRAINT uq_location_authorization_user_purpose UNIQUE (user_id, purpose),
  CONSTRAINT fk_location_authorization_user FOREIGN KEY (user_id) REFERENCES user_account(id),
  CONSTRAINT ck_location_authorization_status CHECK (status IN ('GRANTED', 'DENIED', 'REVOKED')),
  CONSTRAINT ck_location_authorization_purpose CHECK (purpose IN ('PROTECTEE_SHARING', 'VOLUNTEER_ALERT_MATCHING')),
  CONSTRAINT ck_location_authorization_granted CHECK (status <> 'GRANTED' OR granted_at IS NOT NULL),
  CONSTRAINT ck_location_authorization_revoked CHECK (status <> 'REVOKED' OR revoked_at IS NOT NULL)
) ENGINE=InnoDB COMMENT='位置用途授权留痕；绑定本身仍是监护位置读取的必要条件';

CREATE TABLE latest_location (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE' COMMENT 'AVAILABLE 可用 / UNAVAILABLE 不可用 / REVOKED 已撤回',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  user_id         CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '位置所属用户；一人一行',
  latitude        DECIMAL(9,6) NOT NULL COMMENT '纬度 [-90, 90]',
  longitude       DECIMAL(9,6) NOT NULL COMMENT '经度 [-180, 180]',
  accuracy_m      DECIMAL(8,2) NULL COMMENT '定位精度（米）',
  captured_at     DATETIME(3) NOT NULL COMMENT '采集时间',
  received_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT pk_latest_location PRIMARY KEY (id),
  CONSTRAINT uq_latest_location_user UNIQUE (user_id),
  CONSTRAINT fk_latest_location_user FOREIGN KEY (user_id) REFERENCES user_account(id),
  CONSTRAINT ck_latest_location_status CHECK (status IN ('AVAILABLE', 'UNAVAILABLE', 'REVOKED')),
  CONSTRAINT ck_latest_location_latitude CHECK (latitude BETWEEN -90 AND 90),
  CONSTRAINT ck_latest_location_longitude CHECK (longitude BETWEEN -180 AND 180),
  CONSTRAINT ck_latest_location_accuracy CHECK (accuracy_m IS NULL OR accuracy_m >= 0)
) ENGINE=InnoDB COMMENT='仅存最新位置；不存轨迹';
CREATE INDEX ix_latest_location_captured ON latest_location (captured_at);

CREATE TABLE alert (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED 已创建 / DISPATCHING 派发中 / DISPATCHED 已派发 / ACKNOWLEDGED 已确认 / RESOLVED 已解除 / NO_RECIPIENT 无接收人 / DISPATCH_FAILED 派发失败',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  protectee_uid   CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '触发报警的受护人',
  idempotency_key VARCHAR(100) NOT NULL COMMENT '幂等键；同受护人重复触发返回同一条',
  alert_type      VARCHAR(24) NOT NULL COMMENT 'MANUAL 手动 / FALL_DETECTED 跌倒 / VOICE_HELP 语音求助 / COLLISION 碰撞预警',
  route_type      VARCHAR(24) NULL COMMENT '路由决策事实：NULL = 尚未路由',
  route_note      VARCHAR(200) NULL COMMENT '路由说明',
  latitude        DECIMAL(9,6) NULL COMMENT '报警位置快照纬度',
  longitude       DECIMAL(9,6) NULL COMMENT '报警位置快照经度',
  location_captured_at DATETIME(3) NULL COMMENT '位置快照采集时间',
  location_address VARCHAR(255) NULL COMMENT '逆地理编码地名（报警创建时解析一次并定格）；失败或未配 Key 为 NULL',
  location_address_pending TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1 = 地名解析超时未完成，接收方用本端解析兜底',
  resolved_at     DATETIME(3) NULL COMMENT '解除时间；进入 RESOLVED 时必填',
  auto_session_due_at DATETIME(3) NULL COMMENT '计划自动发起会话的时刻；NULL = 未安排',
  auto_session_started_at DATETIME(3) NULL COMMENT '实际开始依次发起的时刻',
  auto_session_state VARCHAR(16) NOT NULL DEFAULT 'NONE' COMMENT 'NONE 未安排 / SCHEDULED 已排期 / RUNNING 已发起 / SKIPPED 被用户关闭 / DONE 已完成',
  CONSTRAINT pk_alert PRIMARY KEY (id),
  CONSTRAINT uq_alert_idempotency UNIQUE (protectee_uid, idempotency_key),
  CONSTRAINT ck_alert_status CHECK (status IN
    ('CREATED', 'DISPATCHING', 'DISPATCHED', 'ACKNOWLEDGED', 'RESOLVED', 'NO_RECIPIENT', 'DISPATCH_FAILED')),
  CONSTRAINT ck_alert_type CHECK (alert_type IN ('MANUAL', 'FALL_DETECTED', 'VOICE_HELP', 'COLLISION')),
  CONSTRAINT ck_alert_route_type CHECK (route_type IS NULL OR route_type IN ('ALL_GUARDIANS', 'NEARBY_VOLUNTEERS', 'NONE')),
  CONSTRAINT ck_alert_route_consistency CHECK (status IN ('CREATED', 'DISPATCHING') OR route_type IS NOT NULL),
  CONSTRAINT ck_alert_location CHECK ((latitude IS NULL AND longitude IS NULL)
    OR (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180)),
  CONSTRAINT ck_alert_resolved CHECK (status <> 'RESOLVED' OR resolved_at IS NOT NULL),
  CONSTRAINT ck_alert_auto_session CHECK (auto_session_state IN ('NONE', 'SCHEDULED', 'RUNNING', 'SKIPPED', 'DONE'))
) ENGINE=InnoDB COMMENT='报警先持久化；位置快照可为空；自动会话与报警各自推进';
CREATE INDEX ix_alert_protectee_created ON alert (protectee_uid, created_at);
CREATE INDEX ix_alert_status_created ON alert (status, created_at);
CREATE INDEX ix_alert_auto_session_due ON alert (auto_session_state, auto_session_due_at);

CREATE TABLE alert_delivery (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待发送 / SENT 已发送 / DELIVERED 已送达 / ACKNOWLEDGED 已确认 / FAILED 失败',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  alert_id        CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属报警',
  receiver_principal_type VARCHAR(16) NOT NULL COMMENT '接收主体类型；当前仅 USER',
  receiver_principal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '接收人账号（USER 时 = user_account.id）',
  route_type      VARCHAR(24) NOT NULL COMMENT '本条投递的路由：ALL_GUARDIANS 监护人 / NEARBY_VOLUNTEERS 附近志愿者',
  acknowledged_at DATETIME(3) NULL COMMENT '确认时间；进入 ACKNOWLEDGED 时必填',
  failure_reason  VARCHAR(500) NULL COMMENT '失败原因；进入 FAILED 时必填',
  retry_count     INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '重试次数',
  dispatch_order  INT UNSIGNED NULL COMMENT '依次发起时的序号，从 1 开始',
  session_id      CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '为该投递创建（或复用）的实时会话',
  CONSTRAINT pk_alert_delivery PRIMARY KEY (id),
  CONSTRAINT uq_alert_delivery_receiver UNIQUE (alert_id, receiver_principal_type, receiver_principal_id),
  CONSTRAINT fk_alert_delivery_alert FOREIGN KEY (alert_id) REFERENCES alert(id),
  CONSTRAINT fk_alert_delivery_session FOREIGN KEY (session_id) REFERENCES realtime_session(id),
  CONSTRAINT ck_alert_delivery_status CHECK (status IN ('PENDING', 'SENT', 'DELIVERED', 'ACKNOWLEDGED', 'FAILED')),
  CONSTRAINT ck_alert_delivery_receiver CHECK (receiver_principal_type = 'USER'),
  CONSTRAINT ck_alert_delivery_route CHECK (route_type IN ('ALL_GUARDIANS', 'NEARBY_VOLUNTEERS')),
  CONSTRAINT ck_alert_delivery_order CHECK (dispatch_order IS NULL OR dispatch_order >= 1),
  CONSTRAINT ck_alert_delivery_ack CHECK (status <> 'ACKNOWLEDGED' OR acknowledged_at IS NOT NULL),
  CONSTRAINT ck_alert_delivery_failed CHECK (status <> 'FAILED' OR failure_reason IS NOT NULL)
) ENGINE=InnoDB COMMENT='一条报警对一个接收主体；接收人由 Java 路由事务固化';
CREATE INDEX ix_alert_delivery_receiver_status ON alert_delivery (receiver_principal_type, receiver_principal_id, status, created_at);
CREATE INDEX ix_alert_delivery_order ON alert_delivery (alert_id, dispatch_order);

CREATE TABLE protectee_alert_setting (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 启用 / DISABLED 停用',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  protectee_uid   CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '受护人账号；一人一行',
  auto_session_enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '报警后是否自动向监护人依次发起会话；默认开启',
  auto_session_delay_seconds INT UNSIGNED NOT NULL DEFAULT 5 COMMENT '报警到发起会话的延迟秒数；本版固定 5，留列以便后续可调',
  CONSTRAINT pk_protectee_alert_setting PRIMARY KEY (id),
  CONSTRAINT uq_protectee_alert_setting_user UNIQUE (protectee_uid),
  CONSTRAINT fk_protectee_alert_setting_user FOREIGN KEY (protectee_uid) REFERENCES user_account(id),
  CONSTRAINT ck_protectee_alert_setting_status CHECK (status IN ('ACTIVE', 'DISABLED')),
  CONSTRAINT ck_protectee_alert_setting_delay CHECK (auto_session_delay_seconds BETWEEN 0 AND 300)
) ENGINE=InnoDB COMMENT='受护人的报警偏好；缺行按默认（开启、5 秒）处理';


-- ============================================================================
-- §7 通知
-- ============================================================================

CREATE TABLE notification (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'UNREAD' COMMENT 'UNREAD 未读 / READ 已读',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  receiver_principal_type VARCHAR(16) NOT NULL COMMENT '接收主体类型：USER / ORGANIZATION / ADMIN',
  receiver_principal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '接收主体 ID（按类型指向对应表；多态，无外键）',
  notification_type VARCHAR(32) NOT NULL COMMENT '通知类型，见 ck_notification_type',
  title           VARCHAR(160) NOT NULL COMMENT '标题',
  body            VARCHAR(1000) NOT NULL COMMENT '正文',
  payload_json    JSON NULL COMMENT '附加数据对象',
  read_at         DATETIME(3) NULL COMMENT '已读时间；进入 READ 时必填',
  source_type     VARCHAR(32) NULL COMMENT '来源业务类型（多态引用的一半）',
  source_id       CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '来源业务 ID（多态引用的一半）',
  CONSTRAINT pk_notification PRIMARY KEY (id),
  CONSTRAINT ck_notification_status CHECK (status IN ('UNREAD', 'READ')),
  CONSTRAINT ck_notification_principal CHECK (receiver_principal_type IN ('USER', 'ORGANIZATION', 'ADMIN')),
  CONSTRAINT ck_notification_type CHECK (notification_type IN
    ('BINDING_REQUEST', 'BINDING_RESULT', 'ALERT', 'APPLICATION_RESULT',
     'ENROLLMENT_RESULT', 'ACTIVITY_CHANGE', 'ORG_REVIEW', 'SYSTEM', 'VOICE_MESSAGE')),
  CONSTRAINT ck_notification_payload CHECK (payload_json IS NULL OR JSON_TYPE(payload_json) = 'OBJECT'),
  CONSTRAINT ck_notification_read CHECK (status <> 'READ' OR read_at IS NOT NULL),
  CONSTRAINT ck_notification_unread CHECK (status = 'READ' OR read_at IS NULL)
) ENGINE=InnoDB COMMENT='多接收主体通知；不作为聊天消息表';
CREATE INDEX ix_notification_receiver_unread ON notification (receiver_principal_type, receiver_principal_id, status, created_at);


-- ============================================================================
-- §8 志愿者业务
-- ============================================================================

CREATE TABLE volunteer_application (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待审 / APPROVED 已通过 / REJECTED 已拒绝 / CANCELLED 已撤回',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  applicant_uid   CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '申请人（监护人）账号',
  reason          VARCHAR(1000) NULL COMMENT '申请理由',
  decided_by_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '审核管理员；进入 APPROVED/REJECTED 时必填',
  decided_at      DATETIME(3) NULL COMMENT '审核时间；进入 APPROVED/REJECTED 时必填',
  decision_note   VARCHAR(1000) NULL COMMENT '审核意见/拒绝原因',
  effective_key   VARCHAR(40) GENERATED ALWAYS AS
    (CASE WHEN deleted_at IS NULL AND status = 'PENDING' THEN CONCAT('V', applicant_uid) ELSE NULL END) STORED,
  CONSTRAINT pk_volunteer_application PRIMARY KEY (id),
  CONSTRAINT uq_volunteer_app_effective UNIQUE (effective_key),
  CONSTRAINT fk_vol_app_user FOREIGN KEY (applicant_uid) REFERENCES user_account(id),
  CONSTRAINT fk_vol_app_admin FOREIGN KEY (decided_by_admin_id) REFERENCES admin_account(id),
  CONSTRAINT ck_vol_app_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
  CONSTRAINT ck_vol_app_decided CHECK (status NOT IN ('APPROVED', 'REJECTED')
    OR (decided_at IS NOT NULL AND decided_by_admin_id IS NOT NULL))
) ENGINE=InnoDB COMMENT='监护人兼任志愿者申请（提交→管理员审核）';
CREATE INDEX ix_vol_app_user_status ON volunteer_application (applicant_uid, status, created_at);
CREATE INDEX ix_vol_app_admin_status ON volunteer_application (decided_by_admin_id, status, created_at);

CREATE TABLE volunteer_activity (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'RECRUITING' COMMENT 'RECRUITING 报名中 / IN_PROGRESS 进行中 / ENDED 已结束 / CANCELLED 已取消',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  tenant_id       CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'FULL-RESERVED',
  external_ref    VARCHAR(128) NULL COMMENT 'FULL-RESERVED',
  extension       JSON NULL,
  organization_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '主办机构',
  title           VARCHAR(160) NOT NULL COMMENT '活动标题',
  description     TEXT NOT NULL COMMENT '活动说明',
  location_text   VARCHAR(300) NOT NULL COMMENT '活动地点文字描述',
  location_lng    DECIMAL(10,6) NULL COMMENT '高德经度',
  location_lat    DECIMAL(10,6) NULL COMMENT '高德纬度',
  starts_at       DATETIME(3) NOT NULL COMMENT '开始时间',
  ends_at         DATETIME(3) NOT NULL COMMENT '结束时间；须晚于开始时间',
  registration_starts_at DATETIME(3) NOT NULL COMMENT '报名开始时间',
  registration_ends_at DATETIME(3) NOT NULL COMMENT '报名结束时间；不晚于活动开始',
  review_required BOOLEAN NOT NULL DEFAULT FALSE COMMENT '报名是否需机构审核；FALSE 报名即通过',
  max_volunteers  INT UNSIGNED NOT NULL COMMENT '人数上限；> 0',
  approved_count  INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已通过人数；≤ 上限',
  is_free         BOOLEAN NOT NULL DEFAULT TRUE COMMENT '恒为 TRUE；本库禁止金额、币种、订单或支付字段',
  CONSTRAINT pk_volunteer_activity PRIMARY KEY (id),
  CONSTRAINT fk_volunteer_activity_org FOREIGN KEY (organization_id) REFERENCES organization(id),
  CONSTRAINT ck_volunteer_activity_status CHECK (status IN ('RECRUITING', 'IN_PROGRESS', 'ENDED', 'CANCELLED', 'SUSPENDED')),
  CONSTRAINT ck_volunteer_activity_time CHECK (starts_at < ends_at),
  CONSTRAINT ck_volunteer_activity_reg CHECK (registration_starts_at <= registration_ends_at
    AND registration_ends_at <= starts_at),
  CONSTRAINT ck_volunteer_activity_quota CHECK (approved_count <= max_volunteers AND max_volunteers > 0),
  CONSTRAINT ck_volunteer_activity_free CHECK (is_free = TRUE),
  CONSTRAINT ck_volunteer_activity_extension CHECK (extension IS NULL OR JSON_TYPE(extension) = 'OBJECT'),
  CONSTRAINT ck_volunteer_activity_geo CHECK
    ((location_lng IS NULL AND location_lat IS NULL)
     OR (location_lng IS NOT NULL AND location_lat IS NOT NULL))
) ENGINE=InnoDB COMMENT='免费志愿活动；含报名窗口与高德经纬度';
CREATE INDEX ix_volunteer_activity_org_status ON volunteer_activity (organization_id, status, starts_at);
CREATE INDEX ix_volunteer_activity_public ON volunteer_activity (status, starts_at);

CREATE TABLE activity_enrollment (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待审 / APPROVED 已通过 / REJECTED 已拒绝 / CANCELLED 已撤回',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  activity_id     CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '报名的活动',
  volunteer_uid   CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '报名志愿者账号',
  decision_reason VARCHAR(1000) NULL COMMENT '审核意见/拒绝原因',
  decided_at      DATETIME(3) NULL COMMENT '审核时间；进入 APPROVED/REJECTED 时必填',
  effective_enrollment_key VARCHAR(53) GENERATED ALWAYS AS (
    CASE WHEN deleted_at IS NULL AND status IN ('PENDING', 'APPROVED', 'REJECTED')
    THEN CONCAT(activity_id, ':', volunteer_uid) ELSE NULL END) STORED,
  CONSTRAINT pk_activity_enrollment PRIMARY KEY (id),
  CONSTRAINT uq_activity_enrollment_effective UNIQUE (effective_enrollment_key),
  CONSTRAINT fk_activity_enrollment_activity FOREIGN KEY (activity_id) REFERENCES volunteer_activity(id),
  CONSTRAINT fk_activity_enrollment_volunteer FOREIGN KEY (volunteer_uid) REFERENCES user_account(id),
  CONSTRAINT ck_activity_enrollment_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
  CONSTRAINT ck_activity_enrollment_decided CHECK (status NOT IN ('APPROVED', 'REJECTED') OR decided_at IS NOT NULL)
) ENGINE=InnoDB COMMENT='活动报名；Java 事务校验志愿者角色与活动归属';
CREATE INDEX ix_activity_enrollment_activity_status ON activity_enrollment (activity_id, status, created_at);
CREATE INDEX ix_activity_enrollment_volunteer ON activity_enrollment (volunteer_uid, created_at);

-- 活动志愿者群聊；管理员为主办机构；活动结束自动封闭（V2）。
CREATE TABLE activity_group (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN 开放发言 / CLOSED 已封闭（活动结束）',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  activity_id     CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属活动；一对一',
  organization_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '群管理员=主办机构账户',
  closed_at       DATETIME(3) NULL COMMENT '封闭时间；CLOSED 必填',
  closed_reason   VARCHAR(200) NULL COMMENT '封闭原因（如：活动已结束）',
  suspended_until DATETIME(3) NULL COMMENT '暂时封禁到期时间；SUSPENDED 必填',
  CONSTRAINT pk_activity_group PRIMARY KEY (id),
  CONSTRAINT uq_activity_group_activity UNIQUE (activity_id),
  CONSTRAINT fk_activity_group_activity FOREIGN KEY (activity_id) REFERENCES volunteer_activity(id),
  CONSTRAINT fk_activity_group_org FOREIGN KEY (organization_id) REFERENCES organization(id),
  CONSTRAINT ck_activity_group_status CHECK (status IN ('OPEN', 'CLOSED', 'SUSPENDED')),
  CONSTRAINT ck_activity_group_closed CHECK (status <> 'CLOSED' OR closed_at IS NOT NULL),
  CONSTRAINT ck_activity_group_suspended CHECK (status <> 'SUSPENDED' OR suspended_until IS NOT NULL)
) ENGINE=InnoDB COMMENT='活动志愿者群聊；管理员为主办机构；活动结束自动封闭，处罚可暂时/永久封禁';
CREATE INDEX ix_activity_group_org ON activity_group (organization_id, status, created_at);

-- 群成员；仅志愿者落行，机构管理员不落行（V2）。
CREATE TABLE activity_group_member (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 在群 / REMOVED 已移出（撤回报名等）',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  group_id        CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属群',
  volunteer_uid   CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '志愿者账号',
  joined_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '入群时间',
  active_member_key VARCHAR(53) GENERATED ALWAYS AS (
    CASE WHEN deleted_at IS NULL AND status = 'ACTIVE'
    THEN CONCAT(group_id, ':', volunteer_uid) ELSE NULL END) STORED,
  CONSTRAINT pk_activity_group_member PRIMARY KEY (id),
  CONSTRAINT uq_activity_group_member_active UNIQUE (active_member_key),
  CONSTRAINT fk_group_member_group FOREIGN KEY (group_id) REFERENCES activity_group(id),
  CONSTRAINT fk_group_member_user FOREIGN KEY (volunteer_uid) REFERENCES user_account(id),
  CONSTRAINT ck_group_member_status CHECK (status IN ('ACTIVE', 'REMOVED'))
) ENGINE=InnoDB COMMENT='活动群成员；仅志愿者落行，机构管理员不落行';
CREATE INDEX ix_group_member_user ON activity_group_member (volunteer_uid, status, created_at);

-- 群聊消息；只允许文字；违禁词在发送时拦截（V2）。
CREATE TABLE activity_group_message (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL 正常 / WITHDRAWN 已撤回（不再下发）',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  group_id        CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属群',
  sender_uid      CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '发送者；志愿者=user_account.id，机构=organization.id（多态，由 sender_type 区分，服务端校验归属）',
  sender_type     VARCHAR(16) NOT NULL COMMENT 'VOLUNTEER 成员 / ORGANIZATION 群管理员',
  content         VARCHAR(2000) NOT NULL COMMENT '纯文字内容；本群不允许图片、语音、视频',
  withdrawn_at    DATETIME(3) NULL COMMENT '撤回时间；WITHDRAWN 必填',
  withdrawn_by    CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '撤回操作者（群管理员机构）',
  CONSTRAINT pk_group_message PRIMARY KEY (id),
  CONSTRAINT fk_group_message_group FOREIGN KEY (group_id) REFERENCES activity_group(id),
  CONSTRAINT ck_group_message_status CHECK (status IN ('NORMAL', 'WITHDRAWN')),
  CONSTRAINT ck_group_message_sender_type CHECK (sender_type IN ('VOLUNTEER', 'ORGANIZATION')),
  CONSTRAINT ck_group_message_withdrawn CHECK (status <> 'WITHDRAWN' OR withdrawn_at IS NOT NULL)
) ENGINE=InnoDB COMMENT='群聊消息；只允许文字；违禁词在发送时拦截';
CREATE INDEX ix_group_message_group_time ON activity_group_message (group_id, status, created_at);

-- 群聊消息举报；群管理员（机构）处理（V2）。
CREATE TABLE activity_group_report (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待处理 / RESOLVED 已受理（撤回消息）/ DISMISSED 已驳回',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  group_id        CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '消息所在群',
  message_id      CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '被举报消息',
  reporter_uid    CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '举报人（群成员志愿者）',
  reason          VARCHAR(500) NOT NULL COMMENT '举报理由',
  handled_at      DATETIME(3) NULL COMMENT '处理时间；RESOLVED/DISMISSED 必填',
  handled_by      CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '处理机构（群管理员）',
  decision_note   VARCHAR(500) NULL COMMENT '处理意见',
  pending_report_key VARCHAR(53) GENERATED ALWAYS AS (
    CASE WHEN deleted_at IS NULL AND status = 'PENDING'
    THEN CONCAT(message_id, ':', reporter_uid) ELSE NULL END) STORED,
  CONSTRAINT pk_group_report PRIMARY KEY (id),
  CONSTRAINT uq_group_report_pending UNIQUE (pending_report_key),
  CONSTRAINT fk_group_report_group FOREIGN KEY (group_id) REFERENCES activity_group(id),
  CONSTRAINT fk_group_report_message FOREIGN KEY (message_id) REFERENCES activity_group_message(id),
  CONSTRAINT fk_group_report_reporter FOREIGN KEY (reporter_uid) REFERENCES user_account(id),
  CONSTRAINT ck_group_report_status CHECK (status IN ('PENDING', 'RESOLVED', 'DISMISSED')),
  CONSTRAINT ck_group_report_handled CHECK (status NOT IN ('RESOLVED', 'DISMISSED') OR handled_at IS NOT NULL)
) ENGINE=InnoDB COMMENT='群聊消息举报；群管理员（机构）处理';
CREATE INDEX ix_group_report_group_status ON activity_group_report (group_id, status, created_at);

-- 平台级违禁词表；活动群聊发送时做包含匹配（V2）。
CREATE TABLE banned_word (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED 生效 / DISABLED 停用',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  word            VARCHAR(100) NOT NULL COMMENT '违禁词；发送内容包含即拦截',
  CONSTRAINT pk_banned_word PRIMARY KEY (id),
  CONSTRAINT uq_banned_word UNIQUE (word),
  CONSTRAINT ck_banned_word_status CHECK (status IN ('ENABLED', 'DISABLED'))
) ENGINE=InnoDB COMMENT='平台级违禁词表；活动群聊发送时做包含匹配';

-- 站内通知；导航栏铃铛的数据源，点击按 link 跳转（V3）。
CREATE TABLE user_notification (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'UNREAD' COMMENT 'UNREAD 未读 / READ 已读',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  recipient_uid   CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '接收者；志愿者=user_account.id，机构=organization.id（多态，由 recipient_type 区分，服务端校验归属）',
  recipient_type  VARCHAR(16) NOT NULL COMMENT 'USER 志愿者 / ORGANIZATION 机构',
  type            VARCHAR(40) NOT NULL COMMENT 'ENROLLMENT_PENDING 新报名待审核 / ENROLLMENT_APPROVED 报名通过 / ENROLLMENT_REJECTED 报名未通过 / GROUP_MESSAGE_WITHDRAWN 群消息被撤回 / PENALTY_APPLIED 处罚通知 / APPEAL_DECIDED 申诉结果 / REPORT_DISMISSED 举报驳回（V4 扩容）',
  title           VARCHAR(100) NOT NULL COMMENT '通知标题（列表/hover 简述第一行）',
  content         VARCHAR(500) NOT NULL COMMENT '通知正文',
  link            VARCHAR(200) NOT NULL COMMENT '点击跳转的前端路由（按接收端拼接）',
  read_at         DATETIME(3) NULL COMMENT '已读时间；READ 必填',
  CONSTRAINT pk_user_notification PRIMARY KEY (id),
  CONSTRAINT ck_user_notification_recipient CHECK (recipient_type IN ('USER', 'ORGANIZATION')),
  CONSTRAINT ck_user_notification_type CHECK (type IN
    ('ENROLLMENT_PENDING', 'ENROLLMENT_APPROVED', 'ENROLLMENT_REJECTED', 'GROUP_MESSAGE_WITHDRAWN',
     'PENALTY_APPLIED', 'APPEAL_DECIDED', 'REPORT_DISMISSED')),
  CONSTRAINT ck_user_notification_status CHECK (status IN ('UNREAD', 'READ')),
  CONSTRAINT ck_user_notification_read CHECK (status <> 'READ' OR read_at IS NOT NULL)
) ENGINE=InnoDB COMMENT='站内通知；导航栏铃铛的数据源，点击按 link 跳转';
CREATE INDEX ix_user_notification_recipient ON user_notification (recipient_uid, status, created_at);

-- ============================================================================
-- V4 举报治理：统一举报 + 预设处罚 + 处罚执行 + 申诉 + 限制（镜像 V4__abuse_governance.sql）
-- ============================================================================

-- 预设处罚字典；处罚只能从中选择，新增需另一名管理员批准（V4）。
CREATE TABLE preset_penalty (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待审批 / ACTIVE 生效 / REJECTED 已驳回',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  category        VARCHAR(16) NOT NULL COMMENT '作用对象：ACTIVITY 活动 / ORGANIZATION 机构 / GROUP 群聊 / USER 个人',
  action          VARCHAR(32) NOT NULL COMMENT '主基础惩罚（该对象的基础惩罚集之一）：ACTIVITY→DELIST 永久下架 / TEMP_DELIST 暂时下架；ORGANIZATION→BAN_ORG 封禁 / BAN_PUBLISH 禁止发布；GROUP→PERM_CLOSE 永久解散 / BAN_GROUP 暂时封禁；USER→BAN_USER 封禁账户',
  duration_days   INT NULL COMMENT '主处罚天数；NULL=永久（纯永久动作不适用）',
  name            VARCHAR(100) NOT NULL COMMENT '处罚名（如：永久封群并下架活动）',
  description     VARCHAR(500) NULL COMMENT '适用场景说明',
  created_by      CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '申请管理员',
  approved_by     CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '批准/驳回管理员；不能与 created_by 相同（服务端校验）',
  handled_at      DATETIME(3) NULL COMMENT '审批时间；ACTIVE/REJECTED 必填',
  CONSTRAINT pk_preset_penalty PRIMARY KEY (id),
  CONSTRAINT ck_preset_penalty_category CHECK (category IN ('ACTIVITY', 'ORGANIZATION', 'GROUP', 'USER')),
  CONSTRAINT ck_preset_action CHECK (action IN ('DELIST', 'TEMP_DELIST', 'BAN_ORG', 'BAN_PUBLISH', 'PERM_CLOSE', 'BAN_GROUP', 'BAN_USER')),
  CONSTRAINT ck_preset_penalty_status CHECK (status IN ('PENDING', 'ACTIVE', 'REJECTED')),
  CONSTRAINT ck_preset_penalty_handled CHECK (status = 'PENDING' OR handled_at IS NOT NULL),
  CONSTRAINT ck_preset_penalty_approved CHECK (status = 'PENDING' OR approved_by IS NOT NULL),
  CONSTRAINT fk_preset_penalty_creator FOREIGN KEY (created_by) REFERENCES admin_account(id)
) ENGINE=InnoDB COMMENT='预设处罚字典（组合式：主基础惩罚+附带组件）；处罚只能从中选择，新增需另一名管理员批准';
CREATE INDEX ix_preset_penalty_category ON preset_penalty (category, status);

-- 预设处罚的附带组件（V8）：跨对象复合共存，时长留空跟随主处罚。
CREATE TABLE preset_penalty_part (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  preset_id       CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  target_kind     VARCHAR(16) NOT NULL COMMENT '附带对象：ACTIVITY / ORGANIZATION / GROUP',
  action          VARCHAR(32) NOT NULL COMMENT '该对象的基础惩罚：ACTIVITY→DELIST/TEMP_DELIST；ORGANIZATION→BAN_ORG/BAN_PUBLISH；GROUP→PERM_CLOSE/BAN_GROUP',
  duration_days   INT NULL COMMENT '附带时长；NULL=跟随主处罚时长',
  CONSTRAINT pk_preset_penalty_part PRIMARY KEY (id),
  CONSTRAINT fk_preset_part_preset FOREIGN KEY (preset_id) REFERENCES preset_penalty(id),
  CONSTRAINT ck_preset_part_kind CHECK (target_kind IN ('ACTIVITY', 'ORGANIZATION', 'GROUP')),
  CONSTRAINT ck_preset_part_action CHECK (action IN
    ('DELIST', 'TEMP_DELIST', 'BAN_ORG', 'BAN_PUBLISH', 'PERM_CLOSE', 'BAN_GROUP'))
) ENGINE=InnoDB COMMENT='预设处罚的附带组件；跨对象复合共存，时长留空跟随主处罚';
CREATE INDEX ix_preset_part_preset ON preset_penalty_part (preset_id, deleted_at);

-- 统一举报；由举报治理管理员（ABUSE_REVIEW 权限）处置（V4）。
CREATE TABLE abuse_report (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待处理 / RESOLVED 已处置（处罚）/ DISMISSED 已驳回',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  category        VARCHAR(16) NOT NULL COMMENT 'ACTIVITY 举报活动 / ORGANIZATION 举报机构 / GROUP 举报群聊 / USER 举报个人 / REPORT_ABUSE 反向举报（恶意举报）',
  reporter_uid    CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '举报人（user_account）；反向举报由系统以被举报人名义创建，被举报对象永远看不到举报人身份',
  reason          VARCHAR(500) NOT NULL COMMENT '举报理由',
  target_activity_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
  target_org_id   CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
  target_group_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
  target_user_id  CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
  source_report_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '反向举报指向的原举报（DISMISSED 后被举报人可匿名反诉）',
  handled_by      CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '处置管理员',
  handled_at      DATETIME(3) NULL,
  decision_note   VARCHAR(500) NULL,
  penalty_id      CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '处置产生的处罚',
  CONSTRAINT pk_abuse_report PRIMARY KEY (id),
  CONSTRAINT fk_abuse_report_reporter FOREIGN KEY (reporter_uid) REFERENCES user_account(id),
  CONSTRAINT fk_abuse_report_activity FOREIGN KEY (target_activity_id) REFERENCES volunteer_activity(id),
  CONSTRAINT fk_abuse_report_org FOREIGN KEY (target_org_id) REFERENCES organization(id),
  CONSTRAINT fk_abuse_report_group FOREIGN KEY (target_group_id) REFERENCES activity_group(id),
  CONSTRAINT fk_abuse_report_user FOREIGN KEY (target_user_id) REFERENCES user_account(id),
  CONSTRAINT fk_abuse_report_source FOREIGN KEY (source_report_id) REFERENCES abuse_report(id),
  CONSTRAINT ck_abuse_report_category CHECK (category IN
    ('ACTIVITY', 'ORGANIZATION', 'GROUP', 'USER', 'REPORT_ABUSE')),
  CONSTRAINT ck_abuse_report_status CHECK (status IN ('PENDING', 'RESOLVED', 'DISMISSED')),
  CONSTRAINT ck_abuse_report_target CHECK (
    (target_activity_id IS NOT NULL) + (target_org_id IS NOT NULL)
      + (target_group_id IS NOT NULL) + (target_user_id IS NOT NULL) = 1),
  CONSTRAINT ck_abuse_report_handled CHECK (status = 'PENDING' OR (handled_at IS NOT NULL AND handled_by IS NOT NULL))
) ENGINE=InnoDB COMMENT='统一举报；由举报治理管理员（ABUSE_REVIEW 权限）处置';
CREATE INDEX ix_abuse_report_status ON abuse_report (status, created_at);
CREATE INDEX ix_abuse_report_reporter ON abuse_report (reporter_uid, created_at);

-- 处罚执行记录；从预设实例化，申诉推翻后 LIFTED 并联动解除限制（V4）。
CREATE TABLE penalty (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 生效中 / LIFTED 申诉撤销 / EXPIRED 到期',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  preset_id       CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '来源预设',
  report_id       CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '来源举报',
  category        VARCHAR(16) NOT NULL COMMENT 'ACTIVITY / ORGANIZATION / USER',
  action          VARCHAR(32) NOT NULL COMMENT '同 preset_penalty.action',
  duration_days   INT NULL COMMENT '封禁天数；NULL=永久',
  target_activity_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
  target_org_id   CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
  target_group_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
  target_user_id  CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
  reason          VARCHAR(500) NOT NULL COMMENT '处罚依据（举报理由摘要 + 管理员意见）',
  applied_by      CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '执行管理员',
  applied_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  expires_at      DATETIME(3) NULL COMMENT '封禁到期时间；NULL=永久/不适用',
  account_disabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT '处罚是否连带禁用了账户（申诉推翻时据此恢复）',
  lifted_by       CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
  lifted_at       DATETIME(3) NULL,
  lift_note       VARCHAR(500) NULL,
  CONSTRAINT pk_penalty PRIMARY KEY (id),
  CONSTRAINT fk_penalty_preset FOREIGN KEY (preset_id) REFERENCES preset_penalty(id),
  CONSTRAINT fk_penalty_report FOREIGN KEY (report_id) REFERENCES abuse_report(id),
  CONSTRAINT fk_penalty_group FOREIGN KEY (target_group_id) REFERENCES activity_group(id),
  CONSTRAINT ck_penalty_category CHECK (category IN ('ACTIVITY', 'ORGANIZATION', 'GROUP', 'USER')),
  CONSTRAINT ck_penalty_status CHECK (status IN ('ACTIVE', 'LIFTED', 'EXPIRED')),
  CONSTRAINT ck_penalty_target CHECK (
    (target_activity_id IS NOT NULL) + (target_org_id IS NOT NULL)
      + (target_group_id IS NOT NULL) + (target_user_id IS NOT NULL) = 1),
  CONSTRAINT ck_penalty_lifted CHECK (status <> 'LIFTED' OR lifted_at IS NOT NULL)
) ENGINE=InnoDB COMMENT='处罚执行记录；从预设实例化，申诉推翻后 LIFTED 并联动解除限制';

-- 处罚执行组件（V8）：预设附带组件的实例化；暂时类组件到期由恢复扫描处理。
CREATE TABLE penalty_part (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  penalty_id      CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  target_kind     VARCHAR(16) NOT NULL COMMENT 'ACTIVITY / ORGANIZATION / GROUP',
  action          VARCHAR(32) NOT NULL COMMENT '基础惩罚，同 preset_penalty_part.action',
  target_activity_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
  target_org_id   CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
  target_group_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
  duration_days   INT NULL COMMENT '生效时长（解析后）；NULL=永久',
  expires_at      DATETIME(3) NULL COMMENT '暂时类组件到期时刻',
  CONSTRAINT pk_penalty_part PRIMARY KEY (id),
  CONSTRAINT fk_part_penalty FOREIGN KEY (penalty_id) REFERENCES penalty(id),
  CONSTRAINT ck_penalty_part_kind CHECK (target_kind IN ('ACTIVITY', 'ORGANIZATION', 'GROUP')),
  CONSTRAINT ck_penalty_part_action CHECK (action IN
    ('DELIST', 'TEMP_DELIST', 'BAN_ORG', 'BAN_PUBLISH', 'PERM_CLOSE', 'BAN_GROUP'))
) ENGINE=InnoDB COMMENT='处罚执行组件；预设组件实例化，暂时类组件到期由扫描恢复';
CREATE INDEX ix_penalty_part_penalty ON penalty_part (penalty_id, deleted_at);
CREATE INDEX ix_penalty_part_expiry ON penalty_part (action, expires_at);

-- 处罚申诉；OVERTURNED 联动解除限制与账户禁用（V4）。
CREATE TABLE appeal (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待处理 / UPHELD 维持处罚 / OVERTURNED 撤销处罚',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  penalty_id      CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  appellant_type  VARCHAR(16) NOT NULL COMMENT 'USER 个人 / ORGANIZATION 机构',
  appellant_uid   CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '申诉人（=被处罚方）',
  reason          VARCHAR(500) NOT NULL,
  handled_by      CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
  handled_at      DATETIME(3) NULL,
  decision_note   VARCHAR(500) NULL,
  CONSTRAINT pk_appeal PRIMARY KEY (id),
  CONSTRAINT fk_appeal_penalty FOREIGN KEY (penalty_id) REFERENCES penalty(id),
  CONSTRAINT ck_appeal_status CHECK (status IN ('PENDING', 'UPHELD', 'OVERTURNED')),
  CONSTRAINT ck_appeal_appellant CHECK (appellant_type IN ('USER', 'ORGANIZATION')),
  CONSTRAINT ck_appeal_handled CHECK (status = 'PENDING' OR (handled_at IS NOT NULL AND handled_by IS NOT NULL))
) ENGINE=InnoDB COMMENT='处罚申诉；OVERTURNED 联动解除限制与账户禁用';
CREATE INDEX ix_appeal_status ON appeal (status, created_at);

-- 生效中的限制；举报锁定/禁活动/封账户/封机构，阈值升级与处罚共用（V4）。
CREATE TABLE abuse_restriction (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 生效中 / LIFTED 已解除',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  kind            VARCHAR(24) NOT NULL COMMENT 'REPORT_LOCKED 举报功能锁定 / ACTIVITY_BANNED 禁止参加活动 / ACCOUNT_BANNED 账户封禁 / ORG_BANNED 机构封禁 / PUBLISH_BANNED 禁止发布活动',
  user_id         CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
  org_id          CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
  penalty_id      CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '来源处罚（阈值升级的自动限制为空）',
  reason          VARCHAR(500) NOT NULL,
  expires_at      DATETIME(3) NULL COMMENT '到期时间；NULL=永久（申诉/解除前置为 LIFTED）',
  created_by      CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '创建者（阈值自动升级为 NULL）',
  CONSTRAINT pk_abuse_restriction PRIMARY KEY (id),
  CONSTRAINT fk_restriction_user FOREIGN KEY (user_id) REFERENCES user_account(id),
  CONSTRAINT fk_restriction_org FOREIGN KEY (org_id) REFERENCES organization(id),
  CONSTRAINT fk_restriction_penalty FOREIGN KEY (penalty_id) REFERENCES penalty(id),
  CONSTRAINT ck_restriction_kind CHECK (kind IN
    ('REPORT_LOCKED', 'ACTIVITY_BANNED', 'ACCOUNT_BANNED', 'ORG_BANNED', 'PUBLISH_BANNED')),
  CONSTRAINT ck_restriction_status CHECK (status IN ('ACTIVE', 'LIFTED')),
  CONSTRAINT ck_restriction_target CHECK ((user_id IS NOT NULL) + (org_id IS NOT NULL) = 1)
) ENGINE=InnoDB COMMENT='生效中的限制；举报锁定/禁活动/封账户/封机构，阈值升级与处罚共用';
CREATE INDEX ix_restriction_user ON abuse_restriction (user_id, kind, status);
CREATE INDEX ix_restriction_org ON abuse_restriction (org_id, kind, status);

-- ============================================================================
-- V5 权限申请（镜像 V5__admin_permission_request.sql）
-- ============================================================================

-- 权限申请；任意管理员可发起（只填权限与理由），ADMIN_GOVERN 审批后给本人开通（V5）。
CREATE TABLE admin_permission_request (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待审批 / APPROVED 已批准（已开通）/ REJECTED 已驳回',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  permission_code VARCHAR(64) NOT NULL COMMENT '申请开通的权限代码；必须存在于 admin_permission 字典',
  reason          VARCHAR(500) NOT NULL COMMENT '申请理由',
  requested_by    CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '申请人（任意管理员，无需任何权限）；批准后受益人就是本人',
  handled_by      CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '审批人（须 ADMIN_GOVERN）；不能与 requested_by 相同（服务端校验）',
  handled_at      DATETIME(3) NULL,
  decision_note   VARCHAR(500) NULL,
  CONSTRAINT pk_admin_permission_request PRIMARY KEY (id),
  CONSTRAINT fk_permreq_requester FOREIGN KEY (requested_by) REFERENCES admin_account(id),
  CONSTRAINT fk_permreq_handler FOREIGN KEY (handled_by) REFERENCES admin_account(id),
  CONSTRAINT ck_permreq_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
  CONSTRAINT ck_permreq_handled CHECK (status = 'PENDING' OR (handled_at IS NOT NULL AND handled_by IS NOT NULL))
) ENGINE=InnoDB COMMENT='权限申请；任意管理员可发起（只填权限与理由），ADMIN_GOVERN 审批后给本人开通';
CREATE INDEX ix_permreq_status ON admin_permission_request (status, created_at);
CREATE INDEX ix_permreq_requester ON admin_permission_request (requested_by, created_at);

-- ============================================================================
-- V6 处罚体系扩展（以上相关表定义已同步为最终形态）：
--   1) volunteer_activity.status 新增 SUSPENDED（暂时下架）；
--   2) activity_group 新增 suspended_until 列与 SUSPENDED（暂时封禁）状态；
--   3) preset_penalty 类别新增 GROUP，动作扩容（TEMP_DELIST / BAN_GROUP /
--      PERM_CLOSE_GROUP_DELIST_ACTIVITY / PERM_CLOSE_GROUP_BAN_ORG；
--      DELIST 与 PERM_CLOSE_GROUP_DELIST_ACTIVITY 为纯永久动作，不允许封禁天数；
--      DELIST_AND_BAN_ORG 为 V4 存量保留，不再接受新建）；
--   4) penalty 新增 target_group_id（四类目标互斥恰一）。
--   到期恢复：每分钟扫描 ACTIVE 且到期的暂时处罚（TEMP_DELIST/BAN_GROUP/BAN_ORG），
--   活动按自然时间轴恢复 RECRUITING/IN_PROGRESS/ENDED、群聊恢复 OPEN（活动已结束则
--   直接 CLOSED），处罚行翻 EXPIRED（AB-01 的读时翻牌仅在恢复路径落地）。
-- ============================================================================
-- V7 处罚矩阵扩展二（相关列/约束已同步为最终形态）：
--   1) abuse_restriction.kind 新增 PUBLISH_BANNED（禁止发布活动；机构封禁仍含发布禁令）；
--   2) preset_penalty.attached_duration_days：附带处罚时长独立设置，NULL=与主处罚时长相同；
--   3) preset_penalty.action 新增 BAN_PUBLISH（机构禁止发布活动，空=永久）与
--      DELIST_AND_BAN_PUBLISH（下架活动并禁止发布活动：主时长=下架时长，空=永久下架+解散群聊；
--      附带时长=禁止发布时长，空=与主时长相同）。
--   DELIST_AND_BAN_PUBLISH 的暂时下架部分到期由扫描恢复（与 TEMP_DELIST 同路径）；
--   禁止发布/机构封禁等限制到期由 AbuseGuardService 读时判定。
-- ============================================================================
-- V8 处罚组合化重构（相关表已同步为最终形态）：
--   1) 每种对象有自己的基础惩罚集：活动=暂时下架/永久下架，机构=封禁/禁止发布，
--      群聊=暂时封禁/永久解散，用户=封禁账户；
--   2) 预设 = 主基础惩罚（作用于举报对象）+ preset_penalty_part 附带组件
--      （机构/活动/群聊互相附带，取目标对象的基础惩罚集，可复合共存；
--      附带时长留空 = 跟随主处罚时长）；
--   3) penalty_part 记录执行明细；暂时类组件（暂时下架/暂时封群）到期由恢复扫描处理；
--   4) 旧组合动作（DELIST_AND_BAN_PUBLISH 等）由迁移映射为基础惩罚+组件，列 action
--      语义升级为「主基础惩罚」；attached_duration_days 列退役。
-- ============================================================================
