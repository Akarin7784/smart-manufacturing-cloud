package com.smartmanufacturing.masterdata;
import com.smartmanufacturing.api.ApiResponse;
import com.smartmanufacturing.api.masterdata.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 工作中心与工位 HTTP 入口。 */
@PreAuthorize("hasAuthority('masterdata:read')")
@RestController
@RequestMapping("/workcenters")
public class WorkCenterController {
    private final WorkCenterService service;
    WorkCenterController(WorkCenterService service) { this.service = service; }

    /** 列出工作中心及工位；默认隐藏已删除的，includeDeleted=true 时一并返回（带 deleted 标志）。 */
    @GetMapping public ApiResponse<List<WorkCenter>> list(@RequestParam(defaultValue = "false") boolean includeDeleted) {
        return ApiResponse.ok(service.list(includeDeleted));
    }
    @GetMapping("/{code}") public ApiResponse<WorkCenter> get(@PathVariable String code) { return ApiResponse.ok(service.get(code)); }

    @PreAuthorize("hasAuthority('masterdata:write')")
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WorkCenter> create(@Valid @RequestBody CreateWorkCenter request) { return ApiResponse.ok(service.create(request)); }

    @PreAuthorize("hasAuthority('masterdata:write')")
    @PostMapping("/{code}/stations") @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WorkCenter> addStation(@PathVariable String code, @Valid @RequestBody CreateStation request) {
        return ApiResponse.ok(service.addStation(code, request));
    }

    /** 修改工作中心（编码不可改）；不存在或已删除 404。 */
    @PreAuthorize("hasAuthority('masterdata:write')")
    @PutMapping("/{code}")
    public ApiResponse<WorkCenter> update(@PathVariable String code, @Valid @RequestBody UpdateWorkCenter request) {
        return ApiResponse.ok(service.update(code, request));
    }

    /** 软删除工作中心及其工位：被工艺路线或执行中工单引用 409 MASTERDATA_IN_USE；工单服务不可用 503。 */
    @PreAuthorize("hasAuthority('masterdata:write')")
    @DeleteMapping("/{code}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String code) { service.delete(code); }

    /** 修改工位名称；工位不存在、已删除或不属于该工作中心 404。 */
    @PreAuthorize("hasAuthority('masterdata:write')")
    @PutMapping("/{code}/stations/{station}")
    public ApiResponse<WorkCenter> updateStation(@PathVariable String code, @PathVariable String station, @Valid @RequestBody UpdateStation request) {
        return ApiResponse.ok(service.updateStation(code, station, request));
    }

    /** 软删除工位：被执行中工单引用 409 MASTERDATA_IN_USE；工单服务不可用 503。 */
    @PreAuthorize("hasAuthority('masterdata:write')")
    @DeleteMapping("/{code}/stations/{station}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteStation(@PathVariable String code, @PathVariable String station) { service.deleteStation(code, station); }
}
