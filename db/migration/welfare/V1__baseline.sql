-- SullivanMINI V1 Baseline — sullivan_welfare (含报警/通知/outbox)
-- docs/final/14 §1 修正版

CREATE TABLE organization (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  tenant_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
  external_ref VARCHAR(128) NULL,
  extension JSON NULL,
  legal_name VARCHAR(160) NOT NULL,
  public_name VARCHAR(160) NOT NULL,
  contact_ciphertext VARBINARY(1024) NULL,
  commercial_subject_ref VARCHAR(128) NULL COMMENT 'FULL-RESERVED',
  commercial_verification_status VARCHAR(32) NULL COMMENT 'FULL-RESERVED',
  CONSTRAINT pk_organization PRIMARY KEY (id),
  CONSTRAINT ck_organization_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'DISABLED')),
  CONSTRAINT ck_organization_commercial_unused CHECK (
    commercial_subject_ref IS NULL AND commercial_verification_status IS NULL)
) ENGINE=InnoDB COMMENT='公益机构';
CREATE INDEX ix_organization_status ON organization (status, created_at);

CREATE TABLE organization_review (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'FINAL',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  organization_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reviewer_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
  action VARCHAR(16) NOT NULL,
  from_status VARCHAR(16) NULL,
  to_status VARCHAR(16) NOT NULL,
  reason VARCHAR(1000) NULL,
  trace_id VARCHAR(64) NULL,
  CONSTRAINT pk_organization_review PRIMARY KEY (id),
  CONSTRAINT fk_organization_review_org FOREIGN KEY (organization_id) REFERENCES organization(id),
  CONSTRAINT ck_organization_review_action CHECK (action IN ('SUBMITTED','APPROVED','REJECTED','DISABLED','RESUBMITTED')),
  CONSTRAINT ck_organization_review_to_status CHECK (to_status IN ('PENDING','APPROVED','REJECTED','DISABLED'))
) ENGINE=InnoDB COMMENT='机构审核不可覆盖历史';
CREATE INDEX ix_organization_review_org_time ON organization_review (organization_id, created_at);

-- 修正: 去 DRAFT (14 §1.5)
CREATE TABLE welfare_service (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  organization_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  title VARCHAR(160) NOT NULL,
  description TEXT NOT NULL,
  is_free BOOLEAN NOT NULL DEFAULT TRUE,
  opened_at DATETIME(3) NULL,
  closed_at DATETIME(3) NULL,
  CONSTRAINT pk_welfare_service PRIMARY KEY (id),
  CONSTRAINT fk_welfare_service_org FOREIGN KEY (organization_id) REFERENCES organization(id),
  CONSTRAINT ck_welfare_service_status CHECK (status IN ('OPEN', 'PAUSED', 'CLOSED')),
  CONSTRAINT ck_welfare_service_free CHECK (is_free)
) ENGINE=InnoDB COMMENT='免费公益服务；禁止添加金额、币种、订单或支付字段';
CREATE INDEX ix_welfare_service_org_status ON welfare_service (organization_id, status, created_at);
CREATE INDEX ix_welfare_service_public ON welfare_service (status, created_at);

CREATE TABLE service_quota (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  welfare_service_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  capacity INT UNSIGNED NULL COMMENT 'NULL 不限名额',
  approved_count INT UNSIGNED NOT NULL DEFAULT 0,
  CONSTRAINT pk_service_quota PRIMARY KEY (id),
  CONSTRAINT uq_service_quota_service UNIQUE (welfare_service_id),
  CONSTRAINT fk_service_quota_service FOREIGN KEY (welfare_service_id) REFERENCES welfare_service(id),
  CONSTRAINT ck_service_quota_status CHECK (status IN ('ACTIVE', 'DISABLED')),
  CONSTRAINT ck_service_quota_capacity CHECK (capacity IS NULL OR approved_count <= capacity)
) ENGINE=InnoDB COMMENT='服务审批并发锁点';

CREATE TABLE service_application (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  welfare_service_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  applicant_uid CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  beneficiary_protectee_uid CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  submitted_by_uid CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  application_note VARCHAR(2000) NULL,
  decision_reason VARCHAR(1000) NULL,
  decided_at DATETIME(3) NULL,
  completed_at DATETIME(3) NULL,
  effective_application_key VARCHAR(53) GENERATED ALWAYS AS (
    CASE WHEN deleted_at IS NULL AND status IN ('PENDING','APPROVED')
    THEN CONCAT(welfare_service_id, ':', beneficiary_protectee_uid) ELSE NULL END) STORED,
  CONSTRAINT pk_service_application PRIMARY KEY (id),
  CONSTRAINT uq_service_application_effective UNIQUE (effective_application_key),
  CONSTRAINT fk_service_application_service FOREIGN KEY (welfare_service_id) REFERENCES welfare_service(id),
  CONSTRAINT ck_service_application_status CHECK (status IN ('PENDING','APPROVED','REJECTED','COMPLETED','CANCELLED'))
) ENGINE=InnoDB COMMENT='服务申请';
CREATE INDEX ix_service_application_service_status ON service_application (welfare_service_id, status, created_at);
CREATE INDEX ix_service_application_beneficiary ON service_application (beneficiary_protectee_uid, status, created_at);

-- 修正: 去 DRAFT, ONGOING→IN_PROGRESS (14 §1.4)
CREATE TABLE volunteer_activity (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'RECRUITING',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  organization_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  title VARCHAR(160) NOT NULL,
  description TEXT NOT NULL,
  location_text VARCHAR(300) NOT NULL,
  starts_at DATETIME(3) NOT NULL,
  ends_at DATETIME(3) NOT NULL,
  max_volunteers INT UNSIGNED NOT NULL,
  approved_count INT UNSIGNED NOT NULL DEFAULT 0,
  is_free BOOLEAN NOT NULL DEFAULT TRUE,
  CONSTRAINT pk_volunteer_activity PRIMARY KEY (id),
  CONSTRAINT fk_volunteer_activity_org FOREIGN KEY (organization_id) REFERENCES organization(id),
  CONSTRAINT ck_volunteer_activity_status CHECK (status IN ('RECRUITING','IN_PROGRESS','ENDED','CANCELLED')),
  CONSTRAINT ck_volunteer_activity_time CHECK (starts_at < ends_at),
  CONSTRAINT ck_volunteer_activity_quota CHECK (approved_count <= max_volunteers AND max_volunteers > 0),
  CONSTRAINT ck_volunteer_activity_free CHECK (is_free)
) ENGINE=InnoDB COMMENT='免费志愿活动';
CREATE INDEX ix_volunteer_activity_org_status ON volunteer_activity (organization_id, status, starts_at);
CREATE INDEX ix_volunteer_activity_public ON volunteer_activity (status, starts_at);

CREATE TABLE activity_enrollment (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  activity_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  volunteer_uid CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  decision_reason VARCHAR(1000) NULL,
  decided_at DATETIME(3) NULL,
  effective_enrollment_key VARCHAR(53) GENERATED ALWAYS AS (
    CASE WHEN deleted_at IS NULL AND status IN ('PENDING','APPROVED','REJECTED')
    THEN CONCAT(activity_id, ':', volunteer_uid) ELSE NULL END) STORED,
  CONSTRAINT pk_activity_enrollment PRIMARY KEY (id),
  CONSTRAINT uq_activity_enrollment_effective UNIQUE (effective_enrollment_key),
  CONSTRAINT fk_activity_enrollment_activity FOREIGN KEY (activity_id) REFERENCES volunteer_activity(id),
  CONSTRAINT ck_activity_enrollment_status CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELLED'))
) ENGINE=InnoDB COMMENT='活动报名';
CREATE INDEX ix_activity_enrollment_activity_status ON activity_enrollment (activity_id, status, created_at);
CREATE INDEX ix_activity_enrollment_volunteer ON activity_enrollment (volunteer_uid, created_at);

-- 修正: 7 状态 + route_type (14 §1.1, 1.2)
CREATE TABLE alert (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'CREATED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  protectee_uid CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  idempotency_key VARCHAR(100) NOT NULL,
  alert_type VARCHAR(24) NOT NULL,
  route_type VARCHAR(24) NULL COMMENT '路由决策事实',
  latitude DECIMAL(9,6) NULL,
  longitude DECIMAL(9,6) NULL,
  location_captured_at DATETIME(3) NULL,
  route_note VARCHAR(200) NULL,
  resolved_at DATETIME(3) NULL,
  CONSTRAINT pk_alert PRIMARY KEY (id),
  CONSTRAINT uq_alert_idempotency UNIQUE (protectee_uid, idempotency_key),
  CONSTRAINT ck_alert_status CHECK (status IN
    ('CREATED','DISPATCHING','DISPATCHED','ACKNOWLEDGED','RESOLVED','NO_RECIPIENT','DISPATCH_FAILED')),
  CONSTRAINT ck_alert_type CHECK (alert_type IN ('MANUAL', 'FALL_DETECTED', 'VOICE_HELP')),
  CONSTRAINT ck_alert_route_type CHECK (route_type IN ('ALL_GUARDIANS','NEARBY_VOLUNTEERS','NONE')),
  CONSTRAINT ck_alert_route_consistency CHECK (status IN ('CREATED','DISPATCHING') OR route_type IS NOT NULL)
) ENGINE=InnoDB COMMENT='报警先持久化；位置快照可为空';
CREATE INDEX ix_alert_protectee_created ON alert (protectee_uid, created_at);
CREATE INDEX ix_alert_status_created ON alert (status, created_at);

-- 修正: 去 CANCELLED (14 §1.6)
CREATE TABLE alert_delivery (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  alert_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  receiver_principal_type VARCHAR(16) NOT NULL,
  receiver_principal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  route_type VARCHAR(24) NOT NULL,
  acknowledged_at DATETIME(3) NULL,
  failure_reason VARCHAR(500) NULL,
  retry_count INT UNSIGNED NOT NULL DEFAULT 0,
  CONSTRAINT pk_alert_delivery PRIMARY KEY (id),
  CONSTRAINT uq_alert_delivery_receiver UNIQUE (alert_id, receiver_principal_type, receiver_principal_id),
  CONSTRAINT fk_alert_delivery_alert FOREIGN KEY (alert_id) REFERENCES alert(id),
  CONSTRAINT ck_alert_delivery_status CHECK (status IN ('PENDING','SENT','DELIVERED','ACKNOWLEDGED','FAILED')),
  CONSTRAINT ck_alert_delivery_receiver CHECK (receiver_principal_type = 'USER'),
  CONSTRAINT ck_alert_delivery_route CHECK (route_type IN ('ALL_GUARDIANS','NEARBY_VOLUNTEERS'))
) ENGINE=InnoDB COMMENT='每条报警对每个接收主体的独立投递';
CREATE INDEX ix_alert_delivery_receiver_status ON alert_delivery (receiver_principal_type, receiver_principal_id, status, created_at);

CREATE TABLE alert_broadcast (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  alert_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  radius_m INT UNSIGNED NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  closed_at DATETIME(3) NULL,
  CONSTRAINT pk_alert_broadcast PRIMARY KEY (id),
  CONSTRAINT uq_alert_broadcast_alert UNIQUE (alert_id),
  CONSTRAINT fk_alert_broadcast_alert FOREIGN KEY (alert_id) REFERENCES alert(id),
  CONSTRAINT ck_alert_broadcast_status CHECK (status IN ('OPEN', 'CLOSED', 'EXPIRED', 'CANCELLED'))
) ENGINE=InnoDB COMMENT='仅在无生效监护人时创建';

CREATE TABLE alert_broadcast_response (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  broadcast_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  alert_delivery_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  volunteer_uid CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  responded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT pk_alert_broadcast_response PRIMARY KEY (id),
  CONSTRAINT uq_alert_broadcast_response_volunteer UNIQUE (broadcast_id, volunteer_uid),
  CONSTRAINT uq_alert_broadcast_response_delivery UNIQUE (alert_delivery_id),
  CONSTRAINT fk_alert_broadcast_response_broadcast FOREIGN KEY (broadcast_id) REFERENCES alert_broadcast(id),
  CONSTRAINT fk_alert_broadcast_response_delivery FOREIGN KEY (alert_delivery_id) REFERENCES alert_delivery(id),
  CONSTRAINT ck_alert_broadcast_response_status CHECK (status IN ('ACCEPTED','DECLINED','EXPIRED','CANCELLED'))
) ENGINE=InnoDB COMMENT='志愿者对广播的响应';

CREATE TABLE notification (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'UNREAD',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  receiver_principal_type VARCHAR(16) NOT NULL,
  receiver_principal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  notification_type VARCHAR(32) NOT NULL,
  title VARCHAR(160) NOT NULL,
  body VARCHAR(1000) NOT NULL,
  payload_json JSON NULL,
  read_at DATETIME(3) NULL,
  source_type VARCHAR(32) NULL,
  source_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
  CONSTRAINT pk_notification PRIMARY KEY (id),
  CONSTRAINT ck_notification_status CHECK (status IN ('UNREAD', 'READ')),
  CONSTRAINT ck_notification_principal CHECK (receiver_principal_type IN ('USER','ORGANIZATION','ADMIN')),
  CONSTRAINT ck_notification_type CHECK (notification_type IN
    ('BINDING_REQUEST','BINDING_RESULT','ALERT','APPLICATION_RESULT',
     'ENROLLMENT_RESULT','ACTIVITY_CHANGE','ORG_REVIEW','SYSTEM'))
) ENGINE=InnoDB COMMENT='多接收主体通知；不作为聊天消息表';
CREATE INDEX ix_notification_receiver_unread ON notification (receiver_principal_type, receiver_principal_id, status, created_at);

CREATE TABLE notification_delivery_attempt (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  notification_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  channel VARCHAR(16) NOT NULL,
  attempt_no INT UNSIGNED NOT NULL,
  provider_message_id VARCHAR(160) NULL,
  failure_reason VARCHAR(500) NULL,
  attempted_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT pk_notification_delivery_attempt PRIMARY KEY (id),
  CONSTRAINT uq_notification_delivery_attempt UNIQUE (notification_id, channel, attempt_no),
  CONSTRAINT fk_notification_delivery_attempt_notification FOREIGN KEY (notification_id) REFERENCES notification(id),
  CONSTRAINT ck_notification_delivery_attempt_status CHECK (status IN ('PENDING','SENT','DELIVERED','FAILED'))
) ENGINE=InnoDB COMMENT='通知渠道重试记录';
CREATE INDEX ix_notification_delivery_attempt_pending ON notification_delivery_attempt (status, attempted_at);

-- outbox 本地消息表 (14 §2.3)
CREATE TABLE outbox_message (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  message_type VARCHAR(48) NOT NULL COMMENT 'ALERT_DISPATCH / NOTIFICATION_PUSH',
  business_ref CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload JSON NOT NULL,
  retry_count INT UNSIGNED NOT NULL DEFAULT 0,
  next_retry_at DATETIME(3) NULL,
  last_error VARCHAR(500) NULL,
  trace_id VARCHAR(64) NULL,
  CONSTRAINT pk_outbox_message PRIMARY KEY (id),
  CONSTRAINT uq_outbox_business UNIQUE (message_type, business_ref),
  CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING','PROCESSING','DONE','FAILED')),
  CONSTRAINT ck_outbox_payload CHECK (JSON_TYPE(payload) = 'OBJECT')
) ENGINE=InnoDB COMMENT='本地可靠任务表，替代消息队列';
CREATE INDEX ix_outbox_claim ON outbox_message (status, next_retry_at, created_at);
