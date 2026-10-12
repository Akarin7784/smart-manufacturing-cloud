package com.smartmanufacturing.workorder;
import com.smartmanufacturing.api.workorder.MaterialShortage;
import com.smartmanufacturing.api.workorder.ReservationLineView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 库存服务就绪前的本地替身适配器（manufacturing.workorder.material-reservation=local，缺省）。
 * <p>
 * 缺省只模拟契约的成功路径：预留总是成功，预留单号与行号由幂等键确定性派生（同键得同单），到期时间为库存会返回的
 * now + 15 分钟。它不检查库存，也不产生过期事件——过期由库存服务判定，这里没有任何自行计算过期的逻辑。
 * <p>
 * <b>预留总是成功，不检查真实库存</b>（cloud / demo 环境同样如此，库存服务接入前不能用于验证缺料）。
 * 为验证下达流程的失败分支，替身提供可编程的故障模拟，均为包内可见、仅供同包测试调用（生产适配器没有这些方法）：
 * <ul>
 *   <li>{@link #simulateShortage}：下一次起预留返回 409 缺料明细；</li>
 *   <li>{@link #simulateReleaseFailure}：释放抛异常，用于验证“释放失败不掩盖原始错误，依赖 TTL 回收”；</li>
 *   <li>{@link #calls()}：记录调用（方法:幂等键），用于断言预留 / 释放次数。</li>
 * </ul>
 * 接入库存后新增 havingValue = "inventory" 的 Feign 适配器并切换配置即可，调用方无需改动。
 */
@Component
@ConditionalOnProperty(name = "manufacturing.workorder.material-reservation", havingValue = "local", matchIfMissing = true)
public class LocalMaterialReservation implements MaterialReservationPort {
    /** 与库存服务约定的 TTL，仅用于模拟其响应中的到期时间。 */
    static final Duration TTL = Duration.ofMinutes(15);
    private final Clock clock;
    private final AtomicReference<List<MaterialShortage>> shortage = new AtomicReference<>();
    private volatile boolean releaseFails;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    /** 同键重复预留返回同一结果（模拟库存幂等）。 */
    private final ConcurrentHashMap<String, Reservation> byKey = new ConcurrentHashMap<>();
    LocalMaterialReservation(Clock clock) { this.clock = clock; }

    @Override public Reservation reserve(String key, String orderId, List<ReservationLineView> lines) {
        calls.add("reserve:" + key);
        List<MaterialShortage> s = shortage.get();
        if (s != null) throw new MaterialShortageException(s);
        return byKey.computeIfAbsent(key, k -> {
            String id = "LR-" + uuid(k);
            List<ReservationLineView> withIds = lines.stream()
                .map(l -> new ReservationLineView(id + "-" + l.materialCode(), l.materialCode(), l.quantity(), l.uom())).toList();
            return new Reservation(id, clock.instant().plus(TTL), withIds);
        });
    }
    @Override public Instant renew(String key, String reservationId) { calls.add("renew:" + key); return clock.instant().plus(TTL); }
    @Override public void release(String key, String reservationId) {
        calls.add("release:" + key);
        if (releaseFails) throw new IllegalStateException("模拟：库存释放失败");
    }

    /** 模拟库存 409：传 null 恢复正常。 */
    void simulateShortage(List<MaterialShortage> shortages) { shortage.set(shortages); }
    /** 模拟释放失败。 */
    void simulateReleaseFailure(boolean fail) { releaseFails = fail; }
    /** 已记录的调用，形如 reserve:{key} / release:{key}。 */
    List<String> calls() { return List.copyOf(calls); }
    /** 清空模拟状态与调用记录。 */
    void reset() { shortage.set(null); releaseFails = false; calls.clear(); byKey.clear(); }

    private static String uuid(String s) { return UUID.nameUUIDFromBytes(s.getBytes(StandardCharsets.UTF_8)).toString(); }
}
