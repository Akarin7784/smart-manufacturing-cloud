package com.smartmanufacturing.quality;

import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试用可推进时钟：默认跟随系统时间，{@link #advance(Duration)} 叠加偏移，用于验证暂存冲销 TTL 过期。
 * {@link #pin(Instant)} 可把时钟固定在指定时刻（可含亚毫秒），让依赖时间精度的用例与执行速度无关（#46）；{@link #reset()} 解除固定。
 * 放在测试源码同一包下被组件扫描，取代 ClockConfiguration 中的系统时钟（ConditionalOnMissingBean）。
 */
@Component
public class MutableClock extends Clock {
    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);
    /** 固定的基准时刻；为 null 时跟随系统时间。 */
    private final AtomicReference<Instant> pinned = new AtomicReference<>();

    /** 把时钟固定在指定时刻（之后 advance 仍在此基础上叠加偏移）。 */
    public void pin(Instant at) { pinned.set(at); offset.set(Duration.ZERO); }

    /** 时钟前进指定时长。 */
    public void advance(Duration d) { offset.updateAndGet(o -> o.plus(d)); }
    /** 恢复为系统时间。 */
    public void reset() { offset.set(Duration.ZERO); pinned.set(null); }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() {
        Instant base = pinned.get();
        return (base == null ? Instant.now() : base).plus(offset.get());
    }
}
