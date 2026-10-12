package com.smartmanufacturing.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.*;

/**
 * 角色权限契约测试：仓库根目录 contracts/auth/role-permissions.json 必须与 {@link Role} 枚举一致。
 * 网关测试读取同一文件签发角色令牌，因此修改 Role 而不同步契约文件时，这里直接失败，避免两边权限表漂移（issue #28）。
 */
class RolePermissionContractTest {

    /** 从模块目录向上查找契约文件；surefire 的工作目录是模块目录，IDE 中也可能是仓库根目录。 */
    static Path contractFile() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("contracts/auth/role-permissions.json");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalStateException("未找到 contracts/auth/role-permissions.json");
    }

    @Test void contractFileMatchesRoleEnum() throws Exception {
        Map<String, List<String>> contract = new ObjectMapper().readValue(contractFile().toFile(), new TypeReference<>() { });
        // 角色集合必须完全一致：新增或删除角色都要同步契约。
        Set<String> enumRoles = Arrays.stream(Role.values()).map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
        Assertions.assertEquals(enumRoles, new TreeSet<>(contract.keySet()), "契约文件的角色集合与 Role 枚举不一致");
        for (Role role : Role.values()) {
            List<String> listed = contract.get(role.name());
            // 契约内不允许重复权限，避免按集合比较时掩盖拼写重复。
            Assertions.assertEquals(listed.size(), new HashSet<>(listed).size(), role + " 的契约权限有重复项");
            // 授权按集合判断，顺序无关。
            Assertions.assertEquals(new TreeSet<>(role.permissions()), new TreeSet<>(listed),
                role + " 的权限与契约文件不一致，请同步 contracts/auth/role-permissions.json");
        }
    }
}
