package org.apache.hop.modern.web.document;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.hop.metadata.serializer.json.ConfigJsonSerializer;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.plugins.TransformPluginType;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.pipeline.PipelineHopMeta;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.ITransformMeta;
import org.apache.hop.pipeline.transform.TransformMeta;

/**
 * Hop's headless graph serialization engine. No filesystem or browser sessions.
 * Workbench (authenticated Spring backend) owns file paths, CAS, audit and atomic write.
 * Hosted in the ALREADY-RUNNING standalone Modern Java21 process; independent of
 * Hop Web images, REST routes, Tomcat and RAP.
 */
@Path("/api/workbench-internal/engine")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public final class WorkbenchEngineResource {
  private static final int MAX_HPL_BYTES = 8 * 1024 * 1024;
  private static final Set<String> ALLOWED_NEW_PLUGINS = Set.of(
      "CheckSum","ConcatFields","DataGrid","ExecSql","FilterRows",
      "GroupBy","Http","InsertUpdate","JsonInput","MergeJoin",
      "ReplaceString","Rest","ScriptValueMod","SelectValues","SetVariable",
      "StreamLookup","StringCut","TableInput","TableOutput","UniqueRowsByHashSet");
  private final String token;
  private final IVariables variables;
  private final MemoryMetadataProvider metadata;

  public WorkbenchEngineResource() {
    this(System.getenv("WORKBENCH_ENGINE_TOKEN"));
  }

  public WorkbenchEngineResource(String token) {
    this.token = token == null ? "" : token;
    variables = new Variables();
    metadata = new MemoryMetadataProvider();
  }

  @POST
  public Response process(@HeaderParam("X-Workbench-Engine-Token") String supplied, EngineRequest request) {
    if (token.length() < 32 || supplied == null ||
        !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                               supplied.getBytes(StandardCharsets.UTF_8))) {
      return error(403, "engine_access_denied");
    }
    if (request == null || request.xmlBase64() == null || request.xmlBase64().length() > MAX_HPL_BYTES * 2
        || request.id() == null || request.id().length() > 350
        || !Set.of("open","save","config-read").contains(request.operation())) return error(400,"invalid_request");
    try {
      byte[] bytes = Base64.getDecoder().decode(request.xmlBase64());
      if (bytes.length > MAX_HPL_BYTES) return error(413,"pipeline_too_large");
      String originalRevision = sha(bytes);
      if (request.revision() == null || !request.revision().equals(originalRevision))
        return error(409,"engine_revision_mismatch");
      PipelineMeta pipeline = new PipelineMeta(new ByteArrayInputStream(bytes), metadata, variables);
      if ("open".equals(request.operation())) {
        return Response.ok(response(request.id(), pipeline, originalRevision, null)).build();
      }
      if (request.nodes() == null || request.edges() == null ||
          request.nodes().size() > 1000 || request.edges().size() > 3000)
        return error(400,"invalid_graph");
      mergeGraph(pipeline, request.nodes(), request.edges());
      if ("config-read".equals(request.operation())) {
        EngineNode node = selectedNode(request, request.nodeId());
        TransformMeta transform = pipeline.findTransform(node.name());
        if (transform == null || transform.getTransform() == null)
          return error(422, "unknown_transform");
        return Response.ok(new ConfigResponse(
            node.id(), node.pluginId(),
            ConfigJsonSerializer.toJson(transform.getTransform(), metadata),
            AutomaticConfigDescriptor.describe(transform.getTransform().getClass()))).build();
      }
      if (request.configEdits() != null) {
        if (request.configEdits().size() > 100) return error(400,"too_many_configs");
        for (Map.Entry<String, JsonNode> entry : request.configEdits().entrySet()) {
          EngineNode node = selectedNode(request, entry.getKey());
          TransformMeta transform = pipeline.findTransform(node.name());
          if (transform == null || transform.getTransform() == null ||
              entry.getValue() == null || !entry.getValue().isObject())
            return error(400,"invalid_transform_config");
          @SuppressWarnings({"unchecked", "rawtypes"})
          ITransformMeta config = (ITransformMeta) ConfigJsonSerializer.fromJson(
              entry.getValue(), (Class) transform.getTransform().getClass(), metadata);
          transform.setTransform(config);
          transform.setChanged();
        }
      }
      byte[] updated = pipeline.getXml(variables).getBytes(StandardCharsets.UTF_8);
      if (updated.length > MAX_HPL_BYTES) return error(413,"pipeline_too_large");
      PipelineMeta check = new PipelineMeta(new ByteArrayInputStream(updated), metadata, variables);
      if (check.getTransforms().size() != request.nodes().size() ||
          check.getPipelineHops().size() != request.edges().size())
        return error(422,"graph_roundtrip_failed");
      return Response.ok(response(request.id(), check, sha(updated),
                                 Base64.getEncoder().encodeToString(updated))).build();
    } catch (IllegalArgumentException ex) {
      return error(400,"invalid_graph");
    } catch (Exception ex) {
      return error(422,"hop_engine_failed");
    }
  }

  private static EngineNode selectedNode(EngineRequest request, String id) {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("missing node id");
    return request.nodes().stream()
        .filter(n -> n != null && id.equals(n.id()))
        .findFirst().orElseThrow(() -> new IllegalArgumentException("unknown node"));
  }

  private EngineGraph response(String id, PipelineMeta pipeline, String revision, String xml) {
    var graph = new PipelineGraphAdapter().project(
        new PipelineDocument(id, java.nio.file.Path.of("/workbench-engine-memory.hpl"), pipeline),
        revision);
    return new EngineGraph(graph.id(),graph.name(),graph.revision(),graph.nodes(),graph.edges(),xml);
  }

  static void mergeGraph(PipelineMeta meta, List<EngineNode> nodes, List<EngineEdge> edges) throws Exception {
    Map<String,TransformMeta> original = new HashMap<>();
    for (TransformMeta t : meta.getTransforms()) original.put(t.getName(), t);
    Map<String,TransformMeta> selected = new HashMap<>();
    Set<String> names = new HashSet<>();
    for (EngineNode node : nodes) {
      if (node == null || node.id() == null || node.id().isBlank() || node.id().length() > 180
          || node.name() == null || node.name().isBlank() || node.name().length() > 180
          || !Objects.equals(node.kind(),"transform")
          || node.pluginId() == null || node.pluginId().isBlank()
          || Math.abs((long)node.x()) > 100000 || Math.abs((long)node.y()) > 100000
          || !names.add(node.name())) throw new IllegalArgumentException("invalid node");
      TransformMeta transform = original.get(node.id());
      if (transform != null) {
        if (!Objects.equals(transform.getPluginId(),node.pluginId()))
          throw new IllegalArgumentException("plugin identity changed");
      } else {
        if (!ALLOWED_NEW_PLUGINS.contains(node.pluginId()))
          throw new IllegalArgumentException("plugin not allowed");
        ITransformMeta plugin = PluginRegistry.getInstance().loadClass(
            TransformPluginType.class,node.pluginId(),ITransformMeta.class);
        if (plugin == null) throw new IllegalArgumentException("plugin unavailable");
        plugin.setDefault();
        transform = new TransformMeta(node.pluginId(),node.name(),plugin);
        meta.addTransform(transform);
      }
      if (selected.putIfAbsent(node.id(),transform) != null)
        throw new IllegalArgumentException("duplicate id");
      transform.setLocation(node.x(),node.y());
    }
    for (EngineNode node:nodes) {
      TransformMeta t=original.get(node.name());
      if (t != null && t != selected.get(node.id()))
        throw new IllegalArgumentException("name collision");
    }
    for (TransformMeta transform:new ArrayList<>(meta.getTransforms()))
      if (!selected.containsValue(transform)) meta.removeTransform(meta.getTransforms().indexOf(transform));

    Map<String,PipelineHopMeta> prior = new HashMap<>();
    for (PipelineHopMeta hop:new ArrayList<>(meta.getPipelineHops())) {
      prior.put(System.identityHashCode(hop.getFromTransform())+"->"+
                System.identityHashCode(hop.getToTransform()),hop);
    }
    for (int i=meta.getPipelineHops().size()-1;i>=0;i--) meta.removePipelineHop(i);
    Set<String> used=new HashSet<>();
    for (EngineEdge edge:edges) {
      if (edge == null || edge.source() == null || edge.target() == null ||
          edge.source().equals(edge.target()) ||
          !selected.containsKey(edge.source()) || !selected.containsKey(edge.target()) ||
          !used.add(edge.source()+"->"+edge.target())) throw new IllegalArgumentException("bad hop");
      TransformMeta from=selected.get(edge.source()),to=selected.get(edge.target());
      String key=System.identityHashCode(from)+"->"+System.identityHashCode(to);
      PipelineHopMeta hop=prior.getOrDefault(key,new PipelineHopMeta(from,to));
      hop.setEnabled(edge.enabled());
      meta.addPipelineHop(hop);
    }
    for(EngineNode node:nodes) selected.get(node.id()).setName(node.name());
  }
  private static String sha(byte[] data) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
  }
  private static Response error(int status,String code) {
    return Response.status(status).entity(Map.of("code",code)).build();
  }
  public record EngineRequest(String operation,String id,String revision,String xmlBase64,
                              List<EngineNode> nodes,List<EngineEdge> edges,
                              String nodeId,Map<String,JsonNode> configEdits) {
    // Compatible with existing in-memory graph checks.
    public EngineRequest(String operation,String id,String revision,String xmlBase64,
                         List<EngineNode> nodes,List<EngineEdge> edges) {
      this(operation,id,revision,xmlBase64,nodes,edges,null,null);
    }
  }
  public record ConfigResponse(String nodeId, String pluginId, JsonNode config,
                               AutomaticConfigDescriptor.Descriptor descriptor) {}
  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  public record EngineNode(String id,String name,String kind,String pluginId,String pluginType,
                           int x,int y) {}
  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  public record EngineEdge(String id,String source,String target,boolean enabled) {}
  public record EngineGraph(String id,String name,String revision,
                            List<PipelineGraphDocument.Node> nodes,
                            List<PipelineGraphDocument.Edge> edges,String xmlBase64) {}
}