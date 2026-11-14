import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.nio.file.Path;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.plugins.ActionPluginType;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.workflow.WorkflowMeta;
import org.apache.hop.workflow.action.ActionMeta;

/** Independently parses a real browser-written .hwf with the official Hop ABORT plugin. */
public final class NativeWorkflowActionProof {
  private static void require(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }
  public static void main(String[] args) throws Exception {
    require(args.length == 1, "pass the retained UUID .hwf path");
    Path file = Path.of(args[0]).toRealPath();
    require(file.toString().endsWith(".hwf"), "only a real .hwf may be parsed");
    HopClientEnvironment.init();
    PluginRegistry.addPluginType(ActionPluginType.getInstance());
    PluginRegistry.init();
    try (FileInputStream in = new FileInputStream(file.toFile())) {
      WorkflowMeta workflow = new WorkflowMeta(in, new MemoryMetadataProvider(),
          Variables.getADefaultVariableSpace());
      workflow.getActions().forEach(a ->
          System.out.println("ACTION " + a.getName() + " :: " + a.getAction().getClass().getName()));
      require(workflow.getActions().size() == 4, "expected two Start + DUMMY + ABORT actions");
      require(workflow.getWorkflowHops().size() == 2, "expected two native workflow hops");
      ActionMeta dummyMeta = workflow.getActions().stream()
          .filter(a -> "Native completion".equals(a.getName())).findFirst()
          .orElseThrow(() -> new AssertionError("native DUMMY node missing"));
      ActionMeta abortMeta = workflow.getActions().stream()
          .filter(a -> "Abort on failure".equals(a.getName())).findFirst()
          .orElseThrow(() -> new AssertionError("native ABORT node missing"));
      require(dummyMeta.getAction().getClass().getName().equals(
          "org.apache.hop.workflow.actions.dummy.ActionDummy"), "native DUMMY class not restored");
      Object abort = abortMeta.getAction();
      require(abort.getClass().getName().equals(
          "org.apache.hop.workflow.actions.abort.ActionAbort"), "native ABORT class not restored");
      Method getMessage = abort.getClass().getMethod("getMessageAbort");
      Method getLogLevel = abort.getClass().getMethod("getMessageLogLevel");
      require("Water level threshold exceeded".equals(getMessage.invoke(abort)), "ABORT message lost");
      require("ERROR".equals(String.valueOf(getLogLevel.invoke(abort))), "ABORT log level lost");
      require(workflow.getWorkflowHops().stream().anyMatch(h ->
          "Native completion".equals(h.getFromAction().getName())
              && "Abort on failure".equals(h.getToAction().getName())), "native error branch hop lost");
      System.out.println("NATIVE_HWF_DUMMY_ABORT_PLUGIN_REVERSE_PARSE_PASS");
      System.out.println("workflow=" + workflow.getName() + ", actions=" + workflow.getActions().size()
          + ", hops=" + workflow.getWorkflowHops().size() + ", abortLogLevel=ERROR");
    }
  }
}
