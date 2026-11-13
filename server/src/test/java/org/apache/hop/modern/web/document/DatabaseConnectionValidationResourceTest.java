/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.core.Response;
import java.util.List;
import org.junit.jupiter.api.Test;

class DatabaseConnectionValidationResourceTest {
  private final DatabaseConnectionValidationResource resource =
      new DatabaseConnectionValidationResource();

  @Test
  void acceptsHostBasedDraftWithoutConnecting() {
    Response response = resource.validate(
        draft("local", "127.0.0.1", "sample", "5432", null, "secret"));
    assertEquals(200, response.getStatus());
    assertTrue(result(response).valid());
  }

  @Test
  void acceptsManualJdbcUrlWithoutHostname() {
    Response response = resource.validate(
        draft("remote", null, null, null, "jdbc:postgresql://localhost:5432/sample", "secret"));
    assertTrue(result(response).valid());
  }

  @Test
  void rejectsOutOfRangePortWithoutReturningPassword() {
    Response response = resource.validate(
        draft("invalid", "localhost", "sample", "65536", null, "TOP_SECRET_VALUE"));
    DatabaseConnectionValidationResource.ValidationResult result = result(response);
    assertFalse(result.valid());
    assertEquals(List.of(new DatabaseConnectionValidationResource.FieldError(
        "rdbms.port", "out_of_range")), result.errors());
    assertFalse(result.toString().contains("TOP_SECRET_VALUE"));
  }

  @Test
  void rejectsNonJdbcUrl() {
    Response response = resource.validate(
        draft("invalid", null, null, null, "https://example.com", "secret"));
    assertFalse(result(response).valid());
    assertEquals("rdbms.manualUrl", result(response).errors().get(0).field());
  }

  @Test
  void missingRequestReturns400() {
    Response response = resource.validate(null);
    assertEquals(400, response.getStatus());
    assertFalse(result(response).valid());
  }

  private static DatabaseConnectionValidationResource.ConnectionDraft draft(
      String name, String host, String database, String port, String url, String password) {
    return new DatabaseConnectionValidationResource.ConnectionDraft(name,
        new DatabaseConnectionValidationResource.RdbmsDraft(
            host, database, port, "user", password, url));
  }

  private static DatabaseConnectionValidationResource.ValidationResult result(Response response) {
    return (DatabaseConnectionValidationResource.ValidationResult) response.getEntity();
  }
}
