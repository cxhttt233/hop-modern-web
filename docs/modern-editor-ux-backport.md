# Modern Web 用户界面反补说明

来源：2026-10-17 在 Workbench 使用 Modern Web 编辑器时的反馈。此 PR **只复用通用编辑器体验，不包含 Workbench 集成**。

## 已反补到独立 Modern Web
- 20 个常用转换的中文分类目录、按名称/插件 ID 搜索、紧凑拖拽组件项，空流程也显示目录。
- React Flow 基于画布坐标放置转换；继续调用原 Modern Web 的 add/move/connect/delete/save/undo/redo 语义接口，不复制 Workbench 草稿或保存适配器。
- 更易辨认的转换节点（中文类型 + 原始名称）、连线锚点与选中后的「配置/删除」快捷操作；双击继续打开原生配置。
- 空流程引导、加载与失败提示、不再把上一次流程或伪造示例当作失败文件显示；无节点时隐藏没用的 MiniMap 和缩放控件。
- 配置面板从原约 460px 调整为约 640px，缩小时自适应；优化表单分组和画布/组件库间距。
- 可复用的纯图状态操作辅助模块 `draftGraph.ts` 及其回归测试（现阶段不替换原 Modern 服务权威编辑）。

## 没有移植的内容
- Workbench 特有的 Vue 外壳、菜单、项目选择、SSO、Cookie、Java17 后端、私有 Hop 引擎及 `/api/workbench/...` API。
- 仅在 Workbench 浏览器使用的草稿/安全保存适配协议与配置缓存。这些需要独立 Modern Web 的命令协议设计后再统一，而不是复制到 `main`。
- 复杂插件表单自动生成：由已更新的 #3（Schema）及 #4（React 渲染）正式推进，#5 审核。本 PR 不虚报 SQL Monaco/MetadataPicker/EditableTable 的已完成度。
- 之前 Workbench 的完整原版图标 SVG （本批先统一使用类型 glyph 及语义配色，待版权和打包性能审查后单独恢复）。

## 发布验收
`npm run typecheck`、`npm run build`、新目录/拖放/空图/不依赖 Workbench 的代码测试；浏览器真实 .hpl 打开、拖放、保存、重新打开属于合并前的完整 E2E 验收范围。
