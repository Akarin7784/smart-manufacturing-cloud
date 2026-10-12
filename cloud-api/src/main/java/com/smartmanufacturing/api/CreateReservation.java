package com.smartmanufacturing.api;
import jakarta.validation.constraints.*;
/** 设备预留输入；调用方提供唯一预留单编号，重复编号明确返回冲突。 */
public record CreateReservation(@NotBlank @Pattern(regexp="[a-zA-Z0-9-]{1,36}") String reservationId,
                                @NotBlank @Size(max=64) String deviceId) {}
