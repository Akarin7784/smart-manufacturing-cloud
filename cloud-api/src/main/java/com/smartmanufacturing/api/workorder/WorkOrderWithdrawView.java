package com.smartmanufacturing.api.workorder;
import java.time.Instant;
/**
 * 撤回下达结果：status 恒为 CREATED；reservationId 为撤回时需要释放的库存预留（无则为 null），
 * releaseStatus 为释放任务状态（PENDING：已登记，由后台异步释放；无预留时为 null）。
 */
public record WorkOrderWithdrawView(String workOrderNo, String status, String previousStatus, String reservationId,
                                    String releaseStatus, Instant withdrawnAt) {}
