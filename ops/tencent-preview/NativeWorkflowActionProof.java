import java.io.FileInputStream;
import java.nio.file.Path;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.logging.LogLevel;
import org.apache.hop.core.plugins.ActionPluginType;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.workflow.WorkflowMeta;
import org.apache.hop.workflow.action.ActionMeta;
import org.apache.hop.workflow.actions.abort.ActionAbort;
import org.apache.hop.workflow.actions.dummy.ActionDummy;

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
      require(workflow.getActions().size() == 4, "expected two Start + DUMMY + ABORT actions");
      require(workflow.getWorkflowHops().size() == 2, "expected two native workflow hops");
      ActionMeta dummyMeta = workflow.findAction("Native completion");
      ActionMeta abortMeta = workflow.findAction("Abort on failure");
      require(dummyMeta != null && dummyMeta.getAction() instanceof ActionDummy, "native DUMMY not restored");
      require(abortMeta != null && abortMeta.getAction() instanceof ActionAbort, "native ABORT not restored");
      ActionAbort abort = (ActionAbort) abortMeta.getAction();
      require("Water level threshold exceeded".equals(abort.getMessageAbort()), "ABORT message lost");
      require(abort.getMessageLogLevel() == LogLevel.ERROR, "ABORT log level lost");
      require(workflow.getWorkflowHops().stream().anyMatch(h ->
          h.getFromAction() == workflow.findAction("Native completion")
              && h.getToAction() == abortMeta), "native error branch hop lost");
      System.out.println("NATIVE_HWF_DUMMY_ABORT_PLUGIN_REVERSE_PARSE_PASS");
      System.out.println("workflow=" + workflow.getName() + ", actions=" + workflow.getActions().size()
          + ", hops=" + workflow.getWorkflowHops().size() + ", abortLogLevel=" + abort.getMessageLogLevel());
    }
  }
}
