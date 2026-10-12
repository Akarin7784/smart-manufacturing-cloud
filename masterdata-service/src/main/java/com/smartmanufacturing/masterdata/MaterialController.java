package com.smartmanufacturing.masterdata;
import com.smartmanufacturing.api.ApiResponse;
import com.smartmanufacturing.api.masterdata.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 物料 HTTP 入口：读接口需 masterdata:read，写接口需 masterdata:write（网关同步维护同样规则）。 */
@PreAuthorize("hasAuthority('masterdata:read')")
@Validated
@RestController
@RequestMapping("/materials")
public class MaterialController {
    private final MaterialService service;
    MaterialController(MaterialService service) { this.service = service; }

    /** 分页查询物料，可按类型过滤；单页上限 200，防止一次拉取全表。 */
    @GetMapping
    public ApiResponse<List<Material>> list(@RequestParam(required = false) MaterialType type,
                                            @RequestParam(defaultValue = "0") @Min(0) int page,
                                            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return ApiResponse.ok(service.list(type, page, size));
    }
    @GetMapping("/{code}") public ApiResponse<Material> get(@PathVariable String code) { return ApiResponse.ok(service.get(code)); }

    @PreAuthorize("hasAuthority('masterdata:write')")
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Material> create(@Valid @RequestBody CreateMaterial request) { return ApiResponse.ok(service.create(request)); }

    @PreAuthorize("hasAuthority('masterdata:write')")
    @PutMapping("/{code}")
    public ApiResponse<Material> update(@PathVariable String code, @Valid @RequestBody UpdateMaterial request) {
        return ApiResponse.ok(service.update(code, request));
    }
}
