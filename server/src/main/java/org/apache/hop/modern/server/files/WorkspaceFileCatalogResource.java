/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.server.files;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Lists browser-openable Hop documents without exposing server absolute paths. */
@Path("/api/files")
@Produces(MediaType.APPLICATION_JSON)
public final class WorkspaceFileCatalogResource {
  private static final int MAX_DEPTH = 8;
  private static final int MAX_FILES = 500;
  private final java.nio.file.Path workspace;

  public WorkspaceFileCatalogResource() {
    this(System.getProperty("hop.modern.workspace"));
  }

  public WorkspaceFileCatalogResource(String workspace) {
    this.workspace = workspace == null || workspace.isBlank() ? null : java.nio.file.Path.of(workspace);
  }

  @GET
  @Path("/pipelines")
  public Response pipelines() {
    return list(".hpl");
  }

  @GET
  @Path("/workflows")
  public Response workflows() {
    return list(".hwf");
  }

  private Response list(String extension) {
    if (workspace == null) {
      return Response.status(503).entity(new ErrorResponse("workspace_unavailable", "workspace is not configured")).build();
    }
    try {
      java.nio.file.Path root = workspace.toRealPath();
      if (!Files.isDirectory(root)) {
        return Response.status(503).entity(new ErrorResponse("workspace_unavailable", "workspace is not a directory")).build();
      }
      List<String> paths = new ArrayList<>();
      try (Stream<java.nio.file.Path> files = Files.walk(root, MAX_DEPTH)) {
        files.filter(p -> !Files.isSymbolicLink(p))
            .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
            .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension))
            .forEach(p -> paths.add(root.relativize(p).toString().replace('\\', '/')));
      }
      paths.sort(Comparator.naturalOrder());
      return Response.ok(new FileListResponse(List.copyOf(paths.subList(0, Math.min(paths.size(), MAX_FILES))))).build();
    } catch (IOException | SecurityException e) {
      return Response.status(503).entity(new ErrorResponse("workspace_unavailable", "workspace cannot be scanned")).build();
    }
  }

  public record FileListResponse(List<String> paths) {}
  public record ErrorResponse(String code, String message) {}
}