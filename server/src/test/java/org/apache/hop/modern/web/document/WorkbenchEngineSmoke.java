package org.apache.hop.modern.web.document;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import jakarta.ws.rs.core.Response;

public final class WorkbenchEngineSmoke {
  static int checks;
  static void ok(boolean pass, String info) {
    if (!pass) throw new AssertionError(info);
    checks++;
    System.out.println("PASS " + info);
  }
  static String sha(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
  public static void main(String[] args) throws Exception {
    org.apache.hop.modern.web.ModernWebServer.initializePipelinePlugins();
    byte[] original=Files.readAllBytes(Path.of(args[0]));
    String revision=sha(original);
    WorkbenchEngineResource resource = new WorkbenchEngineResource("sandbox-only-0123456789abcdefABCDEF");
    var open = new WorkbenchEngineResource.EngineRequest("open","Demo/proof.hpl",revision,
      Base64.getEncoder().encodeToString(original),null,null);
    ok(resource.process("bad",open).getStatus()==403,"reject non-Workbench token");
    var a=resource.process("sandbox-only-0123456789abcdefABCDEF",open);
    ok(a.getStatus()==200,"open Hop XML into graph: "+a.getStatus());
    var opened=(WorkbenchEngineResource.EngineGraph)a.getEntity();
    ok(opened.nodes().size()==2,"original has two nodes");
    ok(opened.edges().size()==1,"original has one connection");
    var nodes=new ArrayList<WorkbenchEngineResource.EngineNode>();
    for(var n:opened.nodes()) nodes.add(new WorkbenchEngineResource.EngineNode(
       n.id(),n.name(),n.kind(),n.pluginId(),n.pluginType(),n.x(),n.y()));
    var before=nodes.get(0);
    nodes.set(0,new WorkbenchEngineResource.EngineNode(before.id(),before.name(),
       before.kind(),before.pluginId(),before.pluginType(),before.x()+25,before.y()+20));
    nodes.add(new WorkbenchEngineResource.EngineNode("grid-1","数据网格 1","transform",
       "DataGrid","transform",260,130));
    var edges=new ArrayList<WorkbenchEngineResource.EngineEdge>();
    for(var e:opened.edges())edges.add(new WorkbenchEngineResource.EngineEdge(
       e.id(),e.source(),e.target(),e.enabled()));
    var savedRequest=new WorkbenchEngineResource.EngineRequest("save","Demo/proof.hpl",revision,
      Base64.getEncoder().encodeToString(original),nodes,edges);
    var save=resource.process("sandbox-only-0123456789abcdefABCDEF",savedRequest);
    ok(save.getStatus()==200,"native Hop XML serialized: "+save.getStatus());
    var changed=(WorkbenchEngineResource.EngineGraph)save.getEntity();
    ok(changed.nodes().size()==3,"added DataGrid");
    byte[] output=Base64.getDecoder().decode(changed.xmlBase64());
    ok(output.length>0,"output is XML bytes");
    ok(!sha(output).equals(revision),"revision changed");
    var reopen=new WorkbenchEngineResource.EngineRequest("open","Demo/proof.hpl",
      sha(output),Base64.getEncoder().encodeToString(output),null,null);
    var reopened=(WorkbenchEngineResource.EngineGraph)
      resource.process("sandbox-only-0123456789abcdefABCDEF",reopen).getEntity();
    ok(reopened.nodes().size()==3,"fresh Hop parse sees new node");
    ok(reopened.edges().size()==1,"original Hop connection preserved");
    ok(reopened.nodes().get(0).x()==before.x()+25,"node position persisted");
    ok(new String(output,java.nio.charset.StandardCharsets.UTF_8).contains("V2 real graph proof"),
       "original native metadata preserved");
    var invalid=new WorkbenchEngineResource.EngineRequest("save","Demo/proof.hpl",revision,
      Base64.getEncoder().encodeToString(original),
      List.of(new WorkbenchEngineResource.EngineNode("evil","Evil","transform",
        "MyEvilPlugin","transform",0,0)),List.of());
    ok(resource.process("sandbox-only-0123456789abcdefABCDEF",invalid).getStatus()==400,
       "untrusted plugin rejected");
    ok(resource.process("sandbox-only-0123456789abcdefABCDEF",new
      WorkbenchEngineResource.EngineRequest("open","Demo/proof.hpl","0".repeat(64),
      Base64.getEncoder().encodeToString(original),null,null)).getStatus()==409,
      "request must carry original content revision");
    System.out.println("WORKBENCH_ENGINE_IN_MEMORY_NATIVE_HOP_SUCCESS checks="+checks);
  }
}