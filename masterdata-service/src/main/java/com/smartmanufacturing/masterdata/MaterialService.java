package com.smartmanufacturing.masterdata;
import com.smartmanufacturing.api.masterdata.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** 物料应用服务：创建、查询与乐观锁更新。物料编码是全系统业务键，创建后不可改。 */
@Service
public class MaterialService {
    private final MaterialRepository materials;
    private final MasterdataReferences references;
    MaterialService(MaterialRepository materials, MasterdataReferences references) { this.materials = materials; this.references = references; }

    /** 按编码查询，不存在返回 404。 */
    @Transactional(readOnly = true)
    public Material get(String code) {
        return materials.find(code).orElseThrow(() -> Errors.notFound("MATERIAL_NOT_FOUND", "物料不存在: " + code));
    }
    @Transactional(readOnly = true)
    public List<Material> list(MaterialType type, int page, int size) { return materials.list(type, page * size, size); }

    /** 新建物料；依赖主键唯一约束裁决重复编码（并发创建同编码时只有一个成功）。 */
    @Transactional
    public Material create(CreateMaterial request) {
        try {
            materials.insert(request);
        } catch (DuplicateKeyException e) {
            throw Errors.conflict("MATERIAL_EXISTS", "物料编码已存在: " + request.code());
        }
        return get(request.code());
    }

    /**
     * 更新物料；版本不匹配时区分“不存在”与“并发修改”，便于客户端决定刷新还是报错。
     * 停用（active 由 true 改为 false，PR2）前检查引用：仍被未作废的 BOM（作为成品或组件）引用返回 409 MASTERDATA_IN_USE。
     */
    @Transactional
    public Material update(String code, UpdateMaterial request) {
        if (!request.active() && get(code).active()) references.requireMaterialUnused(code);
        if (materials.update(code, request) == 0) {
            get(code);
            throw Errors.conflict("MATERIAL_VERSION_CONFLICT", "物料已被他人修改，请刷新后重试");
        }
        return get(code);
    }
}
