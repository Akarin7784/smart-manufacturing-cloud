-- 操作工下拉（GET /auth/operators）所需的工号与姓名（Flyway V3）。两列可空：历史账号为空时接口回退为用户名。
-- 新角色 PLANNER 为 7 个字符，V2 已把 role 加宽到 VARCHAR(32)，无需再改。不插入任何账号或口令。
ALTER TABLE auth_user ADD COLUMN employee_no VARCHAR(32) NULL;
ALTER TABLE auth_user ADD COLUMN display_name VARCHAR(64) NULL;
