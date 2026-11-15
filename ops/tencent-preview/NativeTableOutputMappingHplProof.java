import java.io.FileInputStream;
import java.nio.file.Path;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.plugins.TransformPluginType;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.database.DatabasePluginType;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transforms.tableoutput.TableOutputMeta;

/** Independently reopens the exact Hop .hpl saved by the browser after native field mapping. */
public final class NativeTableOutputMappingHplProof {
  private static void require(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }
  public static void main(String[] args) throws Exception {
    require(args.length == 1, "pass the retained UUID .hpl path");
    Path path = Path.of(args[0]).toRealPath();
    require(path.toString().endsWith(".hpl"), "native HPL only");
    HopClientEnvironment.init();
    PluginRegistry.addPluginType(TransformPluginType.getInstance());
    PluginRegistry.addPluginType(DatabasePluginType.getInstance());
    PluginRegistry.init();
    try (FileInputStream in = new FileInputStream(path.toFile())) {
      PipelineMeta pipeline = new PipelineMeta(in, new MemoryMetadataProvider(),
          Variables.getADefaultVariableSpace());
      var transform = pipeline.getTransforms().stream()
          .filter(t -> t.getTransform() instanceof TableOutputMeta).findFirst()
          .orElseThrow(() -> new AssertionError("native TableOutput missing"));
      TableOutputMeta output = (TableOutputMeta) transform.getTransform();
      require("Warehouse".equals(output.getConnection()), "connection corrupted");
      require("public".equals(output.getSchemaName()), "schema corrupted");
      require("water_level_archive".equals(output.getTableName()), "target table corrupted");
      require(output.isSpecifyFields(), "specify_fields flag lost");
      var fields = output.getFields();
      require(fields.size() == 3, "field mapping count changed");
      require("station_code".equals(fields.get(0).getFieldStream())
          && "station_id".equals(fields.get(0).getFieldDatabase()), "row 1 metadata lost");
      require("level_m_revised".equals(fields.get(1).getFieldStream())
          && "water_level_m_verified".equals(fields.get(1).getFieldDatabase()),
          "row 2 stream→database mapping lost");
      require("observed_at".equals(fields.get(2).getFieldStream())
          && "observation_time".equals(fields.get(2).getFieldDatabase()),
          "row 3 metadata lost");
      System.out.println("NATIVE_HPL_TABLEOUTPUT_MAPPING_REVERSE_PARSE_PASS");
      System.out.println("pipeline=" + pipeline.getName() + ", nativeFields=" + fields.size());
    }
  }
}
