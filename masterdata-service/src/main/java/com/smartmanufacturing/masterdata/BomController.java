package com.smartmanufacturing.masterdata;
import com.smartmanufacturing.api.ApiResponse;
import com.smartmanufacturing.api.masterdata.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** BOM HTTP 入口；发布单独使用 masterdata:release 权限，与草稿编辑分离（设计文档 2.4）。 */
@PreAuthorize("hasAuthority('masterdata:read')")
@Validated
@RestController
@RequestMapping("/boms")
public class BomController {
    private final BomService service;
    BomController(BomService service) { this.service = service; }

    @GetMapping("/{product}") public ApiResponse<List<Bom>> versions(@PathVariable String product) { return ApiResponse.ok(service.listByProduct(product)); }
    @GetMapping("/{product}/{version}") public ApiResponse<Bom> get(@PathVariable String product, @PathVariable String version) {
        return ApiResponse.ok(service.get(product, version));
    }
    /** 查询指定日期生效版本，日期缺省为当天（服务器时区）。 */
    @GetMapping("/{product}/effective")
    public ApiResponse<Bom> effective(@PathVariable String product, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(service.effective(product, date == null ? LocalDate.now() : date));
    }
    /** 多层展开；数量上限与工单数量上限一致。 */
    @GetMapping("/{product}/explosion")
    public ApiResponse<BomExplosion> explode(@PathVariable String product,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                             @RequestParam(defaultValue = "1") @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000000") BigDecimal quantity) {
        return ApiResponse.ok(service.explode(product, date == null ? LocalDate.now() : date, quantity));
    }

    @PreAuthorize("hasAuthority('masterdata:write')")
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Bom> create(@Valid @RequestBody SaveBom request) { return ApiResponse.ok(service.create(request)); }

    @PreAuthorize("hasAuthority('masterdata:write')")
    @PutMapping("/{product}/{version}")
    public ApiResponse<Bom> replace(@PathVariable String product, @PathVariable String version, @Valid @RequestBody SaveBom request) {
        return ApiResponse.ok(service.replaceDraft(product, version, request));
    }

    @PreAuthorize("hasAuthority('masterdata:release')")
    @PostMapping("/{product}/{version}/release")
    public ApiResponse<Bom> release(@PathVariable String product, @PathVariable String version) { return ApiResponse.ok(service.release(product, version)); }
}
