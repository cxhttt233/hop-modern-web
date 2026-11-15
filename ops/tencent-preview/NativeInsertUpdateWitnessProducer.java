import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.plugins.TransformPluginType;
import org.apache.hop.core.database.DatabasePluginType;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.json.ConfigJsonSerializer;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.pipeline.transforms.insertupdate.InsertUpdateMeta;
import org.apache.hop.pipeline.transforms.insertupdate.InsertUpdateLookupField;
import org.apache.hop.pipeline.transforms.insertupdate.InsertUpdateKeyField;
import org.apache.hop.pipeline.transforms.insertupdate.InsertUpdateValue;

/** Produce and reverse-parse a real native Hop InsertUpdate .hpl without executing SQL. */
public final class NativeInsertUpdateWitnessProducer {
  static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
  public static void main(String[] args)throws Exception {
    require(args.length==1,"usage: NativeInsertUpdateWitnessProducer <uuid.hpl>");
    var path=Path.of(args[0]).toAbsolutePath();
    require(path.toString().endsWith(".hpl"),"only a native HPL may be emitted");
    HopClientEnvironment.init();
    PluginRegistry.addPluginType(TransformPluginType.getInstance());
    PluginRegistry.addPluginType(DatabasePluginType.getInstance());
    PluginRegistry.init();
    InsertUpdateMeta meta=new InsertUpdateMeta();
    meta.setDefault();
    meta.setConnection("Warehouse");
    meta.setCommitSize("250");
    meta.setUpdateBypassed(false);
    // The four-argument InsertUpdateLookupField constructor invokes init(),
    // which resets table/schema/lists. Use native setters after construction.
    InsertUpdateLookupField lookup=new InsertUpdateLookupField();
    lookup.setSchemaName("public");
    lookup.setTableName("water_level_archive");
    lookup.setLookupKeys(List.of(
        new InsertUpdateKeyField("station_code", "station_id", "="),
        new InsertUpdateKeyField("observed_at", "observation_time", "=")));
    lookup.setValueFields(List.of(
        new InsertUpdateValue("water_level_m", "level_m", true),
        new InsertUpdateValue("quality_flag", "quality_status", true)));
    meta.setInsertUpdateLookupField(lookup);
    PipelineMeta pipeline=new PipelineMeta();
    pipeline.setName("insert-update-native-waterlevel");
    pipeline.addTransform(new TransformMeta("InsertUpdate", "upsert-water-level", meta));
    var vars=Variables.getADefaultVariableSpace();
    Files.writeString(path,pipeline.getXml(vars),StandardCharsets.UTF_8);
    var provider=new MemoryMetadataProvider();
    try(FileInputStream source=new FileInputStream(path.toFile())){
      var loaded=new PipelineMeta(source,provider,vars);
      require(loaded.getTransforms().size()==1,"transform count");
      require(loaded.getTransforms().get(0).getTransform() instanceof InsertUpdateMeta,
        "Hop native InsertUpdate plugin not loaded");
      var updated=(InsertUpdateMeta)loaded.getTransforms().get(0).getTransform();
      require("Warehouse".equals(updated.getConnection()),"connection changed");
      require("250".equals(updated.getCommitSize()),"commit size changed");
      var look=updated.getInsertUpdateLookupField();
      require("public".equals(look.getSchemaName()),"schema changed");
      require("water_level_archive".equals(look.getTableName()),"table changed");
      require(look.getLookupKeys().size()==2,"native key field list changed");
      require(look.getValueFields().size()==2,"native update field list changed");
      require("station_code".equals(look.getLookupKeys().get(0).getKeyStream())
        && "station_id".equals(look.getLookupKeys().get(0).getKeyLookup()),
        "native key stream vs target direction changed");
      require("level_m".equals(look.getValueFields().get(0).getUpdateStream())
        && "water_level_m".equals(look.getValueFields().get(0).getUpdateLookup()),
        "native value stream vs target direction changed");
      var config=ConfigJsonSerializer.toJson(updated,provider);
      System.out.println("INSERTUPDATE_NATIVE_JSON="+config);
      try {
        var roundTrip=ConfigJsonSerializer.fromJson(config,InsertUpdateMeta.class,provider);
        require(roundTrip.getInsertUpdateLookupField().getLookupKeys().size()==2,
          "native config JSON parser lost keys");
        require(roundTrip.getInsertUpdateLookupField().getValueFields().size()==2,
          "native config JSON parser lost values");
        System.out.println("NATIVE_INSERTUPDATE_CONFIG_JSON_CODEC_PASS");
      }catch(Exception error){
        System.out.println("NATIVE_INSERTUPDATE_CONFIG_JSON_CODEC_FAIL");
        error.printStackTrace(System.out);
        throw error;
      }
      System.out.println("NATIVE_INSERTUPDATE_PRODUCER_HPL_REVERSE_PARSE_PASS");
      System.out.println("plugin=InsertUpdate, keys=2, values=2, file="+path);
    }
  }
}