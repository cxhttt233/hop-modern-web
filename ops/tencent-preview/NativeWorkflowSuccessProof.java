import java.io.FileInputStream;
import java.nio.file.Path;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.plugins.ActionPluginType;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.workflow.WorkflowMeta;

/** Verify a real UI-saved .hwf reopens as the official SUCCESS plugin, not a placeholder. */
public final class NativeWorkflowSuccessProof {
  public static void main(String[] args) throws Exception {
    Path file = Path.of(args[0]).toRealPath();
    if (!file.toString().endsWith(".hwf")) throw new AssertionError("not .hwf");
    HopClientEnvironment.init();
    PluginRegistry.addPluginType(ActionPluginType.getInstance());
    PluginRegistry.init();
    try (FileInputStream in = new FileInputStream(file.toFile())) {
      WorkflowMeta meta = new WorkflowMeta(in, new MemoryMetadataProvider(), Variables.getADefaultVariableSpace());
      if (meta.getActions().size() != 4 || meta.getWorkflowHops().size() != 2)
        throw new AssertionError("native workflow nodes/hops lost");
      var action = meta.getActions().stream().filter(a -> "Success terminal".equals(a.getName()))
          .findFirst().orElseThrow();
      if (!action.getAction().getClass().getName().equals("org.apache.hop.workflow.actions.success.ActionSuccess"))
        throw new AssertionError("SUCCESS plugin not resolved: " + action.getAction().getClass());
      System.out.println("PASS native SUCCESS plugin class, 4 actions, 2 hops");
    }
  }
}