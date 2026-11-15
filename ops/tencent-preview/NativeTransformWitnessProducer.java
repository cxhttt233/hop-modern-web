import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.plugins.TransformPluginType;
import org.apache.hop.core.database.DatabasePluginType;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.pipeline.transforms.tableoutput.TableOutputField;
import org.apache.hop.pipeline.transforms.tableoutput.TableOutputMeta;
import org.apache.hop.pipeline.transforms.sql.ExecSqlMeta;

/**
 * Produce two real .hpl fixtures using Hop's own Java metadata serializer,
 * not browser-side fabricated XML or a copied academic example.
 * Only constructs metadata. Never connects to a DB or executes a pipeline.
 */
public final class NativeTransformWitnessProducer {
  private static void check(boolean ok, String msg) {
    if (!ok) throw new AssertionError(msg);
  }
  private static PipelineMeta nativePipeline(String name, TransformMeta transform) {
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName(name);
    pipeline.addTransform(transform);
    return pipeline;
  }
  private static PipelineMeta writeAndParse(Path dest, PipelineMeta p) throws Exception {
    check(dest.toString().endsWith(".hpl"), "fixture path must end in .hpl");
    var vars = Variables.getADefaultVariableSpace();
    byte[] wire = p.getXml(vars).getBytes(StandardCharsets.UTF_8);
    Files.write(dest, wire);
    try (var source = new ByteArrayInputStream(Files.readAllBytes(dest))) {
      return new PipelineMeta(source, new MemoryMetadataProvider(), vars);
    }
  }
  public static void main(String[] args) throws Exception {
    check(args.length == 2, "usage: NativeTransformWitnessProducer table-output.hpl exec-sql.hpl");
    HopClientEnvironment.init();
    PluginRegistry.addPluginType(TransformPluginType.getInstance());
    PluginRegistry.addPluginType(DatabasePluginType.getInstance());
    PluginRegistry.init();
    TableOutputMeta output = new TableOutputMeta();
    output.setDefault();
    output.setConnection("Warehouse");
    output.setSchemaName("public");
    output.setTableName("water_level_archive");
    output.setSpecifyFields(true);
    output.setFields(List.of(
        new TableOutputField("station_id", "station_code"),
        new TableOutputField("water_level_m", "level_m"),
        new TableOutputField("observation_time", "observed_at")
    ));
    PipelineMeta outputRoundtrip = writeAndParse(Path.of(args[0]),
        nativePipeline("table-output-native-witness", new TransformMeta("TableOutput", "water-level-archive", output)));
    check(outputRoundtrip.getTransforms().size() == 1, "TableOutput count");
    var outputLoaded = outputRoundtrip.getTransforms().get(0);
    check(outputLoaded.getTransform() instanceof TableOutputMeta, "TableOutput native plugin missing");
    TableOutputMeta out = (TableOutputMeta) outputLoaded.getTransform();
    check(out.isSpecifyFields(), "TableOutput specifyFields lost");
    check("water_level_archive".equals(out.getTableName()), "TableOutput target table lost");
    check("Warehouse".equals(out.getConnection()), "TableOutput connection lost");
    check(out.getFields().size() == 3, "TableOutput field mapping count");
    check("level_m".equals(out.getFields().get(1).getFieldStream())
       && "water_level_m".equals(out.getFields().get(1).getFieldDatabase()),
       "TableOutput stream->database mapping lost");

    ExecSqlMeta exec = new ExecSqlMeta();
    exec.setDefault();
    exec.setConnection("Warehouse");
    exec.setSql("SELECT station_code, level_m FROM river_observation WHERE level_m >= 1.5;");
    exec.setSingleStatement(true);
    PipelineMeta sqlRoundtrip = writeAndParse(Path.of(args[1]),
        nativePipeline("exec-sql-native-witness", new TransformMeta("ExecSql", "read-water-observation", exec)));
    check(sqlRoundtrip.getTransforms().size() == 1, "ExecSql count");
    var sqlLoaded = sqlRoundtrip.getTransforms().get(0);
    check(sqlLoaded.getTransform() instanceof ExecSqlMeta, "ExecSql native plugin missing");
    ExecSqlMeta sql = (ExecSqlMeta) sqlLoaded.getTransform();
    check(exec.getSql().equals(sql.getSql()), "ExecSql inline SQL lost");
    check(sql.isSingleStatement(), "ExecSql single-statement flag lost");
    check("Warehouse".equals(sql.getConnection()), "ExecSql connection lost");

    System.out.println("NATIVE_TABLEOUTPUT_PRODUCER_ROUNDTRIP_PASS fields=" + out.getFields().size()
        + " table=" + out.getTableName());
    System.out.println("NATIVE_EXECSQL_PRODUCER_ROUNDTRIP_PASS sqlChars=" + sql.getSql().length());
  }
}