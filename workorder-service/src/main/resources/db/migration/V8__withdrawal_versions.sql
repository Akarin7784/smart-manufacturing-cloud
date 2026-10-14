-- M1 PR2：撤回下达会删除 work_order_lifecycle，这里在撤回记录上保留当时冻结的产品与 BOM / 工艺版本，
-- 供主数据删除草稿前的"是否曾被工单引用"检查（GET /workorders/version-references）。只新增可空列，不改已有数据。
ALTER TABLE wo_release_withdrawal ADD COLUMN product_code VARCHAR(64) NULL;
ALTER TABLE wo_release_withdrawal ADD COLUMN bom_version VARCHAR(8) NULL;
ALTER TABLE wo_release_withdrawal ADD COLUMN routing_version VARCHAR(8) NULL;
