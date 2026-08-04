-- SullivanMINI V1 Baseline — sullivan_navigation
CREATE TABLE model_version (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  tenant_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
  external_ref VARCHAR(128) NULL,
  extension JSON NULL,
  model_name VARCHAR(100) NOT NULL,
  model_revision VARCHAR(100) NOT NULL,
  artifact_uri VARCHAR(500) NOT NULL,
  checksum_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  activated_at DATETIME(3) NULL,
  retired_at DATETIME(3) NULL,
  CONSTRAINT pk_model_version PRIMARY KEY (id),
  CONSTRAINT uq_model_version_name_revision UNIQUE (model_name, model_revision),
  CONSTRAINT ck_model_version_status CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
  CONSTRAINT ck_model_version_extension CHECK (extension IS NULL OR JSON_TYPE(extension) = 'OBJECT')
) ENGINE=InnoDB COMMENT='导航模型登记，不承载客服模型或计费';

CREATE TABLE navigation_session (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  protectee_uid CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  model_version_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
  started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  ended_at DATETIME(3) NULL,
  failure_code VARCHAR(64) NULL,
  CONSTRAINT pk_navigation_session PRIMARY KEY (id),
  CONSTRAINT fk_navigation_session_model FOREIGN KEY (model_version_id) REFERENCES model_version(id),
  CONSTRAINT ck_navigation_session_status CHECK (status IN ('ACTIVE', 'STOPPED', 'FAILED', 'EXPIRED'))
) ENGINE=InnoDB COMMENT='导航会话';
CREATE INDEX ix_navigation_session_protectee_status ON navigation_session (protectee_uid, status, started_at);

CREATE TABLE navigation_sample_event (
  id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  session_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  model_version_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
  sample_seq BIGINT UNSIGNED NOT NULL,
  captured_at DATETIME(3) NULL,
  image_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '仅存摘要，禁止原始帧',
  width_px INT UNSIGNED NULL,
  height_px INT UNSIGNED NULL,
  risk_level VARCHAR(16) NULL,
  guide_text VARCHAR(280) NULL,
  inference_ms INT UNSIGNED NULL,
  result_json JSON NULL,
  CONSTRAINT pk_navigation_sample_event PRIMARY KEY (id),
  CONSTRAINT uq_navigation_sample_sequence UNIQUE (session_id, sample_seq),
  CONSTRAINT fk_navigation_sample_session FOREIGN KEY (session_id) REFERENCES navigation_session(id),
  CONSTRAINT fk_navigation_sample_model FOREIGN KEY (model_version_id) REFERENCES model_version(id),
  CONSTRAINT ck_navigation_sample_status CHECK (status IN ('PROCESSED', 'DROPPED', 'FAILED')),
  CONSTRAINT ck_navigation_sample_risk CHECK (risk_level IN ('SAFE','CAUTION','DANGER','UNKNOWN'))
) ENGINE=InnoDB COMMENT='采样事件，非逐帧日志；不得保存原始图像或 TTS 音频';
CREATE INDEX ix_navigation_sample_created ON navigation_sample_event (created_at);
