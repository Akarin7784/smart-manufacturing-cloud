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

    @GetMapping public ApiResponse<List<WorkCenter>> list() { return ApiResponse.ok(service.list()); }
    @GetMapping("/{code}") public ApiResponse<WorkCenter> get(@PathVariable String code) { return ApiResponse.ok(service.get(code)); }

    @PreAuthorize("hasAuthority('masterdata:write')")
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WorkCenter> create(@Valid @RequestBody CreateWorkCenter request) { return ApiResponse.ok(service.create(request)); }

    @PreAuthorize("hasAuthority('masterdata:write')")
    @PostMapping("/{code}/stations") @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WorkCenter> addStation(@PathVariable String code, @Valid @RequestBody CreateStation request) {
        return ApiResponse.ok(service.addStation(code, request));
    }
}
