package com.smartmanufacturing.inventory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 修改类接口的幂等执行器（与工单服务 #9 同一模型，扩展为保存完整响应）。
 * <ul>
 *   <li>作用域：（调用方身份，幂等键）。调用方为服务令牌的 client_id 或用户令牌的 sub，见 {@link CallerIdentity}。</li>
 *   <li>请求摘要：SHA-256(操作名 + 规范化请求 JSON)。同键不同摘要（含挪用到另一个操作）返回 422 idempotency-key-conflict。</li>
 *   <li>重放：同键同摘要原样返回首次的状态码与响应体原文（逐字节一致），不重复执行业务。</li>
 *   <li>一致性：业务写入与幂等记录在<b>同一本地事务</b>（READ COMMITTED，原因见构造器）提交；业务失败（4xx/5xx）整笔回滚、不留记录，重试会重新执行。</li>
 *   <li>并发同键：后提交者插入幂等记录时触发主键冲突，整笔回滚后读取胜者记录重放，不会重复执行业务。</li>
 * </ul>
 */
@Service
public class IdempotencyService {
    /** 幂等键格式：1–128 位字母、数字及 - _ . :，可容纳 UUID 与 release:{工单号}:{下达键} 形式。 */
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper responseMapper;
    /** 计算摘要专用：属性按字母序输出，保证同一请求的规范化 JSON 稳定，不受字段声明顺序影响。 */
    private final ObjectMapper canonicalMapper = JsonMapper.builder()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    public IdempotencyService(JdbcTemplate jdbc, PlatformTransactionManager txManager, ObjectMapper responseMapper) {
        this.jdbc = jdbc; this.responseMapper = responseMapper;
        this.tx = new TransactionTemplate(txManager);
        // 必须使用 READ COMMITTED：可用量 = 现存量（加锁读）− 有效预留量（普通读）。在 MySQL 默认的 REPEATABLE READ 下，
        // 普通读沿用事务首次读取时的快照，排队等到现存量行锁后仍看不到前一个事务刚提交的预留，会导致超卖
        // （ReservationMySqlIT 的并发用例可复现）。READ COMMITTED 下每条语句读取最新已提交数据；
        // 同一物料的预留、出库、扣减都持有该物料现存量行锁，因此读到的有效预留量是准确的。同时减少间隙锁与死锁。
        // 适用范围：READ COMMITTED 只用于经本模板执行的幂等写事务（入库、出库、预留、扣减、释放等扣减类操作）；
        // 其余数据库访问（查询接口、定时清理、过期扫描等）不经过本模板，仍使用 MySQL 默认的 REPEATABLE READ。
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 已保存（或首次生成）的响应：状态码、响应体 JSON 原文，以及是否为重放。 */
    public record StoredResponse(int status, String body, boolean replayed) {}

    /**
     * 幂等执行一次修改操作。
     *
     * @param principal       调用方身份
     * @param key             Idempotency-Key 请求头，缺失或格式非法返回 400
     * @param operation       操作名（如 inbound、reservation.create:{id}），区分同一个键被挪用到不同操作
     * @param normalizedInput 已规范化的请求对象（含路径参数），用于计算摘要
     * @param successStatus   首次成功时的 HTTP 状态
     * @param action          业务操作，在本方法开启的事务内执行，返回响应体对象
     */
    public StoredResponse execute(String principal, String key, String operation, Object normalizedInput,
                                  int successStatus, Supplier<Object> action) {
        if (key == null || !KEY.matcher(key).matches())
            throw ProblemException.validation("请求头 Idempotency-Key 缺失或格式非法（1–128 位字母、数字及 - _ . :）");
        String hash = hash(operation, normalizedInput);
        try {
            return tx.execute(status -> {
                StoredResponse replay = findReplay(principal, key, hash);
                if (replay != null) return replay;
                String body = toJson(action.get());
                // 最后写幂等记录：并发同键时由主键裁决，失败方整笔回滚（包括已执行的业务写入）。
                try {
                    jdbc.update("insert into idempotency_record (principal, idempotency_key, operation, request_hash, response_status, response_body)"
                        + " values (?, ?, ?, ?, ?, ?)", principal, key, operation, hash, successStatus, body);
                } catch (DuplicateKeyException e) {
                    // 只把幂等记录的主键冲突视为并发同键；业务语句自身的唯一约束冲突不在此处理，避免误判。
                    throw new ConcurrentKeyUse(e);
                }
                return new StoredResponse(successStatus, body, false);
            });
        } catch (ConcurrentKeyUse race) {
            // 并发竞争失败：胜者已提交，读取其记录重放；读不到只可能是记录恰好被清理，提示重试而非 500。
            StoredResponse replay = tx.execute(status -> findReplay(principal, key, hash));
            if (replay == null) throw new ProblemException(ProblemType.IDEMPOTENCY_KEY_CONFLICT, "幂等键正在被并发使用，请稍后重试");
            return replay;
        }
    }

    /** 并发同键的内部信号：抛出后事务回滚，由外层读取胜者记录重放。 */
    private static final class ConcurrentKeyUse extends RuntimeException {
        ConcurrentKeyUse(Throwable cause) { super(cause); }
    }

    /** 查询幂等记录；摘要不一致说明客户端复用了键却改了请求内容，按 422 拒绝而不是静默返回旧结果。 */
    private StoredResponse findReplay(String principal, String key, String hash) {
        List<StoredResponse> rows = jdbc.query(
            "select request_hash, response_status, response_body from idempotency_record where principal = ? and idempotency_key = ?",
            (rs, n) -> {
                if (!hash.equals(rs.getString(1)))
                    throw new ProblemException(ProblemType.IDEMPOTENCY_KEY_CONFLICT, "幂等键已用于不同的请求内容");
                return new StoredResponse(rs.getInt(2), rs.getString(3), true);
            }, principal, key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 请求摘要：操作名与规范化 JSON 以不可见分隔符拼接后做 SHA-256。 */
    String hash(String operation, Object input) {
        try {
            String canonical = operation + '\u001f' + canonicalMapper.writeValueAsString(input);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("无法计算请求摘要", e);
        }
    }

    /** 响应体使用应用统一的 ObjectMapper 序列化，首次响应与重放响应因此逐字节一致。 */
    private String toJson(Object body) {
        try { return responseMapper.writeValueAsString(body); }
        catch (JsonProcessingException e) { throw new IllegalStateException("响应序列化失败", e); }
    }
}
