package com.smartmanufacturing.workorder;
import com.smartmanufacturing.common.BusinessException;

/**
 * 库存服务不可用：5xx、连接 / 读取超时、服务发现找不到实例。
 * 同步调用（下达预留、恢复时重新预留）返回 503 inventory-service-unavailable；后台任务（取消释放、续约）据此退避重试。
 */
public class InventoryUnavailableException extends BusinessException {
    public InventoryUnavailableException(String detail, Throwable cause) {
        super(WorkOrderProblemType.INVENTORY_SERVICE_UNAVAILABLE.code(), detail, WorkOrderProblemType.INVENTORY_SERVICE_UNAVAILABLE.status());
        if (cause != null) initCause(cause);
    }
}
