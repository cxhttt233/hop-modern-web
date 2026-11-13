# ACTIVE — 当前开发入口（2026-11-13）

**只读本页 + 自己正在改的源文件 + 必要的最新 CI 失败日志。** 不按惯性阅读 GitHub Issues #1–#7、Issue #5 的 112 条历史评论、历史 PR 讨论、旧安全阻断清单、几十页 Actions、旧 `docs/implementation-status.md`。它们是归档，不是每轮输入。遇到**正在修改的具体操作**确有权限/阻断/契约争议时，才搜索该操作对应的少量原始记录，不对全部历史做预防性巡检。

## 北极星
让用户从网页完成：**文件选择/新建 → 数据库连接 → 添加 Transform → 好读的中文表单配置 → 保存/磁盘重开 → 在已授权情况下运行并查看结果**。当前按此优先：
1. **配置表单体验**：TableInput、SelectValues、SortRows、TableOutput、FilterRows 等；基本设置、字段表、进阶选项，中文字段、合适的输入控件、保存/取消。
2. **更多真实可配置 Transform**：有 Hop 元数据/字段/默认值，UI 可操作，真实 `.hpl` 持久化通过。不把 Palette 20 项等同真实覆盖。
3. **核心产品功能**：工作区文件管理、Database Connection 管理，接着有授权的执行/结果和 Workflow。

## 当前事实
- 仓库：`cxhttt233/hop-modern-web`。**唯一集成实验主线**：`integration/product-mainline-20261112`；`main` 与生产不等于 staging。任何 SHA/CI 以现场最新 HEAD 为准。
- staging 已有真实 Hop React Flow 编辑与保存重开、SQL Monaco、TableInput 原生字段表、SelectValues 三组原生表、LIST 路径防重叠，以及 V2–V9 浏览器回归。**请直接看源文件，不凭旧 worker branch 判断“尚未做”。**
- 仍缺完整文件管理/新建/另存为；真实项目 DatabaseMeta 列举/管理；更多 Transform 完整表单；有授权的运行。旧 `DatabaseConnectionPanel` 仅 UI 原型，不是真实数据库连接 CRUD。
- 旧执行接口存在匿名调用/CSRF/跨用户权限问题，Workflow 旧分支有 queued startTime 红测试与终态容量问题。**它们只阻止不安全功能发布，不阻止其他开发。**

## 五个开发者（每小时持续执行）
| 任务 | 唯一自有代码分支 | 本轮首先解决的用户障碍 |
|---|---|---|
| T1 | `integration/execution-r1` | 文件/项目元数据/数据库连接服务端真实能力及必要访问边界 |
| T2 | `work/task-2-workflow-lifecycle-r3` | Workflow `.hwf` 可编辑模型、动作/连线、序列化/重开；执行旧红测不隐藏 |
| T3 | `work/task-3-transform-coverage-r2` | Batch A/B 下一种真实 Transform 的原生配置和 `.hpl` 兼容性 |
| T4 | `work/task-4-transform-palette-r1` | 将真实原生字段表做成好读、好操作的中文 Transform 配置 UI |
| T5 | `integration/product-mainline-20261112` | 集成真实用户旅程/文件+连接入口，浏览器验收并维护本页 |

## 每轮操作协议（控制上下文）
1. 选**一个真实用户障碍**。读取本页、涉及的**最多 2–5 个生产文件**、当前分支/最新 CI 的**必要摘要**。无需批量遍历 Issues/PR 评论/Actions；之前没变化的状态不重查。
2. 直接做代码→相应测试→提交；无权限的执行/跨用户文件/连接凭据不要暴露。被平台明确拒绝过的**同一操作**不可换入口重试；没有命中该操作时**不主动去读或枚举历史拒绝清单**，专注独立开发。
3. 交接仅输出：**用户场景—变更 commit—实际测试—下一步**。跨模块有明确 API 依赖时才联系 T5；不要对 Issue #5 每轮追加长篇历史状态。
4. 真实测试失败要修，不能删除/跳过正确性或安全负例伪造绿灯。普通开发/非特权隔离 staging 无需额外人工 APPROVED；涉及执行、用户文件、凭据/跨用户权限时先验证身份与隔离后再开放。

**深入参考（按需，不每轮读取）**：[`docs/product-delivery-board.md`](product-delivery-board.md)（详细功能矩阵）、[`docs/architecture.md`](architecture.md)（产品边界）；唯一技术架构 `cxhttt233/hop@design/web-modern-architecture:docs/web-modern-architecture-plan.md` blob `d29641632498bf560564c789b253dc39b91c16cd`。
