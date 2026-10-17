package org.apache.hop.modern.web.document;
import java.util.*;
import java.nio.file.*;
import java.io.*;
import java.security.*;
import java.util.Base64;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
public class WorkbenchEngineProblemProbe {
  public static void main(String[] args) throws Exception {
    org.apache.hop.modern.web.ModernWebServer.initializePipelinePlugins();
    byte[] bytes=Files.readAllBytes(Path.of(args[0]));
    var meta=new PipelineMeta(new ByteArrayInputStream(bytes),new MemoryMetadataProvider(),new Variables());
    System.out.println("BASE_NODES="+meta.getTransforms().size());
    var nodes=new ArrayList<WorkbenchEngineResource.EngineNode>();
    for(var t:meta.getTransforms()) nodes.add(new WorkbenchEngineResource.EngineNode(
      t.getName(),t.getName(),"transform",t.getPluginId(),"transform",t.getLocation().x,t.getLocation().y));
    nodes.add(new WorkbenchEngineResource.EngineNode("json-input-1","JSON 输入 1","transform","JsonInput","transform",50,90));
    nodes.add(new WorkbenchEngineResource.EngineNode("http-1","HTTP 客户端 2","transform","Http","transform",270,90));
    var edges=List.of(new WorkbenchEngineResource.EngineEdge("draft-hop-json-input-1-http-1","json-input-1","http-1",true));
    var live=new WorkbenchEngineResource("sandbox-012345678901234567890123456789");
    String base=Base64.getEncoder().encodeToString(bytes);
    String revision=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    var configRequests=List.of("json-input-1","http-1");
    Map<String,com.fasterxml.jackson.databind.JsonNode> configs=new HashMap<>();
    for(String nodeId:configRequests){
      var request=new WorkbenchEngineResource.EngineRequest("config-read","Demo/proof.hpl",
          revision,base,nodes,edges,nodeId,null);
      var reply=live.process("sandbox-012345678901234567890123456789",request);
      System.out.println("CONFIG_READ_"+nodeId+"_STATUS="+reply.getStatus());
      if(reply.getStatus()!=200) throw new IllegalStateException(String.valueOf(reply.getEntity()));
      var config=(WorkbenchEngineResource.ConfigResponse)reply.getEntity();
      if(config.descriptor()==null || config.config()==null || !config.config().isObject())
        throw new IllegalStateException("descriptor or config unavailable");
      System.out.println("CONFIG_FIELDS_"+nodeId+"="+config.config().size());
      configs.put(nodeId,config.config());
    }
    var saveRequest=new WorkbenchEngineResource.EngineRequest("save","Demo/proof.hpl",
        revision,base,nodes,edges,null,configs);
    var saved=live.process("sandbox-012345678901234567890123456789",saveRequest);
    System.out.println("SAVE_WITH_CONFIG_STATUS="+saved.getStatus());
    if(saved.getStatus()!=200)throw new IllegalStateException("config save failed: "+saved.getEntity());
    var newXml=Base64.getDecoder().decode(((WorkbenchEngineResource.EngineGraph)saved.getEntity()).xmlBase64());
    var confirmed=new PipelineMeta(new ByteArrayInputStream(newXml),new MemoryMetadataProvider(),new Variables());
    System.out.println("CONFIG_AND_GRAPH_NATIVE_ROUNDTRIP="+confirmed.getTransforms().size());
    try {
      WorkbenchEngineResource.mergeGraph(meta,nodes,edges);
      System.out.println("MERGE_OK="+meta.getTransforms().size());
      byte[] updated=meta.getXml(new Variables()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
      var reread=new PipelineMeta(new ByteArrayInputStream(updated),new MemoryMetadataProvider(),new Variables());
      System.out.println("SERIAL_AND_REOPEN_OK="+reread.getTransforms().size()+" "+reread.getPipelineHops().size());
    } catch(Throwable ex) {
      System.out.println("FAILED_TYPE="+ex.getClass().getName()+" "+ex.getMessage());
      ex.printStackTrace(System.out);
      throw ex;
    }
  }
}