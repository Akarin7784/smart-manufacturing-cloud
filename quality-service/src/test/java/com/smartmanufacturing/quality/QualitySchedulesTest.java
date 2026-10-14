package com.smartmanufacturing.quality;

import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

/** 调度器只负责转调：各任务方法被调用，返回 0 与非 0 两种情况都只记日志。 */
class QualitySchedulesTest {
    private final PendingReversalExpiryJob expiry = mock(PendingReversalExpiryJob.class);
    private final OutboxPublisher publisher = mock(OutboxPublisher.class);
    private final DedupTableCleaner cleaner = mock(DedupTableCleaner.class);
    private final WritebackWorker worker = mock(WritebackWorker.class);
    private final WritebackService writebacks = mock(WritebackService.class);
    private final QualitySchedules schedules = new QualitySchedules(expiry, publisher, cleaner, worker, writebacks);

    @Test void purgeDedupCleansDedupTablesAndTerminalWritebacks() {
        when(cleaner.purgeExpired()).thenReturn(3, 0);
        when(writebacks.purgeTerminal(1000, 100)).thenReturn(2, 0);
        schedules.purgeDedup();
        schedules.purgeDedup();
        verify(cleaner, times(2)).purgeExpired();
        verify(writebacks, times(2)).purgeTerminal(1000, 100);
    }

    @Test void writebackRunsOneBatch() {
        schedules.writeback();
        verify(worker).runBatch();
    }

    @Test void expireAndPublishDelegate() {
        when(expiry.expireDue()).thenReturn(1, 0);
        schedules.expire();
        schedules.expire();
        verify(expiry, times(2)).expireDue();
        schedules.publish();
        verify(publisher).publishBatch();
    }
}
