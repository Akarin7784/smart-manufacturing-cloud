package com.smartmanufacturing.api.workorder;
import java.time.Instant;
import java.util.List;
/** 工单级物料预留视图；expiresAt 由库存服务给出，工单服务只镜像不自行计算。 */
public record MaterialReservationView(String reservationId, String status, Instant expiresAt, List<ReservationLineView> lines) {}
