package com.smartmanufacturing.quality;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 共享 Spring 上下文下的测试隔离：所有 @SpringBootTest 复用同一个 H2 库与事件发送替身，
 * 其他测试类留下的 WAITING 冲销暂存、未投递（或处于退避中）的 outbox 事件会污染按全表统计的断言，
 * 导致结果依赖测试类的执行顺序。每个用例开始前直接清空这些表与替身记录，不使用 @DirtiesContext。
 */
final class QualityTestIsolation {
    private QualityTestIsolation() {}

    static void reset(JdbcTemplate jdbc, RecordingEventSender sender, MutableClock clock) {
        jdbc.update("delete from pending_reversal");
        jdbc.update("delete from outbox_event");
        if (sender != null) sender.clear();
        if (clock != null) clock.reset();
    }
}
