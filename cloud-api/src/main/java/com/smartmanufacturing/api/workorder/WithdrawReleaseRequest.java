package com.smartmanufacturing.api.workorder;
import jakarta.validation.constraints.Size;
/** 撤回下达请求（请求体可省略）：note 为撤回说明，写入状态审计日志。 */
public record WithdrawReleaseRequest(@Size(max = 256) String note) {}
