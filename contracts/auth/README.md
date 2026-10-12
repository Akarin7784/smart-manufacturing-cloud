# 角色权限契约（role-permissions.json）

`role-permissions.json` 是 auth-service `Role` 枚举的角色 → 权限表导出，作为认证服务与网关之间的契约文件：

- **auth-service** 的 `RolePermissionContractTest` 断言该文件与 `Role` 枚举完全一致（角色集合、每个角色的权限集合）；修改 `Role` 而未同步本文件时 CI 失败。
- **cloud-gateway** 的 `GatewayRoutingTest` 直接读取本文件为各角色签发测试令牌，不再手工复制权限清单。

修改角色权限时：先改 `Role`，再同步本文件（同一个 PR），两边测试都会重新验证。权限顺序不影响授权，测试按集合比较。
