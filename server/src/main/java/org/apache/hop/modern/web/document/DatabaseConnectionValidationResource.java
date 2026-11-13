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
import java.util.ArrayList;
import java.util.List;

/**
 * Validates a browser database-connection draft without opening a socket, storing credentials,
 * or returning submitted secrets. This is deliberately not a connection test.
 */
@Path("/api/database-connections")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public final class DatabaseConnectionValidationResource {
  @POST
  @Path("/validate")
  public Response validate(ConnectionDraft draft) {
    List<FieldError> errors = new ArrayList<>();
    if (draft == null) {
      return Response.status(400)
          .entity(new ValidationResult(false, List.of(new FieldError("request", "required"))))
          .build();
    }
    if (blank(draft.name())) {
      errors.add(new FieldError("name", "required"));
    } else if (draft.name().length() > 128) {
      errors.add(new FieldError("name", "too_long"));
    }
    RdbmsDraft db = draft.rdbms();
    if (db == null) {
      errors.add(new FieldError("rdbms", "required"));
    } else {
      boolean hasUrl = !blank(db.manualUrl());
      if (hasUrl) {
        if (!db.manualUrl().startsWith("jdbc:") || db.manualUrl().length() > 4096) {
          errors.add(new FieldError("rdbms.manualUrl", "invalid_jdbc_url"));
        }
      } else {
        if (blank(db.hostname())) {
          errors.add(new FieldError("rdbms.hostname", "required"));
        } else if (db.hostname().length() > 253) {
          errors.add(new FieldError("rdbms.hostname", "too_long"));
        }
        if (blank(db.databaseName())) {
          errors.add(new FieldError("rdbms.databaseName", "required"));
        }
        if (!blank(db.port())) {
          try {
            int port = Integer.parseInt(db.port());
            if (port < 1 || port > 65535) {
              errors.add(new FieldError("rdbms.port", "out_of_range"));
            }
          } catch (NumberFormatException e) {
            errors.add(new FieldError("rdbms.port", "not_a_number"));
          }
        }
      }
    }
    return Response.ok(new ValidationResult(errors.isEmpty(), List.copyOf(errors))).build();
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  public record ConnectionDraft(String name, RdbmsDraft rdbms) {}

  public record RdbmsDraft(
      String hostname,
      String databaseName,
      String port,
      String username,
      String password,
      String manualUrl) {}

  public record FieldError(String field, String code) {}

  public record ValidationResult(boolean valid, List<FieldError> errors) {}
}
