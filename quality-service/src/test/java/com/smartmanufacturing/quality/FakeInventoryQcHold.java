package com.smartmanufacturing.quality;

import org.springframework.stereotype.Component;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 测试用库存 QC-HOLD 替身：记录每次调用；可按幂等键预置结果序列（用完后默认 201 记账）；可挂“执行中”钩子，
 * 在调用库存的瞬间（领取事务已提交、结果尚未回写）执行动作，用于验证“HOLD 执行中被作废”。
 * 放在测试源码同一包下被组件扫描，取代 HTTP 实现（WritebackClientConfiguration 对 InventoryQcHoldClient 使用 ConditionalOnMissingBean）。
 */
@Component
public class FakeInventoryQcHold implements InventoryQcHoldClient {
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Map<String, Deque<Outcome>> scripted = new ConcurrentHashMap<>();
    private final AtomicLong txn = new AtomicLong(1000);
    private volatile boolean ready = true;
    private volatile Consumer<Call> during;

    @Override public boolean ready() { return ready; }

    @Override
    public Outcome call(Call call) {
        calls.add(call);
        Consumer<Call> hook = during;
        if (hook != null) { during = null; hook.accept(call); }
        Deque<Outcome> q = scripted.get(call.idempotencyKey());
        Outcome o = q == null ? null : q.poll();
        return o != null ? o : new Booked(txn.incrementAndGet(), false);
    }

    /** 为某个幂等键预置接下来的结果。 */
    public void script(String key, Outcome... outcomes) {
        scripted.computeIfAbsent(key, k -> new ArrayDeque<>()).addAll(List.of(outcomes));
    }
    /** 下一次调用时（调用库存的瞬间）执行一次 hook。 */
    public void during(Consumer<Call> hook) { this.during = hook; }
    public void ready(boolean ready) { this.ready = ready; }
    /** 某 NCR 的调用记录（按调用顺序）。 */
    public List<Call> callsOf(String ncrNo) { return calls.stream().filter(c -> c.ncrNo().equals(ncrNo)).toList(); }
    public void clear() { calls.clear(); scripted.clear(); during = null; ready = true; }
}
