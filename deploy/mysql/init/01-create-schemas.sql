-- =============================================================
-- 仅建立统一数据库与开发账号授权。建表由 Java 应用 Flyway 负责。
-- =============================================================

CREATE DATABASE IF NOT EXISTS sullivan
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- 开发期使用同一应用账号。生产可按 Java/Python 两个进程分别授予最小表权限。
GRANT ALL PRIVILEGES ON `sullivan`.* TO 'sullivan'@'%';
FLUSH PRIVILEGES;
