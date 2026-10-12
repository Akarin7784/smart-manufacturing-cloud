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

    @GetMapping("/{product}") public ApiResponse<List<Routing>> versions(@PathVariable String product) { return ApiResponse.ok(service.listByProduct(product)); }
    @GetMapping("/{product}/{version}") public ApiResponse<Routing> get(@PathVariable String product, @PathVariable String version) {
        return ApiResponse.ok(service.get(product, version));
    }
    /** 查询指定日期生效版本，日期缺省为当天（服务器时区）。 */
    @GetMapping("/{product}/effective")
    public ApiResponse<Routing> effective(@PathVariable String product, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(service.effective(product, date == null ? LocalDate.now() : date));
    }

    @PreAuthorize("hasAuthority('masterdata:write')")
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Routing> create(@Valid @RequestBody SaveRouting request) { return ApiResponse.ok(service.create(request)); }

    @PreAuthorize("hasAuthority('masterdata:write')")
    @PutMapping("/{product}/{version}")
    public ApiResponse<Routing> replace(@PathVariable String product, @PathVariable String version, @Valid @RequestBody SaveRouting request) {
        return ApiResponse.ok(service.replaceDraft(product, version, request));
    }

    @PreAuthorize("hasAuthority('masterdata:release')")
    @PostMapping("/{product}/{version}/release")
    public ApiResponse<Routing> release(@PathVariable String product, @PathVariable String version) { return ApiResponse.ok(service.release(product, version)); }
}
