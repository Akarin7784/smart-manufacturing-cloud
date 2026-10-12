package com.smartmanufacturing.quality;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;

/**
 * 坏消息留档（#30）：记录原始 msgId、tag、错误原因与载荷摘要（SHA-256、字节数、前 512 个字符），供人工排查后到工单侧补发。
 * 单条 INSERT 自动提交，与业务事务无关；写入失败（如数据库不可用）由调用方返回 RECONSUME_LATER，消息不会丢。
 */
@Component
public class InvalidEventRecorder {
    /** 摘录长度上限，与 invalid_event.payload_excerpt 列宽一致。 */
    static final int EXCERPT_CHARS = 512;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public InvalidEventRecorder(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    /** 记录一条坏消息。 */
    public void record(String msgId, String tag, byte[] payload, String reason) {
        byte[] body = payload == null ? new byte[0] : payload;
        String text = new String(body, StandardCharsets.UTF_8);
        jdbc.update("insert into invalid_event (msg_id, tag, reason, payload_sha256, payload_excerpt, payload_bytes, received_at)"
                + " values (?, ?, ?, ?, ?, ?, ?)",
            truncate(msgId == null ? "" : msgId, 128), truncate(tag, 64), truncate(reason == null ? "未知原因" : reason, 512),
            sha256(body), truncate(text, EXCERPT_CHARS), body.length, Timestamp.from(clock.instant()));
    }

    private static String truncate(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
