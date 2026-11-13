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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.util.Objects;
import org.apache.hop.core.xml.XmlHandler;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.workflow.WorkflowMeta;

/**
 * Read-only, root-confined first .hwf slice. This endpoint deliberately does not
 * register executable documents: parsing a workflow is not authorization to run it.
 */
@Path("/api/workflows")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public final class WorkflowPreviewResource {
  private final java.nio.file.Path root;
  private final IHopMetadataProvider metadata;
  private final IVariables variables;

  public WorkflowPreviewResource(
      java.nio.file.Path root, IHopMetadataProvider metadata, IVariables variables)
      throws java.io.IOException {
    this.root = Objects.requireNonNull(root).toRealPath();
    this.metadata = Objects.requireNonNull(metadata);
    this.variables = Objects.requireNonNull(variables);
  }

  @POST
  @Path("/preview")
  public Response preview(OpenRequest request) {
    if (request == null || request.path() == null || request.path().isBlank()) {
      return error(400, "invalid_request", "path is required");
    }
    try {
      java.nio.file.Path relative = java.nio.file.Path.of(request.path());
      if (relative.isAbsolute() || !request.path().toLowerCase(java.util.Locale.ROOT).endsWith(".hwf")) {
        return error(400, "invalid_path", "a relative .hwf path is required");
      }
      java.nio.file.Path path = root.resolve(relative).normalize();
      if (!path.startsWith(root)) return error(403, "outside_root", "path is outside workflow root");
      // Resolve the real parent, so symlinked directories cannot escape the configured root.
      java.nio.file.Path realParent = path.getParent().toRealPath();
      if (!realParent.startsWith(root)) return error(403, "outside_root", "path is outside workflow root");
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        return error(404, "not_found", "workflow file was not found");
      }
      // NOFOLLOW_LINKS closes the final-component symlink race.
      try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
        var xml = XmlHandler.loadXmlFile(input, null, false, false);
        WorkflowMeta meta = new WorkflowMeta();
        meta.loadXml(XmlHandler.getSubNode(xml, WorkflowMeta.XML_TAG),
            path.toString(), metadata, variables);
        return Response.ok(new PreviewResponse(meta.getName(), relative.toString(), meta.getXml(variables))).build();
      }
    } catch (NoSuchFileException e) {
      return error(404, "not_found", "workflow file was not found");
    } catch (java.nio.file.InvalidPathException | IllegalArgumentException e) {
      return error(400, "invalid_path", "workflow path is invalid");
    } catch (Exception e) {
      return error(422, "invalid_workflow", "workflow could not be parsed");
    }
  }

  private static Response error(int status, String code, String message) {
    return Response.status(status).entity(new ErrorResponse(code, message)).build();
  }

  public record OpenRequest(String path) {}
  public record PreviewResponse(String name, String path, String xml) {}
  public record ErrorResponse(String code, String message) {}
}
