import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.plugins.ActionPluginType;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.workflow.WorkflowMeta;
import org.apache.hop.workflow.action.ActionMeta;
import org.apache.hop.workflow.actions.fileexists.ActionFileExists;

public final class NativeFileExistsArtifactProof {
  static void check(boolean x,String text){if(!x)throw new AssertionError(text);}
  public static void main(String[] args) throws Exception {
    Path destination=Path.of(args[0]).toAbsolutePath();
    check(destination.toString().endsWith(".hwf"),"native HWF required");
    HopClientEnvironment.init();
    PluginRegistry.addPluginType(ActionPluginType.getInstance());
    PluginRegistry.init();
    var plugin=PluginRegistry.getInstance().findPluginWithId(ActionPluginType.class,"FILE_EXISTS",true);
    check(plugin!=null,"official FILE_EXISTS plugin discovery failed");
    ActionFileExists fileAction=new ActionFileExists("Check upstream file");
    fileAction.setFilename("/workspace/synthetic-waterlevel.csv");
    WorkflowMeta workflow=new WorkflowMeta();
    workflow.setName("FILE_EXISTS native plugin producer witness");
    workflow.addAction(new ActionMeta(fileAction));
    var variables=Variables.getADefaultVariableSpace();
    Files.writeString(destination,workflow.getXml(variables),StandardCharsets.UTF_8);
    try(var source=new FileInputStream(destination.toFile())){
      WorkflowMeta loaded=new WorkflowMeta(source,new MemoryMetadataProvider(),variables);
      check(loaded.getActions().size()==1,"action lost in WorkflowMeta");
      check(loaded.getActions().get(0).getAction() instanceof ActionFileExists,
          "ActionMeta incorrectly parsed as non-native FILE_EXISTS");
      ActionFileExists parsed=(ActionFileExists)loaded.getActions().get(0).getAction();
      check("/workspace/synthetic-waterlevel.csv".equals(parsed.getFilename()),
          "native filename lost in XML roundtrip");
      System.out.println("NATIVE_HOP_FILE_EXISTS_ARTIFACT_AND_HWF_PARSE_PASS");
      System.out.println("plugin=FILE_EXISTS, config filename="+parsed.getFilename());
    }
  }
}