package com.smartmanufacturing.api.workorder;
import java.time.Instant;
/**
 * 取消结果：previousStatus 为取消前状态；reservationId 为取消时仍有效、需要释放的库存预留单（无则为 null），
 * releaseStatus 为释放任务状态（PENDING：已登记，由后台异步释放；无预留时为 null）。
 */
public record WorkOrderCancellationView(String workOrderNo, String status, String previousStatus, String reservationId,
                                        String releaseStatus, Instant cancelledAt) {}
