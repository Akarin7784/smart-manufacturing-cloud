-- 质检角色（Flyway V2）：角色与权限映射在代码中的 Role 枚举里，数据库只存角色名。
-- 新角色名 QUALITY_INSPECTOR 为 17 个字符，超过 V1 中 role 列的 VARCHAR(16)，这里加宽到 VARCHAR(32)，为后续角色留余量。
-- 不在迁移中插入用户：演示账号 qc01 的密码由 init-env 随机生成，经环境变量在认证服务启动时创建（见 AuthBootstrap），迁移中不出现任何口令或散列。
ALTER TABLE auth_user MODIFY COLUMN role VARCHAR(32) NOT NULL;
