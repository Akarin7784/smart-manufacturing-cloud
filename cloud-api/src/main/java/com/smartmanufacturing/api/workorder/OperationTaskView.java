package com.smartmanufacturing.api.workorder;
import java.util.List;
/** 工序任务视图：下达时由工艺路线快照生成，首道工序为 READY，其余为 PENDING。 */
public record OperationTaskView(String opNo, String name, String workCenterCode, String status,
                                boolean reportPoint, boolean firstArticleRequired, List<String> inspectionPointCodes) {}
