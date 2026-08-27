-- =============================================================
-- SullivanMINI V4 — 机构审核记录字段
-- 给 organization 增加审核人、审核时间、审核意见三列（幂等）。
-- 沿用 V1/V3 的 information_schema 判断追加列模式，保证重复执行安全。
-- =============================================================

SET @org_reviewed_by_sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE organization ADD COLUMN reviewed_by_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT ''审核管理员ID''',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'organization' AND column_name = 'reviewed_by_admin_id');
PREPARE org_reviewed_by_stmt FROM @org_reviewed_by_sql;
EXECUTE org_reviewed_by_stmt;
DEALLOCATE PREPARE org_reviewed_by_stmt;

SET @org_reviewed_at_sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE organization ADD COLUMN reviewed_at DATETIME(3) NULL COMMENT ''审核时间''',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'organization' AND column_name = 'reviewed_at');
PREPARE org_reviewed_at_stmt FROM @org_reviewed_at_sql;
EXECUTE org_reviewed_at_stmt;
DEALLOCATE PREPARE org_reviewed_at_stmt;

SET @org_review_note_sql = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE organization ADD COLUMN review_note VARCHAR(500) NULL COMMENT ''审核意见/驳回原因''',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'organization' AND column_name = 'review_note');
PREPARE org_review_note_stmt FROM @org_review_note_sql;
EXECUTE org_review_note_stmt;
DEALLOCATE PREPARE org_review_note_stmt;
