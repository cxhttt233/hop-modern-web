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
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
import org.apache.hop.workflow.actions.start.ActionStart;
import java.nio.file.InvalidPathException;
import org.apache.hop.modern.server.files.WorkspacePaths;
import java.util.List;
import java.util.Map;
import org.apache.hop.workflow.WorkflowHopMeta;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.core.logging.LogLevel;
import org.apache.hop.workflow.WorkflowMeta;
import org.apache.hop.workflow.action.ActionMeta;
import org.apache.hop.workflow.actions.abort.ActionAbort;
import org.apache.hop.workflow.actions.dummy.ActionDummy;
import org.apache.hop.workflow.actions.success.ActionSuccess;
import org.apache.hop.workflow.actions.fileexists.ActionFileExists;

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

  /** Create a genuine Hop workflow with a native START node; never overwrite. */
  @POST
  @Path("/create")
  public Response create(CreateRequest request) {
    if (request == null || request.path() == null || request.path().isBlank()
        || request.name() == null || request.name().isBlank()
        || request.name().length() > 200 || !request.path().toLowerCase(java.util.Locale.ROOT).endsWith(".hwf")) {
      return error(400, "valid .hwf path and workflow name are required");
    }
    String configured = System.getProperty("hop.modern.workspace");
    if (configured == null || configured.isBlank()) return error(503, "workspace is not configured");
    try {
      java.nio.file.Path root = java.nio.file.Path.of(configured);
      java.nio.file.Path file = new WorkspacePaths(root).destinationFile(request.path());
      if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return error(409, "workflow already exists");
      WorkflowMeta workflow = new WorkflowMeta();
      workflow.setNameSynchronizedWithFilename(false);
      workflow.setName(request.name().trim());
      ActionMeta start = new ActionMeta(new ActionStart("Start"));
      start.setLocation(160, 120);
      workflow.addAction(start);
      byte[] bytes = store.create(file, workflow, root);
      String id = UUID.randomUUID().toString();
      Opened document = new Opened(file, workflow, bytes);
      opened.put(id, document);
      return Response.status(201).entity(snapshot(id, document)).build();
    } catch (FileAlreadyExistsException e) {
      return error(409, "workflow already exists");
    } catch (SecureWorkspaceSave.ExternalModificationException | IllegalArgumentException e) {
      return error(409, "workflow path is unsafe or changed");
    } catch (IOException | HopException e) {
      return error(503, "workflow could not be created; retry safely");
    }
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

  /** Only whitelisted native Hop action classes may be constructed, never arbitrary client JSON. */
  @POST
  @Path("/action-add")
  public Response addAction(ActionAddRequest request) {
    if (request == null || request.id() == null || request.name() == null
        || request.name().isBlank() || !request.name().equals(request.name().trim())
        || request.name().length() > 200 || request.type() == null
        || request.x() < 0 || request.y() < 0 || request.x() > 100000 || request.y() > 100000) {
      return error(400, "valid id, native action type, name and coordinates are required");
    }
    if (!"DUMMY".equals(request.type()) && !"ABORT".equals(request.type()) && !"SUCCESS".equals(request.type())
        && !"FILE_EXISTS".equals(request.type())) {
      return error(400, "unsupported native action type");
    }
    ActionMeta action;
    if ("DUMMY".equals(request.type())) {
      action = new ActionMeta(new ActionDummy(request.name()));
    } else if ("SUCCESS".equals(request.type())) {
      action = new ActionMeta(new ActionSuccess(request.name(), ""));
    } else if ("FILE_EXISTS".equals(request.type())) {
      if (!validFileExistsPath(request.filename())) {
        return error(400, "FILE_EXISTS requires a safe workspace-relative filename");
      }
      ActionFileExists exists = new ActionFileExists(request.name());
      exists.setFilename(request.filename());
      action = new ActionMeta(exists);
    } else {
      if (request.message() == null || request.message().isBlank() || request.message().length() > 1000) {
        return error(400, "ABORT requires a message of 1-1000 characters");
      }
      final LogLevel level;
      try {
        level = LogLevel.valueOf(request.logLevel() == null ? "ERROR" : request.logLevel());
      } catch (IllegalArgumentException invalidLevel) {
        return error(400, "unsupported ABORT log level");
      }
      ActionAbort abort = new ActionAbort(request.name(), "");
      abort.setMessageAbort(request.message());
      abort.setMessageLogLevel(level);
      action = new ActionMeta(abort);
    }
    Opened document = opened.get(request.id());
    if (document == null) return error(404, "workflow document not found");
    synchronized (document) {
      if (document.workflow.getActions().stream()
          .anyMatch(existing -> existing.getName().equalsIgnoreCase(request.name()))) {
        return error(409, "workflow action name already exists");
      }
      action.setLocation(request.x(), request.y());
      document.workflow.addAction(action);
      document.workflow.setChanged();
      document.dirty = true;
      return Response.ok(snapshot(request.id(), document)).build();
    }
  }

  /** FILE_EXISTS is metadata only: no filesystem probes or workflow execution here. */
  private static boolean validFileExistsPath(String filename) {
    if (filename == null || filename.isBlank() || filename.length() > 1024
        || filename.indexOf('\\') >= 0 || filename.indexOf('\0') >= 0
        || filename.contains("${") || filename.contains("%{") || filename.contains("://")) return false;
    try {
      java.nio.file.Path path = java.nio.file.Path.of(filename);
      if (path.isAbsolute()) return false;
      for (java.nio.file.Path part : path) {
        if ("..".equals(part.toString()) || ".".equals(part.toString())) return false;
      }
      return true;
    } catch (java.nio.file.InvalidPathException invalid) {
      return false;
    }
  }

  /** Update only native ABORT fields; never replace the ActionMeta or its hops. */
  @POST
  @Path("/action-abort-edit")
  public Response editAbort(ActionAbortEditRequest request) {
    if (request == null || request.id() == null || request.name() == null
        || request.name().isBlank() || request.message() == null
        || request.message().isBlank() || request.message().length() > 1000
        || request.logLevel() == null) {
      return error(400, "ABORT message (1-1000) and log level are required");
    }
    final LogLevel level;
    try {
      level = LogLevel.valueOf(request.logLevel());
    } catch (IllegalArgumentException invalid) {
      return error(400, "unsupported ABORT log level");
    }
    Opened document = opened.get(request.id());
    if (document == null) return error(404, "workflow document not found");
    synchronized (document) {
      ActionMeta action = document.workflow.findAction(request.name());
      if (action == null) return error(404, "workflow action not found");
      if (!(action.getAction() instanceof ActionAbort abort)) {
        return error(409, "selected action is not a native ABORT");
      }
      if (request.message().equals(abort.getMessageAbort())
          && level == abort.getMessageLogLevel()) {
        return Response.ok(snapshot(request.id(), document)).build();
      }
      abort.setMessageAbort(request.message());
      abort.setMessageLogLevel(level);
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
        // The checked write itself must resolve every parent through anchored
        // directory descriptors. A pre-save Files.isRegularFile/hash check is
        // unsafe if a parent is replaced with an external symlink afterwards.
        String configured = System.getProperty("hop.modern.workspace");
        java.nio.file.Path workspace = configured == null || configured.isBlank()
            ? null : java.nio.file.Path.of(configured);
        document.originalBytes = store.saveIfUnchanged(document.file,
            document.workflow, document.originalBytes, workspace);
        document.dirty = false;
        return Response.ok(snapshot(request.id(), document)).build();
      } catch (SecureWorkspaceSave.ExternalModificationException e) {
        return error(409, "workflow changed on disk or path replaced; reopen before saving");
      } catch (IOException | HopException e) {
        return error(500, "workflow could not be saved");
      }
    }
  }

  private static Snapshot snapshot(String id, Opened document) {
    List<ActionSummary> actions = document.workflow.getActions().stream()
        .map(action -> new ActionSummary(action.getName(), action.getTypeDesc(),
            org.apache.hop.core.plugins.PluginRegistry.getInstance().getPluginId(
                org.apache.hop.core.plugins.ActionPluginType.class, action.getAction()),
            action.getAction() instanceof ActionAbort abort ? abort.getMessageAbort() : null,
            action.getAction() instanceof ActionAbort abort
                ? (abort.getMessageLogLevel() == null ? "ERROR" : abort.getMessageLogLevel().name())
                : null,
            action.getAction() instanceof ActionFileExists exists ? exists.getFilename() : null,
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

  public record CreateRequest(String path, String name) {}
  public record OpenRequest(String path) {}
  public record EditRequest(String id, String description) {}
  public record SaveRequest(String id) {}
  public record MoveRequest(String id, String name, int x, int y) {}
  public record ActionDeleteRequest(String id, String name, int connectedHops) {}
  public record ActionRenameRequest(String id, String name, String newName) {}
  public record ActionAddRequest(String id, String type, String name, int x, int y,
      String message, String logLevel, String filename) {}
  public record HopCreateRequest(String id, String from, String to) {}
  public record HopDeleteRequest(String id, int index, String from, String to) {}
  public record HopEnabledRequest(String id, int index, String from, String to, boolean enabled) {}
  public record ActionAbortEditRequest(String id, String name, String message, String logLevel) {}
  public record ActionSummary(String name, String type, String pluginId, String message, String logLevel, String filename, int x, int y) {}
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