-- 新建认证库及专用账号；独立初始化服务支持已有 MySQL 数据卷升级。
CREATE DATABASE IF NOT EXISTS manufacturing_auth CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS 'auth_app'@'%' IDENTIFIED BY 'local-demo-password';
GRANT ALL PRIVILEGES ON manufacturing_auth.* TO 'auth_app'@'%';
