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
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.ContextResolver;
import java.io.IOException;
import java.net.URI;
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
import org.apache.hop.modern.web.execution.PipelineExecutionResource;
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
    initializePipelinePlugins();
    HttpServer server =
        GrizzlyHttpServerFactory.createHttpServer(DEFAULT_URI, createResourceConfig());
    Runtime.getRuntime().addShutdownHook(new Thread(server::shutdownNow));
    Thread.currentThread().join();
  }

  /** Registers the pipeline transform plugin type required to parse real .hpl transforms. */
  public static void initializePipelinePlugins() throws Exception {
    PluginRegistry.addPluginType(TransformPluginType.getInstance());
    PluginRegistry.init();
  }

  static ResourceConfig createResourceConfig() {
    IVariables variables = Variables.getADefaultVariableSpace();
    IHopMetadataProvider metadataProvider = new MemoryMetadataProvider();
    ProductContext productContext = new ProductContext(variables, metadataProvider);
    PipelineDocumentStore store =
        new PipelineDocumentStore(productContext.metadataProvider(), productContext.variables());
    PipelineDocumentRegistry registry = new PipelineDocumentRegistry();
    PipelineOpenResource openResource =
        new PipelineOpenResource(store, new PipelineGraphAdapter(), registry);
    PipelineConfigResource configResource =
        new PipelineConfigResource(registry, metadataProvider);
    PipelineEditResource editResource = new PipelineEditResource(registry, store, new PipelineGraphAdapter());
    PipelineExecutionResource executionResource =
        new PipelineExecutionResource(registry, productContext);
    return new ResourceConfig()
        .register(openResource)
        .register(configResource)
        .register(editResource)
        .register(executionResource)
        .register(new NoStoreApiResponseFilter())
        .register(new JavaTimeObjectMapperProvider())
        .register(JacksonFeature.class);
  }
  /** Prevent shared browser and intermediary caches from retaining pipeline state or execution data. */
  private static final class NoStoreApiResponseFilter implements ContainerResponseFilter {
    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response)
        throws IOException {
      String path = request.getUriInfo().getPath();
      if (path.equals("api") || path.startsWith("api/")) {
        response.getHeaders().putSingle("Cache-Control", "no-store");
      }
    }
  }

  private static final class JavaTimeObjectMapperProvider implements ContextResolver<ObjectMapper> {
    private final ObjectMapper mapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            // A request body must contain exactly one JSON value, never trailing tokens.
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            // Reject ambiguous duplicate JSON fields before binding to Pipeline API records.
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Override
    public ObjectMapper getContext(Class<?> type) {
      return mapper;
    }
  }
}
