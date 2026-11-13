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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Read-only discovery of real pipeline files in the configured workspace.
 * Never returns server absolute paths or follows directory symbolic links.
 */
@Path("/api/files")
@Produces(MediaType.APPLICATION_JSON)
public final class WorkspaceFileResource {
  private static final int MAX_FILES = 500;

  @GET
  @Path("/pipelines")
  public Response pipelines() {
    String configured = System.getProperty("hop.modern.workspace");
    if (configured == null || configured.isBlank()) {
      return error(503, "workspace_not_configured");
    }
    try {
      java.nio.file.Path root = java.nio.file.Path.of(configured).toRealPath();
      if (!Files.isDirectory(root)) {
        return error(503, "workspace_not_directory");
      }
      // walk() does not follow symbolic links by default. Explicitly reject symlink files.
      try (Stream<java.nio.file.Path> entries = Files.walk(root, 8)) {
        List<FileEntry> files = entries
            .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            .filter(path -> !Files.isSymbolicLink(path))
            .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".hpl"))
            .map(path -> new FileEntry(root.relativize(path).toString().replace('\\', '/'),
                path.getFileName().toString()))
            .sorted(Comparator.comparing(FileEntry::path))
            .limit(MAX_FILES + 1L)
            .toList();
        boolean truncated = files.size() > MAX_FILES;
        return Response.ok(new FileListResponse(
            truncated ? files.subList(0, MAX_FILES) : files, truncated)).build();
      }
    } catch (IOException | SecurityException | java.nio.file.InvalidPathException e) {
      return error(503, "workspace_unavailable");
    }
  }

  private static Response error(int status, String code) {
    return Response.status(status).entity(new ErrorResponse(code)).build();
  }

  public record FileEntry(String path, String name) {}
  public record FileListResponse(List<FileEntry> files, boolean truncated) {}
  public record ErrorResponse(String code) {}
}
