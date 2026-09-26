/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web.document;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Objects;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.metadata.serializer.json.ConfigJsonSerializer;
import org.apache.hop.pipeline.transform.ITransformMeta;
import org.apache.hop.pipeline.transform.TransformMeta;

/** Thin HTTP adapter for authoritative Hop transform configuration JSON. */
@Path("/api/pipelines")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public final class PipelineConfigResource {
  private final PipelineDocumentRegistry registry;
  private final IHopMetadataProvider metadataProvider;

  public PipelineConfigResource(
      PipelineDocumentRegistry registry, IHopMetadataProvider metadataProvider) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.metadataProvider = Objects.requireNonNull(metadataProvider, "metadataProvider");
  }

  @POST
  @Path("/{id}/config/read")
  public Response read(@PathParam("id") String id, ReadRequest request) {
    if (id == null || id.isBlank() || request == null || blank(request.nodeId())) {
      return error(400, "invalid_request", "document id and nodeId are required");
    }
    PipelineDocument document = registry.get(id);
    if (document == null) {
      return error(404, "document_not_found", "opened pipeline document was not found");
    }
    synchronized (document) {
      TransformMeta transform = document.pipeline().findTransform(request.nodeId());
      if (transform == null || transform.getTransform() == null) {
        return error(422, "unknown_transform", "transform was not found");
      }
      try {
        return Response.ok(response(transform)).build();
      } catch (HopException e) {
        return error(422, "invalid_config", "transform config could not be serialized");
      }
    }
  }

  @POST
  @Path("/{id}/config/write")
  public Response write(@PathParam("id") String id, WriteRequest request) {
    if (id == null
        || id.isBlank()
        || request == null
        || blank(request.nodeId())
        || request.config() == null
        || !request.config().isObject()) {
      return error(400, "invalid_request", "document id, nodeId and object config are required");
    }
    PipelineDocument document = registry.get(id);
    if (document == null) {
      return error(404, "document_not_found", "opened pipeline document was not found");
    }
    synchronized (document) {
      TransformMeta transform = document.pipeline().findTransform(request.nodeId());
      if (transform == null || transform.getTransform() == null) {
        return error(422, "unknown_transform", "transform was not found");
      }
      try {
        ITransformMeta decoded = decode(request.config(), transform.getTransform().getClass());
        transform.setTransform(decoded);
        transform.setChanged();
        document.pipeline().setChanged();
        return Response.ok(response(transform)).build();
      } catch (HopException | ClassCastException e) {
        return error(422, "invalid_config", "transform config could not be decoded");
      }
    }
  }

  private ConfigResponse response(TransformMeta transform) throws HopException {
    return new ConfigResponse(
        transform.getName(),
        transform.getTransformPluginId(),
        ConfigJsonSerializer.toJson(transform.getTransform(), metadataProvider));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private ITransformMeta decode(JsonNode config, Class<?> type) throws HopException {
    Object decoded = ConfigJsonSerializer.fromJson(config, (Class) type, metadataProvider);
    return (ITransformMeta) decoded;
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static Response error(int status, String code, String message) {
    return Response.status(status)
        .entity(new PipelineOpenResource.ErrorResponse(code, message))
        .build();
  }

  public record ReadRequest(String nodeId) {}

  public record WriteRequest(String nodeId, JsonNode config) {}

  public record ConfigResponse(String nodeId, String pluginId, JsonNode config) {}
}
