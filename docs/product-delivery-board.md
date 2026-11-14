# Hop Modern Web · 产品真实能力与交付验收

> 2026-11-14 基线；下列“已验证”只表示此日期在腾讯云测试环境有明确证据，不能等同于多租户/生产验收。项目架构以 `cxhttt233/hop` 的 `docs/web-modern-architecture-plan.md` 为唯一依据。

## 目标用户路径

选择/创建真实 `.hpl` 或 `.hwf` → 管理 DatabaseMeta 连接 → 添加/配置 Transform 或 Action → 保存并重开 → 经授权运行、查看日志及执行结果。

| 阶段 | 目前实际能力与证据 | 仍然缺失或未验收 |
|---|---|---|
| 文件/工程 | Pipeline/Workflow 文件目录 API、路径搜索、隔离工作区；现有文件打开，`.hpl` 原子保存 | 新建真实文件及工程管理的浏览器完整流程仍需逐步确认 |
| 数据库连接 | TableInput 原生配置可以保存连接名称；浏览器存在未挂载的 `DatabaseConnectionPanel.tsx` 草稿 | **缺少 Java 端 DatabaseMeta 连接 REST 和真实管理入口**；连接新建、编辑、保存、测试、跨 Transform 复用尚未走通 |
| Pipeline/Transform | React Flow Pipeline 编辑，SelectValues 原生三列表，TableInput 原生列表，`.hpl` 保存重开；2026-11-14 使用独立 TableInput 文件成功实现空列表新增字段 → config/write → save → reopen | FilterRows/TableOutput/SortRows 等类型是否有完整、正确的原生配置往返需逐项验证，不可把 Schema 存在当成验收完成 |
| Workflow | `.hwf` 原生打开、描述与节点位置编辑、连接创建/启停/删除、保存重开；[#125](https://github.com/cxhttt233/hop-modern-web/pull/125) 验证模拟磁盘写失败后的草稿恢复和重试 | Action 新增、删除、参数编辑、完整拖拽流程与产品级工作流操作尚未完成 |
| React 交互 | [#124](https://github.com/cxhttt233/hop-modern-web/pull/124) 验证取消编辑不落盘；[#129](https://github.com/cxhttt233/hop-modern-web/pull/129) 提供字段筛选、原始行号安全操作和可重复的保存重开端到端测试 | 数值输入中间态、特殊插件枚举、复杂 JSON/SQL 编辑与恢复按真实问题继续验证 |
| 运行/结果 | 现有 Hop 核心拥有执行能力，但不等于当前 SPA 已安全接通 | 前端授权运行、实时日志、指标、错误追踪、结果查看和多用户权限体系缺少完整闭环 |

## 当前优先级

1. **T1：连接元数据链路**。先打通最小但真实的 DatabaseMeta 增删改查和工作区/身份隔离；不把“已有输入框”当作可用连接管理。
2. **T2：Workflow Action 链路**。在可保存的真实 .hwf 中新增/删除一个 Action，保留完整连接关系，并证明重新打开不丢失。
3. **T3：下一种 Transform 真正可用**。先选一份真实 .hpl 和具体不支持的属性，再用 Hop 原生 Meta 写入，重开检查所有值和未知元数据。
4. **T4：针对实际操作中遇到的交互问题**。不再重复做原生列表搜索，优先输入、异常恢复、错误定位等。
5. **T5：贯通与集成**。优先审查有完整测试证据的分支，确认主线在五槽位中都可使用，持续压缩重复 Draft，而不是为小修堆积 PR。

## 可重复验证

- 内网每槽位 `http://127.0.0.1:4196/t1/` 到 `/t5/`（共享后端按槽位隔离测试文件）；公网 HTTPS 8093 有 Basic Auth。不要绕过测试桥安全边界。
- `node /home/ubuntu/hmw-agent-lab/bin/smoke-product.mjs tN`：真实 Chromium 验证页面、文件列表、原生字段表、Workflow 入口；**不是持久化测试**。
- `node e2e/t4-cancel-no-pollution.mjs`：取消时不会污染保存文件；`node e2e/t4-native-filter-roundtrip.mjs`：用一次性 .hpl 验证 A/B/C 筛选后编辑/删除、两轮磁盘写入重开与源索引安全。
- Java 分支编译 `/home/ubuntu/hmw-agent-lab/bin/check-java-branch <branch>`；不部署、不运行 JUnit；共享 Java 后端仅在服务端 Java 源码树变化时才更新，非 Java 提交不会无意义重启。
- 2026-11-14 手工验证 `.hwf` 模拟 500 首次磁盘写失败 → 显示未保存 → 重试成功 → 重开内容一致；PR #125 合并。
- 一切新 PR 需明确：**改动前失败操作** → **新行为** → **运行或保存重开结果** → **安全/回归限制**；缺专项测试的保持 Draft，而不是只凭页面打开和打包成功合并。

## 历史误区和清理

- 早期文档所述“Workflow 仍是未合并只读预览”已经过时；当前 `.hwf` 编辑链路真实存在，但不代表 Action 管理完整。
- [#123](https://github.com/cxhttt233/hop-modern-web/pull/123) 未合入：现有 `readNativeWireList` 已将缺失/空原生 LIST 规范为 `[]`，且当前 main 的 TableInput 新增/保存/重开实测可用，故该 PR 无新增能力。
- 旧 [#93](https://github.com/cxhttt233/hop-modern-web/pull/93)、[#94](https://github.com/cxhttt233/hop-modern-web/pull/94)、[#98](https://github.com/cxhttt233/hop-modern-web/pull/98) 字段搜索 Draft 被新版并有磁盘回归的 #129 覆盖，已关闭，避免重复开发。
- 部署基础设施 [#126](https://github.com/cxhttt233/hop-modern-web/pull/126)、[#127](https://github.com/cxhttt233/hop-modern-web/pull/127)、[#128](https://github.com/cxhttt233/hop-modern-web/pull/128) 和 Java 编译帮助 [#130](https://github.com/cxhttt233/hop-modern-web/pull/130) 已入主线。它们解决工具障碍，不代表 ETL 业务链路已全部实现。
