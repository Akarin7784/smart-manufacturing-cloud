package com.smartmanufacturing.api.workorder;
import jakarta.validation.constraints.*;
/** 解除一个冻结原因；仍有其他原因时工单保持 ON_HOLD。 */
public record ResumeRequest(@NotNull HoldReason reason, @Size(max = 256) String note) {}
