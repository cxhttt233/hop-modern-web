# Hop Modern Web 产品方向

**真实用户路径**：选择或创建 .hpl/.hwf 文件 → 选择或管理数据库连接 → 添加和配置 Transform / Workflow → 保存并重新打开 → 经授权运行并查看结果。

## 优先改善
1. **配置好用**：中文可读、常用参数突出、原生字段表/SQL 有合适控件、保存与取消明确。
2. **配置真的可用**：从 Hop 原生 Meta 读取正确字段与类型，保存后再打开不丢失内容。优先 TableInput、SelectValues、SortRows、TableOutput、FilterRows；随后逐步扩展其他常用 Transform。
3. **产品链路完整**：真实文件浏览/新建、DatabaseMeta 管理、.hwf 编辑、运行与结果反馈，而不是只展示菜单与演示数据。

## 已有基础
- React Flow Pipeline 编辑、真实 Hop .hpl 保存与重开；TableInput、SelectValues 原生配置。
- RowGenerator Schema 已合入，但尚不等于完整浏览器闭环。
- 字段行复制和 .hpl 原子保存已合入。
- Workflow 只读预览仍在独立 [PR #46](https://github.com/cxhttt233/hop-modern-web/pull/46)，未作为共享功能发布。

**当前分工及分支：** 见 [ACTIVE.md](ACTIVE.md)。各任务自主选择对用户有价值的小步改进；能安全复用的成果由 T5 按需集成。

历史技术决策、旧 CI、PR 评论可在 GitHub 历史中按需追溯，不是开发入口。
