# Hop Modern Web — 产品目标与五任务交付板
> 2026-11-13。**唯一产品执行交接入口**；每轮阅读后须核对 GitHub 最新 HEAD/CI，不把这里的 SHA 当永恒事实。维护者：Task5 总集成。
> 产品技术架构仍以 `cxhttt233/hop@design/web-modern-architecture/docs/web-modern-architecture-plan.md`、blob `d29641632498bf560564c789b253dc39b91c16cd` 为唯一架构依据。本文件是需求/验收板，不覆盖任何安全或代码审查门禁。

## 0. 北极星：不是测试数，是能实际使用

**目标操作路径：** 从产品网页选择/新建工作区内的 Pipeline（`.hpl`）→ 查找并新增真实 Transform → 通过易读表单配置输入/字段处理/输出 → 选择或创建已授权的数据库连接 → 保存为文件并从磁盘重开 → 在可信用户授权下执行 → 查看进度、失败原因和少量结果样本；后续用同样标准覆盖 Workflow（`.hwf`）。

每一功能都按可见的真实 Hop 用户操作验收，不用假按钮、硬编码数据库连接、无法运行的静态菜单、虚构文件树或仅 Node 单测代替。**首个里程碑是单人可信本地工作区可用，然后扩展多用户权限模型；两者不得以匿名提权实现。**

衡量指标：
1. 完成真实演示链路的步骤数、阻塞数和失败后可恢复性；
2. **可可靠配置并保存/重开**的 Transform 个数（必须列名称与真实 .hpl 证据），而非 Palette 显示数量；
3. 文件/连接管理入口是否调用真实、已授权 Hop API；
4. 浏览器 E2E 和 Java serializer/XML 回归是否覆盖新增交互；
5. 受保护执行是否真的有 principal/CSRF/owner 负例和独立批准。

## 1. 当前可用事实与最大缺口

| 用户能力 | 当前 verified staging 状态 | 未解决缺口 |
| --- | --- | --- |
| 打开已有 .hpl / React Flow / 增删改连线、撤销/重做 | 真实 Hop E2E V2–V7 | 仅输入**服务器可见的绝对文件路径**，不是文件管理器；缺新建、列表、保存为、文件组织 |
| TableInput | SQL + 数据库连接**名称文本**编辑、原生字段 TABLE，V4/V8 | 没有真实已授权的数据库连接列表/新建/编辑/测试连接 |
| SelectValues | field/remove/meta 三表、选取未指定字段，V9、保存重开 | 表单信息层级仍与泛型 JSON 混杂，缺清晰基本/字段/高级分区 |
| SortRows | Java producer 发布 /fields/field 类型契约 | 尚无同等真实浏览器的编辑→保存→磁盘重开证明 |
| 20 个中文 Palette 项 | V6 检查菜单完整、实测 TableInput/SelectValues/FilterRows 语义添加 | **显示 20 ≠ 可配置 20**；更多插件 native serializer/表单/真实 .hpl E2E 未逐个交付 |
| 文件管理/连接管理 | React 中有最近路径、旧 `DatabaseConnectionPanel` / `MetadataPicker` 原型组件 | 组件并未构成受保护的完整真实 CRUD；无项目级文件树、浏览器打开/保存为、数据库元数据授权枚举 |
| 运行/Workflow | T1 局部 H2 HTTP 运行示例有 source CI；T2 有 Workflow 分支 | 当前 staging 不能安全执行；T1 PR#30 身份/CSRF/跨用户 P1，T2 PR#38 queued startTime 红 CI 和终态容量回收问题，禁止带入预览 |
| 实验集成 | V2–V9 合后 exact-head `a8f71365` Actions 43259623388 3/3 SUCCESS；PR#42 disjoint LIST path fix `c2325143` 已合 staging | 每个新 staging SHA 必须单独重跑 exact-head CI，禁止把前轮绿等同当前绿 |

特别注意：`ModernWebServer` 现用全局空 `MemoryMetadataProvider`，并未提供可信项目用户的 DatabaseMeta 列表。不能通过公开无认证文件/连接元数据 API 加速演示。不能偷偷替执行 API 关闭安全拒绝测试。

## 2. 三条产品需求线和优先级

### A. 配置表单：让人愿意每天使用（P0，最优先的独立可开发线）

- **每种 Transform 有任务型结构**：顶部显示节点名称/插件，主操作 1–3 处，关键参数直接可见；「字段」是有序 typed 表格，「高级」折叠；不要把几十个 `javaField` 和完整 JSON textarea 默认铺给用户。
- 按真实属性构建：TableInput（连接名称/SQL/字段）、SelectValues（三组字段）、SortRows（排序字段/升序/大小写）、TableOutput（连接/表名/字段）、FilterRows（条件）、DataGrid（手工数据）依次落地。
- 枚举用真实 `options` 的 select；Boolean 用开关；数字有合理输入约束；SQL 脚本有编辑器；密码不回显；字段标签中文且带必要提示。不能强制编造枚举、原生序列化路径或字段默认值。
- 未保存草稿 / 取消 / 保存 / 无效数据提示清晰；切换 Transform 不串修改。浏览器每种类型至少一条真实 `.hpl` save + disk reopen + Cancel 回归。

### B. 更多 Transform：按可用深度扩展（P0）

交付批次按 **能够完成真实 ETL 示例** 排序：
- Batch A: `TableInput`、`SelectValues`、`SortRows`、`TableOutput`、`FilterRows`；
- Batch B: `DataGrid`、`JsonInput`、`GroupBy`、`StringCut`、`ReplaceString`；
- Batch C: `MergeJoin`、`StreamLookup`、`InsertUpdate`、`ExecSql`、`Rest`，其余按需要。
每个插件必须有 **原生 Java 元数据 Schema / 正确嵌套路径 / React 可编辑 / 真实 HPL 数据不丢失 / 官方 CI**，覆盖至少一个异常/取消路径。Palette 名称、数量和泛型 JSON 表单都不单独计入完成。

### C. 核心功能：消除“空有编辑画布但不能用”的断层（P0→P1）

1. **文件与工作区管理：** 在已认证、已授权项目根下列出/搜索 `.hpl`/`.hwf`；创建空 Pipeline（由真实 Hop Java 模型产生）、打开、重命名/另存为、下载、删除有确认与恢复策略、最近文件；必须有根目录/symlink/root/跨用户安全负例。现有无认证服务禁止直接暴露服务器文件列表或任意路径修改。
2. **数据库连接管理：** 读取已授权项目的 `DatabaseMeta` **名称/类型/必要脱敏字段**，新建编辑、测试连接、保存、在 Transform 中选择；密码保密，拒绝匿名/跨项目读取。无真实授权接口时仅保留连接名称输入，禁止伪造 MetadataPicker 选项。
3. **数据执行与反馈：** 在可信 principal/Permission/CSRF/owner 门禁完成后，运行已保存 Pipeline，看到运行状态、错误、日志与受限结果样本，停止只作用于自己的执行。不得把 T1/T2 有真实安全缺陷的接口直接合入 preview。
4. **Workflow（继 Pipeline 核心闭环之后）：** 能打开和编辑 `.hwf` 的真实动作/连线/参数，保存重开；之后再按相同权限边界联通执行。
5. **操作基础：** 新建、保存、另存为、打开、切换时未保存提醒、连接/插件搜索、字段编辑体验、错误定位、状态反馈；优先完善用户每次必经路径，而非偏门视觉微调。

## 3. 五 Lane 分工与**下一轮应交付的产品切片**

| Lane（唯一授权写入分支） | 消费者需求 / 必须优先做的真实切片 | 交付验收 |
| --- | --- | --- |
| **T1** `integration/execution-r1` | 文件工作区 + 项目级连接元数据的**后端安全边界**；先把 principal、项目根、文件/metadata 访问/失败路径真正确定，若安全实作被阻，提供可独立实现的非特权文件模型及正版 Hop 测试，不重试历史 Safety BLOCKED 同义补丁 | 真实 Java/Jersey 断言受控文件路径和连接元数据对象；匿名/跨项目拒绝，不泄露连接凭据；未经独立 review 不对外开放 |
| **T2** `work/task-2-workflow-lifecycle-r3` | 围绕 Workflow 的可编辑性推进 `.hwf` **真实模型/序列化/原生动作与连线**独立测试与合约；停止重复只读分析已知红 `startTime` | 正式 Hop Workflow `.hwf` 打开/动作语义/保存重开测试；保留 queued 真实红测试与执行发布阻断；不碰被拒等价 Adapter 补丁 |
| **T3** `work/task-3-transform-coverage-r2` | 围绕 Batch A/B，选下一个**未被阻断的原生 Transform**，优先 SortRows/ TableOutput/FilterRows；产出可由 T4 消费的真实字段、枚举、LIST/OBJECT、native path/default 与敏感标记 | 生产 descriptor + 正式 Java JUnit `.hpl` 往返 + 清晰消费例子；不能把仅 20 项 Palette 算作覆盖 |
| **T4** `work/task-4-transform-palette-r1` | 配置表单的信息架构和产品级编辑体验。**不要停在“已有 raw JSON/SQL Monaco”**：基于 staging `GenericConfigPanel`、`NativeListEditor`、`NativeObjectListEditor` 的真实行为，做分区/标签/辅助解释、原生字段编辑、校验和取消/保存可见反馈的实质改进；先 Batch A | 真实 React 页面操作 + Playwright 实际 Hop 配置写入/磁盘重开，不猜 T3 wire contract；分支旧于 staging 时不要以旧文件欠缺为“没有能力” |
| **T5** `integration/task-5-readiness-r1` 及授权 isolated `integration/product-mainline-20261112` | 负责唯一真实用户旅程验收：文件→DB 连接→TableInput→SelectValues→TableOutput→保存→重开→（有可信执行后）运行；按可回滚最小切片消费 T1/T2/T3/T4。先建立产品可用性矩阵与 File/Connection 管理的依赖合同，不单纯清 CI | exact-head 双侧 CI + 真实浏览器端到端 + 未认证执行路由持续拒绝；非特权 staging 可按已有授权纳入，受保护 API 与 main/生产仍需独立安全审查批准 |

下一轮每个任务开始：**先读取此文件、当前 staging 源码和 own branch，比较“用户能力缺口”与已交付行为，选一个真正阻断用户旅程的最小实作**。不要优先重读几十条旧 CI、把旧 HEAD 当成当前能力。CI 用于验证新成果，不是任务本身。

## 4. 防空转规则

- 每轮以一句用户场景 + 一项现存缺口 + 最终可点 UI 或真实 Hop API + 回归测试为工作单元。
- 若只发现阻塞：给出**可写的独立替代切片**（非被阻断操作的同义变体），或明确指出需要哪种授权/独立审查；不要机械地再次核验同一 HEAD 并宣称“已清障”。
- 每个任务的提交必须附 `source SHA`、实际代码 diff、对应用户能力、真实测试、下一交接对象。无新代码/验收不得冒称 MEDIUM。
- 跨 Lane 交接统一在 Issue #5 更新；T5 维护本文件，不允许旧任务提示词中 2026-10 月的 HEAD 覆盖此处的新事实。
- **不变的边界：** 仅授权分支/仓库与隔离 CI；历史 Safety BLOCKED 及同义变体不绕行；不代写其他 Lane 被拒补丁；不触碰设备/云/现网；`main` 与生产部署必须有 exact-head 全绿、真正独立批准与产品/权限审查。当前 `AUTH_EXECUTION_GATE=BLOCKED`，`UNIFIED_RELEASE=NO`。
