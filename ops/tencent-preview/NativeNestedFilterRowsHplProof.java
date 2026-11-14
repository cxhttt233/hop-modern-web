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

public final class NativeNestedFilterRowsHplProof {
  public static void main(String[] args) throws Exception {
    HopClientEnvironment.init();
    PluginRegistry.addPluginType(TransformPluginType.getInstance());
    PluginRegistry.addPluginType(DatabasePluginType.getInstance());
    PluginRegistry.addPluginType(ActionPluginType.getInstance());
    PluginRegistry.init();
    try (FileInputStream in = new FileInputStream(Path.of(args[0]).toFile())) {
      PipelineMeta pipeline = new PipelineMeta(in, new MemoryMetadataProvider(), Variables.getADefaultVariableSpace());
      TransformMeta transform = pipeline.getTransforms().stream()
        .filter(t -> t.getTransform() instanceof FilterRowsMeta).findFirst()
        .orElseThrow(() -> new AssertionError("FilterRows missing"));
      Condition root = ((FilterRowsMeta) transform.getTransform()).getCondition();
      List<Condition> children = root.getChildren();
      if (children.size() != 3) throw new AssertionError("root children=" + children.size());
      Condition nested = children.get(1);
      if (nested.getChildren().size() != 1) throw new AssertionError("nested children=" + nested.getChildren().size());
      if (!"water_level".equals(nested.getChildren().get(0).getLeftValueName()))
        throw new AssertionError("nested water_level lost");
      if (!"rainfall_24h".equals(nested.getLeftValueName()))
        throw new AssertionError("parent field changed");
      if (children.get(2).getOperator() != Condition.Operator.AND)
        throw new AssertionError("AND changed");
      if (!"6.5".equals(children.get(2).getRightValue().getText()))
        throw new AssertionError("Number constant changed");
      System.out.println("NATIVE_NESTED_FILTERROWS_XML_REVERSE_PARSE_PASS");
    }
  }
}
