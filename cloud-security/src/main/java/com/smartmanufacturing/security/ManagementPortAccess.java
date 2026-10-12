package com.smartmanufacturing.security;

import org.springframework.core.env.Environment;

/**
 * 管理端口判定：只有请求真正落在独立的 management 端口上时，才允许匿名抓取 Prometheus 指标。
 *
 * <p>设计原因：cloud profile 下 actuator 迁到独立端口（compose 内网可达、不映射到宿主机），
 * Prometheus 无需持有 JWT 即可抓取；而业务端口（含对外发布的网关 8080）上的 actuator 仍要求
 * {@code metrics:read} 权限。端口取自 Spring Boot 启动后写入的 {@code local.management.port}，
 * 因此随机端口（测试）和固定端口（compose）都能正确判定；未启用独立端口时该属性不存在，一律返回 false。</p>
 */
public final class ManagementPortAccess {
    /** Prometheus 抓取路径，固定写死，避免通配放开其他 actuator 端点。 */
    public static final String PROMETHEUS_PATH = "/actuator/prometheus";

    private ManagementPortAccess() {
    }

    /**
     * 判断本次请求的本地端口是否为独立管理端口。
     *
     * @param environment Spring 环境，读取实际监听的管理端口
     * @param localPort   请求到达的本地套接字端口（不是 Host 头，客户端无法伪造）
     * @return 已启用独立管理端口且请求落在该端口时为 true
     */
    public static boolean isManagementPort(Environment environment, int localPort) {
        Integer managementPort = environment.getProperty("local.management.port", Integer.class);
        Integer serverPort = environment.getProperty("local.server.port", Integer.class);
        // 管理端口与业务端口相同（未分离）时不放开，防止指标经业务端口被匿名读取。
        return managementPort != null && managementPort > 0 && !managementPort.equals(serverPort) && managementPort == localPort;
    }
}
