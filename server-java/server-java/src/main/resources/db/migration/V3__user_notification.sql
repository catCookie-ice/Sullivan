-- ============================================================================
-- V3 站内通知（导航栏铃铛）
-- ============================================================================
-- 各角色在群聊/报名链路上的事件通知：
--   ENROLLMENT_PENDING        志愿者提交需审核报名 → 通知机构（新的报名待审核）
--   ENROLLMENT_APPROVED       机构批准报名         → 通知志愿者（报名通过，可去群聊）
--   ENROLLMENT_REJECTED       机构拒绝报名         → 通知志愿者（含拒绝原因）
--   GROUP_MESSAGE_WITHDRAWN   机构撤回群消息       → 通知群内志愿者（机构撤回了一条消息）
-- recipient 为多态引用（志愿者=USER/user_account.id，机构=ORGANIZATION/organization.id），
-- 与群消息 sender 一样不建外键、由服务端校验归属。
-- ============================================================================

CREATE TABLE user_notification (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'UNREAD' COMMENT 'UNREAD 未读 / READ 已读',
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3) NULL,
  version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  recipient_uid   CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '接收者；志愿者=user_account.id，机构=organization.id（多态，由 recipient_type 区分，服务端校验归属）',
  recipient_type  VARCHAR(16) NOT NULL COMMENT 'USER 志愿者 / ORGANIZATION 机构',
  type            VARCHAR(40) NOT NULL COMMENT '通知类型（见文件头）',
  title           VARCHAR(100) NOT NULL COMMENT '通知标题（列表/hover 简述第一行）',
  content         VARCHAR(500) NOT NULL COMMENT '通知正文',
  link            VARCHAR(200) NOT NULL COMMENT '点击跳转的前端路由（按接收端拼接）',
  read_at         DATETIME(3) NULL COMMENT '已读时间；READ 必填',
  CONSTRAINT pk_user_notification PRIMARY KEY (id),
  CONSTRAINT ck_user_notification_recipient CHECK (recipient_type IN ('USER', 'ORGANIZATION')),
  CONSTRAINT ck_user_notification_type CHECK (type IN
    ('ENROLLMENT_PENDING', 'ENROLLMENT_APPROVED', 'ENROLLMENT_REJECTED', 'GROUP_MESSAGE_WITHDRAWN')),
  CONSTRAINT ck_user_notification_status CHECK (status IN ('UNREAD', 'READ')),
  CONSTRAINT ck_user_notification_read CHECK (status <> 'READ' OR read_at IS NOT NULL)
) ENGINE=InnoDB COMMENT='站内通知；导航栏铃铛的数据源，点击按 link 跳转';
CREATE INDEX ix_user_notification_recipient ON user_notification (recipient_uid, status, created_at);
