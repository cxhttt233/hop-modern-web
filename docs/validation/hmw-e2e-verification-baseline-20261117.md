# HMW E2E 验收基线（2026-11-17）

## 目的

将 T2–T5 已存在的原生 Hop 文件回归整合到一个单命令入口，避免把前端 Smoke 当成真实用户链路验收。测试只使用 UUID 临时文件，在真实 React 预览、slot bridge、Java Hop 服务中运行，失败保存截图和 Playwright Trace。

## 2026-11-17 首轮实测

运行命令：

    node e2e/verify-hmw.mjs --trace=all --out=/home/ubuntu/hmw-agent-lab/e2e-evidence/20261117-e2e-gate

结果：4/4 PASS，零测试失败，逐场景保存执行日志、成功截图、Playwright Trace。原始 JSON 报告保存在腾讯云的 e2e-evidence/20261117-e2e-gate/2026-11-17T03-27-36-351Z-1fb7b3dd/report.json。上述首轮测试运行时的脚本仍位于独立未提交工作树；合并前需要以 GitHub 上的准确提交重新运行。

| 场景 | 被测槽位真实 SHA | 结果 |
|---|---|---|
| T2 FILE_EXISTS .hwf 新增/丢弃/503 重试/重开 | 7e6f43cb4502 | PASS |
| T3 FilterRows 复合条件与常量 .hpl 保存重开 | 3f0eb4fc2e67 | PASS |
| T4 ExecSql Monaco/SQL 编辑、取消、保存重开 | 373580830a74 | PASS |
| T5 外部文件冲突 409、不覆盖、200 重试、重开 | 373580830a74 | PASS |

共享 Java active SHA：f2dc07653f8f。T2 和 T3 为当时的预览开发分支；T4/T5 是 main。该证据**不**等于所有场景在同一个 main 提交上的交叉验收。

## 失败检验

将 HMW_E2E_WORKSPACE_ROOT 临时设置为不存在的 /tmp/hmw-e2e-negative-missing，仅运行 T5 场景，测试按预期退出码 1、报告 pass=false，留下 browser-failure.png、playwright-trace.zip 和 stdout/stderr。这里是故意构造的环境错误，不是实际生产回归。

## 验收原则

- T5 合并相关 PR 前选择对应真实场景，集成后运行全部四条，查阅 report.json、浏览器截图和 Trace。
- 报告必须记录实际预览 SHA 与活跃 Java SHA；若运行中版本改变，结果视为失败，不能把不同分支说成同一版本。
- 只验证当前四条用户链路；T1 可信认证和数据库连接 CRUD、Workflow 真正执行/日志、跨用户隔离仍是独立未完成验收项。
- 先以验收观察模式运作，在脚本稳定并覆盖真实失败后再考虑强制 GitHub 门禁；不要用主观 AI 自评替代真实 E2E。