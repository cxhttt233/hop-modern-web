import java.io.FileInputStream;
import java.nio.file.Path;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.plugins.TransformPluginType;
import org.apache.hop.core.database.DatabasePluginType;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transforms.sql.ExecSqlMeta;

/** Independently reopens the exact ExecSql .hpl saved by the T4 browser UI. */
public final class NativeExecSqlHplProof {
  private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
  public static void main(String[] args) throws Exception {
    check(args.length == 1, "pass the retained UUID .hpl path");
    Path file = Path.of(args[0]).toRealPath();
    check(file.toString().endsWith(".hpl"), "only a real .hpl may be parsed");
    HopClientEnvironment.init();
    PluginRegistry.addPluginType(TransformPluginType.getInstance());
    PluginRegistry.addPluginType(DatabasePluginType.getInstance());
    PluginRegistry.init();
    try (FileInputStream in = new FileInputStream(file.toFile())) {
      PipelineMeta pipeline = new PipelineMeta(in, new MemoryMetadataProvider(),
          Variables.getADefaultVariableSpace());
      var meta = pipeline.getTransforms().stream()
          .filter(t -> t.getTransform() instanceof ExecSqlMeta).findFirst()
          .orElseThrow(() -> new AssertionError("native ExecSql transform missing"));
      ExecSqlMeta sql = (ExecSqlMeta) meta.getTransform();
      String expected = "SELECT station_code, level_m, observed_at\n"
          + "FROM river_observation\n"
          + "WHERE level_m >= 2.75 AND status = 'ALERT'\n"
          + "ORDER BY observed_at DESC;";
      check(expected.equals(sql.getSql()), "native ExecSql SQL changed after save/reopen");
      check("Warehouse".equals(sql.getConnection()), "native ExecSql connection changed");
      check(sql.isSingleStatement(), "native ExecSql single-statement flag changed");
      check(sql.getSqlFromFile() == null || sql.getSqlFromFile().isBlank(),
          "SQL-from-file changed during inline SQL editing");
      System.out.println("NATIVE_HPL_EXECSQL_SQL_REVERSE_PARSE_PASS");
      System.out.println("pipeline=" + pipeline.getName() + ", transform=" + meta.getName()
          + ", SQL chars=" + sql.getSql().length());
    }
  }
}
