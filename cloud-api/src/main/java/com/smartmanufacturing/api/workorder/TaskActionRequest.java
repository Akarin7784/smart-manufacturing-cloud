package com.smartmanufacturing.api.workorder;
import jakarta.validation.constraints.Size;
/** 取消派工 / 开工 / 暂停 / 恢复的请求体；reason 在取消派工与暂停时必填（服务端校验），其余可空。 */
public record TaskActionRequest(@Size(max = 256) String reason) {}
