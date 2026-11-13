# Hop Modern Web · 当前开发入口

产品目标：让用户在浏览器中管理 Hop 工程与数据库连接，编辑真实 Transform/Workflow，保存并重新打开；获得授权后能够运行并查看结果。

| 任务 | 当前开发分支 | 方向 |
|---|---|---|
| T1 | Agent 自主创建 | 文件、连接与服务端体验 |
| T2 | Agent 自主创建 | Workflow 阅读、编辑、保存 |
| T3 | Agent 自主创建 | 实际可用的原生 Transform 配置 |
| T4 | Agent 自主创建 | 中文表单、字段表格、交互体验 |
| T5 | `integration/product-mainline-20261112` | 产品集成及自主改进 |

当前已进入集成分支：真实 Hop Pipeline 浏览器编辑/重开、TableInput 和 SelectValues 字段表、RowGenerator 配置 Schema、字段行复制、.hpl 原子保存。

仍需继续：真实文件浏览/新建、DatabaseMeta 管理、更多 Transform 完整可用、Workflow 编辑保存、有身份与权限的执行和结果。旧 Workflow 预览 [PR #46](https://github.com/cxhttt233/hop-modern-web/pull/46) 尚未合并：读取工作流内容前需有可靠的访问控制。

每轮直接根据当前代码决定有用的下一步。历史 Issues、PR 讨论和旧 CI 作为存档，需要时再查。
