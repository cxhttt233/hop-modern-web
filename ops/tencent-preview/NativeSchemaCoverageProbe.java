import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.plugins.ActionPluginType;
import org.apache.hop.core.plugins.IPlugin;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.plugins.TransformPluginType;
import org.apache.hop.workflow.action.IAction;
import org.apache.hop.pipeline.transform.ITransformMeta;
import org.apache.hop.modern.web.document.AutomaticConfigDescriptor;

/** External T5 read-only coverage probe; does not execute Hop actions or pipelines. */
public final class NativeSchemaCoverageProbe {
 record Row(String category,String id,String pluginName,String pluginClass,int properties,int lists,int grouped,String status,String detail){}
 static String cell(String s){
  return s==null?"":s.replace("\\","/").replace(",",";").replace("\n"," ").replace("\r"," ").replace('"','\'');
 }
 static void scan(String category,List<IPlugin> plugins,Class<?> expected,List<Row> rows){
  for(IPlugin plugin:plugins){
   String id=plugin.getIds().length>0?plugin.getIds()[0]:"?";
   String className="";
   try {
    Class<?> type=category.equals("Transform")?TransformPluginType.class:ActionPluginType.class;
    Object object=PluginRegistry.getInstance().loadClass(plugin);
    if(!expected.isInstance(object)){
      rows.add(new Row(category,id,plugin.getName(),object==null?"<null>":object.getClass().getName(),0,0,0,"UNSUPPORTED","wrong native meta instance"));
      continue;
    }
    className=object.getClass().getName();
    var desc=AutomaticConfigDescriptor.describe(object.getClass());
    int properties=desc.properties().size();
    int lists=(int)desc.properties().stream().filter(x->x.shape().name().equals("LIST")).count();
    int grouped=(int)desc.properties().stream().filter(x->x.layout()!=null&&x.layout().group()!=null
      && !x.layout().group().isBlank()).count();
    String status=properties==0?"NO_ANNOTATED_FIELDS":"T0_SCHEMA";
    rows.add(new Row(category,id,plugin.getName(),className,properties,lists,grouped,status,""));
   }catch(Throwable ex){
    rows.add(new Row(category,id,plugin.getName(),className,0,0,0,"LOAD_FAILED",
      ex.getClass().getSimpleName()+": "+String.valueOf(ex.getMessage())));
   }
  }
 }
 public static void main(String[] args)throws Exception{
   HopClientEnvironment.init();
   PluginRegistry.addPluginType(TransformPluginType.getInstance());
   PluginRegistry.addPluginType(ActionPluginType.getInstance());
   PluginRegistry.init();
   var registry=PluginRegistry.getInstance();
   var rows=new ArrayList<Row>();
   scan("Transform",new ArrayList<IPlugin>(registry.getPlugins(TransformPluginType.class)),ITransformMeta.class,rows);
   scan("Action",new ArrayList<IPlugin>(registry.getPlugins(ActionPluginType.class)),IAction.class,rows);
   rows.sort(Comparator.comparing(Row::category).thenComparing(Row::id));
   System.out.println("category,id,plugin_name,meta_class,annotated_properties,top_level_lists,grouped_properties,status,reason");
   for(var r:rows)System.out.println(String.join(",",cell(r.category),cell(r.id),cell(r.pluginName),
     cell(r.pluginClass),String.valueOf(r.properties),String.valueOf(r.lists),String.valueOf(r.grouped),
     cell(r.status),cell(r.detail)));
   for(var cat:List.of("Transform","Action")){
    var list=rows.stream().filter(r->r.category.equals(cat)).toList();
    int schema=(int)list.stream().filter(r->r.status.equals("T0_SCHEMA")).count();
    int empty=(int)list.stream().filter(r->r.status.equals("NO_ANNOTATED_FIELDS")).count();
    int fail=(int)list.stream().filter(r->r.status.equals("LOAD_FAILED")||r.status.equals("UNSUPPORTED")).count();
    System.err.println("T5_EXTERNAL_SCHEMA_COVERAGE category="+cat+" total="+list.size()+" T0_SCHEMA="+schema
      +" NO_ANNOTATED_FIELDS="+empty+" FAILED="+fail);
   }
 }
}