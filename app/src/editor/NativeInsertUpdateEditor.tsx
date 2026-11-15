import { readNativeWireList, resolveNativeWireListItem, writeNativeWireList,
  type HopNativeWireDescriptor } from "@hop-modern/contracts";
import type { ConfigPropertyDescriptor } from "./configDescriptor";
import { NativeListEditor, canEditNativeList } from "./NativeListEditor";

const LISTS = [
  { key: "key", title: "匹配键字段", labels: {
      name: "输入匹配字段", field: "数据库匹配列", condition: "匹配条件", name2: "第二输入字段" } },
  { key: "value", title: "更新字段映射", labels: {
      name: "目标数据库列", rename: "输入数据流字段", update: "允许更新" } },
] as const;

const asRecord=(value:unknown):value is Record<string,unknown> =>
  !!value && typeof value==="object" && !Array.isArray(value);

/** Never infer nested XML paths. These contracts must come from Hop 2.20 Java. */
export function canEditInsertUpdateLookup(
  descriptor: HopNativeWireDescriptor & {className:string},
  property: ConfigPropertyDescriptor,
  value: unknown,
): value is Record<string,unknown> {
  if (descriptor.className!=="org.apache.hop.pipeline.transforms.insertupdate.InsertUpdateMeta"
      || property.key!=="lookup" || property.shape!=="OBJECT" || !asRecord(value)) return false;
  const children=property.children??[];
  if (!children.some(c=>c.key==="schema" && c.shape==="STRING")
      || !children.some(c=>c.key==="table" && c.shape==="STRING")) return false;
  return LISTS.every(({key})=>{
    const child=children.find(c=>c.key===key);
    const wire=resolveNativeWireListItem(descriptor,"lookup."+key,0);
    return child?.shape==="LIST" && wire?.listPointer==="/lookup/"+key
      && canEditNativeList(value[key]??[],child.elementProperties??[]);
  });
}

interface Props {
  descriptor: HopNativeWireDescriptor & {className:string};
  property: ConfigPropertyDescriptor;
  value: Record<string,unknown>;
  disabled?:boolean;
  onChange:(next:Record<string,unknown>)=>void;
}

/**
 * Edits the two officially attested nested lists and target fields locally.
 * Spread-copy retains any unknown metadata. The outer save is always Hop Java.
 */
export function NativeInsertUpdateEditor({descriptor,property,value,disabled,onChange}:Props){
  const children=property.children??[];
  return <div className="config-native-object-lists">
    <p>依据 Apache Hop 原生 InsertUpdate 配置编辑匹配条件与更新映射。修改仅保存为草稿，
      取消配置不会写入磁盘；不会执行数据库更新。</p>
    <div className="config-native-insertupdate-target" role="group" aria-label="InsertUpdate 目标表">
      <label>Schema <input aria-label="InsertUpdate Schema" disabled={disabled}
        value={String(value.schema??"")}
        onChange={event=>onChange({...value,schema:event.target.value})}/></label>
      <label>目标表 <input aria-label="InsertUpdate 目标表" disabled={disabled}
        value={String(value.table??"")}
        onChange={event=>onChange({...value,table:event.target.value})}/></label>
    </div>
    {LISTS.map(({key,title,labels})=>{
      const child=children.find(c=>c.key===key);
      if(!child)return null;
      const wirePath="lookup."+key;
      const rows=readNativeWireList({lookup:value},descriptor,wirePath)??[];
      return <section key={key} aria-label={title} className="config-native-list-section">
        <h4>{title}</h4>
        <NativeListEditor title={title} value={rows} properties={child.elementProperties??[]}
          columnLabels={labels} disabled={disabled} onChange={nextRows=>{
            const changed=writeNativeWireList({lookup:value},descriptor,wirePath,nextRows);
            if(!asRecord(changed.lookup))throw new Error("Hop InsertUpdate lookup must remain an object");
            onChange(changed.lookup);
          }}/>
      </section>;
    })}
  </div>;
}
