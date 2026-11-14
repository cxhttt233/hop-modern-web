# Hop Modern Web · 当前开发入口

> 状态核查：2026-11-14 19:15（北京时间）。后续开发请以实时 `main` 和可复现浏览器操作为准，不把本页当作永远有效的完成证明。

**产品目标**：在浏览器里完成真实 Hop 文件/数据库连接管理 → Transform 或 Workflow 编辑 → 保存重开 → 经授权运行和查看结果。

**架构依据**：`cxhttt233/hop` 分支 `design/web-modern-architecture` 的 `docs/web-modern-architecture-plan.md` 是原项目唯一架构与实现依据。本仓库本页和 [产品交付板](product-delivery-board.md) 只负责当前进度、用户路径和验收，不重新设计架构。

## 五项任务的下一产品断点

| 任务 | 已证明的基础 | 下一步最高价值的未闭环 |
|---|---|---|
| T1 服务端 | 工作区隔离目录和 .hpl 原子保存；#132 草稿 REST 通过隔离 API 测试但仍未合入；已用 Apache Hop 2.20.0 PostgreSQL 插件证实原生 DatabaseMeta 内存保存读取（#138） | **真正的 DatabaseMeta 项目持久化、UI、复用与授权**：不能将 .properties 草稿等同原生连接，不能将仅内存测试等同项目保存重开 |
| T2 Workflow | 真实 .hwf 编辑、连接创建/切换/删除、保存失败恢复（#125）、Action 重命名并保存重开（#133），独立 JVM 与正式浏览器均验证 | **Action 新增、删除和参数配置**，后续需完整 Workflow 运行/监控与授权 |
| T3 Transform | SelectValues 三表、TableInput 原生列表和 [#137](https://github.com/cxhttt233/hop-modern-web/pull/137) FilterRows 原生单条件（字段比较、成立/不成立目标）均在真实 .hpl 保存重开验证 | **FilterRows AND/OR 组合、常量值**及 TableOutput/SortRows 的完整原生编辑；复杂 Condition 继续使用高级 JSON 回退 |
| T4 React | [#124](https://github.com/cxhttt233/hop-modern-web/pull/124) 取消不落盘、[#129](https://github.com/cxhttt233/hop-modern-web/pull/129) 安全筛选、[#134](https://github.com/cxhttt233/hop-modern-web/pull/134) 数字草稿、[#136](https://github.com/cxhttt233/hop-modern-web/pull/136) 中文 IME 均经过专项浏览器文件往返测试 | **原生未知枚举值保护、复杂 JSON/SQL 编辑、错误恢复**；不要重复已合入的字段筛选、数值草稿或 IME |
| T5 集成 | 新版 #133/#134/#136/#137/#138 已合并；T5 独立预览上重跑 #136/#137 真正保存重开，五槽位同步 main 和 Smoke 通过 | **真实产品链路跨模块验收、PR 集成与过时 Draft 清理**；尚无授权运行与完整结果展示 |

## 共同执行规则

- 每轮从最新 `main` 建开发分支，先在浏览器亲自体验本模块；先判断“用户在哪一步不能继续”，再选改动。每轮结论应区分 *已合并*、*已部署预览*、*已通过真实持久化*。
- 共享腾讯云测试入口：内网 `http://127.0.0.1:4196/t1/` … `/t5/`，公网 `https://43.142.133.101:8093/t1/` … `/t5/` 需要现有访问认证。8093 是 HTTPS，HTTP 访问返回 400 非应用故障；不需要 8092。
- 部署：`/home/ubuntu/hmw-agent-lab/bin/preview-branch tN <branch>`，浏览器 Smoke：`node /home/ubuntu/hmw-agent-lab/bin/smoke-product.mjs tN`。自动预览只选比 main 新的任务分支；review 旧分支可手动部署，但不能让旧版本长期覆盖主线。
- 新 Java API 可通过 `/home/ubuntu/hmw-agent-lab/bin/with-isolated-java-branch <branch> <test command>` 在临时 loopback 8084 JVM 测试，不必先合并到 main；旧的 8081 测试 JVM 已停用节省内存。
- T1 可在隔离环境直接运行 `/home/ubuntu/hmw-agent-lab/bin/check-native-database-meta`：已核验 PostgreSQL 2.20.0 方言插件，使用真正的 Hop DatabaseMeta/MemoryMetadataProvider 读写；此命令**不做持久化、外部连接或共享 Java 部署**。
- 安装的 Hop SNAPSHOT Java JAR 可直接编译独立分支：`/home/ubuntu/hmw-agent-lab/bin/check-java-branch <branch>`，不需要 Maven 解析远端依赖；**它仅做编译，不代替 JUnit、HTTP、安全与真实保存重开测试**。
- 各槽位测试工作区独立；测试必须使用临时 .hpl/.hwf 副本并清理，不污染共享样例。云服务器内存仅 3.3GiB、Swap 紧张，五项任务按 :26/:34/:42/:50/:58 错峰，分支构建有共享锁。
- 系统安全验证与真实授权执行尚未完成；请勿把隔离的预览桥接当作生产权限体系。保留密码秘密，不在日志中输出。
- 旧问题与 PR 仅作可追溯证据，当前产品事实见 [产品交付板](product-delivery-board.md)，不要复制旧“Workflow 尚不可编辑”等已经过时的文字。
