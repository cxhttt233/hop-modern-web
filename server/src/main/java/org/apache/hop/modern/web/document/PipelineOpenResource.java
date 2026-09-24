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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.apache.hop.core.exception.HopMissingPluginsException;
import org.apache.hop.core.exception.HopXmlException;

/** Minimal browser transport for opening a real pipeline as Graph JSON. */
@Path("/api/pipelines")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public final class PipelineOpenResource {
  private final PipelineDocumentStore store;
  private final PipelineGraphAdapter graphAdapter;

  public PipelineOpenResource(PipelineDocumentStore store, PipelineGraphAdapter graphAdapter) {
    this.store = Objects.requireNonNull(store, "store");
    this.graphAdapter = Objects.requireNonNull(graphAdapter, "graphAdapter");
  }

  @POST
  @Path("/open")
  public Response open(OpenRequest request) {
    if (request == null || request.path() == null || request.path().isBlank()) {
      return error(400, "invalid_request", "path is required");
    }

    try {
      java.nio.file.Path path = java.nio.file.Path.of(request.path()).toAbsolutePath().normalize();
      if (!Files.exists(path)) {
        return error(404, "not_found", "pipeline file was not found");
      }
      if (!Files.isRegularFile(path)) {
        return error(400, "invalid_path", "path must reference a file");
      }

      byte[] bytes = Files.readAllBytes(path);
      String id = opaqueToken("document", path.toString().getBytes(StandardCharsets.UTF_8));
      String revision = opaqueToken("revision", bytes);
      PipelineDocument document = new PipelineDocument(id, path, store.open(path));
      return Response.ok(graphAdapter.project(document, revision)).build();
    } catch (InvalidPathException e) {
      return error(400, "invalid_path", "path is invalid");
    } catch (NoSuchFileException e) {
      return error(404, "not_found", "pipeline file was not found");
    } catch (HopXmlException | HopMissingPluginsException e) {
      return error(422, "invalid_pipeline", "pipeline could not be parsed");
    } catch (IOException e) {
      return error(500, "io_error", "pipeline could not be read");
    } catch (RuntimeException e) {
      return error(500, "server_error", "pipeline could not be opened");
    }
  }

  private static String opaqueToken(String namespace, byte[] value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(namespace.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      return HexFormat.of().formatHex(digest.digest(value));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private static Response error(int status, String code, String message) {
    return Response.status(status).entity(new ErrorResponse(code, message)).build();
  }

  public record OpenRequest(String path) {}

  public record ErrorResponse(String code, String message) {}
}
