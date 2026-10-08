import type { HopGraphDocument } from "@hop-modern/contracts";

// One maintainable source for localized transform names, categories and Palette search.
// pluginId is the immutable technical identity; display names never enter graph commands.
export const P0_TRANSFORMS = [
  "CheckSum", "ConcatFields", "DataGrid", "ExecSql", "FilterRows", "GroupBy", "Http",
  "InsertUpdate", "JsonInput", "MergeJoin", "ReplaceString", "Rest", "ScriptValueMod",
  "SelectValues", "SetVariable", "StreamLookup", "StringCut", "TableInput", "TableOutput",
  "UniqueRowsByHashSet",
] as const;

export type TransformCategory = "input" | "output" | "field" | "flow";

export const CATEGORY_ORDER: Array<{ id: TransformCategory; label: string }> = [
  { id: "input", label: "输入与接入" },
  { id: "output", label: "输出与写入" },
  { id: "field", label: "字段处理" },
  { id: "flow", label: "流程与关联" },
];

export const TRANSFORM_CATALOG: Record<string, { name: string; category: TransformCategory; glyph: string }> = {
  CheckSum: { name: "校验和", category: "field", glyph: "校" },
  ConcatFields: { name: "字段拼接", category: "field", glyph: "拼" },
  DataGrid: { name: "数据网格", category: "input", glyph: "数" },
  ExecSql: { name: "执行 SQL", category: "output", glyph: "SQL" },
  FilterRows: { name: "过滤记录", category: "flow", glyph: "滤" },
  GroupBy: { name: "分组汇总", category: "flow", glyph: "组" },
  Http: { name: "HTTP 客户端", category: "input", glyph: "HTTP" },
  InsertUpdate: { name: "插入 / 更新", category: "output", glyph: "写" },
  JsonInput: { name: "JSON 输入", category: "input", glyph: "JSON" },
  MergeJoin: { name: "合并连接", category: "flow", glyph: "合" },
  ReplaceString: { name: "字符串替换", category: "field", glyph: "替" },
  Rest: { name: "REST 客户端", category: "input", glyph: "REST" },
  ScriptValueMod: { name: "脚本计算", category: "field", glyph: "脚" },
  SelectValues: { name: "选择字段", category: "field", glyph: "选" },
  SetVariable: { name: "设置变量", category: "output", glyph: "变" },
  StreamLookup: { name: "流查询", category: "flow", glyph: "查" },
  StringCut: { name: "字符串截取", category: "field", glyph: "截" },
  TableInput: { name: "表输入", category: "input", glyph: "表" },
  TableOutput: { name: "表输出", category: "output", glyph: "出" },
  UniqueRowsByHashSet: { name: "记录去重", category: "flow", glyph: "去" },
  Dummy: { name: "占位转换", category: "flow", glyph: "·" },
  SortRows: { name: "排序记录", category: "flow", glyph: "序" },
};

export function transformMeta(pluginId: string) {
  return TRANSFORM_CATALOG[pluginId] ?? {
    name: pluginId,
    category: "flow" as TransformCategory,
    glyph: pluginId.slice(0, 2).toUpperCase(),
  };
}

type GraphNode = HopGraphDocument["nodes"][number];

export function transformPrimaryName(node: GraphNode): string {
  if (node.pluginId === "Dummy" && node.id === "source") return "输入";
  if (node.pluginId === "Dummy" && node.id === "sink") return "输出";
  return transformMeta(node.pluginId ?? "Unknown").name;
}

export function transformSecondaryName(node: GraphNode): string {
  const name = (node.name ?? "").trim();
  const generated = !name || name === node.id || /-\d+$/.test(name);
  // Preserve a user-authored Hop name, but keep localized type primary.
  return generated || name === transformPrimaryName(node) ? (node.pluginId ?? "Unknown") : name;
}
