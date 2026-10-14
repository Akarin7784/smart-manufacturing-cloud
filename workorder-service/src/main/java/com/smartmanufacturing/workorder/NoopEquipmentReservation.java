package com.smartmanufacturing.workorder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * M1 占位实现：不占用任何设备、总是成功，只打调试日志（reservation=NONE），保证 P2 替换实现时调用点不变。
 * 仅此一个实现，因此不需要条件装配；P2 引入真实实现时再按属性切换。
 */
@Component
class NoopEquipmentReservation implements EquipmentReservationPort {
    private static final Logger LOG = LoggerFactory.getLogger(NoopEquipmentReservation.class);
    @Override public void reserve(String workOrderNo, String operationNo, String stationCode, String key) {
        LOG.debug("设备预留占位：工单 {} 工序 {} 工位 {} reservation=NONE", workOrderNo, operationNo, stationCode);
    }
    @Override public void release(String workOrderNo, String operationNo, String stationCode, String key) {
        LOG.debug("设备释放占位：工单 {} 工序 {} 工位 {} reservation=NONE", workOrderNo, operationNo, stationCode);
    }
}
