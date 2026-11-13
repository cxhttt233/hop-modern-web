/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web.document;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Read-only, bounded workspace discovery for native Hop workflows (.hwf). */
@Path("/api/files/workflows")
@Produces(MediaType.APPLICATION_JSON)
public final class WorkflowFilesResource {
  private static final int MAX_DEPTH = 8;
  private static final int MAX_FILES = 500;
  private final java.nio.file.Path workspace;

  public WorkflowFilesResource() {
    this(System.getProperty("hop.modern.workspace"));
  }

  public WorkflowFilesResource(String workspace) {
    this.workspace = workspace == null || workspace.isBlank() ? null : Paths.get(workspace);
  }

  @GET
  public Response list() {
    if (workspace == null || !Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(workspace)) {
      return Response.status(503).entity(new ErrorResponse(
          "workspace_unavailable", "workspace is not configured or unavailable")).build();
    }
    try {
      java.nio.file.Path root = workspace.toRealPath();
      // Files.walk does not follow symlinked directories by default. Explicitly reject
      // symlinked files, too, so returned entries cannot point outside the workspace.
      List<String> names;
      try (Stream<java.nio.file.Path> paths = Files.walk(root, MAX_DEPTH)) {
        names = paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            .filter(path -> !Files.isSymbolicLink(path))
            .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".hwf"))
            .map(path -> root.relativize(path).toString().replace('\\', '/'))
            .sorted(Comparator.naturalOrder())
            .limit(MAX_FILES)
            .toList();
      }
      return Response.ok(new FileListResponse(names, names.size() == MAX_FILES)).build();
    } catch (IOException | SecurityException e) {
      return Response.status(503).entity(new ErrorResponse(
          "workspace_unavailable", "workflow files could not be listed")).build();
    }
  }

  public record FileListResponse(List<String> files, boolean possiblyTruncated) {}
  public record ErrorResponse(String code, String message) {}
}
