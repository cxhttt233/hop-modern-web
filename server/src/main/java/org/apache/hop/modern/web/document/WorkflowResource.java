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
import org.apache.hop.modern.server.files.WorkspacePaths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.hop.workflow.WorkflowHopMeta;
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
      // Enforce the same server-side workspace boundary as pipeline documents.
      // The browser bridge is not a security boundary: direct API callers must be checked.
      String workspace = System.getProperty("hop.modern.workspace");
      java.nio.file.Path file = workspace == null || workspace.isBlank()
          ? java.nio.file.Path.of(request.path()).toAbsolutePath().normalize()
          : new WorkspacePaths(java.nio.file.Path.of(workspace)).existingFile(request.path());
      if (!Files.isRegularFile(file)) return error(404, "workflow file not found");
      WorkflowMeta workflow = store.open(file);
      String id = UUID.randomUUID().toString();
      Opened document = new Opened(file, workflow, Files.readAllBytes(file));
      opened.put(id, document);
      return Response.ok(snapshot(id, document)).build();
    } catch (IllegalArgumentException e) {
      return error(400, "path must reference a .hwf file inside the workspace");
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
  @Path("/move")
  public Response move(MoveRequest request) {
    if (request == null || request.id() == null || request.name() == null
        || request.x() < 0 || request.y() < 0 || request.x() > 100000 || request.y() > 100000) {
      return error(400, "valid id, name, x and y are required");
    }
    Opened document = opened.get(request.id());
    if (document == null) return error(404, "workflow document not found");
    synchronized (document) {
      var action = document.workflow.getActions().stream()
          .filter(item -> request.name().equals(item.getName())).findFirst().orElse(null);
      if (action == null || action.getLocation() == null) return error(404, "workflow action not found");
      // A click without movement must not turn a clean document into an unsaved draft.
      if (action.getLocation().x == request.x() && action.getLocation().y == request.y()) {
        return Response.ok(snapshot(request.id(), document)).build();
      }
      action.getLocation().x = request.x();
      action.getLocation().y = request.y();
      document.workflow.setChanged();
      document.dirty = true;
      return Response.ok(snapshot(request.id(), document)).build();
    }
  }

  @POST
  @Path("/action-rename")
  public Response renameAction(ActionRenameRequest request) {
    if (request == null || request.id() == null || request.name() == null
        || request.newName() == null || request.newName().isBlank()
        || !request.newName().equals(request.newName().trim())) {
      return error(400, "valid id, action name and nonblank new name are required");
    }
    Opened document = opened.get(request.id());
    if (document == null) return error(404, "workflow document not found");
    synchronized (document) {
      var action = document.workflow.findAction(request.name());
      if (action == null) return error(404, "workflow action not found");
      if (request.name().equals(request.newName())) {
        return Response.ok(snapshot(request.id(), document)).build();
      }
      if (document.workflow.findAction(request.newName()) != null) {
        return error(409, "workflow action name already exists");
      }
      action.setName(request.newName());
      document.workflow.setChanged();
      document.dirty = true;
      return Response.ok(snapshot(request.id(), document)).build();
    }
  }

  /**
   * Create only explicitly supported Hop-native actions. Never synthesize plugin XML.
   * The Dummy action has no required external configuration or secrets.
   */
  @POST
  @Path("/action-create")
  public Response createAction(ActionCreateRequest request) {
    if (request == null || request.id() == null || request.name() == null
        || request.name().isBlank() || !request.name().equals(request.name().trim())
        || request.name().length() > 200 || !"DUMMY".equals(request.type())
        || request.x() < 0 || request.y() < 0 || request.x() > 100000 || request.y() > 100000) {
      return error(400, "valid id, name, supported type DUMMY, x and y are required");
    }
    Opened document = opened.get(request.id());
    if (document == null) return error(404, "workflow document not found");
    synchronized (document) {
      if (document.workflow.findAction(request.name()) != null) {
        return error(409, "workflow action name already exists");
      }
      var nativeAction = new org.apache.hop.workflow.actions.dummy.ActionDummy(request.name());
      var meta = new org.apache.hop.workflow.action.ActionMeta(nativeAction);
      meta.setName(request.name());
      meta.setLocation(request.x(), request.y());
      document.workflow.addAction(meta);
      document.workflow.setChanged();
      document.dirty = true;
      return Response.ok(snapshot(request.id(), document)).build();
    }
  }

  @POST
  @Path("/action-delete")
  public Response deleteAction(ActionDeleteRequest request) {
    if (request == null || request.id() == null || request.name() == null
        || request.name().isBlank()) return error(400, "valid id and action name are required");
    Opened document = opened.get(request.id());
    if (document == null) return error(404, "workflow document not found");
    synchronized (document) {
      var action = document.workflow.findAction(request.name());
      if (action == null) return error(404, "workflow action not found");
      // Never silently discard edges: require an explicit acknowledgement of their count.
      long connected = document.workflow.getWorkflowHops().stream()
          .filter(hop -> hop.getFromAction() == action || hop.getToAction() == action).count();
      if (request.connectedHops() != connected) {
        return error(409, "connected hops changed; review and confirm deletion again");
      }
      document.workflow.getWorkflowHops().removeIf(
          hop -> hop.getFromAction() == action || hop.getToAction() == action);
      int index = document.workflow.getActions().indexOf(action);
      if (index < 0) return error(409, "workflow action list changed");
      document.workflow.removeAction(index);
      document.workflow.setChanged();
      document.dirty = true;
      return Response.ok(snapshot(request.id(), document)).build();
    }
  }

  @POST
  @Path("/hop-enabled")
  public Response setHopEnabled(HopEnabledRequest request) {
    if (request == null || request.id() == null || request.index() < 0) {
      return error(400, "valid id and hop index are required");
    }
    Opened document = opened.get(request.id());
    if (document == null) return error(404, "workflow document not found");
    synchronized (document) {
      var hops = document.workflow.getWorkflowHops();
      if (request.index() >= hops.size()) return error(404, "workflow hop not found");
      var hop = hops.get(request.index());
      // Index plus endpoints guards against acting on a stale or reordered list.
      if (!hop.getFromAction().getName().equals(request.from())
          || !hop.getToAction().getName().equals(request.to())) {
        return error(409, "workflow hop changed; reopen before editing");
      }
      if (hop.isEnabled() != request.enabled()) {
        hop.setEnabled(request.enabled());
        document.workflow.setChanged();
        document.dirty = true;
      }
      return Response.ok(snapshot(request.id(), document)).build();
    }
  }

  @POST
  @Path("/hop-create")
  public Response createHop(HopCreateRequest request) {
    if (request == null || request.id() == null || request.from() == null
        || request.to() == null || request.from().isBlank() || request.to().isBlank()) {
      return error(400, "valid id and endpoints are required");
    }
    Opened document = opened.get(request.id());
    if (document == null) return error(404, "workflow document not found");
    synchronized (document) {
      var from = document.workflow.findAction(request.from());
      var to = document.workflow.findAction(request.to());
      if (from == null || to == null) return error(404, "workflow action not found");
      if (from == to) return error(400, "self connections are not supported");
      boolean exists = document.workflow.getWorkflowHops().stream()
          .anyMatch(hop -> hop.getFromAction() == from && hop.getToAction() == to);
      if (exists) return error(409, "workflow connection already exists");
      var hop = new WorkflowHopMeta(from, to);
      hop.setEnabled(true);
      document.workflow.addWorkflowHop(hop);
      document.workflow.setChanged();
      document.dirty = true;
      return Response.ok(snapshot(request.id(), document)).build();
    }
  }

  @POST
  @Path("/hop-delete")
  public Response deleteHop(HopDeleteRequest request) {
    if (request == null || request.id() == null || request.index() < 0
        || request.from() == null || request.to() == null) {
      return error(400, "valid id, hop index and endpoints are required");
    }
    Opened document = opened.get(request.id());
    if (document == null) return error(404, "workflow document not found");
    synchronized (document) {
      var hops = document.workflow.getWorkflowHops();
      if (request.index() >= hops.size()) return error(404, "workflow hop not found");
      var hop = hops.get(request.index());
      if (!request.from().equals(hop.getFromAction().getName())
          || !request.to().equals(hop.getToAction().getName())) {
        return error(409, "workflow hop changed; reopen before editing");
      }
      hops.remove(request.index());
      document.workflow.setChanged();
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
    List<ActionSummary> actions = document.workflow.getActions().stream()
        .map(action -> new ActionSummary(action.getName(), action.getTypeDesc(),
            action.getLocation() == null ? 0 : action.getLocation().x,
            action.getLocation() == null ? 0 : action.getLocation().y))
        .toList();
    List<HopSummary> hops = document.workflow.getWorkflowHops().stream()
        .map(hop -> new HopSummary(hop.getFromAction().getName(),
            hop.getToAction().getName(), hop.isEnabled()))
        .toList();
    return new Snapshot(id, document.file.toString(), document.workflow.getName(),
        document.workflow.getDescription(), document.dirty, actions, hops);
  }

  private static Response error(int status, String message) {
    return Response.status(status).entity(Map.of("error", message)).build();
  }

  public record OpenRequest(String path) {}
  public record EditRequest(String id, String description) {}
  public record SaveRequest(String id) {}
  public record MoveRequest(String id, String name, int x, int y) {}
  public record ActionCreateRequest(String id, String type, String name, int x, int y) {}
  public record ActionDeleteRequest(String id, String name, int connectedHops) {}
  public record ActionRenameRequest(String id, String name, String newName) {}
  public record HopCreateRequest(String id, String from, String to) {}
  public record HopDeleteRequest(String id, int index, String from, String to) {}
  public record HopEnabledRequest(String id, int index, String from, String to, boolean enabled) {}
  public record ActionSummary(String name, String type, int x, int y) {}
  public record HopSummary(String from, String to, boolean enabled) {}
  public record Snapshot(String id, String path, String name, String description, boolean dirty,
      List<ActionSummary> actions, List<HopSummary> hops) {}

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