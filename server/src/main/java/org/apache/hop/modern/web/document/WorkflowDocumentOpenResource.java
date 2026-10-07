/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web.document;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.file.NoSuchFileException;
import java.util.Objects;
import org.apache.hop.modern.web.execution.WorkflowExecutionResource.ErrorResponse;

/** Root-confined .hwf open transport; the opaque cookie scopes documents and executions. */
@Path("/api/v2/documents")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public final class WorkflowDocumentOpenResource {
  private final WorkflowDocumentRegistry registry;

  public WorkflowDocumentOpenResource(WorkflowDocumentRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  @POST
  public Response open(OpenRequest request, @CookieParam("hop-modern-session") String cookie) {
    if (request == null || request.uri() == null || request.uri().isBlank()) {
      return Response.status(400).entity(new ErrorResponse("invalid_document", "uri is required")).build();
    }
    String session = registry.session(cookie);
    try {
      var document = registry.open(session, request.uri());
      return Response.status(201).entity(document)
          .header("Set-Cookie", "hop-modern-session=" + session + "; Path=/api/v2; HttpOnly; SameSite=Strict")
          .build();
    } catch (NoSuchFileException e) {
      return Response.status(404).entity(new ErrorResponse("document_not_found", "workflow file was not found")).build();
    } catch (IllegalArgumentException e) {
      return Response.status(400).entity(new ErrorResponse("invalid_document", e.getMessage())).build();
    } catch (Exception e) {
      return Response.status(422).entity(new ErrorResponse("document_open_failed", "workflow could not be opened")).build();
    }
  }

  public record OpenRequest(String uri) {}
}
