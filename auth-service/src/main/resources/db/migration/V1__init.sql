-- 认证服务初始表结构（Flyway V1，由原 schema.sql 转换）。用户数据只存放在认证库，密码字段仅保存 BCrypt 散列。
CREATE TABLE IF NOT EXISTS auth_user (
  id VARCHAR(64) PRIMARY KEY,
  username VARCHAR(64) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  role VARCHAR(16) NOT NULL,
  enabled BOOLEAN NOT NULL,
  failed_attempts INT NOT NULL DEFAULT 0,
  locked_until TIMESTAMP NULL
);
