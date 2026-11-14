# Hop Modern Web · 当前开发入口

> 状态核查：2026-11-14 17:10（北京时间）。后续开发请以实时 `main` 和可复现浏览器操作为准，不把本页当作永远有效的完成证明。

**产品目标**：在浏览器里完成真实 Hop 文件/数据库连接管理 → Transform 或 Workflow 编辑 → 保存重开 → 经授权运行和查看结果。

**架构依据**：`cxhttt233/hop` 分支 `design/web-modern-architecture` 的 `docs/web-modern-architecture-plan.md` 是原项目唯一架构与实现依据。本仓库本页和 [产品交付板](product-delivery-board.md) 只负责当前进度、用户路径和验收，不重新设计架构。

## 五项任务的下一产品断点

| 任务 | 已证明的基础 | 下一步最高价值的未闭环 |
|---|---|---|
| T1 服务端 | 工作区隔离文件目录搜索和真实 .hpl 打开/原子保存 | **DatabaseMeta 元数据管理**：浏览器入口 + 连接的新建/保存/重开/复用，后续安全连接测试。目前 Java REST 资源中无数据库连接资源，已有 `DatabaseConnectionPanel` 未被使用 |
| T2 Workflow | 真实 .hwf 打开、描述与节点坐标编辑、连接创建/切换/删除、保存重开；#125 验证保存失败可恢复 | **Action 管理**：新增/删除/修改真实 Action，再保存重开，不重复做已合入的连接创建 |
| T3 Transform | TableInput 空原生 LIST 增行/保存重开，SelectValues 三表编辑与保存；#123 已证明多余并关闭 | **更多真实 Transform 类型**：先给出当前失败场景，再实现 FilterRows、TableOutput、SortRows 等的完整配置往返 |
| T4 React | 中文字段表、#124 取消不落盘、#129 原生列表筛选保持源索引、禁用危险排序、保存重开测试 | **复杂表单交互**：数值草稿/IME/错误恢复等，切勿重复做 #129 的筛选 |
| T5 集成 | main 当前集成 #122/#124/#125/#129；五槽位自动构建和浏览器 Smoke 正常 | **主动验收 PR、清理重复 Draft、推进产品端到端链路**。合并先看真实文件往返证据，不仅看 TypeScript 编译 |

## 共同执行规则

- 每轮从最新 `main` 建开发分支，先在浏览器亲自体验本模块；先判断“用户在哪一步不能继续”，再选改动。每轮结论应区分 *已合并*、*已部署预览*、*已通过真实持久化*。
- 共享腾讯云测试入口：内网 `http://127.0.0.1:4196/t1/` … `/t5/`，公网 `https://43.142.133.101:8093/t1/` … `/t5/` 需要现有访问认证。8093 是 HTTPS，HTTP 访问返回 400 非应用故障；不需要 8092。
- 部署：`/home/ubuntu/hmw-agent-lab/bin/preview-branch tN <branch>`，浏览器 Smoke：`node /home/ubuntu/hmw-agent-lab/bin/smoke-product.mjs tN`。自动预览只选比 main 新的任务分支；review 旧分支可手动部署，但不能让旧版本长期覆盖主线。
- 安装的 Hop SNAPSHOT Java JAR 可直接编译独立分支：`/home/ubuntu/hmw-agent-lab/bin/check-java-branch <branch>`，不需要 Maven 解析远端依赖；**它仅做编译，不代替 JUnit、HTTP、安全与真实保存重开测试**。
- 各槽位测试工作区独立；测试必须使用临时 .hpl/.hwf 副本并清理，不污染共享样例。云服务器内存仅 3.3GiB、Swap 紧张，五项任务按 :26/:34/:42/:50/:58 错峰，分支构建有共享锁。
- 系统安全验证与真实授权执行尚未完成；请勿把隔离的预览桥接当作生产权限体系。保留密码秘密，不在日志中输出。
- 旧问题与 PR 仅作可追溯证据，当前产品事实见 [产品交付板](product-delivery-board.md)，不要复制旧“Workflow 尚不可编辑”等已经过时的文字。
