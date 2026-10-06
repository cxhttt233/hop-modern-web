/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web.execution;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.modern.web.ProductContext;
import org.apache.hop.modern.web.document.PipelineDocument;
import org.apache.hop.modern.web.document.PipelineDocumentRegistry;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.engine.IPipelineEngine;
import org.apache.hop.pipeline.engines.local.LocalPipelineEngine;
import org.apache.hop.web.api.execution.ExecutionRegistry;
import org.apache.hop.web.api.execution.PipelineExecutionLifecycle;

/** Thin product HTTP adapter over Hop's authoritative pipeline execution lifecycle. */
@Path("/api/executions")
@Produces(MediaType.APPLICATION_JSON)
public final class PipelineExecutionResource {
  private final PipelineDocumentRegistry documents;
  private final ProductContext context;
  private final ExecutionRegistry<IPipelineEngine<PipelineMeta>> executions;

  public PipelineExecutionResource(
      PipelineDocumentRegistry documents,
      ProductContext context) {
    this(
        documents,
        context,
        new ExecutionRegistry<>(Duration.ofHours(1), 256));
  }

  PipelineExecutionResource(
      PipelineDocumentRegistry documents,
      ProductContext context,
      ExecutionRegistry<IPipelineEngine<PipelineMeta>> executions) {
    this.documents = Objects.requireNonNull(documents, "documents");
    this.context = Objects.requireNonNull(context, "context");
    this.executions = Objects.requireNonNull(executions, "executions");
  }

  @POST
  @Path("/pipelines/{documentId}")
  public Response start(@PathParam("documentId") String documentId) {
    PipelineDocument document = documents.get(documentId);
    if (document == null) {
      return error(404, "document_not_found", "opened pipeline document was not found");
    }

    PipelineMeta executionPipeline;
    try {
      synchronized (document) {
        executionPipeline =
            new PipelineMeta(
                new ByteArrayInputStream(
                    document.pipeline().getXml(context.variables()).getBytes(StandardCharsets.UTF_8)),
                context.metadataProvider(),
                context.variables());
      }
    } catch (HopException | RuntimeException e) {
      return error(
          422,
          "execution_snapshot_failed",
          e.getMessage() == null ? "pipeline execution snapshot could not be created" : e.getMessage());
    }

    String executionId = UUID.randomUUID().toString();
    LocalPipelineEngine engine =
        new LocalPipelineEngine(executionPipeline, context.variables(), null);
    engine.setMetadataProvider(context.metadataProvider());
    executions.register(executionId, documentId, engine);
    try {
      PipelineExecutionLifecycle.start(executionId, executions);
      return Response.accepted(status(executionId).getEntity()).build();
    } catch (HopException | RuntimeException e) {
      // A start failure never produces a queryable execution. The authoritative lifecycle
      // has already marked the entry completed; remove it so failed starts do not retain
      // an engine/snapshot until the normal completed-entry TTL expires.
      executions.remove(executionId);
      return error(
          422,
          "execution_start_failed",
          e.getMessage() == null ? "pipeline execution could not be started" : e.getMessage());
    }
  }

  @POST
  @Path("/{executionId}/cancel")
  public Response cancel(@PathParam("executionId") String executionId) {
    ExecutionRegistry.Entry<IPipelineEngine<PipelineMeta>> entry =
        executions.find(executionId).orElse(null);
    if (entry == null) {
      return error(404, "execution_not_found", "pipeline execution was not found");
    }

    IPipelineEngine<PipelineMeta> engine = entry.execution();
    if (!engine.isFinished() && !engine.isStopped()) {
      engine.stopAll();
    }
    return status(executionId);
  }

  @GET
  @Path("/{executionId}")
  public Response status(@PathParam("executionId") String executionId) {
    ExecutionRegistry.Entry<IPipelineEngine<PipelineMeta>> entry =
        executions.find(executionId).orElse(null);
    if (entry == null) {
      return error(404, "execution_not_found", "pipeline execution was not found");
    }

    IPipelineEngine<PipelineMeta> engine = entry.execution();
    Instant start =
        engine.getExecutionStartDate() == null
            ? entry.createdAt()
            : engine.getExecutionStartDate().toInstant();
    Instant end =
        engine.getExecutionEndDate() == null
            ? entry.completedAt().orElse(null)
            : engine.getExecutionEndDate().toInstant();
    Long durationMillis =
        end == null ? null : Math.max(0L, Duration.between(start, end).toMillis());

    return Response.ok(
            new ExecutionStatus(
                executionId,
                entry.owner(),
                state(engine),
                start,
                end,
                durationMillis,
                engine.getErrors()))
        .build();
  }

  private static String state(IPipelineEngine<PipelineMeta> engine) {
    if (engine.isStopped()) {
      return "stopped";
    }
    if (engine.isFinished()) {
      return engine.getErrors() > 0 ? "failed" : "completed";
    }
    if (engine.isPreparing()) {
      return "preparing";
    }
    if (engine.isRunning()) {
      return "running";
    }
    return "created";
  }

  private static Response error(int status, String code, String message) {
    return Response.status(status).entity(new ErrorResponse(code, message)).build();
  }

  public record ExecutionStatus(
      String id,
      String documentId,
      String state,
      Instant start,
      Instant end,
      Long durationMillis,
      int errors) {}

  public record ErrorResponse(String code, String message) {}
}
