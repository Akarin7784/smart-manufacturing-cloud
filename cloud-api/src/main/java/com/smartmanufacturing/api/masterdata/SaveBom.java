package com.smartmanufacturing.api.masterdata;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
/** 新建或替换 BOM 草稿；版本号由工艺员指定（如 A01），同产品内唯一。 */
public record SaveBom(@NotBlank @Size(max = 64) String productCode,
                      @NotBlank @Pattern(regexp = "[A-Z0-9]{1,8}") String version,
                      @NotNull LocalDate effectiveFrom,
                      @NotEmpty @Size(max = 500) List<@Valid @NotNull BomLine> lines) {}
