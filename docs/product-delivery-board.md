# Hop Modern Web · 产品真实能力与交付验收

> 2026-11-14 夜间按主线复核；下列“已验证”只表示此日期在腾讯云测试环境有明确证据，不能等同于多租户/生产验收。项目架构以 `cxhttt233/hop` 的 `docs/web-modern-architecture-plan.md` 为唯一依据。

## 目标用户路径

选择/创建真实 `.hpl` 或 `.hwf` → 管理 DatabaseMeta 连接 → 添加/配置 Transform 或 Action → 保存并重开 → 经授权运行、查看日志及执行结果。

| 阶段 | 目前实际能力与证据 | 仍然缺失或未验收 |
|---|---|---|
| 文件/工程 | Pipeline/Workflow 文件目录 API、路径搜索、隔离工作区；现有文件打开，`.hpl` 原子保存 | 新建真实文件及工程管理的浏览器完整流程仍需逐步确认 |
| 数据库连接 | #140 已合入：Apache Hop 原生 DatabaseMeta/JsonMetadataProvider 工作区 JSON CRUD，隔离 Java/HTTP 及授权拒绝、symlink 拒绝实测；#144 隔离 PostgreSQL 2.20 加载器已合入，未授权外联数据库 | **React 入口及 per-user/per-slot 可信授权域缺失**；共享 8080 因未配 token 返回 503，预览桥 8181 当前路由 404，不能简单反代或公开 token；尚未完成在浏览器创建、关闭重开、TableInput 引用 |
| Pipeline/Transform | 真实 .hpl 编辑保存重开、SelectValues 三组列表、TableInput 原生字段表；#137 FilterRows 单条件/True/False、#142 取消安全回归、#60 原生空字段列表保存重开的专项测试已合并 | **FilterRows AND/OR/原生常量 CValue**尚待验证；复杂条件仍可退回高级 JSON |
| Workflow | 真实 .hwf 原生开/改/保存与连接操作，#125 保存失败恢复、#133 Action 改名、#141 Action 删除并校验关联 Hop 409 已在浏览器真实 .hwf 保存重开 | **Action 新增、复杂参数配置、授权执行与日志链路**未交付 |
| React 交互 | #124 取消安全、#129 安全筛选、#134 数字草稿、#136 中文 IME 的真实 .hpl 文件往返已验收集成；#143 ENUM 代码只有 Draft | **可见的复杂 JSON/SQL 编辑、恢复**仍需专项；ENUM 必须先有原生 producer-attested 可见列，不能用假的 native wire fixture |
| 运行/结果 | 现有 Hop 核心拥有执行能力，但不等于当前 SPA 已安全接通 | 前端授权运行、实时日志、指标、错误追踪、结果查看和多用户权限体系缺少完整闭环 |

## 当前优先级

1. **T1：连接安全桥接**。以 #140 原生持久化为基础，先实现请求级/槽位级可信 workspace 授权与路由拒绝测试，再接 React 创建/重开/引用；不要暴露服务 token。
2. **T2：Action 新增**。#141 已交付删除，下一步只做新增、合法原生参数初始化、连线及 .hwf 保存重开。
3. **T3：FilterRows 复合条件**。先证明 Condition/CValue 的原生序列化再支持 AND/OR 与常量比较；#60 空列表专项测试已合并。
4. **T4：可见的 SQL/JSON 交互问题**。旧枚举 Draft 收敛至 #143，必须先由 T3 提供真实可见 ENUM producer；优先处理复杂编辑器的输入/取消/错误恢复。
5. **T5：真正的产品链路**。先验收已合主线功能，合并有真实保存重开证据的 PR；防止旧 Draft 和过时文档误导任务方向，长期目标是授权运行/日志。

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
