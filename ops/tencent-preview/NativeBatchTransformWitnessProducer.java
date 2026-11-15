import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.plugins.TransformPluginType;
import org.apache.hop.core.database.DatabasePluginType;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.ITransformMeta;
import org.apache.hop.pipeline.transform.TransformMeta;

/** T5 external producer of *new* native Hop HPLs for uncustomized Transforms. */
public final class NativeBatchTransformWitnessProducer {
  private static final List<String> IDS=List.of(
    "CheckSum","ConcatFields","DataGrid","GroupBy","Http","JsonInput",
    "MergeJoin","Rest","SetVariable","StreamLookup");
  private static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
  public static void main(String[]args)throws Exception {
    require(args.length==1,"usage: NativeBatchTransformWitnessProducer <temporary-output-folder>");
    Path folder=Path.of(args[0]).toAbsolutePath().normalize();
    require(Files.isDirectory(folder),"temporary test folder must exist");
    HopClientEnvironment.init();
    PluginRegistry.addPluginType(TransformPluginType.getInstance());
    PluginRegistry.addPluginType(DatabasePluginType.getInstance());
    PluginRegistry.init();
    var vars=Variables.getADefaultVariableSpace();
    var provider=new MemoryMetadataProvider();
    int produced=0;
    for(var id:IDS){
      Path file=folder.resolve("native-batch-"+id+".hpl");
      try {
        var plugin=PluginRegistry.getInstance().findPluginWithId(TransformPluginType.class,id,true);
        require(plugin!=null,"native plugin missing");
        var meta=(ITransformMeta)PluginRegistry.getInstance().loadClass(plugin,ITransformMeta.class);
        meta.setDefault();
        var pipeline=new PipelineMeta();
        pipeline.setName("t5-native-batch-"+id);
        pipeline.addTransform(new TransformMeta(id,"t5-"+id,meta));
        Files.writeString(file,pipeline.getXml(vars),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        try(var input=new FileInputStream(file.toFile())){
          var nativeRead=new PipelineMeta(input,provider,vars);
          require(nativeRead.getTransforms().size()==1,"native transform count lost");
          require(nativeRead.getTransforms().get(0).getTransform().getClass()==meta.getClass(),"native plugin changed on read");
        }
        produced++;
        System.out.println("NATIVE_BATCH_PRODUCER_PASS plugin="+id+" file="+file);
      }catch(Throwable e){
        Files.deleteIfExists(file);
        System.out.println("NATIVE_BATCH_PRODUCER_FAIL plugin="+id+" exception="+e.getClass().getSimpleName()+
          " message="+String.valueOf(e.getMessage()).replace("\n"," ").replace("\r"," ").substring(0,
            Math.min(180,String.valueOf(e.getMessage()).length())));
      }
    }
    System.out.println("NATIVE_BATCH_PRODUCER_TOTAL attempted="+IDS.size()+" nativeReopened="+produced);
    require(produced>=6,"Too few official Hop fixtures produced for T5 external E2E");
  }
}
