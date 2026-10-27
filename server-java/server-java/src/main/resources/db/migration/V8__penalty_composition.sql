-- ============================================================================
-- V8 处罚组合化重构：基础惩罚 + 跨对象附带组件
-- ============================================================================
-- 设计模式：
--   每种对象有自己的**基础惩罚**（base action）：
--     ACTIVITY     : TEMP_DELIST 暂时下架(需时长) / DELIST 永久下架
--     ORGANIZATION : BAN_ORG 封禁机构(时长可空=永久) / BAN_PUBLISH 禁止发布活动(同左)
--     GROUP        : BAN_GROUP 暂时封禁(需时长) / PERM_CLOSE 永久封禁解散
--     USER         : BAN_USER 封禁账户(时长可空=永久；仅可作主惩罚，无附带)
--   预设 = 主基础惩罚(作用于举报对象) + 若干**附带组件**(作用于其他对象类型，
--   取该对象的基础惩罚集；可复合共存，如「永久封群 + 下架活动 + 封禁机构」)。
--   附带时长留空 = 跟随主处罚时长（主处罚永久则同为永久）。
--   列 action 语义升级为「主基础惩罚」（列名不变，减少无谓改动）。
--
--   语句顺序敏感：先建组件表 → 再迁旧组合为组件（旧动作值与附带时长列都还在）
--   → 再映射动作/退役附带列 → 最后收紧 CHECK。
-- ============================================================================

-- 1) 预设附带组件表（跨对象；同一预设内 target_kind 不重复、且不与预设对象相同——服务端校验）
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

-- 2) 旧组合预设迁移为附带组件（此时旧动作值与附带时长列都还在）
--    注意 id 列为 CHAR(26)：UUID 去横线后取前 26 位（一次性迁移，碰撞可忽略）。
INSERT INTO preset_penalty_part (id, created_at, updated_at, version, preset_id, target_kind, action, duration_days)
  SELECT LEFT(REPLACE(UUID(), '-', ''), 26), NOW(3), NOW(3), 0, id, 'ACTIVITY', 'DELIST', NULL
    FROM preset_penalty WHERE action = 'PERM_CLOSE_GROUP_DELIST_ACTIVITY';
INSERT INTO preset_penalty_part (id, created_at, updated_at, version, preset_id, target_kind, action, duration_days)
  SELECT LEFT(REPLACE(UUID(), '-', ''), 26), NOW(3), NOW(3), 0, id, 'ORGANIZATION', 'BAN_ORG', attached_duration_days
    FROM preset_penalty WHERE action = 'PERM_CLOSE_GROUP_BAN_ORG';
INSERT INTO preset_penalty_part (id, created_at, updated_at, version, preset_id, target_kind, action, duration_days)
  SELECT LEFT(REPLACE(UUID(), '-', ''), 26), NOW(3), NOW(3), 0, id, 'ORGANIZATION', 'BAN_PUBLISH', attached_duration_days
    FROM preset_penalty WHERE action = 'DELIST_AND_BAN_PUBLISH';
INSERT INTO preset_penalty_part (id, created_at, updated_at, version, preset_id, target_kind, action, duration_days)
  SELECT LEFT(REPLACE(UUID(), '-', ''), 26), NOW(3), NOW(3), 0, id, 'ORGANIZATION', 'BAN_ORG', attached_duration_days
    FROM preset_penalty WHERE action = 'DELIST_AND_BAN_ORG';

-- 3) 先移除旧动作 CHECK：新值（PERM_CLOSE 等）不在旧集合内，MySQL CHECK 逐行实时校验，
--    必须「先放宽 → 再改数据 → 再收紧」，否则 UPDATE 被旧约束拒绝。
ALTER TABLE preset_penalty DROP CHECK ck_preset_penalty_action;

-- 4) 旧动作映射为主基础惩罚
UPDATE preset_penalty SET action = 'TEMP_DELIST' WHERE action = 'DELIST_AND_BAN_PUBLISH' AND duration_days IS NOT NULL;
UPDATE preset_penalty SET action = 'DELIST' WHERE action = 'DELIST_AND_BAN_PUBLISH';
UPDATE preset_penalty SET action = 'TEMP_DELIST' WHERE action = 'DELIST_AND_BAN_ORG' AND duration_days IS NOT NULL;
UPDATE preset_penalty SET action = 'DELIST' WHERE action = 'DELIST_AND_BAN_ORG';
UPDATE preset_penalty SET action = 'PERM_CLOSE' WHERE action IN ('PERM_CLOSE_GROUP_DELIST_ACTIVITY', 'PERM_CLOSE_GROUP_BAN_ORG');

-- 5) 附带时长列退役（组件表接管），动作 CHECK 收紧为基础惩罚集
ALTER TABLE preset_penalty DROP COLUMN attached_duration_days;
ALTER TABLE preset_penalty
  ADD CONSTRAINT ck_preset_action CHECK (action IN
    ('DELIST', 'TEMP_DELIST', 'BAN_ORG', 'BAN_PUBLISH', 'PERM_CLOSE', 'BAN_GROUP', 'BAN_USER'));

-- 5) 处罚实例组件（执行记录；target_id 按目标类型落列，应用层保证一致性）
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
