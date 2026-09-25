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
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.modern.web.document.PipelineConfigResource;
import org.apache.hop.modern.web.document.PipelineDocumentRegistry;
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
    HttpServer server =
        GrizzlyHttpServerFactory.createHttpServer(DEFAULT_URI, createResourceConfig());
    Runtime.getRuntime().addShutdownHook(new Thread(server::shutdownNow));
    Thread.currentThread().join();
  }

  static ResourceConfig createResourceConfig() {
    IVariables variables = Variables.getADefaultVariableSpace();
    IHopMetadataProvider metadataProvider = new MemoryMetadataProvider();
    PipelineDocumentStore store = new PipelineDocumentStore(metadataProvider, variables);
    PipelineDocumentRegistry registry = new PipelineDocumentRegistry();
    PipelineOpenResource openResource =
        new PipelineOpenResource(store, new PipelineGraphAdapter(), registry);
    PipelineConfigResource configResource =
        new PipelineConfigResource(registry, metadataProvider);
    return new ResourceConfig()
        .register(openResource)
        .register(configResource)
        .register(JacksonFeature.class);
  }
}
