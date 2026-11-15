# Hop Modern Web · 当前开发入口

> 最后复核：2026-11-15 13:22（Asia/Shanghai）。GitHub main `52e087d`，T1–T5 预览均为该版本；共享 Java `aab4faf`（其后提交是 docs/ops/前端/测试，无后端业务代码差异），8080 systemd 单实例正常。后续状态以实际 GitHub、Java active SHA、浏览器和原生文件重开为准。

**产品目标**：在浏览器里完成真实 Hop 文件/数据库连接管理 → Transform 或 Workflow 编辑 → 保存重开 → 经授权运行和查看结果。

**架构唯一依据**：`cxhttt233/hop` 分支 `design/web-modern-architecture` 的 `docs/web-modern-architecture-plan.md`。本页和产品交付板只说明现行分工/状态/验收，不能改写架构；重点看原方案 A3、4.5、6 节的 FormSchemaGenerator / T0-T1-T2 分层。

## 五项任务的下一产品断点

**2026-11-15 用户确认：专业表单自动生成器从 Java 元数据到 React 渲染，完全且仅由 T3 一个任务负责。** 不再跨 T1/T3/T4 拆 Schema、样式和 Action 表单。对既有已合并功能只维护，不重复实现。五个独立工作边界如下：

| 任务 | 独占责任（不跨界修改） | 当前证据与下一步真正阻塞 |
|---|---|---|
| **T1 · 服务端与认证** | 文件/工作区、数据库连接元数据、可信用户身份与授权；**不修改表单 Schema/生成器/渲染器/API** | 原生 DatabaseMeta 隔离测试与未授权 25/25 fail-closed 已通过。**没有可信身份发行者，原生数据库管理入口必须继续关闭**；不得用槽位/IP/浏览器静态 token/伪造 X-User 请求头代替用户身份。T1 继续审核可验证的身份与最小权限边界，不阻塞 T3 的只读元数据生成。 |
| **T2 · Workflow 图与 Action 能力** | 原生 .hwf 图节点、Action 创建/连接/修改、图保存冲突与回归；**不实现 Action 配置表单生成器** | START/DUMMY/ABORT/SUCCESS 原生闭环已验收，已有 ABORT 参数编辑/503 保留草稿。下一候选 FILE_EXISTS 官方 Hop 2.20 JAR 已取得、sha256 `66d423e8832af84651e90a990c378270ed6a1e2f6bbac36a4a51cf2a52b1ea78`，可在隔离 JVM 通过 `HMW_ISOLATED_ACTION_FILE_EXISTS=1` 加载，独立原生 Java .hwf 生成→反解析已验证；**未在共享 Java 启用**。可继续 Action/图功能，但不要自己开发/改动表单引擎。 |
| **T3 · 唯一表单生成器负责人** | **独占**：SWT-free Hop Metadata/注解/i18n、业务语义、FormSchema 生成与扩展、Widget/布局/Tab/组/字段顺序/密度/宽幅、自适应 React 通用渲染、Transform **和 Workflow Action** 共用机制、完整批量覆盖分析与真实持久化验收 | TableInput/InsertUpdate/TableOutput 和现有 NativeListEditor 的原生功能已验收，但目前只是若干局部能力，**不能宣称整体智能生成器已完成**。先从三类代表插件抽象统一 T0 元数据 Schema、T1 `form.json` 声明布局、T2 必要专用插槽；再枚举所有已安装 Transform/Action，输出生成成功/降级/失败及原因；随机抽取未手写适配插件实测无需 React 改码即可形成专业布局并经 Hop Java .hpl/.hwf 重开。T3 自己完成前后端，其他任务不进入本代码边界。 |
| **T4 · 非表单 React 全局交互** | 画布、导航、图操作与非表单的通用体验、安全的独立 UI 回归；**不修改 GenericConfigPanel/NativeListEditor/FormSchema/生成器样式** | ExecSql/TableInput Monaco 与 #180 连续撤销的现有成果均已通过，停止重复。对于表单样式/IME/布局问题交由 T3 修复；T4 只处理明确属于独立画布/全局交互的故障。 |
| **T5 · 总集成和外部验收** | 五槽部署版本、8080 单实例、资源/回滚、跨模块 E2E、安全验收；**不接管生成器代码** | #173 secure .hpl/.hwf descriptor-relative 保存，T5 409→200 恢复，5/5 Smoke 与业务回归已通过。T5 主要检查 T3 交付是否在**未特殊处理插件**中生成用户可理解表单，不以字段数量/PR 数量/Smoke 代替体验；其他四条保持分工。 |

**T3 交付门槛**：同一套生成器支持 Transform + Workflow Action；一批真实插件可批量试用，报告统计覆盖率/降级原因；至少 TableInput、InsertUpdate、TableOutput 和未特化抽样插件在真实浏览器中完成“找到连接/主要参数 → 可读的专业布局 → 取消不写盘/保存成功 → 官方 Hop Java 原生磁盘重开”。专门的布局声明是通用 Schema 的可选增强，不是为每个 Transform 写一个 React 页面。安全凭据不能出现在客户端 Schema 中。

**交接/代码冲突规则**：T3 是本里程碑唯一负责人的 GitHub 跟踪入口 [Issue #3](https://github.com/cxhttt233/hop-modern-web/issues/3)。T1/T2/T4/T5 遇到生成器问题仅向 #3 提供复现/原生样本/验收反馈，不直接改 T3 独占的 Java Schema 或 React 表单模块。T3 所需外部基础设施必须是稳定独立接口，不能成为其完整交付的前置等待。

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
