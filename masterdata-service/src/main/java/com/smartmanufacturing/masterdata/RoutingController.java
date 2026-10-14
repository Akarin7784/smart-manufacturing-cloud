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
import java.time.LocalDate;
import java.util.List;

/** 工艺路线 HTTP 入口；权限划分与 BOM 一致。 */
@PreAuthorize("hasAuthority('masterdata:read')")
@Validated
@RestController
@RequestMapping("/routings")
public class RoutingController {
    private final RoutingService service;
    RoutingController(RoutingService service) { this.service = service; }

    /** 列出产品的全部工艺路线版本。 */
    @GetMapping("/{product}") public ApiResponse<List<Routing>> versions(@PathVariable String product) { return ApiResponse.ok(service.listByProduct(product)); }
    /** 查询指定版本工艺路线，不存在返回 404。 */
    @GetMapping("/{product}/{version}") public ApiResponse<Routing> get(@PathVariable String product, @PathVariable String version) {
        return ApiResponse.ok(service.get(product, version));
    }
    /** 查询指定日期生效版本，日期缺省为当天（服务器时区）。 */
    @GetMapping("/{product}/effective")
    public ApiResponse<Routing> effective(@PathVariable String product, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(service.effective(product, date == null ? LocalDate.now() : date));
    }

    /** 新建工艺路线草稿。 */
    @PreAuthorize("hasAuthority('masterdata:write')")
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Routing> create(@Valid @RequestBody SaveRouting request) { return ApiResponse.ok(service.create(request)); }

    /** 整体替换工艺路线草稿。 */
    @PreAuthorize("hasAuthority('masterdata:write')")
    @PutMapping("/{product}/{version}")
    public ApiResponse<Routing> replace(@PathVariable String product, @PathVariable String version, @Valid @RequestBody SaveRouting request) {
        return ApiResponse.ok(service.replaceDraft(product, version, request));
    }

    /** 发布工艺路线草稿；同一产品的发布按产品行锁串行。 */
    @PreAuthorize("hasAuthority('masterdata:release')")
    @PostMapping("/{product}/{version}/release")
    public ApiResponse<Routing> release(@PathVariable String product, @PathVariable String version) { return ApiResponse.ok(service.release(product, version)); }

    /** 作废已发布版本（RELEASED → OBSOLETE，PR2）：作废后不再参与生效查询；非已发布 409 ROUTING_NOT_RELEASED。 */
    @PreAuthorize("hasAuthority('masterdata:release')")
    @PostMapping("/{product}/{version}/retire")
    public ApiResponse<Routing> retire(@PathVariable String product, @PathVariable String version) { return ApiResponse.ok(service.retire(product, version)); }

    /** 删除草稿（物理删除，已发布 / 作废版本只读，返回 409 ROUTING_NOT_DRAFT）。 */
    @PreAuthorize("hasAuthority('masterdata:write')")
    @DeleteMapping("/{product}/{version}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDraft(@PathVariable String product, @PathVariable String version) { service.deleteDraft(product, version); }
}
