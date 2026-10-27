-- ============================================================================
-- V5 权限申请：任何管理员（无需权限）可申请「给自己开通一项权限」——
-- 申请表只有权限与理由，不涉及角色；由持 ADMIN_GOVERN 的管理员审批。
-- 批准即落地：系统把申请人加入持有该权限的承载角色（按权限自动生成，如
-- PERM_ABUSE_REVIEW；角色不存在则创建），申请人全程不接触角色概念。
-- 动机：admin-web 页面按权限显隐后，缺权限的管理员看不到也进不去对应页面，
-- 需要一条不依赖任何权限的正式通道去请求权限，而不是靠线下找人。
-- ============================================================================

CREATE TABLE admin_permission_request (
  id              CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待审批 / APPROVED 已批准（已落地）/ REJECTED 已驳回',
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
