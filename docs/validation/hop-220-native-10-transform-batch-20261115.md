# Apache Hop 2.20：十种未特化 Transform 的真实浏览器与原生文件批量试用

2026-11-15 · 腾讯云 HMW T5 槽 · 当前共享 Hop Java f2dc076。T5 外部产品验收，非 T3 生成器代码改动。

## 已经实测通过

| 验收项目 | 结果 |
|---|---:|
| 官方 Hop Java 创建十份真实 .hpl，再独立解析 | **10/10** |
| 用户页面打开并识别正确插件，自动显示配置表单 | **10/10** |
| 取消配置未修改任何磁盘字节 | **10/10** |
| React 提交默认配置 HTTP 200、保存到磁盘并原生重新加载一致 | **10/10** |
| REST URL 真实修改并保存重开 | **1/1** |
| REST 密码控件遮蔽显示 | **2/2** |
| 浏览器 JS 异常或实际执行 ETL/HTTP 请求 | **0** |

| Transform | 显示字段数 | 显示分组数 | 基础闭环 |
|---|---:|---:|---|
| `CheckSum` | 7 | 2 | 通过 |
| `ConcatFields` | 6 | 2 | 通过 |
| `DataGrid` | 2 | 2 | 通过 |
| `GroupBy` | 10 | 3 | 通过 |
| `Http` | 14 | 4 | 通过 |
| `JsonInput` | 18 | 2 | 通过 |
| `MergeJoin` | 5 | 3 | 通过 |
| `Rest` | 34 | 9 | 通过 |
| `SetVariable` | 2 | 2 | 通过 |
| `StreamLookup` | 6 | 1 | 通过 |

REST 这一项并非仅原样往返：直接使用 **通用 React 表单**把 URL 改为 `https://example.invalid/hmw-native-rest-witness`，然后保存真实 `.hpl`，重开读取 URL 完全一致。这是保留且未针对 REST 单独编码的配置界面；`example.invalid` 用作不会请求的示例值，未执行 Transform 或访问网络。

## 可重复运行

```bash
bash ops/tencent-preview/test-native-batch-transform-e2e.sh
```

`NativeBatchTransformWitnessProducer.java` 用 Apache Hop 原生元数据、`PipelineMeta.getXml` 生成十个临时 HPL；Playwright 走 T5 用户页面的工作区桥接，以 UUID 副本测试取消、配置提交、磁盘保存与重开；测试退出后清理副本。逐插件完整 JSON 结果见 `hop-220-native-10-transform-batch-20261115.json`。

## 不能扩大解释

- 十个插件中 **九个验证了默认配置的等价往返**，还没有修改它们各自的复杂字段、集合或参数，更没有运行任何 ETL。
- REST 虽能修改 URL，但其 34 个原生配置字段仍暴露 `method`、`retryDelayMs`、`connectionTimeout`、`non_preemptive_basic_auth` 等技术字段名；没有形成原版 Hop 任务导向的标签、帮助、分组和条件显隐。这是 **T3 的真实专业 UX 缺口**。
- 五种 Action 能读取 T0 元数据不等于已经具备共享表单编辑 UI；Action 的 `.hwf` 参数编辑、Schema 对齐、保存重开仍需 T3 验收。
- T5 仅提供独立外部测试；T3 继续单一负责 FormSchemaGenerator（Java Meta、控件语义、i18n、布局、React、批量 Transform+Action），不拆分给其他任务。T1 可信身份认证未解决前不能开放数据库管理接口。
