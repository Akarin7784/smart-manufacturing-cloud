-- 质检隔离库位 QC-HOLD（Flyway V2，设计见 docs/quality-inventory-writeback-design.md）。
-- system_location=TRUE 的库位为系统库位：只能经 /inventory/qc-hold/** 变动，通用出入库返回 400；
-- 其余额为隔离量，不计入现存量合计与可用量，也不能被预留或扣减。
ALTER TABLE warehouse_location ADD COLUMN system_location BOOLEAN NOT NULL DEFAULT FALSE;

-- 种子：系统库位 QC-HOLD（仓库 QC）。迁移只执行一次，若同名库位已被手工建立则直接改为系统库位。
INSERT INTO warehouse_location (code, warehouse_code, name, enabled, system_location)
SELECT 'QC-HOLD', 'QC', '质检隔离库位', TRUE, TRUE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM warehouse_location WHERE code = 'QC-HOLD');
UPDATE warehouse_location SET system_location = TRUE, enabled = TRUE WHERE code = 'QC-HOLD';
