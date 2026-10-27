-- ============================================================================
-- V2 活动报名启用 + 活动群聊 + 违禁词 + 举报
-- ============================================================================
-- 1) volunteer_activity 增加 review_required：机构发布时可开启「需审核」，
--    开启后报名行先进 PENDING，机构批准才占名额（approved_count 只统计 APPROVED）。
-- 2) activity_group：每个活动至多一个群（uq_activity_group_activity），
--    管理员即主办机构账户（organization_id），不另设管理员行。
-- 3) activity_group_member：只有志愿者落成员行；机构的权限由 activity_group.organization_id 推导。
-- 4) activity_group_message：只允许文字（content VARCHAR），无媒体字段；
--    status WITHDRAWN 的消息不再下发。
-- 5) activity_group_report：成员举报消息，机构（群管理员）处理；
--    同一成员对同一消息同时只允许一条 PENDING（生成列唯一兜底）。
-- 6) banned_word：平台级违禁词，发送时拦截；种子为治理类基础词表，可后续扩充。
-- ============================================================================

ALTER TABLE volunteer_activity
  ADD COLUMN review_required BOOLEAN NOT NULL DEFAULT FALSE
  COMMENT '报名是否需机构审核；FALSE 报名即通过' AFTER registration_ends_at;

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
  CONSTRAINT pk_activity_group PRIMARY KEY (id),
  CONSTRAINT uq_activity_group_activity UNIQUE (activity_id),
  CONSTRAINT fk_activity_group_activity FOREIGN KEY (activity_id) REFERENCES volunteer_activity(id),
  CONSTRAINT fk_activity_group_org FOREIGN KEY (organization_id) REFERENCES organization(id),
  CONSTRAINT ck_activity_group_status CHECK (status IN ('OPEN', 'CLOSED')),
  CONSTRAINT ck_activity_group_closed CHECK (status <> 'CLOSED' OR closed_at IS NOT NULL)
) ENGINE=InnoDB COMMENT='活动志愿者群聊；管理员为主办机构；活动结束自动封闭';
CREATE INDEX ix_activity_group_org ON activity_group (organization_id, status, created_at);

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

-- 种子词表：从涉诈高风险词起步（公益群最常见的是借活动名义行骗）。
INSERT INTO banned_word (id, word) VALUES
  ('bw_seed_01', '转账'),
  ('bw_seed_02', '汇款'),
  ('bw_seed_03', '刷单'),
  ('bw_seed_04', '返利'),
  ('bw_seed_05', '博彩'),
  ('bw_seed_06', '办证'),
  ('bw_seed_07', '高利贷'),
  ('bw_seed_08', '兼职日结');
