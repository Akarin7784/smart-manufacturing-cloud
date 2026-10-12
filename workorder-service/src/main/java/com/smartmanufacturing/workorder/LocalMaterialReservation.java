package com.smartmanufacturing.workorder;
import com.smartmanufacturing.api.workorder.ReservationLineView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 库存服务就绪前的本地替身适配器（manufacturing.workorder.material-reservation=local，缺省）。
 * 只模拟契约的“成功路径”：总是预留成功、预留单号由幂等键确定性派生（满足幂等），到期时间模拟库存返回的
 * now + 15 分钟。它不检查库存、不产生过期事件——过期判定权在库存服务，这里不做任何自算过期逻辑。
 * 接入库存后新增 havingValue = "inventory" 的 Feign 适配器并切换配置即可，调用方无需改动。
 */
@Component
@ConditionalOnProperty(name = "manufacturing.workorder.material-reservation", havingValue = "local", matchIfMissing = true)
public class LocalMaterialReservation implements MaterialReservationPort {
    /** 与库存服务约定的 TTL，仅用于模拟其响应中的到期时间。 */
    static final Duration TTL = Duration.ofMinutes(15);
    private final Clock clock;
    LocalMaterialReservation(Clock clock) { this.clock = clock; }

    @Override public Reservation reserve(String key, String orderId, List<ReservationLineView> lines) {
        String id = "LR-" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
        return new Reservation(id, clock.instant().plus(TTL), List.copyOf(lines));
    }
    @Override public Instant renew(String key, String reservationId) { return clock.instant().plus(TTL); }
    @Override public void release(String key, String reservationId) { }
}
