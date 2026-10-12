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

-- GR-60（FG-GR60-20，BOM A01）工单下达时预留的全部叶子物料期初库存：数量 = 单件用量（已含损耗率，逐层展开）× 10 件 × 1.5，
-- 非整数向上取整；原材料放 RM-A-01 / 批次 L20261001，外购件与包材放 RM-A-02 / 批次 L20261002。
-- RM-40CR-D25（500 kg）与 PP-BRG-6204（2000 个）沿用上文已有期初，远大于需求（9.45 kg、30 个），不重复写入。
-- RM-HT250-HSG HT250 箱体铸件：单件用量 1.02 件，期初 16 件
INSERT INTO inventory_material (material_code, uom)
SELECT 'RM-HT250-HSG', '件' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM inventory_material WHERE material_code = 'RM-HT250-HSG');
INSERT INTO lot (material_code, lot_no)
SELECT 'RM-HT250-HSG', 'L20261001' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM lot WHERE material_code = 'RM-HT250-HSG' AND lot_no = 'L20261001');
INSERT INTO stock_txn (txn_type, material_code, location_code, lot_no, qty, balance_after, ref_type, ref_no, operator, occurred_at)
SELECT 'INBOUND', 'RM-HT250-HSG', 'RM-A-01', 'L20261001', 16.000000, 16.000000, 'DEMO', 'DEMO-INIT', 'demo', CURRENT_TIMESTAMP(3) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stock_on_hand WHERE material_code = 'RM-HT250-HSG' AND location_code = 'RM-A-01' AND lot_no = 'L20261001');
INSERT INTO stock_on_hand (material_code, location_code, lot_no, qty)
SELECT 'RM-HT250-HSG', 'RM-A-01', 'L20261001', 16.000000 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stock_on_hand WHERE material_code = 'RM-HT250-HSG' AND location_code = 'RM-A-01' AND lot_no = 'L20261001');
-- RM-HT250-COV HT250 端盖铸件：单件用量 1 件，期初 15 件
INSERT INTO inventory_material (material_code, uom)
SELECT 'RM-HT250-COV', '件' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM inventory_material WHERE material_code = 'RM-HT250-COV');
INSERT INTO lot (material_code, lot_no)
SELECT 'RM-HT250-COV', 'L20261001' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM lot WHERE material_code = 'RM-HT250-COV' AND lot_no = 'L20261001');
INSERT INTO stock_txn (txn_type, material_code, location_code, lot_no, qty, balance_after, ref_type, ref_no, operator, occurred_at)
SELECT 'INBOUND', 'RM-HT250-COV', 'RM-A-01', 'L20261001', 15.000000, 15.000000, 'DEMO', 'DEMO-INIT', 'demo', CURRENT_TIMESTAMP(3) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stock_on_hand WHERE material_code = 'RM-HT250-COV' AND location_code = 'RM-A-01' AND lot_no = 'L20261001');
INSERT INTO stock_on_hand (material_code, location_code, lot_no, qty)
SELECT 'RM-HT250-COV', 'RM-A-01', 'L20261001', 15.000000 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stock_on_hand WHERE material_code = 'RM-HT250-COV' AND location_code = 'RM-A-01' AND lot_no = 'L20261001');
-- RM-20CRMNTI-D30 20CrMnTi 圆钢 Φ30：单件用量 1.05 kg，期初 16 kg
INSERT INTO inventory_material (material_code, uom)
SELECT 'RM-20CRMNTI-D30', 'kg' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM inventory_material WHERE material_code = 'RM-20CRMNTI-D30');
INSERT INTO lot (material_code, lot_no)
SELECT 'RM-20CRMNTI-D30', 'L20261001' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM lot WHERE material_code = 'RM-20CRMNTI-D30' AND lot_no = 'L20261001');
INSERT INTO stock_txn (txn_type, material_code, location_code, lot_no, qty, balance_after, ref_type, ref_no, operator, occurred_at)
SELECT 'INBOUND', 'RM-20CRMNTI-D30', 'RM-A-01', 'L20261001', 16.000000, 16.000000, 'DEMO', 'DEMO-INIT', 'demo', CURRENT_TIMESTAMP(3) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stock_on_hand WHERE material_code = 'RM-20CRMNTI-D30' AND location_code = 'RM-A-01' AND lot_no = 'L20261001');
INSERT INTO stock_on_hand (material_code, location_code, lot_no, qty)
SELECT 'RM-20CRMNTI-D30', 'RM-A-01', 'L20261001', 16.000000 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stock_on_hand WHERE material_code = 'RM-20CRMNTI-D30' AND location_code = 'RM-A-01' AND lot_no = 'L20261001');
-- RM-20CRMNTI-D90 20CrMnTi 锻坯 Φ90：单件用量 2 件，期初 30 件
INSERT INTO inventory_material (material_code, uom)
SELECT 'RM-20CRMNTI-D90', '件' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM inventory_material WHERE material_code = 'RM-20CRMNTI-D90');
INSERT INTO lot (material_code, lot_no)
SELECT 'RM-20CRMNTI-D90', 'L20261001' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM lot WHERE material_code = 'RM-20CRMNTI-D90' AND lot_no = 'L20261001');
INSERT INTO stock_txn (txn_type, material_code, location_code, lot_no, qty, balance_after, ref_type, ref_no, operator, occurred_at)
SELECT 'INBOUND', 'RM-20CRMNTI-D90', 'RM-A-01', 'L20261001', 30.000000, 30.000000, 'DEMO', 'DEMO-INIT', 'demo', CURRENT_TIMESTAMP(3) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stock_on_hand WHERE material_code = 'RM-20CRMNTI-D90' AND location_code = 'RM-A-01' AND lot_no = 'L20261001');
INSERT INTO stock_on_hand (material_code, location_code, lot_no, qty)
SELECT 'RM-20CRMNTI-D90', 'RM-A-01', 'L20261001', 30.000000 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stock_on_hand WHERE material_code = 'RM-20CRMNTI-D90' AND location_code = 'RM-A-01' AND lot_no = 'L20261001');
-- PP-BRG-6202 深沟球轴承 6202-2RS：单件用量 4 个，期初 60 个
INSERT INTO inventory_material (material_code, uom)
SELECT 'PP-BRG-6202', '个' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM inventory_material WHERE material_code = 'PP-BRG-6202');
INSERT INTO lot (material_code, lot_no)
SELECT 'PP-BRG-6202', 'L20261002' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM lot WHERE material_code = 'PP-BRG-6202' AND lot_no = 'L20261002');
INSERT INTO stock_txn (txn_type, material_code, location_code, lot_no, qty, balance_after, ref_type, ref_no, operator, occurred_at)
SELECT 'INBOUND', 'PP-BRG-6202', 'RM-A-02', 'L20261002', 60.000000, 60.000000, 'DEMO', 'DEMO-INIT', 'demo', CURRENT_TIMESTAMP(3) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stock_on_hand WHERE material_code = 'PP-BRG-6202' AND location_code = 'RM-A-02' AND lot_no = 'L20261002');
INSERT INTO stock_on_hand (material_code, location_code, lot_no, qty)
SELECT 'PP-BRG-6202', 'RM-A-02', 'L20261002', 60.000000 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stock_on_hand WHERE material_code = 'PP-BRG-6202' AND location_code = 'RM-A-02' AND lot_no = 'L20261002');
-- PP-SEAL-15 油封 TC 15×30×7：单件用量 1 个，期初 15 个
INSERT INTO inventory_material (material_code, uom)
SELECT 'PP-SEAL-15', '个' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM inventory_material WHERE material_code = 'PP-SEAL-15');
INSERT INTO lot (material_code, lot_no)
SELECT 'PP-SEAL-15', 'L20261002' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM lot WHERE material_code = 'PP-SEAL-15' AND lot_no = 'L20261002');
INSERT INTO stock_txn (txn_type, material_code, location_code, lot_no, qty, balance_after, ref_type, ref_no, operator, occurred_at)
SELECT 'INBOUND', 'PP-SEAL-15', 'RM-A-02', 'L20261002', 15.000000, 15.000000, 'DEMO', 'DEMO-INIT', 'demo', CURRENT_TIMESTAMP(3) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stock_on_hand WHERE material_code = 'PP-SEAL-15' AND location_code = 'RM-A-02' AND lot_no = 'L20261002');
INSERT INTO stock_on_hand (material_code, location_code, lot_no, qty)
SELECT 'PP-SEAL-15', 'RM-A-02', 'L20261002', 15.000000 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stock_on_hand WHERE material_code = 'PP-SEAL-15' AND location_code = 'RM-A-02' AND lot_no = 'L20261002');
-- PP-SEAL-20 油封 TC 20×35×7：单件用量 1 个，期初 15 个
INSERT INTO inventory_material (material_code, uom)
SELECT 'PP-SEAL-20', '个' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM inventory_material WHERE material_code = 'PP-SEAL-20');
INSERT INTO lot (material_code, lot_no)
SELECT 'PP-SEAL-20', 'L20261002' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM lot WHERE material_code = 'PP-SEAL-20' AND lot_no = 'L20261002');
INSERT INTO stock_txn (txn_type, material_code, location_code, lot_no, qty, balance_after, ref_type, ref_no, operator, occurred_at)
SELECT 'INBOUND', 'PP-SEAL-20', 'RM-A-02', 'L20261002', 15.000000, 15.000000, 'DEMO', 'DEMO-INIT', 'demo', CURRENT_TIMESTAMP(3) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stock_on_hand WHERE material_code = 'PP-SEAL-20' AND location_code = 'RM-A-02' AND lot_no = 'L20261002');
INSERT INTO stock_on_hand (material_code, location_code, lot_no, qty)
SELECT 'PP-SEAL-20', 'RM-A-02', 'L20261002', 15.000000 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stock_on_hand WHERE material_code = 'PP-SEAL-20' AND location_code = 'RM-A-02' AND lot_no = 'L20261002');
-- PP-KEY-6 平键 6×6×20：单件用量 2 个，期初 30 个
INSERT INTO inventory_material (material_code, uom)
SELECT 'PP-KEY-6', '个' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM inventory_material WHERE material_code = 'PP-KEY-6');
INSERT INTO lot (material_code, lot_no)
SELECT 'PP-KEY-6', 'L20261002' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM lot WHERE material_code = 'PP-KEY-6' AND lot_no = 'L20261002');
INSERT INTO stock_txn (txn_type, material_code, location_code, lot_no, qty, balance_after, ref_type, ref_no, operator, occurred_at)
SELECT 'INBOUND', 'PP-KEY-6', 'RM-A-02', 'L20261002', 30.000000, 30.000000, 'DEMO', 'DEMO-INIT', 'demo', CURRENT_TIMESTAMP(3) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stock_on_hand WHERE material_code = 'PP-KEY-6' AND location_code = 'RM-A-02' AND lot_no = 'L20261002');
INSERT INTO stock_on_hand (material_code, location_code, lot_no, qty)
SELECT 'PP-KEY-6', 'RM-A-02', 'L20261002', 30.000000 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stock_on_hand WHERE material_code = 'PP-KEY-6' AND location_code = 'RM-A-02' AND lot_no = 'L20261002');
-- PP-BOLT-M6 内六角螺栓 M6×20：单件用量 6.06 个，期初 91 个
INSERT INTO inventory_material (material_code, uom)
SELECT 'PP-BOLT-M6', '个' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM inventory_material WHERE material_code = 'PP-BOLT-M6');
INSERT INTO lot (material_code, lot_no)
SELECT 'PP-BOLT-M6', 'L20261002' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM lot WHERE material_code = 'PP-BOLT-M6' AND lot_no = 'L20261002');
INSERT INTO stock_txn (txn_type, material_code, location_code, lot_no, qty, balance_after, ref_type, ref_no, operator, occurred_at)
SELECT 'INBOUND', 'PP-BOLT-M6', 'RM-A-02', 'L20261002', 91.000000, 91.000000, 'DEMO', 'DEMO-INIT', 'demo', CURRENT_TIMESTAMP(3) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stock_on_hand WHERE material_code = 'PP-BOLT-M6' AND location_code = 'RM-A-02' AND lot_no = 'L20261002');
INSERT INTO stock_on_hand (material_code, location_code, lot_no, qty)
SELECT 'PP-BOLT-M6', 'RM-A-02', 'L20261002', 91.000000 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stock_on_hand WHERE material_code = 'PP-BOLT-M6' AND location_code = 'RM-A-02' AND lot_no = 'L20261002');
-- PP-GASKET 端盖密封垫：单件用量 1 个，期初 15 个
INSERT INTO inventory_material (material_code, uom)
SELECT 'PP-GASKET', '个' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM inventory_material WHERE material_code = 'PP-GASKET');
INSERT INTO lot (material_code, lot_no)
SELECT 'PP-GASKET', 'L20261002' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM lot WHERE material_code = 'PP-GASKET' AND lot_no = 'L20261002');
INSERT INTO stock_txn (txn_type, material_code, location_code, lot_no, qty, balance_after, ref_type, ref_no, operator, occurred_at)
SELECT 'INBOUND', 'PP-GASKET', 'RM-A-02', 'L20261002', 15.000000, 15.000000, 'DEMO', 'DEMO-INIT', 'demo', CURRENT_TIMESTAMP(3) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stock_on_hand WHERE material_code = 'PP-GASKET' AND location_code = 'RM-A-02' AND lot_no = 'L20261002');
INSERT INTO stock_on_hand (material_code, location_code, lot_no, qty)
SELECT 'PP-GASKET', 'RM-A-02', 'L20261002', 15.000000 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stock_on_hand WHERE material_code = 'PP-GASKET' AND location_code = 'RM-A-02' AND lot_no = 'L20261002');
-- PP-OIL-220 合成齿轮油 VG220：单件用量 0.255 L，期初 4 L
INSERT INTO inventory_material (material_code, uom)
SELECT 'PP-OIL-220', 'L' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM inventory_material WHERE material_code = 'PP-OIL-220');
INSERT INTO lot (material_code, lot_no)
SELECT 'PP-OIL-220', 'L20261002' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM lot WHERE material_code = 'PP-OIL-220' AND lot_no = 'L20261002');
INSERT INTO stock_txn (txn_type, material_code, location_code, lot_no, qty, balance_after, ref_type, ref_no, operator, occurred_at)
SELECT 'INBOUND', 'PP-OIL-220', 'RM-A-02', 'L20261002', 4.000000, 4.000000, 'DEMO', 'DEMO-INIT', 'demo', CURRENT_TIMESTAMP(3) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stock_on_hand WHERE material_code = 'PP-OIL-220' AND location_code = 'RM-A-02' AND lot_no = 'L20261002');
INSERT INTO stock_on_hand (material_code, location_code, lot_no, qty)
SELECT 'PP-OIL-220', 'RM-A-02', 'L20261002', 4.000000 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stock_on_hand WHERE material_code = 'PP-OIL-220' AND location_code = 'RM-A-02' AND lot_no = 'L20261002');
-- PK-LBL-NP 铭牌标签：单件用量 1 张，期初 15 张
INSERT INTO inventory_material (material_code, uom)
SELECT 'PK-LBL-NP', '张' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM inventory_material WHERE material_code = 'PK-LBL-NP');
INSERT INTO lot (material_code, lot_no)
SELECT 'PK-LBL-NP', 'L20261002' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM lot WHERE material_code = 'PK-LBL-NP' AND lot_no = 'L20261002');
INSERT INTO stock_txn (txn_type, material_code, location_code, lot_no, qty, balance_after, ref_type, ref_no, operator, occurred_at)
SELECT 'INBOUND', 'PK-LBL-NP', 'RM-A-02', 'L20261002', 15.000000, 15.000000, 'DEMO', 'DEMO-INIT', 'demo', CURRENT_TIMESTAMP(3) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stock_on_hand WHERE material_code = 'PK-LBL-NP' AND location_code = 'RM-A-02' AND lot_no = 'L20261002');
INSERT INTO stock_on_hand (material_code, location_code, lot_no, qty)
SELECT 'PK-LBL-NP', 'RM-A-02', 'L20261002', 15.000000 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stock_on_hand WHERE material_code = 'PK-LBL-NP' AND location_code = 'RM-A-02' AND lot_no = 'L20261002');
-- PK-BOX-GR60 包装纸箱：单件用量 1 个，期初 15 个
INSERT INTO inventory_material (material_code, uom)
SELECT 'PK-BOX-GR60', '个' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM inventory_material WHERE material_code = 'PK-BOX-GR60');
INSERT INTO lot (material_code, lot_no)
SELECT 'PK-BOX-GR60', 'L20261002' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM lot WHERE material_code = 'PK-BOX-GR60' AND lot_no = 'L20261002');
INSERT INTO stock_txn (txn_type, material_code, location_code, lot_no, qty, balance_after, ref_type, ref_no, operator, occurred_at)
SELECT 'INBOUND', 'PK-BOX-GR60', 'RM-A-02', 'L20261002', 15.000000, 15.000000, 'DEMO', 'DEMO-INIT', 'demo', CURRENT_TIMESTAMP(3) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stock_on_hand WHERE material_code = 'PK-BOX-GR60' AND location_code = 'RM-A-02' AND lot_no = 'L20261002');
INSERT INTO stock_on_hand (material_code, location_code, lot_no, qty)
SELECT 'PK-BOX-GR60', 'RM-A-02', 'L20261002', 15.000000 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stock_on_hand WHERE material_code = 'PK-BOX-GR60' AND location_code = 'RM-A-02' AND lot_no = 'L20261002');
