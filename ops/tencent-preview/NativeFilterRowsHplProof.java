import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.List;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.Condition;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.plugins.TransformPluginType;
import org.apache.hop.core.database.DatabasePluginType;
import org.apache.hop.core.plugins.ActionPluginType;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.pipeline.transforms.filterrows.FilterRowsMeta;

/** Independently parses the exact UUID .hpl written by the T3 Playwright test. */
public final class NativeFilterRowsHplProof {
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
          .filter(t -> t.getTransform() instanceof FilterRowsMeta).findFirst()
          .orElseThrow(() -> new AssertionError("FilterRows transform missing"));
      FilterRowsMeta meta = (FilterRowsMeta) transform.getTransform();
      Condition root = meta.getCondition();
      List<Condition> children = root.getChildren();
      require(children.size() == 3, "expected 3 native conditions, got " + children.size());
      require("water_level".equals(children.get(0).getLeftValueName()), "first left field lost");
      require("warning_level".equals(children.get(0).getRightValueName()), "first right field lost");
      require("rainfall_24h".equals(children.get(1).getLeftValueName()), "second left field lost");
      require("rainfall_limit".equals(children.get(1).getRightValueName()), "second right field lost");
      require(children.get(1).getOperator() == Condition.Operator.AND, "native AND lost");
      require(children.get(2).getOperator() == Condition.Operator.AND, "edited AND lost");
      Condition.CValue constant = children.get(2).getRightValue();
      require(constant != null, "native CValue missing");
      require("threshold".equals(constant.getName()), "CValue name lost");
      require("Number".equals(constant.getType()), "CValue type lost");
      require("6.5".equals(constant.getText()), "CValue text lost");
      require(constant.getLength() == 10 && constant.getPrecision() == 1,
          "CValue length/precision lost");
      require(!constant.isNullValue(), "CValue null flag changed");
      System.out.println("NATIVE_HPL_FILTERROWS_XML_REVERSE_PARSE_PASS");
      System.out.println("pipeline=" + pipeline.getName() + ", transform=" + transform.getName()
          + ", children=" + children.size() + ", operators=" + children.get(1).getOperator()
          + "/" + children.get(2).getOperator() + ", constant=Number:6.5");
    }
  }
}
