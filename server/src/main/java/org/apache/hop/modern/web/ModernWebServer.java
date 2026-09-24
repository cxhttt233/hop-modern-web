/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web;

import java.net.URI;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.modern.web.document.PipelineDocumentStore;
import org.apache.hop.modern.web.document.PipelineGraphAdapter;
import org.apache.hop.modern.web.document.PipelineOpenResource;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;

/** Provisional loopback-only HTTP host for the first real browser pipeline slice. */
public final class ModernWebServer {
  static final URI DEFAULT_URI = URI.create("http://127.0.0.1:8080/");

  private ModernWebServer() {}

  public static void main(String[] args) throws Exception {
    HopClientEnvironment.init();
    IVariables variables = Variables.getADefaultVariableSpace();
    PipelineDocumentStore store =
        new PipelineDocumentStore(new MemoryMetadataProvider(), variables);
    PipelineOpenResource openResource =
        new PipelineOpenResource(store, new PipelineGraphAdapter());

    ResourceConfig config = new ResourceConfig().register(openResource).register(JacksonFeature.class);
    HttpServer server = GrizzlyHttpServerFactory.createHttpServer(DEFAULT_URI, config);
    Runtime.getRuntime().addShutdownHook(new Thread(server::shutdownNow));
    Thread.currentThread().join();
  }
}
