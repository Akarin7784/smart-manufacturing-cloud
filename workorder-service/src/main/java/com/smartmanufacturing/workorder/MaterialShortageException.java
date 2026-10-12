package com.smartmanufacturing.workorder;
import com.smartmanufacturing.api.workorder.MaterialShortage;
import com.smartmanufacturing.common.BusinessException;
import org.springframework.http.HttpStatus;
import java.util.List;

/** 库存预留 409：整单未预留，details 为库存返回的逐行缺料明细，原样放入响应 data。 */
public class MaterialShortageException extends BusinessException {
    public MaterialShortageException(List<MaterialShortage> shortages) {
        super("MATERIAL_SHORTAGE", "物料不足，工单未下达", HttpStatus.CONFLICT, List.copyOf(shortages));
    }
    @SuppressWarnings("unchecked")
    public List<MaterialShortage> shortages() { return (List<MaterialShortage>) details(); }
}
