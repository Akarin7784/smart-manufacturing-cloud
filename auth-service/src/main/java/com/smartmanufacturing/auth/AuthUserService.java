package com.smartmanufacturing.auth;

import com.smartmanufacturing.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 管理用户密码散列及登录锁定，所有查询和更新均限定在认证数据库。 */
@Service
public class AuthUserService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final TransactionTemplate transactions;
    private final String dummyHash;

    public AuthUserService(JdbcTemplate jdbc, PasswordEncoder passwords, TransactionTemplate transactions) {
        this.jdbc = jdbc; this.passwords = passwords; this.transactions = transactions;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    /** 登录失败五次锁定十五分钟；失败计数先提交，再在事务外返回统一认证错误。 */
    public UserView authenticate(LoginRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) throw invalidCredentials();
        UserView result = transactions.execute(status -> {
            // 行锁避免同一账号并发失败请求丢失计数；不存在的账号也执行一次散列验证。
            var rows = jdbc.query("select id, username, password_hash, role, enabled, failed_attempts, locked_until from auth_user where username = ? for update",
                (rs, row) -> new Account(rs.getString(1), rs.getString(2), rs.getString(3), Role.valueOf(rs.getString(4)),
                    rs.getBoolean(5), rs.getInt(6), rs.getTimestamp(7)), request.username());
            if (rows.isEmpty()) { passwords.matches(request.password(), dummyHash); return null; }
            Account account = rows.getFirst();
            Instant now = Instant.now();
            if (!account.enabled() || (account.lockedUntil() != null && account.lockedUntil().toInstant().isAfter(now))) {
                passwords.matches(request.password(), dummyHash); return null;
            }
            int attempts = account.lockedUntil() == null ? account.attempts() : 0;
            if (!passwords.matches(request.password(), account.hash())) {
                int failed = attempts + 1;
                jdbc.update("update auth_user set failed_attempts = ?, locked_until = ? where id = ?",
                    failed, failed >= 5 ? Timestamp.from(now.plusSeconds(900)) : null, account.id());
                return null;
            }
            jdbc.update("update auth_user set failed_attempts = 0, locked_until = null where id = ?", account.id());
            return account.view();
        });
        if (result == null) throw invalidCredentials();
        return result;
    }

    /** 创建用户时校验 BCrypt 的字节长度上限，防止不同长密码被截断为同一输入。 */
    public UserView create(CreateUserRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72)
            throw new BusinessException("INVALID_ARGUMENT", "密码 UTF-8 编码不得超过 72 字节", HttpStatus.BAD_REQUEST);
        String id = UUID.randomUUID().toString();
        try {
            jdbc.update("insert into auth_user (id, username, password_hash, role, enabled, failed_attempts) values (?, ?, ?, ?, true, 0)",
                id, request.username(), passwords.encode(request.password()), request.role().name());
        } catch (DuplicateKeyException e) {
            throw new BusinessException("USERNAME_EXISTS", "用户名已存在", HttpStatus.CONFLICT);
        }
        return new UserView(id, request.username(), request.role(), true);
    }

    /** 返回管理视图，密码散列仅由登录流程读取，不进入响应对象。 */
    public List<UserView> list() {
        return jdbc.query("select id, username, role, enabled from auth_user order by username",
            (rs, row) -> new UserView(rs.getString(1), rs.getString(2), Role.valueOf(rs.getString(3)), rs.getBoolean(4)));
    }

    /** 操作工下拉：只查启用账号，只取 id、工号、姓名三列（工号 / 姓名为空时回退为用户名），按工号排序。 */
    public List<OperatorView> operators() {
        return jdbc.query("select id, coalesce(employee_no, username) as emp, coalesce(display_name, username) as name from auth_user where enabled = true order by emp",
            (rs, row) -> new OperatorView(rs.getString(1), rs.getString(2), rs.getString(3)));
    }

    /** 更新角色和状态并清除登录锁定；已有短期令牌的权限保持到过期。 */
    public UserView update(String id, UpdateUserRequest request, String actorId) {
        if (id.equals(actorId) && (!request.enabled() || request.role() != Role.ADMIN))
            throw new BusinessException("SELF_LOCKOUT_FORBIDDEN", "不能禁用自己或移除自己的管理员角色", HttpStatus.CONFLICT);
        int count = jdbc.update("update auth_user set role = ?, enabled = ?, failed_attempts = 0, locked_until = null where id = ?",
            request.role().name(), request.enabled(), id);
        if (count == 0) throw new BusinessException("USER_NOT_FOUND", "用户不存在", HttpStatus.NOT_FOUND);
        return list().stream().filter(user -> user.id().equals(id)).findFirst().orElseThrow();
    }

    private BusinessException invalidCredentials() {
        // 不区分账号不存在、禁用、锁定和密码错误，避免泄露账号状态。
        return new BusinessException("INVALID_CREDENTIALS", "用户名或密码错误，或账号暂不可用", HttpStatus.UNAUTHORIZED);
    }

    /** 仅用于认证内部的数据库映射，禁止直接作为接口响应。 */
    private record Account(String id, String username, String hash, Role role, boolean enabled, int attempts, Timestamp lockedUntil) {
        UserView view() { return new UserView(id, username, role, enabled); }
    }
}
