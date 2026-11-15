package org.apache.hop.modern.web.document;
import java.nio.file.*;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.workflow.WorkflowMeta;
import org.apache.hop.workflow.actions.start.ActionStart;
public class T2CreateProof {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] args)throws Exception {
  HopClientEnvironment.init();
  org.apache.hop.core.plugins.PluginRegistry.addPluginType(org.apache.hop.core.plugins.ActionPluginType.getInstance());
  org.apache.hop.core.plugins.PluginRegistry.init();
  Path dir=Files.createTempDirectory("t2-create-uuid-");
  String before=System.getProperty("hop.modern.workspace");
  try {
   System.setProperty("hop.modern.workspace",dir.toString());
   var vars=Variables.getADefaultVariableSpace();
   var store=new WorkflowDocumentStore(new MemoryMetadataProvider(),vars);
   var resource=new WorkflowResource(store);
   var req=new WorkflowResource.CreateRequest("new.hwf","New workflow");
   var created=resource.create(req);
   check(created.getStatus()==201,"create status "+created.getStatus());
   var snapshot=(WorkflowResource.Snapshot)created.getEntity();
   check(snapshot.actions().size()==1,"missing start");
   check(resource.create(req).getStatus()==409,"overwrite not rejected");
   check(resource.create(new WorkflowResource.CreateRequest("../outside.hwf","bad")).getStatus()==409,"traversal not rejected");
   Path outside=Files.createTempDirectory("t2-external-");
   try {
    Files.createSymbolicLink(dir.resolve("escape"),outside);
    check(resource.create(new WorkflowResource.CreateRequest("escape/no.hwf","bad")).getStatus()==409,"symlink not rejected");
    check(!Files.exists(outside.resolve("no.hwf")),"outside modified");
   }finally{Files.delete(dir.resolve("escape"));Files.delete(outside);}
   var loaded=store.open(dir.resolve("new.hwf"));
   check(loaded.getActions().size()==1 && loaded.getActions().get(0).getAction() instanceof ActionStart,"native START lost");
   check(resource.open(new WorkflowResource.OpenRequest("new.hwf")).getStatus()==200,"reopen failed");
   System.out.println("T2_NATIVE_CREATE_201_DUPLICATE_409_TRAVERSAL_409_SYMLINK_409_OFFICIAL_REOPEN_PASS");
  }finally{if(before==null)System.clearProperty("hop.modern.workspace");else System.setProperty("hop.modern.workspace",before);Files.deleteIfExists(dir.resolve("new.hwf"));Files.delete(dir);}
 }
}