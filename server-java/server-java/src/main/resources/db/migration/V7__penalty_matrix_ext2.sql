-- ============================================================================
-- V7 处罚矩阵扩展二：禁止发布活动 + 附带处罚时长独立设置
-- ============================================================================
-- 1) 新限制种类 PUBLISH_BANNED（机构禁止发布活动；机构封禁 ORG_BANNED 仍然包含发布禁令）。
-- 2) 预设新增列 attached_duration_days：附带处罚（如 DELIST_AND_BAN_PUBLISH 的禁止发布、
--    PERM_CLOSE_GROUP_BAN_ORG 的机构处罚）可单独设置时长；NULL = 与主处罚时长相同。
-- 3) 预设动作扩容：
--    ORGANIZATION : BAN_PUBLISH 禁止发布活动 N 天（空=永久）
--    ACTIVITY     : DELIST_AND_BAN_PUBLISH 下架活动（主时长，空=永久下架+解散群聊）
--                   并禁止发布活动（附带时长，空=与下架时长相同）
-- ============================================================================

ALTER TABLE abuse_restriction DROP CHECK ck_restriction_kind;
ALTER TABLE abuse_restriction
  ADD CONSTRAINT ck_restriction_kind CHECK (kind IN
    ('REPORT_LOCKED', 'ACTIVITY_BANNED', 'ACCOUNT_BANNED', 'ORG_BANNED', 'PUBLISH_BANNED'));

ALTER TABLE preset_penalty
  ADD COLUMN attached_duration_days INT NULL COMMENT '附带处罚时长；NULL=与主处罚时长相同' AFTER duration_days;

ALTER TABLE preset_penalty DROP CHECK ck_preset_penalty_action;
ALTER TABLE preset_penalty
  ADD CONSTRAINT ck_preset_penalty_action CHECK (action IN
    ('DELIST', 'TEMP_DELIST', 'DELIST_AND_BAN_ORG', 'DELIST_AND_BAN_PUBLISH', 'BAN_GROUP',
     'PERM_CLOSE_GROUP_DELIST_ACTIVITY', 'PERM_CLOSE_GROUP_BAN_ORG', 'BAN_ORG', 'BAN_PUBLISH', 'BAN_USER'));
