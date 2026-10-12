package com.smartmanufacturing.workorder;
import com.smartmanufacturing.common.BusinessException;

/**
 * 库存返回了按契约不应出现在当前调用中的结果（如 400 validation-error、422 idempotency-key-conflict、
 * 未知 type 或无法解析的响应）。说明本服务请求构造有误或双方契约不一致，按 500 internal-error 返回并记录 ERROR 日志，
 * 不向终端用户暴露库存细节。problemType 为库存返回的 type（可能为 null）。
 */
public class InventoryContractException extends BusinessException {
    private final String problemType;
    public InventoryContractException(String problemType, String detail) {
        super(WorkOrderProblemType.INTERNAL_ERROR.code(), "库存调用失败：" + detail, WorkOrderProblemType.INTERNAL_ERROR.status());
        this.problemType = problemType;
    }
    /** 库存返回的 Problem type。 */
    public String problemType() { return problemType; }
}
