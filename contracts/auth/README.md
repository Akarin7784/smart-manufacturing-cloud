# 角色权限契约（role-permissions.json）

`role-permissions.json` 是 auth-service `Role` 枚举的角色 → 权限表导出，作为认证服务与网关之间的契约文件：

- **auth-service** 的 `RolePermissionContractTest` 断言该文件与 `Role` 枚举完全一致（角色集合、每个角色的权限集合）；修改 `Role` 而未同步本文件时 CI 失败。
- **cloud-gateway** 的 `GatewayRoutingTest` 直接读取本文件为各角色签发测试令牌，不再手工复制权限清单。

修改角色权限时：先改 `Role`，再同步本文件（同一个 PR），两边测试都会重新验证。权限顺序不影响授权，测试按集合比较。

## 服务客户端 scope 不在本文件

本文件只描述**用户角色 → 权限**。服务间调用（OAuth2 client_credentials）的客户端 scope 不属于角色，维护在 auth-service
`application.yml` 的 `manufacturing.auth.clients`（`client-id`、`secret-hash`、`scopes`、`audience`），由资源服务按 `aud` 与 `SCOPE_*` 校验：

| client-id | scopes | audience | 用途 |
| --- | --- | --- | --- |
| `workorder-service` | `inventory:reserve` | `inventory-service` | 物料预留 |
| `quality-service`（待实现，见 docs/quality-inventory-writeback-design.md） | `inventory:qc-hold` | `inventory-service` | 质检隔离库位 QC-HOLD 入库、放行、报废 |

服务令牌不含 `permissions`，网关的角色权限规则对它不适用；`/api/inventory/qc-hold/**` 为内部接口，网关不对外转发。
