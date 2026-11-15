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

/** Independent official Hop Java parse of browser-edited InsertUpdate HPL. */
public final class NativeInsertUpdateMappingHplProof {
  static void require(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
  public static void main(String[] args)throws Exception {
    require(args.length==1,"pass browser-persisted UUID .hpl");
    Path file=Path.of(args[0]).toRealPath();
    require(file.toString().endsWith(".hpl"),"native HPL only");
    HopClientEnvironment.init();
    PluginRegistry.addPluginType(TransformPluginType.getInstance());
    PluginRegistry.addPluginType(DatabasePluginType.getInstance());
    PluginRegistry.init();
    try(FileInputStream in=new FileInputStream(file.toFile())){
      PipelineMeta pipeline=new PipelineMeta(in,new MemoryMetadataProvider(),
        Variables.getADefaultVariableSpace());
      require(pipeline.getTransforms().size()==1,"transform count");
      var transform=pipeline.getTransforms().get(0);
      require(transform.getTransform() instanceof InsertUpdateMeta,"native Hop InsertUpdate plugin lost");
      InsertUpdateMeta m=(InsertUpdateMeta)transform.getTransform();
      require("Warehouse".equals(m.getConnection()),"connection lost");
      require("250".equals(m.getCommitSize()),"commit size lost");
      require(!m.isUpdateBypassed(),"upsert mode changed");
      var look=m.getInsertUpdateLookupField();
      require("research".equals(look.getSchemaName()),"edited schema lost");
      require("water_level_archive_checked".equals(look.getTableName()),"edited target table lost");
      require(look.getLookupKeys().size()==2,"matching key count changed");
      require(look.getValueFields().size()==2,"updated value count changed");
      var key0=look.getLookupKeys().get(0);
      require("station_code_checked".equals(key0.getKeyStream()),"edited stream match missing");
      require("station_id".equals(key0.getKeyLookup()),"target lookup column corrupted");
      require("=".equals(key0.getKeyCondition()),"match operation corrupted");
      var key1=look.getLookupKeys().get(1);
      require("observed_at".equals(key1.getKeyStream()),"second key stream lost");
      require("observation_time".equals(key1.getKeyLookup()),"second DB key lost");
      var value0=look.getValueFields().get(0);
      require("water_level_m".equals(value0.getUpdateLookup()),"DB update target corrupted");
      require("level_m".equals(value0.getUpdateStream()),"first input stream corrupted");
      require(value0.isUpdate(),"first update flag disabled");
      var value1=look.getValueFields().get(1);
      require("quality_flag".equals(value1.getUpdateLookup()),"second DB update target corrupted");
      require("quality_status_checked".equals(value1.getUpdateStream()),"edited update stream missing");
      require(value1.isUpdate(),"second update flag disabled");
      System.out.println("NATIVE_HPL_INSERTUPDATE_MAPPING_REVERSE_PARSE_PASS");
      System.out.println("keys="+look.getLookupKeys().size()+", values="+look.getValueFields().size()
        +", table="+look.getTableName());
    }
  }
}
