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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.InvalidPathException;
import java.util.PriorityQueue;
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
    java.nio.file.Path configured = null;
    if (workspace != null && !workspace.isBlank()) {
      try {
        configured = java.nio.file.Path.of(workspace);
      } catch (InvalidPathException ignored) {
        // Invalid configuration must not prevent the API resource from starting.
      }
    }
    this.workspace = configured;
  }

  @GET
  @Path("/pipelines")
  public Response pipelines(@QueryParam("search") String search) {
    return list(".hpl", search);
  }

  @GET
  @Path("/workflows")
  public Response workflows(@QueryParam("search") String search) {
    return list(".hwf", search);
  }

  private Response list(String extension, String search) {
    if (search != null && search.length() > 128) {
      return Response.status(400).entity(new ErrorResponse("invalid_search", "search is too long")).build();
    }
    String needle = search == null ? "" : search.toLowerCase(Locale.ROOT);
    if (workspace == null) {
      return Response.status(503).entity(new ErrorResponse("workspace_unavailable", "workspace is not configured")).build();
    }
    try {
      java.nio.file.Path root = workspace.toRealPath();
      if (!Files.isDirectory(root)) {
        return Response.status(503).entity(new ErrorResponse("workspace_unavailable", "workspace is not a directory")).build();
      }
      // Keep only the first MAX_FILES alphabetically, without holding the full scan in memory.
      PriorityQueue<String> firstPaths =
          new PriorityQueue<>(MAX_FILES + 1, Comparator.reverseOrder());
      try (Stream<java.nio.file.Path> files = Files.walk(root, MAX_DEPTH)) {
        files.filter(p -> !Files.isSymbolicLink(p))
            .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
            .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension))
            .map(p -> root.relativize(p).toString().replace('\\', '/'))
            .filter(path -> path.toLowerCase(Locale.ROOT).contains(needle))
            .forEach(relative -> {
              if (firstPaths.size() < MAX_FILES) {
                firstPaths.add(relative);
              } else if (relative.compareTo(firstPaths.peek()) < 0) {
                firstPaths.poll();
                firstPaths.add(relative);
              }
            });
      }
      return Response.ok(new FileListResponse(firstPaths.stream().sorted().toList())).build();
    } catch (IOException | UncheckedIOException | SecurityException e) {
      return Response.status(503).entity(new ErrorResponse("workspace_unavailable", "workspace cannot be scanned")).build();
    }
  }

  public record FileListResponse(List<String> paths) {}
  public record ErrorResponse(String code, String message) {}
}