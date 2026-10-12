package com.smartmanufacturing.workorder;
import com.smartmanufacturing.api.workorder.MaterialShortage;
import com.smartmanufacturing.common.BusinessException;
import org.springframework.http.HttpStatus;
import java.util.List;

/**
 * 用途：表示库存预留返回 409（可用量不足，整单未预留）。由物料预留适配器抛出，下达流程据此不写任何本地数据，
 * 工单保持 CREATED；统一异常处理把 details（库存返回的逐行缺料明细）原样放入响应 data，调用方据此补料后用同一个
 * Idempotency-Key 重试下达。
 */
public class MaterialShortageException extends BusinessException {
    public MaterialShortageException(List<MaterialShortage> shortages) {
        super("MATERIAL_SHORTAGE", "物料不足，工单未下达", HttpStatus.CONFLICT, List.copyOf(shortages));
    }
    @SuppressWarnings("unchecked")
    public List<MaterialShortage> shortages() { return (List<MaterialShortage>) details(); }
}
