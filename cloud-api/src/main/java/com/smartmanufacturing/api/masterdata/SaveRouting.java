package com.smartmanufacturing.api.masterdata;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
/** 新建或替换工艺路线草稿。 */
public record SaveRouting(@NotBlank @Size(max = 64) String productCode,
                          @NotBlank @Pattern(regexp = "[A-Z0-9]{1,8}") String version,
                          @NotNull LocalDate effectiveFrom,
                          @NotEmpty @Size(max = 100) List<@Valid @NotNull Operation> operations) {}
