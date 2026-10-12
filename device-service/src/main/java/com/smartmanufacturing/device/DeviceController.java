package com.smartmanufacturing.device;
import com.smartmanufacturing.api.*;
import com.smartmanufacturing.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.beans.factory.annotation.Value;
import java.util.*;
/** 设备查询及动态配置演示；刷新作用域保证 Nacos 更新后重建配置值。 */
@org.springframework.security.access.prepost.PreAuthorize("hasAuthority('device:read')")
@RestController
@RefreshScope
@RequestMapping("/devices")
public class DeviceController {
    private final JdbcTemplate jdbc;
    private final String welcome;
    public DeviceController(JdbcTemplate jdbc, @Value("${demo.welcome:智能制造设备服务}") String welcome) {
        this.jdbc = jdbc; this.welcome = welcome;
    }
    /** 查询设备列表，数据量增长时应扩展为分页查询。 */
    @GetMapping public ApiResponse<List<Device>> list() {
        return ApiResponse.ok(jdbc.query("select id, name, status from device order by id",
            (rs, row) -> new Device(rs.getString(1), rs.getString(2), rs.getString(3))));
    }
    /** 返回刷新作用域中的配置值，用于验证 Nacos 动态刷新。 */
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('config:read')")
    @GetMapping("/config") public ApiResponse<String> config() { return ApiResponse.ok(welcome); }
    /** 按编号查询设备，不存在时返回 404，供远程调用方识别。 */
    @GetMapping("/{id}") public ApiResponse<Device> get(@PathVariable String id) {
        List<Device> devices = jdbc.query("select id, name, status from device where id = ?",
            (rs, row) -> new Device(rs.getString(1), rs.getString(2), rs.getString(3)), id);
        if (devices.isEmpty()) throw new BusinessException("DEVICE_NOT_FOUND", "设备不存在", HttpStatus.NOT_FOUND);
        return ApiResponse.ok(devices.getFirst());
    }
}
