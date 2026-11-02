/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.ext.ContextResolver;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.plugins.TransformPluginType;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.modern.web.document.PipelineConfigResource;
import org.apache.hop.modern.web.document.PipelineDocumentRegistry;
import org.apache.hop.modern.web.document.PipelineEditResource;
import org.apache.hop.modern.web.document.PipelineDocumentStore;
import org.apache.hop.modern.web.document.PipelineGraphAdapter;
import org.apache.hop.modern.web.document.PipelineOpenResource;
import org.eclipse.jetty.server.Server;
import org.glassfish.jersey.jetty.JettyHttpContainerFactory;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.jackson.internal.jackson.jaxrs.cfg.JaxRSFeature;
import org.glassfish.jersey.server.ResourceConfig;

/** Loopback-only Jetty/Jersey host for the modern browser pipeline slice. */
public final class ModernWebServer {
  static final URI DEFAULT_URI = URI.create("http://127.0.0.1:8080/");
  private static final AtomicBoolean PLUGINS_INITIALIZED = new AtomicBoolean(false);

  private ModernWebServer() {}

  public static void main(String[] args) throws Exception {
    HopClientEnvironment.init();
    initializePipelinePlugins();
    Server server = JettyHttpContainerFactory.createServer(DEFAULT_URI, createResourceConfig());
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try {
        server.stop();
      } catch (Exception e) {
        System.err.println("Modern Web Jetty shutdown failed: " + e.getMessage());
      }
    }));
    Thread.currentThread().join();
  }

  /** Registers the pipeline transform plugin type required to parse real .hpl transforms. */
  public static void initializePipelinePlugins() throws Exception {
    PluginRegistry.addPluginType(TransformPluginType.getInstance());
    PluginRegistry.init();
    PLUGINS_INITIALIZED.set(true);
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
    PipelineEditResource editResource = new PipelineEditResource(registry, store, new PipelineGraphAdapter());
    return new ResourceConfig()
        .register(openResource)
        .register(configResource)
        .register(editResource)
        .register(new ReadinessResource(PLUGINS_INITIALIZED::get))
        // Keep the supported provider setting: full-stream reads blocked on Grizzly.
        // The production Jetty listener must also enforce strict JSON boundaries.
        .register(new JacksonFeature().jaxrsFeature(JaxRSFeature.READ_FULL_STREAM, false))
        .register(new StrictJsonMapperProvider());
  }

  /** Reject ambiguous or trailing request JSON on the production Jetty/Jersey stack. */
  public static final class StrictJsonMapperProvider implements ContextResolver<ObjectMapper> {
    private final ObjectMapper mapper = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    @Override
    public ObjectMapper getContext(Class<?> type) {
      return mapper;
    }
  }
}
