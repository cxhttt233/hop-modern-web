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

/** Read-only workspace file catalog for the browser's Open dialog. */
@Path("/api/files")
@Produces(MediaType.APPLICATION_JSON)
public final class WorkspaceFileCatalogResource {
  private static final int MAX_DEPTH = 8;
  private static final int MAX_RESULTS = 500;
  private final java.nio.file.Path workspace;

  public WorkspaceFileCatalogResource() {
    this(System.getProperty("hop.modern.workspace"));
  }

  WorkspaceFileCatalogResource(String configuredRoot) {
    workspace = configuredRoot == null || configuredRoot.isBlank()
        ? null : java.nio.file.Path.of(configuredRoot).toAbsolutePath().normalize();
  }

  @GET
  @Path("/pipelines")
  public Response pipelines() {
    return list("hpl");
  }

  @GET
  @Path("/workflows")
  public Response workflows() {
    return list("hwf");
  }

  private Response list(String extension) {
    if (workspace == null || !Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(workspace)) {
      return Response.status(503).entity(new ErrorResult("workspace_unavailable")).build();
    }
    try (Stream<java.nio.file.Path> stream = Files.walk(workspace, MAX_DEPTH)) {
      List<String> names = stream
          .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
          .filter(path -> !Files.isSymbolicLink(path))
          .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
              .endsWith("." + extension))
          .map(path -> workspace.relativize(path).toString().replace('\\', '/'))
          .sorted(Comparator.naturalOrder())
          .limit(MAX_RESULTS)
          .toList();
      return Response.ok(new CatalogResult(names)).build();
    } catch (IOException | SecurityException e) {
      return Response.status(503).entity(new ErrorResult("workspace_unavailable")).build();
    }
  }

  public record CatalogResult(List<String> files) {}
  public record ErrorResult(String code) {}
}