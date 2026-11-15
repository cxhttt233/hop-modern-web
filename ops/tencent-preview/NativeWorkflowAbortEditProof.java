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

/** Independent official Hop parser for the exact ABORT .hwf edited by the UI. */
public final class NativeWorkflowAbortEditProof {
  private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
  public static void main(String[] args)throws Exception {
    check(args.length==1,"pass original browser-persisted UUID .hwf");
    Path file=Path.of(args[0]).toRealPath();
    check(file.toString().endsWith(".hwf"),"native HWF only");
    HopClientEnvironment.init();
    PluginRegistry.addPluginType(ActionPluginType.getInstance());
    PluginRegistry.init();
    try(FileInputStream in=new FileInputStream(file.toFile())){
      WorkflowMeta workflow=new WorkflowMeta(in,new MemoryMetadataProvider(),
        Variables.getADefaultVariableSpace());
      check(workflow.getActions().size()==3,"expected original two + one ABORT action");
      check(workflow.getWorkflowHops().size()==1,"original hop was lost or cloned");
      ActionMeta action=workflow.getActions().stream()
        .filter(a->a.getName().equals("Stop on alert")).findFirst()
        .orElseThrow(()->new AssertionError("ABORT action absent after reopen"));
      check(action.getAction() instanceof ActionAbort,
        "native ActionAbort plugin class not restored");
      ActionAbort abort=(ActionAbort)action.getAction();
      check("River warning: 8.25 metres; stop propagation".equals(abort.getMessageAbort()),
        "ABORT message lost or changed");
      check(abort.getMessageLogLevel()==LogLevel.DEBUG,"ABORT DEBUG log level lost");
      check(workflow.getWorkflowHops().stream().anyMatch(h ->
        "Start".equals(h.getFromAction().getName())
          && "Stop on alert".equals(h.getToAction().getName())),
        "native hop Start -> Stop on alert missing");
      System.out.println("NATIVE_HWF_ABORT_EDIT_REVERSE_PARSE_PASS");
      System.out.println("actions="+workflow.getActions().size()+", hops="+workflow.getWorkflowHops().size()
        +", message="+abort.getMessageAbort()+", level="+abort.getMessageLogLevel());
    }
  }
}
