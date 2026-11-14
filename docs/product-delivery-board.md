# Hop Modern Web · 产品真实能力与交付验收

> 2026-11-14 基线；下列“已验证”只表示此日期在腾讯云测试环境有明确证据，不能等同于多租户/生产验收。项目架构以 `cxhttt233/hop` 的 `docs/web-modern-architecture-plan.md` 为唯一依据。

## 目标用户路径

选择/创建真实 `.hpl` 或 `.hwf` → 管理 DatabaseMeta 连接 → 添加/配置 Transform 或 Action → 保存并重开 → 经授权运行、查看日志及执行结果。

| 阶段 | 目前实际能力与证据 | 仍然缺失或未验收 |
|---|---|---|
| 文件/工程 | Pipeline/Workflow 文件目录 API、路径搜索、隔离工作区；现有文件打开，`.hpl` 原子保存 | 新建真实文件及工程管理的浏览器完整流程仍需逐步确认 |
| 数据库连接 | TableInput 可以保存连接引用名称；#132 隔离 Java API 已验证认证拒绝和无密码草稿 CRUD（仍 Draft，非主线）；[ #138 ](https://github.com/cxhttt233/hop-modern-web/pull/138) 证明原生 PostgreSQL DatabaseMeta 插件 + MemoryMetadataProvider 内存往返 | **未实现项目级原生 metadata/rdbms 落盘、浏览器管理和跨 Transform 复用；当前测试运行时默认缺 PostgreSQL 方言与 JDBC 运行依赖；无生产会话权限体系** |
| Pipeline/Transform | 真实 .hpl 编辑保存重开、SelectValues 三列表、TableInput 原生字段表；[#137](https://github.com/cxhttt233/hop-modern-web/pull/137) 原生 FilterRows 单条件比较与 True/False 目标表单经浏览器、config/write、.hpl 保存重开及 T5 主线重复验证 | **FilterRows AND/OR 组合与常量值、TableOutput/SortRows 完整配置**尚待验证；复杂过滤条件保留高级 JSON 回退，不应误称已全支持 |
| Workflow | .hwf 原生开/改/保存与连接操作、[#125](https://github.com/cxhttt233/hop-modern-web/pull/125) 失败后重试；[#133](https://github.com/cxhttt233/hop-modern-web/pull/133) Action 重命名已在隔离 JVM 和共享后端真实浏览器验证保存重开，原有 Hop 引用保持 | Action 新增、删除、复杂参数、完整拖动和执行流程尚未交付 |
| React 交互 | #124 取消不落盘，#129 安全列表筛选与源索引，[#134](https://github.com/cxhttt233/hop-modern-web/pull/134) 数字草稿/取消，[#136](https://github.com/cxhttt233/hop-modern-web/pull/136) 中文 IME 组合/取消都已通过真实 .hpl 浏览器保存重开；T5 集成测试复跑 #136 成功 | 尚未全面验证未知原生枚举值保留、复杂 JSON/SQL 编辑和异常恢复 |
| 运行/结果 | 现有 Hop 核心拥有执行能力，但不等于当前 SPA 已安全接通 | 前端授权运行、实时日志、指标、错误追踪、结果查看和多用户权限体系缺少完整闭环 |

## 当前优先级

1. **T1：连接元数据链路**。使用已经安装并独立验证的 Apache Hop 2.20.0 PostgreSQL 方言及 `DatabaseMeta`，实现带访问控制的项目级持久化、浏览器创建/重开/复用；现有 #132 只能视为草稿起点。
2. **T2：Workflow Action 链路**。在可保存的真实 .hwf 中新增/删除一个 Action，保留完整连接关系，并证明重新打开不丢失。
3. **T3：扩展已可用的 FilterRows 单条件**。安全实现原生 Condition AND/OR、常量比较，或转向下一个真实缺口类型；不能破坏复杂 JSON 的默认保留行为。
4. **T4：原生未知枚举与复杂表单**。数值草稿和中文 IME 已测试集成，继续审查旧未知值 Draft、从最新 main 构造能复现的错误并修复。
5. **T5：贯通与集成**。优先审查有完整测试证据的分支，确认主线在五槽位中都可使用，持续压缩重复 Draft，而不是为小修堆积 PR。

## 可重复验证

- 内网每槽位 `http://127.0.0.1:4196/t1/` 到 `/t5/`（共享后端按槽位隔离测试文件）；公网 HTTPS 8093 有 Basic Auth。不要绕过测试桥安全边界。
- `node /home/ubuntu/hmw-agent-lab/bin/smoke-product.mjs tN`：真实 Chromium 验证页面、文件列表、原生字段表、Workflow 入口；**不是持久化测试**。
- `node e2e/t4-cancel-no-pollution.mjs`：取消时不会污染保存文件；`node e2e/t4-native-filter-roundtrip.mjs`：用一次性 .hpl 验证 A/B/C 筛选后编辑/删除、两轮磁盘写入重开与源索引安全。
- 真实 `.hpl` 浏览器专项用例：`e2e/t3-filterrows-atomic-roundtrip.mjs`、`e2e/t4-ime-cancel-no-pollution.mjs`、`e2e/t4-number-draft-roundtrip.mjs`；默认只修改 UUID 隔离测试副本，已在 T5 的集成 main 上复测前两者成功。
- Java API 分支隔离测试 `/home/ubuntu/hmw-agent-lab/bin/with-isolated-java-branch <branch> <command>`；T1 native DatabaseMeta 插件注册/内存往返 `/home/ubuntu/hmw-agent-lab/bin/check-native-database-meta`，其 2.20.0 PostgreSQL 方言 JAR 在独立 vendor 实验目录，非共享 8080 的已安装插件。
- Java 分支编译 `/home/ubuntu/hmw-agent-lab/bin/check-java-branch <branch>`；不部署、不运行 JUnit；共享 Java 后端仅在服务端 Java 源码树变化时才更新，非 Java 提交不会无意义重启。
- 2026-11-14 手工验证 `.hwf` 模拟 500 首次磁盘写失败 → 显示未保存 → 重试成功 → 重开内容一致；PR #125 合并。
- 一切新 PR 需明确：**改动前失败操作** → **新行为** → **运行或保存重开结果** → **安全/回归限制**；缺专项测试的保持 Draft，而不是只凭页面打开和打包成功合并。

## 历史误区和清理

- 早期文档所述“Workflow 仍是未合并只读预览”已经过时；当前 `.hwf` 编辑链路真实存在，但不代表 Action 管理完整。
- [#123](https://github.com/cxhttt233/hop-modern-web/pull/123) 未合入：现有 `readNativeWireList` 已将缺失/空原生 LIST 规范为 `[]`，且当前 main 的 TableInput 新增/保存/重开实测可用，故该 PR 无新增能力。
- 旧 [#93](https://github.com/cxhttt233/hop-modern-web/pull/93)、[#94](https://github.com/cxhttt233/hop-modern-web/pull/94)、[#98](https://github.com/cxhttt233/hop-modern-web/pull/98) 字段搜索 Draft 被新版并有磁盘回归的 #129 覆盖，已关闭，避免重复开发。
- 部署基础设施 [#126](https://github.com/cxhttt233/hop-modern-web/pull/126)、[#127](https://github.com/cxhttt233/hop-modern-web/pull/127)、[#128](https://github.com/cxhttt233/hop-modern-web/pull/128) 和 Java 编译帮助 [#130](https://github.com/cxhttt233/hop-modern-web/pull/130) 已入主线。它们解决工具障碍，不代表 ETL 业务链路已全部实现。
