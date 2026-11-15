# Apache Hop 2.20 原生配置元数据覆盖基线

**实测时间：** 2026-11-15；**环境：** 腾讯云 Hop Modern Web 测试服务器，已加载官方 Hop 2.20 的精简运行时、主线 Java FormSchema 反射描述器（后端 `f2dc076`）。本报告属于 **T5 独立外部验收**，不是 T3 自报的生成器完成情况。

## 事实与口径

| 插件类别 | 当前 JVM 注册可见 | 具备至少 1 个 `@HopMetadataProperty` | 无可注解字段 | 扫描失败 |
|---|---:|---:|---:|---:|
| Transform | **26** | **25** | 1（`Dummy`） | 0 |
| Workflow Action | **5** | **5** | 0 | 0 |
| 合计 | **31** | **30** | **1** | **0** |

这是**元数据反射可生成非空 T0 Schema**的覆盖率，不是**可编辑表单的可用率**。26/5 是**此服务器安装且注册成功的插件数量**，不等于 Apache Hop 全部社区插件或全部官方 Transform/Action。字段数量包含超类属性；例如 Action 的被注解字段不一定都应在专业配置界面展示。

逐插件元数据可见性、类名、原生字段数、顶层列表数及状态见 `hop-220-native-schema-registry-20261115.csv`。统计代码为 `ops/tencent-preview/NativeSchemaCoverageProbe.java`；仅反射获取插件实例和注解，不执行工作流、不访问 JDBC/HTTP/业务文件。

复测方式（只读，按当前 JVM 动态统计，不固定插件数量）：

```bash
HMW_SCHEMA_COVERAGE_OUT=/tmp/hmw-native-schema.csv \
  bash ops/tencent-preview/test-native-schema-coverage.sh
```

预期诊断：`T5_READ_ONLY_TRANSFORM_ACTION_SCHEMA_COVERAGE_PASS`。在同一运行时上记录的 Transform 26 / Action 5、其中 Transform 25 非空 / 1 空、Action 5 非空、加载失败 0。

## 对 T3 的下一阶段验收要求

1. **不要以 30/31 的 T0 非空比例声称 30/31 个专业表单可用。** 例如 `InsertUpdate` 顶层只有 4 个属性，深层 `lookup.key/value` 的业务性列表需要正确的 Schema/wire 路径；`SelectValues` 顶层只有 1 个对象字段，同样需要展开，否则只是 JSON 属性查看器。
2. 给出分层可用性统计：**T0 有 Schema → 控件/分组可自动渲染 → 用户可以完成主要参数配置 → 原生 Java 配置往返/文件保存重开通过**。分别标记 `T0仅元数据`、`降级或原始JSON`、`不可操作`、`业务可用`，并对失败解释原因。
3. **Transform** 用当前注册的 26 类枚举自动生成，再抽至少 10 个不同类别、以前没有单独 React 特例的 Transform 实际浏览器测试。至少覆盖 SQL / REST / HTTP / JsonInput / GroupBy / SortRows / DataGrid / MergeJoin 等不同结构，且严格区分已尝试与已通过。
4. **Action** 当前仅有 SPECIAL、DUMMY、ABORT、SUCCESS、FILE_EXISTS 五类可见。T2 已通过官方 Java 对 FILE_EXISTS 的业务 API/文件重开；但还需 T3 提供同一套 Schema 驱动的 Action 参数 UI。**T0 元数据覆盖不代表 Action UI 已实现**。
5. 低频插件允许基于 Schema 自动布局退化，但不能要求为所有 Transform/Action 手写 React；不确定的嵌套原生结构应拒绝危险写入或明确降级，保持 `.hpl/.hwf`、未知字段、凭据及工作区安全。

**责任：** T3 一个人独占生成器全部 Java/React 及批量试用产品实现；T5 只提供独立外部覆盖审计和产品验收，绝不拆分 T3 代码任务。T1 仍保持无可信用户身份发行者时的数据库管理 fail-closed。
