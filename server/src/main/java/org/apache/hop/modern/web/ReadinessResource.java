/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Loopback readiness contract. A reachable HTTP listener is not necessarily
 * ready to open a pipeline; plugin initialization must have completed.
 */
@Path("/api/ready")
public final class ReadinessResource {
  private final BooleanSupplier initialized;

  public ReadinessResource(BooleanSupplier initialized) {
    this.initialized = Objects.requireNonNull(initialized, "initialized");
  }

  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public Response get() {
    if (!initialized.getAsBoolean()) {
      return Response.status(Response.Status.SERVICE_UNAVAILABLE)
          .header("Cache-Control", "no-store")
          .entity("not_ready")
          .build();
    }
    return Response.ok("ready")
        .header("Cache-Control", "no-store")
        .build();
  }
}
