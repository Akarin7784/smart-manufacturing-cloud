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

    /** 列出产品的全部 BOM 版本（含草稿、已发布、作废），按生效日排序。 */
    @GetMapping("/{product}") public ApiResponse<List<Bom>> versions(@PathVariable String product) { return ApiResponse.ok(service.listByProduct(product)); }
    /** 查询指定版本 BOM，不存在返回 404。 */
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

    /** 新建 BOM 草稿；产品须为自制件，同产品版本号重复返回 409。 */
    @PreAuthorize("hasAuthority('masterdata:write')")
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Bom> create(@Valid @RequestBody SaveBom request) { return ApiResponse.ok(service.create(request)); }

    /** 整体替换草稿的生效日与 BOM 行；已发布版本只读，返回 409。 */
    @PreAuthorize("hasAuthority('masterdata:write')")
    @PutMapping("/{product}/{version}")
    public ApiResponse<Bom> replace(@PathVariable String product, @PathVariable String version, @Valid @RequestBody SaveBom request) {
        return ApiResponse.ok(service.replaceDraft(product, version, request));
    }

    /** 发布草稿：执行跨聚合校验并截断上一已发布版本；同一产品的发布按产品行锁串行。 */
    @PreAuthorize("hasAuthority('masterdata:release')")
    @PostMapping("/{product}/{version}/release")
    public ApiResponse<Bom> release(@PathVariable String product, @PathVariable String version) { return ApiResponse.ok(service.release(product, version)); }

    /** 作废已发布版本（RELEASED → OBSOLETE，PR2）：作废后不再参与生效查询；非已发布 409 BOM_NOT_RELEASED。 */
    @PreAuthorize("hasAuthority('masterdata:release')")
    @PostMapping("/{product}/{version}/retire")
    public ApiResponse<Bom> retire(@PathVariable String product, @PathVariable String version) { return ApiResponse.ok(service.retire(product, version)); }

    /** 删除草稿（物理删除，已发布 / 作废版本只读，返回 409 BOM_NOT_DRAFT）。 */
    @PreAuthorize("hasAuthority('masterdata:write')")
    @DeleteMapping("/{product}/{version}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDraft(@PathVariable String product, @PathVariable String version) { service.deleteDraft(product, version); }
}
