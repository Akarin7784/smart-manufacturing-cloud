package com.smartmanufacturing.workorder.messaging;

import java.time.Instant;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

/** 后台发送已提交的发件箱事件；允许至少一次投递，由消费端持久化去重。 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "messaging.rocketmq.enabled", havingValue = "true")
public class OutboxDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxDispatcher.class);
    private final OutboxStore store;
    private final EventPublisher publisher;
    public OutboxDispatcher(OutboxStore store, EventPublisher publisher) { this.store = store; this.publisher = publisher; }

    /** 逐条抢占并发送；单条发送失败不阻塞其他事件，下次调度按持久化状态重试。 */
    @Scheduled(fixedDelayString = "${messaging.rocketmq.poll-interval-ms:2000}")
    public void dispatch() {
        for (String id : store.dueIds(Instant.now())) {
            var claimed = store.claim(id, Instant.now());
            if (claimed.isEmpty()) continue;
            OutboxStore.Claim claim = claimed.get();
            try {
                publisher.publish(claim.eventId(), claim.payload());
                store.sent(claim, Instant.now());
            } catch (Exception e) {
                store.failed(claim, Instant.now(), e);
                LOG.warn("Event {} send failed on attempt {}", id, claim.attempts(), e);
            }
        }
    }
}
