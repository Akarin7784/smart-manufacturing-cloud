-- M1 PR2：撤回下达记录。撤回会删除 work_order_lifecycle（使工单可重新下达），这里保留被撤回的下达键与预留单作为审计，
-- 并阻止同一 Idempotency-Key 再次下达（库存按 release:{工单号}:{键} 派生预留键，同键会重放到已释放的预留单）。
-- 团队约定：不使用 IF NOT EXISTS。关闭（COMPLETED → CLOSED）不需要新表，沿用 work_order.status 与状态日志。
CREATE TABLE wo_release_withdrawal (
  order_id VARCHAR(64) NOT NULL,
  release_key VARCHAR(128) NOT NULL,
  reservation_id VARCHAR(64) NULL,
  withdrawn_by VARCHAR(128) NOT NULL,
  withdrawn_at TIMESTAMP(3) NOT NULL,
  CONSTRAINT pk_wo_release_withdrawal PRIMARY KEY (order_id, release_key)
);
