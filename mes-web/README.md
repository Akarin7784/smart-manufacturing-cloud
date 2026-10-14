# mes-web — 智能制造系统（MES）前端

基于 Vite + Vue 3 + TypeScript + Element Plus + Pinia + Vue Router 的 MES 前端骨架（M0）。

## 已包含
- 登录页：调用 `POST /api/auth/login`（经 Gateway `/api/auth/**` 路由，StripPrefix 后转发到 auth-service `/auth/login`），成功后保存访问令牌（sessionStorage）。
- 主布局：侧边栏 + 顶栏，窄屏（<1024px）自动折叠，按钮/菜单触控高度 ≥48px，适合车间平板。
- 占位页面：工单（`/workorders`）、报工（`/reports`），M1 实现具体功能。
- 请求层（`src/api/http.ts`）：自动附加 `Authorization: Bearer <token>`；解析 `application/problem+json` 错误；401 清除登录态并跳转登录页；403 提示「无权限」。
- 路由守卫：未登录访问受保护页面跳转登录页并携带回跳地址（仅允许站内相对路径）。

## M1 页面
- **工单列表** `/workorders`：`GET /api/workorders`，按状态 / 关键字筛选、前端分页（后端暂无分页参数），新建工单 `POST /api/workorders`（Idempotency-Key）。
- **工单详情** `/workorders/:id`：`/progress` 报工进度、`/release` 下达快照、`/reports` 报工流水（冲销记录与被冲销记录区分显示）；下达 / 冻结 / 解除冻结 / 手工完工。
- **工序任务（派工）面板**：派工 / 改派 / 取消派工 / 开工 / 暂停 / 恢复，调用真实后端接口（`src/api/dispatch.ts`，契约见 `docs/workorder-dispatch-design.md`）。
- **报工页** `/reports`（车间平板）：选工单 → 选工序 → 录入合格 / 不良 / 报废 / 工时（小时，2 位小数）/ 批次 / 备注；批次去首尾空白，留空由后端回退为工单号；每次提交生成 Idempotency-Key，失败重试复用同一个键，提交中防重复点击；problem+json 按 type 显示中文提示，validation-error 字段错误回显到对应输入框。

权限：报工 / 开工 / 暂停 / 恢复需 `execution:report`，派工类需 `execution:dispatch`；兼容期持有 `workorder:write` 亦可。

## 运行
需要 Node.js ≥ 20.19。

```bash
cd mes-web
cp .env.example .env      # 按需修改 VITE_GATEWAY_URL（默认 http://localhost:8080）
npm ci
npm run dev               # 开发服务器，/api 代理到 Gateway
```

| 命令 | 说明 |
|---|---|
| `npm run dev` | 本地开发 |
| `npm run build` | 类型检查 + 生产构建（输出 `dist/`） |
| `npm run lint` | ESLint 检查 |
| `npm test` | Vitest 单测 |
| `npm run test:coverage` | 单测 + 覆盖率（`coverage/lcov.info`，门槛 80%） |

## 约定
- 代码中不得出现账号、密码、令牌等敏感信息；环境差异只通过 `.env`（不入库）注入。
- 新增代码须配中文注释，单测覆盖率不低于 80%。
- 生产部署时由反向代理把 `/api` 转发到 Gateway。
