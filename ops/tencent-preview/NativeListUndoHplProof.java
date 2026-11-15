import java.io.FileInputStream;
import java.nio.file.Path;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.plugins.TransformPluginType;
import org.apache.hop.core.database.DatabasePluginType;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transforms.insertupdate.InsertUpdateMeta;
import org.apache.hop.pipeline.transforms.tableoutput.TableOutputMeta;

/** Official Hop native readback of both .hpl files saved after two UI undos. */
public final class NativeListUndoHplProof {
  static void check(boolean result, String message){if(!result)throw new AssertionError(message);}
  static PipelineMeta pipeline(Path file)throws Exception{
    Path real=file.toRealPath();
    check(real.toString().endsWith(".hpl"),"only native HPL");
    try(var in=new FileInputStream(real.toFile())){
      return new PipelineMeta(in,new MemoryMetadataProvider(),Variables.getADefaultVariableSpace());
    }
  }
  public static void main(String[] args)throws Exception {
    check(args.length==2,"Expected UI-saved TableOutput .hpl and InsertUpdate .hpl");
    HopClientEnvironment.init();
    PluginRegistry.addPluginType(TransformPluginType.getInstance());
    PluginRegistry.addPluginType(DatabasePluginType.getInstance());
    PluginRegistry.init();
    var a=pipeline(Path.of(args[0]));
    check(a.getTransforms().size()==1,"TableOutput transform lost");
    check(a.getTransforms().get(0).getTransform() instanceof TableOutputMeta,"not native TableOutput");
    var out=(TableOutputMeta)a.getTransforms().get(0).getTransform();
    check("Warehouse".equals(out.getConnection()),"TableOutput connection lost");
    check("water_level_archive".equals(out.getTableName()),"TableOutput table lost");
    check(out.getFields().size()==3,"TableOutput multi-undo row count incorrect");
    check("station_code".equals(out.getFields().get(0).getFieldStream())
        && "station_id".equals(out.getFields().get(0).getFieldDatabase()),"TableOutput first row lost");
    check("level_m".equals(out.getFields().get(1).getFieldStream())
        && "water_level_m".equals(out.getFields().get(1).getFieldDatabase()),"TableOutput second row lost");
    check("observed_at".equals(out.getFields().get(2).getFieldStream())
        && "observation_time".equals(out.getFields().get(2).getFieldDatabase()),"TableOutput third row lost");

    var b=pipeline(Path.of(args[1]));
    check(b.getTransforms().size()==1,"InsertUpdate transform lost");
    check(b.getTransforms().get(0).getTransform() instanceof InsertUpdateMeta,"not native InsertUpdate");
    var up=(InsertUpdateMeta)b.getTransforms().get(0).getTransform();
    var look=up.getInsertUpdateLookupField();
    check("Warehouse".equals(up.getConnection()),"InsertUpdate connection lost");
    check("public".equals(look.getSchemaName()),"InsertUpdate schema lost");
    check("water_level_archive".equals(look.getTableName()),"InsertUpdate table lost");
    check(look.getLookupKeys().size()==2 && look.getValueFields().size()==2,
        "InsertUpdate key/value row count corrupted after undo");
    check("station_code".equals(look.getLookupKeys().get(0).getKeyStream())
        && "station_id".equals(look.getLookupKeys().get(0).getKeyLookup()),"InsertUpdate first key lost");
    check("observed_at".equals(look.getLookupKeys().get(1).getKeyStream())
        && "observation_time".equals(look.getLookupKeys().get(1).getKeyLookup()),"InsertUpdate second key lost");
    check("water_level_m".equals(look.getValueFields().get(0).getUpdateLookup())
        && "level_m".equals(look.getValueFields().get(0).getUpdateStream()),
        "InsertUpdate value row 1 lost");
    check("quality_flag".equals(look.getValueFields().get(1).getUpdateLookup())
        && "quality_status".equals(look.getValueFields().get(1).getUpdateStream()),
        "InsertUpdate value row 2 lost");
    System.out.println("NATIVE_HOP_TABLEOUTPUT_INSERTUPDATE_MULTIUNDO_REVERSE_PARSE_PASS");
    System.out.println("TableOutput fields=3, InsertUpdate keys=2 values=2");
  }
}
