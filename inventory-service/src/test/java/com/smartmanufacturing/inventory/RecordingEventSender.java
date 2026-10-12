package com.smartmanufacturing.inventory;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用事件发送替身：记录每次发送，并可注入前 N 次失败，用于验证 outbox 的重试与 eventId 不变。
 * 放在测试源码的同一包下，被组件扫描后取代 RocketMQ 发送器（MessagingConfiguration 对 EventSender 使用 ConditionalOnMissingBean）。
 */
@Component
public class RecordingEventSender implements EventSender {
    /** 一次发送记录。 */
    public record Sent(String tag, String key, String eventId, String payload) {}

    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresLeft = new AtomicInteger();
    /** 发送尝试次数（含失败），用于断言 outbox 首次失败即结束本批。 */
    private final AtomicInteger attempts = new AtomicInteger();

    @Override
    public void send(String tag, String key, String eventId, String payload) {
        attempts.incrementAndGet();
        if (failuresLeft.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) throw new IllegalStateException("模拟 Broker 不可用");
        sent.add(new Sent(tag, key, eventId, payload));
    }

    public List<Sent> sent() { return sent; }
    /** 让接下来的 n 次发送失败。 */
    public void failNext(int n) { failuresLeft.set(n); }
    public int attempts() { return attempts.get(); }
    public void clear() { sent.clear(); failuresLeft.set(0); attempts.set(0); }
}
