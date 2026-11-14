import java.io.FileInputStream;
import java.nio.file.Path;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.plugins.TransformPluginType;
import org.apache.hop.core.database.DatabasePluginType;
import org.apache.hop.core.plugins.ActionPluginType;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.pipeline.transforms.tableinput.TableInputMeta;

/** Independently parses the exact UUID .hpl written by the T4 browser test. */
public final class NativeTableInputSqlHplProof {
  private static void require(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }
  public static void main(String[] args) throws Exception {
    require(args.length == 1, "pass the retained UUID .hpl path");
    Path file = Path.of(args[0]).toRealPath();
    require(file.toString().endsWith(".hpl"), "only a real .hpl may be parsed");
    HopClientEnvironment.init();
    PluginRegistry.addPluginType(TransformPluginType.getInstance());
    PluginRegistry.addPluginType(DatabasePluginType.getInstance());
    PluginRegistry.addPluginType(ActionPluginType.getInstance());
    PluginRegistry.init();
    try (FileInputStream in = new FileInputStream(file.toFile())) {
      PipelineMeta pipeline = new PipelineMeta(in, new MemoryMetadataProvider(),
          Variables.getADefaultVariableSpace());
      TransformMeta transform = pipeline.getTransforms().stream()
          .filter(t -> t.getTransform() instanceof TableInputMeta).findFirst()
          .orElseThrow(() -> new AssertionError("native TableInput transform missing"));
      TableInputMeta meta = (TableInputMeta) transform.getTransform();
      String expected = "SELECT customer_id, total_amount, currency\n"
          + "FROM invoices\n"
          + "WHERE total_amount >= 125.50 AND status = 'PAID'\n"
          + "ORDER BY total_amount DESC;";
      require(expected.equals(meta.getSql()), "native TableInput SQL differs after HPL reopen");
      require("Warehouse".equals(meta.getConnection()), "native TableInput connection changed");
      require(meta.getSqlFromFile() == null || meta.getSqlFromFile().isBlank(),
          "SQL file source unexpectedly overrides inline SQL");
      System.out.println("NATIVE_HPL_TABLEINPUT_SQL_REVERSE_PARSE_PASS");
      System.out.println("pipeline=" + pipeline.getName() + ", transform=" + transform.getName()
          + ", connection=" + meta.getConnection() + ", SQL chars=" + meta.getSql().length());
    }
  }
}
