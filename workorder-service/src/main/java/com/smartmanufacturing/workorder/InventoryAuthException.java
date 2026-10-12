package com.smartmanufacturing.workorder;

/**
 * 库存拒绝了本服务的服务令牌：401（令牌无效 / 过期 / aud 不符）或 403（scope 不足，或续约的不是本服务持有的预留）。
 * 属于配置或身份问题，调用方可以重试（令牌会重新申请），但必须告警（日志 ERROR + mes_workorder_inventory_auth_failure_total）。
 * 对同步接口按库存不可用（503）返回，终端用户无法自行处理。
 */
public class InventoryAuthException extends InventoryUnavailableException {
    private final int httpStatus;
    public InventoryAuthException(int httpStatus, String detail) {
        super("库存拒绝服务令牌（HTTP " + httpStatus + "）：" + detail, null);
        this.httpStatus = httpStatus;
    }
    /** 库存返回的 HTTP 状态（401 或 403）。 */
    public int httpStatus() { return httpStatus; }
}
