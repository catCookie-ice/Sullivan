-- ============================================================================
-- V4 举报治理：统一举报 + 预设处罚 + 处罚执行 + 申诉 + 限制（阈值自动升级）
-- ============================================================================
-- 1) preset_penalty：预设处罚字典。处罚不能随意设置，只能从 ACTIVE 预设中选择；
--    管理员可申请新增（PENDING），由**另一名**管理员批准后生效（自批被拒）。
-- 2) abuse_report：统一举报（活动/机构/群聊/个人 + 恶意举报反向举报），
--    交给持有 ABUSE_REVIEW 权限的管理员处置；四类目标列互斥，恰好一列非空。
-- 3) penalty：处罚执行记录（从预设实例化）；申诉推翻后置 LIFTED。
-- 4) appeal：被处罚方申诉；UPHELD 维持 / OVERTURNED 撤销（联动解除限制）。
-- 5) abuse_restriction：生效中的限制（举报锁定/禁参加活动/账户封禁/机构封禁）。
--    恶意举报阈值升级（驳回占大多数→锁举报→禁活动→封账户）落在这里。
-- 建表顺序按外键依赖：preset → report → penalty → appeal → restriction。
-- ============================================================================

CREATE TABLE preset_penalty (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待审批 / ACTIVE 生效 / REJECTED 已驳回',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  category        VARCHAR(16) NOT NULL COMMENT '作用对象：ACTIVITY 活动 / ORGANIZATION 机构 / USER 个人',
  action          VARCHAR(32) NOT NULL COMMENT 'DELIST 下架活动 / DELIST_AND_BAN_ORG 下架并封禁机构 / BAN_ORG 封禁机构 / BAN_USER 封禁用户',
  duration_days   INT NULL COMMENT '封禁天数；NULL=永久（DELIST 类不适用）',
  name            VARCHAR(100) NOT NULL COMMENT '处罚名（如：下架并封禁机构3天）',
  description     VARCHAR(500) NULL COMMENT '适用场景说明',
  created_by      CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '申请管理员',
  approved_by     CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '批准/驳回管理员；不能与 created_by 相同（服务端校验）',
  handled_at      DATETIME(3) NULL COMMENT '审批时间；ACTIVE/REJECTED 必填',
  CONSTRAINT pk_preset_penalty PRIMARY KEY (id),
  CONSTRAINT ck_preset_penalty_category CHECK (category IN ('ACTIVITY', 'ORGANIZATION', 'USER')),
  CONSTRAINT ck_preset_penalty_action CHECK (action IN ('DELIST', 'DELIST_AND_BAN_ORG', 'BAN_ORG', 'BAN_USER')),
  CONSTRAINT ck_preset_penalty_status CHECK (status IN ('PENDING', 'ACTIVE', 'REJECTED')),
  CONSTRAINT ck_preset_penalty_handled CHECK (status = 'PENDING' OR handled_at IS NOT NULL),
  CONSTRAINT ck_preset_penalty_approved CHECK (status = 'PENDING' OR approved_by IS NOT NULL),
  CONSTRAINT fk_preset_penalty_creator FOREIGN KEY (created_by) REFERENCES admin_account(id)
) ENGINE=InnoDB COMMENT='预设处罚字典；处罚只能从中选择，新增需另一名管理员批准';
CREATE INDEX ix_preset_penalty_category ON preset_penalty (category, status);

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
  CONSTRAINT ck_penalty_category CHECK (category IN ('ACTIVITY', 'ORGANIZATION', 'USER')),
  CONSTRAINT ck_penalty_status CHECK (status IN ('ACTIVE', 'LIFTED', 'EXPIRED')),
  CONSTRAINT ck_penalty_target CHECK (
    (target_activity_id IS NOT NULL) + (target_org_id IS NOT NULL) + (target_user_id IS NOT NULL) = 1),
  CONSTRAINT ck_penalty_lifted CHECK (status <> 'LIFTED' OR lifted_at IS NOT NULL)
) ENGINE=InnoDB COMMENT='处罚执行记录；从预设实例化，申诉推翻后 LIFTED 并联动解除限制';

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

CREATE TABLE abuse_restriction (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 生效中 / LIFTED 已解除',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  kind            VARCHAR(24) NOT NULL COMMENT 'REPORT_LOCKED 举报功能锁定 / ACTIVITY_BANNED 禁止参加活动 / ACCOUNT_BANNED 账户封禁 / ORG_BANNED 机构封禁',
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
    ('REPORT_LOCKED', 'ACTIVITY_BANNED', 'ACCOUNT_BANNED', 'ORG_BANNED')),
  CONSTRAINT ck_restriction_status CHECK (status IN ('ACTIVE', 'LIFTED')),
  CONSTRAINT ck_restriction_target CHECK ((user_id IS NOT NULL) + (org_id IS NOT NULL) = 1)
) ENGINE=InnoDB COMMENT='生效中的限制；举报锁定/禁活动/封账户/封机构，阈值升级与处罚共用';
CREATE INDEX ix_restriction_user ON abuse_restriction (user_id, kind, status);
CREATE INDEX ix_restriction_org ON abuse_restriction (org_id, kind, status);

-- 站内通知类型扩容：处罚通知 / 申诉结果 / 举报驳回（MySQL 不能原地改 CHECK，先删后建）。
ALTER TABLE user_notification DROP CHECK ck_user_notification_type;
ALTER TABLE user_notification ADD CONSTRAINT ck_user_notification_type CHECK (type IN
  ('ENROLLMENT_PENDING', 'ENROLLMENT_APPROVED', 'ENROLLMENT_REJECTED', 'GROUP_MESSAGE_WITHDRAWN',
   'PENALTY_APPLIED', 'APPEAL_DECIDED', 'REPORT_DISMISSED'));
