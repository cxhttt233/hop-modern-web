/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web.document;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Objects;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.plugins.TransformPluginType;
import org.apache.hop.pipeline.editor.PipelineEditor;
import org.apache.hop.pipeline.transform.ITransformMeta;
import org.apache.hop.pipeline.transform.TransformMeta;

@Path("/api/pipelines")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public final class PipelineEditResource {
  private final PipelineDocumentRegistry registry;
  private final PipelineDocumentStore store;
  private final PipelineGraphAdapter graphAdapter;

  public PipelineEditResource(PipelineDocumentRegistry registry, PipelineDocumentStore store, PipelineGraphAdapter graphAdapter) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.store = Objects.requireNonNull(store, "store");
    this.graphAdapter = Objects.requireNonNull(graphAdapter, "graphAdapter");
  }

  @POST @Path("/{id}/edit/add")
  public Response add(@PathParam("id") String id, AddRequest request) {
    PipelineDocument document = registry.get(id);
    if (document == null) return error(404, "document_not_found", "opened pipeline document was not found");
    if (request == null || blank(request.nodeId()) || blank(request.pluginId())) return error(400, "invalid_request", "nodeId and pluginId are required");
    synchronized (document) {
      try {
        ITransformMeta config = PluginRegistry.getInstance().loadClass(TransformPluginType.class, request.pluginId(), ITransformMeta.class);
        if (config == null) return error(422, "unknown_plugin", "transform plugin was not found");
        TransformMeta transform = new TransformMeta(request.pluginId(), request.nodeId(), config);
        transform.setLocation(request.x() == null ? 0 : request.x(), request.y() == null ? 0 : request.y());
        if (!new PipelineEditor(document.pipeline()).addTransform(transform)) return error(422, "no_change", "transform could not be added");
        return graph(document);
      } catch (Exception e) {
        return error(422, "invalid_transform", "transform could not be created");
      }
    }
  }

  @POST @Path("/{id}/edit/delete")
  public Response delete(@PathParam("id") String id, NodeRequest request) {
    PipelineDocument document = registry.get(id);
    if (document == null) return error(404, "document_not_found", "opened pipeline document was not found");
    if (request == null || blank(request.nodeId())) return error(400, "invalid_request", "nodeId is required");
    synchronized (document) {
      if (!new PipelineEditor(document.pipeline()).deleteTransform(request.nodeId())) return error(422, "unknown_transform", "transform was not found");
      return graph(document);
    }
  }

  @POST @Path("/{id}/edit/connect")
  public Response connect(@PathParam("id") String id, ConnectRequest request) {
    PipelineDocument document = registry.get(id);
    if (document == null) return error(404, "document_not_found", "opened pipeline document was not found");
    if (request == null || blank(request.from()) || blank(request.to())) return error(400, "invalid_request", "from and to are required");
    synchronized (document) {
      if (new PipelineEditor(document.pipeline()).addHop(request.from(), request.to()) == null) return error(422, "no_change", "hop could not be added");
      return graph(document);
    }
  }

  @POST @Path("/{id}/edit/undo")
  public Response undo(@PathParam("id") String id) { return history(id, true); }

  @POST @Path("/{id}/edit/redo")
  public Response redo(@PathParam("id") String id) { return history(id, false); }

  @POST @Path("/{id}/save")
  public Response save(@PathParam("id") String id) {
    PipelineDocument document = registry.get(id);
    if (document == null) return error(404, "document_not_found", "opened pipeline document was not found");
    synchronized (document) {
      try {
        store.save(document.path(), document.pipeline());
        return graph(document);
      } catch (Exception e) {
        return error(500, "save_failed", "pipeline could not be saved");
      }
    }
  }

  private Response history(String id, boolean undo) {
    PipelineDocument document = registry.get(id);
    if (document == null) return error(404, "document_not_found", "opened pipeline document was not found");
    synchronized (document) {
      PipelineEditor editor = new PipelineEditor(document.pipeline());
      if (!(undo ? editor.undo() : editor.redo())) return error(422, "no_change", undo ? "nothing to undo" : "nothing to redo");
      return graph(document);
    }
  }

  private Response graph(PipelineDocument document) {
    return Response.ok(graphAdapter.project(document, "")).build();
  }

  private static boolean blank(String value) { return value == null || value.isBlank(); }
  private static Response error(int status, String code, String message) {
    return Response.status(status).entity(new ErrorResponse(code, message)).build();
  }

  public record AddRequest(String nodeId, String pluginId, Integer x, Integer y) {}
  public record NodeRequest(String nodeId) {}
  public record ConnectRequest(String from, String to) {}
  public record ErrorResponse(String code, String message) {}
}
