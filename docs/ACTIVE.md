# Hop Modern Web · 当前开发入口

> 状态核查：2026-11-14 21 点（GitHub main、共享 8080 与 T5 主线浏览器回归已确认）。后续仍以实际文件往返和最新 main 为准。

**产品目标**：在浏览器里完成真实 Hop 文件/数据库连接管理 → Transform 或 Workflow 编辑 → 保存重开 → 经授权运行和查看结果。

**架构依据**：`cxhttt233/hop` 分支 `design/web-modern-architecture` 的 `docs/web-modern-architecture-plan.md` 是原项目唯一架构与实现依据。本仓库本页和 [产品交付板](product-delivery-board.md) 只负责当前进度、用户路径和验收，不重新设计架构。

## 五项任务的下一产品断点

| 任务 | 已证明的基础 | 下一步最高价值的未闭环 |
|---|---|---|
| T1 服务端 | 工作区安全基础、#140 Apache Hop 原生 DatabaseMeta/JsonMetadataProvider JSON CRUD（含 token 拒绝、symlink 拒绝），#144 PostgreSQL 2.20 隔离 runner | **连接 API 的可信用户/槽位授权域与 React 入口**：共享 8080 无后端 token 返回 503，桥 8181 目前 404；不可简单开放代理或向浏览器提供服务端 token；之后验收创建→保存重开→TableInput 引用 |
| T2 Workflow | 真实 .hwf 编辑、连接、Action 删除/改名，#147 已增加配置工作区时的直接 Java API 文件越界保护，并通过独立 JVM 真文件重开测试 | **原生 Action 新增、参数合法初始化、连接/保存/重开**；后续安全授权执行和日志 |
| T3 Transform | SelectValues 三列表、TableInput、FilterRows 单条件（#137）+ 取消回归（#142）；#60 原生空列表的 Hop .hpl 往返专项测试已合入 | **原生 FilterRows AND/OR + 常量比较**，需证明 Condition/CValue 真实序列化；复杂条件维持 JSON 回退，随后 TableOutput/JsonInput |
| T4 React | #124/#129/#134/#136 + #151 外部变更 409 可理解提示/草稿保留/重试已通过真实浏览器保存重开；#143 ENUM 未验收 | **真实可见的复杂 JSON/SQL 编辑、保存失败恢复**；原生 ENUM 需真实 producer 后再验证 |
| T5 集成 | #147/#149/#150/#151 已合主线，Pipeline 外部改动/删除/symlink 防覆盖、409 浏览器恢复；共享 8080 与 T5 main 37d2aa6 复测 4→5 节点保存重开、无 JS 错误；重复旧 PR 已关闭 | **T1 安全数据库连接全链路、T2 Action 新增、T3 复合条件、T4 JSON/SQL，然后授权执行/日志**；不能用单纯 Smoke 代替真实能力 |

## 共同执行规则

- 每轮从最新 `main` 建开发分支，先在浏览器亲自体验本模块；先判断“用户在哪一步不能继续”，再选改动。每轮结论应区分 *已合并*、*已部署预览*、*已通过真实持久化*。
- 共享腾讯云测试入口：内网 `http://127.0.0.1:4196/t1/` … `/t5/`，公网 `https://43.142.133.101:8093/t1/` … `/t5/` 需要现有访问认证。8093 是 HTTPS，HTTP 访问返回 400 非应用故障；不需要 8092。
- 部署：`/home/ubuntu/hmw-agent-lab/bin/preview-branch tN <branch>`，浏览器 Smoke：`node /home/ubuntu/hmw-agent-lab/bin/smoke-product.mjs tN`。自动预览只选比 main 新的任务分支；review 旧分支可手动部署，但不能让旧版本长期覆盖主线。
- 新 Java API 可通过 `/home/ubuntu/hmw-agent-lab/bin/with-isolated-java-branch <branch> <test command>` 在临时 loopback 8084 JVM 测试，不必先合并到 main；旧的 8081 测试 JVM 已停用节省内存。
- T1 可在隔离环境直接运行 `/home/ubuntu/hmw-agent-lab/bin/check-native-database-meta`：已核验 PostgreSQL 2.20.0 方言插件，使用真正的 Hop DatabaseMeta/MemoryMetadataProvider 读写；此命令**不做持久化、外部连接或共享 Java 部署**。
- 安装的 Hop SNAPSHOT Java JAR 可直接编译独立分支：`/home/ubuntu/hmw-agent-lab/bin/check-java-branch <branch>`，不需要 Maven 解析远端依赖；**它仅做编译，不代替 JUnit、HTTP、安全与真实保存重开测试**。
- 各槽位测试工作区独立；测试必须使用临时 .hpl/.hwf 副本并清理，不污染共享样例。云服务器物理内存仅 3.3GiB、Swap 已扩至 6GiB（原 4GiB 已占满），仍需严格错峰，五项任务按 :26/:34/:42/:50/:58 错峰，分支构建有共享锁。
- 原生连接 8080 API 目前无 token 配置返回 503，预览桥 8181 的 native-connections 路由仍为 404，**不得用简单放行 GET/PUT 或将静态 token 放在浏览器来绕过**。
- 系统安全验证与真实授权执行尚未完成；请勿把隔离的预览桥接当作生产权限体系。保留密码秘密，不在日志中输出。
- 旧问题与 PR 仅作可追溯证据，当前产品事实见 [产品交付板](product-delivery-board.md)，不要复制旧“Workflow 尚不可编辑”等已经过时的文字。
