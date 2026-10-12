#!/usr/bin/env python3
"""生成 Grafana 概览看板 JSON（infra/observability/grafana/dashboards/smc-overview.json）。

设计原因：看板 JSON 冗长且不能写注释，手工编辑容易出错、评审困难；用本脚本以“面板 = 标题 + PromQL + 说明”
的紧凑形式维护，修改后重新运行即可，生成结果同样提交到仓库供 Grafana provisioning 直接加载。
用法：python3 infra/observability/grafana/generate-dashboard.py
"""
import json
import pathlib

DS = {"type": "prometheus", "uid": "prometheus"}  # 与 provisioning 数据源的固定 uid 一致
APP = 'application=~"$application"'  # 所有查询都按看板变量过滤服务


def ts(pid, title, exprs, desc, x, y, w=12, h=8, unit="short", legend="{{application}}"):
    """时间序列面板；exprs 为 (PromQL, 图例) 列表。"""
    return {
        "id": pid, "type": "timeseries", "title": title, "description": desc, "datasource": DS,
        "gridPos": {"x": x, "y": y, "w": w, "h": h},
        "fieldConfig": {"defaults": {"unit": unit}, "overrides": []},
        "options": {"legend": {"displayMode": "table", "placement": "bottom", "calcs": ["lastNotNull", "max"]},
                    "tooltip": {"mode": "multi"}},
        "targets": [{"refId": chr(65 + i), "datasource": DS, "expr": e, "legendFormat": l or legend}
                    for i, (e, l) in enumerate(exprs)],
    }


def row(pid, title, y):
    """分组行，便于按“概览 / HTTP / JVM / 业务”折叠浏览。"""
    return {"id": pid, "type": "row", "title": title, "collapsed": False, "gridPos": {"x": 0, "y": y, "w": 24, "h": 1}, "panels": []}


panels = [
    row(1, "服务概览", 0),
    {
        "id": 2, "type": "stat", "title": "服务存活（up）", "datasource": DS,
        "description": "Prometheus 能否抓取各服务独立管理端口；0 表示服务不可用（对应告警 ServiceDown）。",
        "gridPos": {"x": 0, "y": 1, "w": 24, "h": 4},
        "fieldConfig": {"defaults": {"mappings": [{"type": "value", "options": {
            "0": {"text": "DOWN", "color": "red"}, "1": {"text": "UP", "color": "green"}}}],
            "thresholds": {"mode": "absolute", "steps": [{"color": "red", "value": None}, {"color": "green", "value": 1}]}},
            "overrides": []},
        "options": {"colorMode": "background", "graphMode": "none", "reduceOptions": {"calcs": ["lastNotNull"]}},
        "targets": [{"refId": "A", "datasource": DS, "expr": 'up{job="spring-boot"}', "legendFormat": "{{instance}}"}],
    },
    row(10, "HTTP", 5),
    ts(11, "请求速率（req/s）", [(f'sum by (application) (rate(http_server_requests_seconds_count{{{APP}}}[$__rate_interval]))', None)],
       "各服务入站 HTTP 请求速率（含网关转发前后的两次计数）。", 0, 6, unit="reqps"),
    ts(12, "5xx 比例", [(f'sum by (application) (rate(http_server_requests_seconds_count{{{APP},outcome="SERVER_ERROR"}}[$__rate_interval])) / sum by (application) (rate(http_server_requests_seconds_count{{{APP}}}[$__rate_interval]))', None)],
       "5xx 请求占比；告警 HighHttp5xxRatio 的阈值为 5 分钟内超过 5%。", 12, 6, unit="percentunit"),
    ts(13, "P95 延迟", [(f'histogram_quantile(0.95, sum by (application, le) (rate(http_server_requests_seconds_bucket{{{APP}}}[$__rate_interval])))', None)],
       "基于直方图桶计算的 P95 响应时间（服务端开启了 percentiles-histogram）。", 0, 14, unit="s"),
    ts(14, "4xx 速率（按状态码）", [(f'sum by (application, status) (rate(http_server_requests_seconds_count{{{APP},outcome="CLIENT_ERROR"}}[$__rate_interval]))', "{{application}} {{status}}")],
       "401/403/409/422 等客户端错误速率，可观察越权、业务冲突与校验失败。", 12, 14, unit="reqps"),
    row(20, "JVM", 22),
    ts(21, "堆使用率", [(f'sum by (application) (jvm_memory_used_bytes{{{APP},area="heap"}}) / sum by (application) (jvm_memory_max_bytes{{{APP},area="heap"}} > 0)', None)],
       "已用堆 / 最大堆；告警 JvmHeapUsageHigh 的阈值为持续 5 分钟超过 90%。", 0, 23, unit="percentunit"),
    ts(22, "GC 暂停时间（s/s）", [(f'sum by (application) (rate(jvm_gc_pause_seconds_sum{{{APP}}}[$__rate_interval]))', None)],
       "每秒花在 GC 暂停上的时间。", 12, 23, unit="s"),
    ts(23, "活动线程数", [(f'sum by (application) (jvm_threads_live_threads{{{APP}}})', None)],
       "JVM 存活线程数。", 0, 31, unit="short"),
    ts(24, "Hikari 连接池（活动 / 等待）", [
        (f'sum by (application) (hikaricp_connections_active{{{APP}}})', "{{application}} 活动"),
        (f'sum by (application) (hikaricp_connections_pending{{{APP}}})', "{{application}} 等待")],
       "数据库连接池占用；等待数持续大于 0 说明连接池或数据库成为瓶颈（网关无数据源，不显示）。", 12, 31, unit="short"),
    row(30, "关键业务接口（基于 http.server.requests 按 URI 模板统计）", 39),
    # 说明：仓库目前没有自定义业务计数器/计时器（Micrometer Counter/Timer），业务面板复用 Spring 自动埋点的
    # http.server.requests，按 URI 模板与方法筛选关键业务操作；后续新增业务指标后再替换为专用面板。
    ts(31, "工单创建 / 下达速率（按状态码）", [
        ('sum by (status) (rate(http_server_requests_seconds_count{application="workorder-service",method="POST",uri="/workorders"}[$__rate_interval]))', "创建 {{status}}"),
        ('sum by (status) (rate(http_server_requests_seconds_count{application="workorder-service",method="POST",uri="/workorders/{id}/release"}[$__rate_interval]))', "下达 {{status}}")],
       "工单服务创建与下达接口的调用速率，按状态码区分成功与业务拒绝（409/422）。", 0, 40, unit="reqps"),
    ts(32, "工单下达 P95 耗时", [
        ('histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{application="workorder-service",method="POST",uri="/workorders/{id}/release"}[$__rate_interval])))', "下达 P95"),
        ('histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{application="workorder-service",method="POST",uri="/workorders"}[$__rate_interval])))', "创建 P95")],
       "下达涉及主数据展开与物料预留，是最重的业务操作，单独关注其延迟。", 12, 40, unit="s"),
    ts(33, "登录结果速率", [
        ('sum by (status) (rate(http_server_requests_seconds_count{application="auth-service",method="POST",uri="/auth/login"}[$__rate_interval]))', "登录 {{status}}"),
        ('sum by (status) (rate(http_server_requests_seconds_count{application="auth-service",method="POST",uri="/auth/oauth2/token"}[$__rate_interval]))', "服务令牌 {{status}}")],
       "用户登录与服务令牌（client_credentials）签发速率；401 激增可能是暴力尝试或客户端密钥失配。", 0, 48, unit="reqps"),
    ts(34, "BOM / 工艺路线发布速率", [
        ('sum by (uri, status) (rate(http_server_requests_seconds_count{application="masterdata-service",method="POST",uri=~"/(boms|routings)/{product}/{version}/release"}[$__rate_interval]))', "{{uri}} {{status}}")],
       "主数据版本发布操作速率。", 12, 48, unit="reqps"),
    # 工单预留释放：对应告警 WorkorderReservation*；reservation_gone（410）只在这里观察，不告警。
    row(40, "工单预留释放", 56),
    ts(41, "预留释放结果（次 / 15m）", [
        ('sum by (result) (increase(mes_workorder_reservation_release_total{application="workorder-service"}[15m]))', "{{result}}")],
        "取消工单后释放预留的每次尝试结果；reservation_consumed / reservation_not_found / error 触发 WorkorderReservationReleaseFailed，reservation_gone 与 contract_mismatch 只计数（契约告警走 mes_workorder_inventory_contract_mismatch_total）。", 0, 57),
    ts(42, "待释放任务 / 库存鉴权失败", [
        ('max(mes_workorder_reservation_release_pending{application="workorder-service"})', "待释放任务"),
        ('sum by (operation) (increase(mes_workorder_inventory_auth_failure_total{application="workorder-service"}[5m]))', "鉴权失败 {{operation}}")],
        "待释放任务持续 15 分钟大于 0 触发 WorkorderReservationReleaseBacklog；鉴权失败增量大于 0 触发 WorkorderInventoryAuthFailure。", 12, 57),
]

dashboard = {
    "uid": "smc-overview", "title": "Smart Manufacturing 概览（JVM / HTTP / 业务）",
    "tags": ["smart-manufacturing", "jvm", "http"], "timezone": "browser", "schemaVersion": 39, "version": 1,
    "editable": False, "refresh": "30s", "time": {"from": "now-1h", "to": "now"},
    "templating": {"list": [{
        "name": "application", "label": "服务", "type": "query", "datasource": DS,
        "query": {"query": "label_values(process_uptime_seconds, application)", "refId": "app"},
        "definition": "label_values(process_uptime_seconds, application)",
        "includeAll": True, "multi": True, "allValue": ".*", "current": {"text": "All", "value": "$__all"}, "refresh": 2,
    }]},
    "panels": panels,
}

out = pathlib.Path(__file__).parent / "dashboards" / "smc-overview.json"
out.write_text(json.dumps(dashboard, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"已生成 {out}")
