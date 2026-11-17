# HMW 端到端验收（第一阶段）

环境：腾讯云测试机，真实 T2-T5 预览、槽位桥接和 Apache Hop Java 8080。使用 UUID 命名的临时原生 HPL/HWF 文件，不接触真实数据库，不执行工作流，不覆盖正式样例。

## 运行

    node e2e/verify-hmw.mjs --list
    node e2e/verify-hmw.mjs
    node e2e/verify-hmw.mjs --trace=all
    node e2e/verify-hmw.mjs --case=t3-filterrows-composite
    node e2e/verify-hmw.mjs --out=/home/ubuntu/hmw-agent-lab/e2e-evidence

默认串行执行四条浏览器 E2E：T2 Workflow Action FILE_EXISTS 与 503 保存重试、T3 FilterRows 复合条件和原生重开、T4 Monaco SQL 编辑和原生重开、T5 409 外部文件冲突和重试。--trace=all 也保存成功案例的 Trace。

报告目录：e2e/.artifacts 或 --out 指定目录。每次创建独立报告子目录，包含 report.json、README.md、每个案例的 stdout.log/stderr.log、browser-success.png 或 browser-failure.png。失败生成 playwright-trace.zip。可通过 npx playwright show-trace /absolute/path/playwright-trace.zip 复盘 DOM、网络、操作与截图。

report.json 记录本次检出的代码 SHA、每槽位实际部署 SHA、活跃 Java SHA、执行时间、耗时、退出码、超时及失败说明。主线 SHA 不等于预览槽位 SHA，不得混同。脚本不自动重试，异常或超时后退出非零；失效的子进程可能留下 UUID 文件，按日志指定名称核实后清理，不得清理共享样例。

## 验收边界

第一阶段只涵盖这四条已具备真实业务路径的 E2E，不涵盖 T1 数据库连接可信授权、跨用户隔离、真实工作流执行和日志。T1 的 native Transform API no-op Roundtrip 是辅助证据而不是浏览器 E2E。

T5 在相关 PR 合并前或主线集成后使用该工具提供复现命令、报告和截图。失败必须附错误、Trace 和具体阻断；不能因为基础 Smoke 通过就宣称 E2E 通过，也不能让开发 Agent 自行修改基础分支上的验收规则以降低门槛。暂不将该工具作为远程 GitHub CI 的强制检查，因为 GitHub runner 无法直接访问私有腾讯云桥接和工作区；先由 T5 验证并建立稳定基线。