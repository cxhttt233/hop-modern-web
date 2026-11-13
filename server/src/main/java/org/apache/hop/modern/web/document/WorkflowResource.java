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
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.workflow.WorkflowMeta;

/** Small authoritative .hwf open/edit/save boundary; no XML rewriting in the browser. */
@Path("/api/workflows")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public final class WorkflowResource {
  private final WorkflowDocumentStore store;
  private final Map<String, Opened> opened = new ConcurrentHashMap<>();
  public WorkflowResource(WorkflowDocumentStore store) {
    this.store = store;
  }

  @POST
  @Path("/open")
  public Response open(OpenRequest request) {
    if (request == null || request.path() == null || request.path().isBlank()) {
      return error(400, "path is required");
    }
    try {
      java.nio.file.Path file = java.nio.file.Path.of(request.path()).toAbsolutePath().normalize();
      if (!Files.isRegularFile(file)) return error(404, "workflow file not found");
      WorkflowMeta workflow = store.open(file);
      String id = UUID.randomUUID().toString();
      Opened document = new Opened(file, workflow, Files.readAllBytes(file));
      opened.put(id, document);
      return Response.ok(snapshot(id, document)).build();
    } catch (IllegalArgumentException e) {
      return error(400, "path must reference a .hwf file");
    } catch (IOException e) {
      return error(422, "workflow could not be read");
    } catch (Exception e) {
      return error(422, "workflow could not be parsed");
    }
  }

  @POST
  @Path("/edit")
  public Response edit(EditRequest request) {
    if (request == null || request.id() == null || request.description() == null) {
      return error(400, "id and description are required");
    }
    Opened document = opened.get(request.id());
    if (document == null) return error(404, "workflow document not found");
    synchronized (document) {
      document.workflow.setDescription(request.description());
      document.dirty = true;
      return Response.ok(snapshot(request.id(), document)).build();
    }
  }

  @POST
  @Path("/save")
  public Response save(SaveRequest request) {
    if (request == null || request.id() == null) return error(400, "id is required");
    Opened document = opened.get(request.id());
    if (document == null) return error(404, "workflow document not found");
    synchronized (document) {
      try {
        // A different editor or process may have changed the file since open.
        // Never silently overwrite its newer version.
        if (!Files.isRegularFile(document.file)
            || !Arrays.equals(document.originalBytes, Files.readAllBytes(document.file))) {
          return error(409, "workflow changed on disk; reopen before saving");
        }
        store.save(document.file, document.workflow);
        document.originalBytes = Files.readAllBytes(document.file);
        document.dirty = false;
        return Response.ok(snapshot(request.id(), document)).build();
      } catch (IOException | HopException e) {
        return error(500, "workflow could not be saved");
      }
    }
  }

  private static Snapshot snapshot(String id, Opened document) {
    return new Snapshot(id, document.file.toString(), document.workflow.getName(),
        document.workflow.getDescription(), document.dirty);
  }

  private static Response error(int status, String message) {
    return Response.status(status).entity(Map.of("error", message)).build();
  }

  public record OpenRequest(String path) {}
  public record EditRequest(String id, String description) {}
  public record SaveRequest(String id) {}
  public record Snapshot(String id, String path, String name, String description, boolean dirty) {}

  private static final class Opened {
    final java.nio.file.Path file;
    final WorkflowMeta workflow;
    boolean dirty;
    byte[] originalBytes;
    Opened(java.nio.file.Path file, WorkflowMeta workflow, byte[] originalBytes) {
      this.file = file;
      this.workflow = workflow;
      this.originalBytes = originalBytes;
    }
  }
}