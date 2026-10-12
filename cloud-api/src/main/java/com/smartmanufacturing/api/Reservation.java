package com.smartmanufacturing.api;
/** 预留结果只携带业务标识，不向客户端暴露 Seata 内部事务上下文。 */
public record Reservation(String reservationId, String deviceId) {}
