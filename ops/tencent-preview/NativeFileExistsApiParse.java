import java.io.FileInputStream;
import java.nio.file.Path;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.plugins.ActionPluginType;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.workflow.WorkflowMeta;
import org.apache.hop.workflow.actions.fileexists.ActionFileExists;

/** Independent Hop 2.20 consumer of an API-produced HWF; never executes the workflow. */
public final class NativeFileExistsApiParse {
  public static void main(String[] args) throws Exception {
    Path file = Path.of(args[0]);
    if (!file.toString().endsWith(".hwf")) throw new AssertionError("not HWF");
    HopClientEnvironment.init();
    PluginRegistry.addPluginType(ActionPluginType.getInstance());
    PluginRegistry.init();
    if (PluginRegistry.getInstance().findPluginWithId(ActionPluginType.class,"FILE_EXISTS",true)==null)
      throw new AssertionError("official FILE_EXISTS plugin missing");
    try (var input = new FileInputStream(file.toFile())) {
      WorkflowMeta wf = new WorkflowMeta(input,new MemoryMetadataProvider(),Variables.getADefaultVariableSpace());
      boolean nativeFound = wf.getActions().stream().anyMatch(a ->
          a.getAction() instanceof ActionFileExists f && "inputs/flow.csv".equals(f.getFilename()));
      if (!nativeFound) throw new AssertionError("native FILE_EXISTS filename not preserved");
      boolean hopFound = wf.getWorkflowHops().stream().anyMatch(h ->
          "Start".equals(h.getFromAction().getName()) && "Check input".equals(h.getToAction().getName()));
      if (!hopFound) throw new AssertionError("native hop lost");
      System.out.println("T2_FILE_EXISTS_API_HWF_OFFICIAL_JAVA_PARSE_PASS");
    }
  }
}