-- ============================================================================
-- V6 处罚体系扩展：暂时下架/暂时封禁 + 群聊成为处罚对象
-- ============================================================================
-- 1) 预设处罚动作矩阵扩容：
--    ACTIVITY : DELIST 永久下架（群解散，无封禁天数） / TEMP_DELIST 暂时下架 N 天（群聊暂时封禁）
--    GROUP    : BAN_GROUP 暂时封禁群聊 N 天 / PERM_CLOSE_GROUP_DELIST_ACTIVITY 永久封禁并下架活动
--               / PERM_CLOSE_GROUP_BAN_ORG 永久封禁并附带机构处罚（封禁天数为机构侧时长，空=永久）
--    ORGANIZATION : BAN_ORG 封禁 N 天（其活动/群聊至少暂时下架封禁同时长；空=永久→永久下架+解散）
--    USER     : BAN_USER 不变
-- 2) 活动与群聊新增 SUSPENDED 状态（暂时下架/暂时封禁），到期由每分钟扫描
--    按 penalty.expires_at 恢复（活动恢复按自然时间轴取 RECRUITING/IN_PROGRESS/ENDED）。
-- 3) penalty 目标列扩到四类（活动/机构/群聊/用户互斥恰一）。
-- ============================================================================

ALTER TABLE volunteer_activity DROP CHECK ck_volunteer_activity_status;
ALTER TABLE volunteer_activity
  ADD CONSTRAINT ck_volunteer_activity_status CHECK (status IN
    ('RECRUITING', 'IN_PROGRESS', 'ENDED', 'CANCELLED', 'SUSPENDED'));

ALTER TABLE activity_group
  ADD COLUMN suspended_until DATETIME(3) NULL COMMENT '暂时封禁到期时间；SUSPENDED 必填' AFTER closed_reason;
ALTER TABLE activity_group DROP CHECK ck_activity_group_status;
ALTER TABLE activity_group
  ADD CONSTRAINT ck_activity_group_status CHECK (status IN ('OPEN', 'CLOSED', 'SUSPENDED'));
ALTER TABLE activity_group
  ADD CONSTRAINT ck_activity_group_suspended CHECK (status <> 'SUSPENDED' OR suspended_until IS NOT NULL);

ALTER TABLE preset_penalty DROP CHECK ck_preset_penalty_category;
ALTER TABLE preset_penalty
  ADD CONSTRAINT ck_preset_penalty_category CHECK (category IN ('ACTIVITY', 'ORGANIZATION', 'GROUP', 'USER'));
ALTER TABLE preset_penalty DROP CHECK ck_preset_penalty_action;
ALTER TABLE preset_penalty
  ADD CONSTRAINT ck_preset_penalty_action CHECK (action IN
    ('DELIST', 'TEMP_DELIST', 'DELIST_AND_BAN_ORG', 'BAN_GROUP', 'PERM_CLOSE_GROUP_DELIST_ACTIVITY',
     'PERM_CLOSE_GROUP_BAN_ORG', 'BAN_ORG', 'BAN_USER'));
-- 注：DELIST_AND_BAN_ORG 为 V4 旧动作，仅为存量行保留；新申请走 DELIST/TEMP_DELIST +
-- 机构/群聊类处罚组合，服务端已不再接受以它新建预设。

ALTER TABLE penalty
  ADD COLUMN target_group_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER target_activity_id,
  ADD CONSTRAINT fk_penalty_group FOREIGN KEY (target_group_id) REFERENCES activity_group(id);
ALTER TABLE penalty DROP CHECK ck_penalty_target;
ALTER TABLE penalty
  ADD CONSTRAINT ck_penalty_target CHECK (
    (target_activity_id IS NOT NULL) + (target_org_id IS NOT NULL)
      + (target_group_id IS NOT NULL) + (target_user_id IS NOT NULL) = 1);
