-- =============================================================
-- 清除所有管理员数据（幂等）
--
-- 用途：清空 admin_account / admin_role / admin_permission /
--        admin_role_permission / admin_account_role / admin_registration_code
--       六张表的所有数据。
--
-- 说明：
--   1. 先解除唯一引用管理员的外部表（volunteer_application.decided_by_admin_id），
--      否则外键约束会阻止删除 admin_account。
--   2. 按外键依赖顺序删除（子表先删），全部为硬删除。
--   3. 角色/权限/授权（admin_role 等）会在 Java 下次启动时由
--      AdminSeedInitializer 重新播种；SUPER_ADMIN 账号由
--      HighestAdminBootstrap 重建（前提是 BOOTSTRAP_ADMIN_LOGIN/PASSWORD
--      已在 server-java 的 .env 中配置且密码 ≥ 12 位）。
--
-- 执行（本地 mysql client，凭据来自 EndWork/.env 的 DB_USERNAME/DB_PASSWORD）：
--   "/c/Program Files/MySQL/MySQL Server 9.1/bin/mysql.exe" \
--     -h 127.0.0.1 -P 3306 -u <DB_USERNAME> -p sullivan < cleanup_admin_data.sql
-- =============================================================

-- 1) 解除外部引用：志愿者兼任申请已审核的，清除审核管理员与审核时间。
--    （保留申请本身，仅解除对 admin_account 的引用。）
UPDATE volunteer_application
   SET decided_by_admin_id = NULL,
       decided_at = NULL
 WHERE decided_by_admin_id IS NOT NULL;

-- 2) 按外键依赖顺序删除管理员数据（子表先删）。
DELETE FROM admin_registration_code;
DELETE FROM admin_account_role;
DELETE FROM admin_role_permission;
DELETE FROM admin_account;
DELETE FROM admin_role;
DELETE FROM admin_permission;

-- 3) 幂等校验：以下应全部返回 0。
SELECT 'admin_registration_code' AS tbl, COUNT(*) AS rows_left FROM admin_registration_code
UNION ALL SELECT 'admin_account_role', COUNT(*) FROM admin_account_role
UNION ALL SELECT 'admin_role_permission', COUNT(*) FROM admin_role_permission
UNION ALL SELECT 'admin_account', COUNT(*) FROM admin_account
UNION ALL SELECT 'admin_role', COUNT(*) FROM admin_role
UNION ALL SELECT 'admin_permission', COUNT(*) FROM admin_permission
UNION ALL SELECT 'volunteer_application.decided_by_admin_id(remaining)', COUNT(*) FROM volunteer_application WHERE decided_by_admin_id IS NOT NULL;
