-- 时间列精度改为毫秒（设计已由蓝色大肥鱼拍板）：workorder 独立 Flyway 历史，V1–V4 不得修改，本 V5 只追加。
-- 背景：原列为 TIMESTAMP（精度到秒），MySQL 对小数秒四舍五入，写入值最多比实际晚近 0.5 秒，同一秒内拉批会漏掉已到期记录；
-- 且租约到期、重试退避、outbox 按时间排序在秒级精度下分不出同一秒内的先后。
-- 规则与 quality、inventory 一致：库中存到毫秒，Java 写入前截断到毫秒（见 DbTimes）。
-- 每列单独一条 ALTER（H2 的 MySQL 兼容模式不支持一条语句多个 MODIFY）；只用 MODIFY COLUMN 提升精度，NULL / NOT NULL 与 DEFAULT 保持原样；TIMESTAMP → TIMESTAMP(3) 不丢失已有数据。

-- 事务性发件箱：occurred_at 决定投递顺序，next_attempt_at 决定退避到期，published_at 记录投递时间。
ALTER TABLE outbox_event MODIFY COLUMN occurred_at TIMESTAMP(3) NOT NULL;
ALTER TABLE outbox_event MODIFY COLUMN published_at TIMESTAMP(3) NULL;
ALTER TABLE outbox_event MODIFY COLUMN next_attempt_at TIMESTAMP(3) NULL;

-- 预留释放任务：next_attempt_at 兼作领取租约（lease）到期时间与退避到期时间，created_at 决定领取顺序，completed_at 记录终态时间。
ALTER TABLE wo_reservation_release_task MODIFY COLUMN next_attempt_at TIMESTAMP(3) NULL;
ALTER TABLE wo_reservation_release_task MODIFY COLUMN created_at TIMESTAMP(3) NOT NULL;
ALTER TABLE wo_reservation_release_task MODIFY COLUMN completed_at TIMESTAMP(3) NULL;

-- 预留镜像：expires_at 为库存返回的预留租约到期时间（库存侧为毫秒精度，秒级存储会四舍五入到晚于真实到期），
-- last_renewed_at 决定续约轮次的扫描与判定，二者都参与调度。
ALTER TABLE wo_material_reservation MODIFY COLUMN expires_at TIMESTAMP(3) NOT NULL;
ALTER TABLE wo_material_reservation MODIFY COLUMN last_renewed_at TIMESTAMP(3) NULL;

-- 状态转换审计：按 (order_id, created_at) 排序展示，同一秒内的连续转换需分得清先后。
ALTER TABLE work_order_status_log MODIFY COLUMN created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);
