# HMW E2E Monaco 渲染时序回归（2026-11-17）

## 被验证的真实问题

合并第一个 E2E 门禁（#195，main c39b7bd）后，再次从干净 main 提交运行四条场景，T2/T3/T5 PASS，但 T4 ExecSql 因立即读取 Monaco 重新展开后的渲染行，报告错误 "Monaco reopen did not reflect draft"。当次退出 1、3/4；原始证据保存在腾讯云：

    /home/ubuntu/hmw-agent-lab/e2e-evidence/20261117-e2e-postmerge/2026-11-17T03-36-52-132Z-b7d36a4c/

该失败保留 browser-failure.png、playwright-trace.zip、stdout/stderr、report.json。前后 T4 preview SHA 稳定，Java SHA 稳定，不能归结为测试时切换了部署版本。

## 诊断与最小修复

单独在同一 T4 预览中打印 Monaco DOM 文本及原生 SQL textarea，确认两者均包含预期完整的四行 SQL，且 Monaco DOM 使用 NBSP 空格。原用例在折叠后重新展开时，立即对 innerText 作子串匹配；偶发读到了编辑器尚未稳定的渲染状态。

用明确的 Playwright waitForFunction 条件替换瞬时断言，要求重新渲染后的全部 Monaco 文本与期望 SQL 规范化一致，同时严格要求原生 textarea 的 SQL 内容与原始草稿逐字相等。不是固定 sleep，不取消断言，也不修改业务代码。

## 复核

在独立主线工作树中，改动后的 T4 完整用户操作链路连续运行 3 次，每次约 6.3～6.6 秒，3/3 PASS，且每轮真实取消、编辑、保存、磁盘重开，保存截图与 Trace。

    /home/ubuntu/hmw-agent-lab/e2e-evidence/20261117-t4-stability/

保留失败记录是验证 Harness 的一部分。三次通过说明本次修正确实改善了重现路径，但不代表后续绝无其它渲染时序问题；T5 仍需按实际变更继续执行 E2E。