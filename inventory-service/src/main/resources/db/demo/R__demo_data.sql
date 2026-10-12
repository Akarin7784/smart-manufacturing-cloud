-- 演示数据（仅 demo profile 加载）：两个库位、与主数据 GR-60 演示物料对应的期初库存（计量单位与主数据一致）。
-- 可重复执行：只在目标记录不存在时插入，不覆盖运行中产生的数据；期初入库同时写入流水，保证余额与流水可对账。
INSERT INTO warehouse_location (code, warehouse_code, name)
SELECT 'RM-A-01', 'WH-RM', '原材料库 A 区 01 位' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM warehouse_location WHERE code = 'RM-A-01');
INSERT INTO warehouse_location (code, warehouse_code, name)
SELECT 'RM-A-02', 'WH-RM', '原材料库 A 区 02 位' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM warehouse_location WHERE code = 'RM-A-02');

INSERT INTO inventory_material (material_code, uom)
SELECT 'RM-40CR-D25', 'kg' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM inventory_material WHERE material_code = 'RM-40CR-D25');
INSERT INTO inventory_material (material_code, uom)
SELECT 'PP-BRG-6204', '个' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM inventory_material WHERE material_code = 'PP-BRG-6204');

INSERT INTO lot (material_code, lot_no)
SELECT 'RM-40CR-D25', 'L20261001' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM lot WHERE material_code = 'RM-40CR-D25' AND lot_no = 'L20261001');
INSERT INTO lot (material_code, lot_no)
SELECT 'PP-BRG-6204', 'L20261002' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM lot WHERE material_code = 'PP-BRG-6204' AND lot_no = 'L20261002');

INSERT INTO stock_txn (txn_type, material_code, location_code, lot_no, qty, balance_after, ref_type, ref_no, operator, occurred_at)
SELECT 'INBOUND', 'RM-40CR-D25', 'RM-A-01', 'L20261001', 500.000000, 500.000000, 'DEMO', 'DEMO-INIT', 'demo', CURRENT_TIMESTAMP(3) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stock_on_hand WHERE material_code = 'RM-40CR-D25' AND location_code = 'RM-A-01' AND lot_no = 'L20261001');
INSERT INTO stock_on_hand (material_code, location_code, lot_no, qty)
SELECT 'RM-40CR-D25', 'RM-A-01', 'L20261001', 500.000000 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stock_on_hand WHERE material_code = 'RM-40CR-D25' AND location_code = 'RM-A-01' AND lot_no = 'L20261001');

INSERT INTO stock_txn (txn_type, material_code, location_code, lot_no, qty, balance_after, ref_type, ref_no, operator, occurred_at)
SELECT 'INBOUND', 'PP-BRG-6204', 'RM-A-02', 'L20261002', 2000.000000, 2000.000000, 'DEMO', 'DEMO-INIT', 'demo', CURRENT_TIMESTAMP(3) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stock_on_hand WHERE material_code = 'PP-BRG-6204' AND location_code = 'RM-A-02' AND lot_no = 'L20261002');
INSERT INTO stock_on_hand (material_code, location_code, lot_no, qty)
SELECT 'PP-BRG-6204', 'RM-A-02', 'L20261002', 2000.000000 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stock_on_hand WHERE material_code = 'PP-BRG-6204' AND location_code = 'RM-A-02' AND lot_no = 'L20261002');
