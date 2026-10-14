-- M1 PR2：工作中心与工位软删除。已删除的行默认不在列表中返回，不能再被新工艺路线 / 派工引用；
-- 保留行本身，使历史工艺路线（外键 fk_routing_op_wc / fk_station_wc）与工单快照中的编码仍可追溯。
-- 团队约定：只新增迁移，不修改 V1；不使用 IF NOT EXISTS；新增列带默认值，存量数据无需回填。
ALTER TABLE work_center ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE station ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE;
