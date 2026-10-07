/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.ws.rs.ext.ContextResolver;
import java.net.URI;
import java.nio.file.Path;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.plugins.ActionPluginType;
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
import org.apache.hop.modern.web.document.WorkflowDocumentOpenResource;
import org.apache.hop.modern.web.document.WorkflowDocumentRegistry;
import org.apache.hop.modern.web.execution.PipelineExecutionResource;
import org.apache.hop.modern.web.execution.WorkflowExecutionAdapter;
import org.apache.hop.modern.web.execution.WorkflowExecutionResource;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;

/** Provisional loopback-only HTTP host for real Hop documents and execution slices. */
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

  /** Registers plugin types required to parse real .hpl and .hwf documents. */
  public static void initializePipelinePlugins() throws Exception {
    PluginRegistry.addPluginType(TransformPluginType.getInstance());
    PluginRegistry.addPluginType(ActionPluginType.getInstance());
    PluginRegistry.init();
  }

  static ResourceConfig createResourceConfig() {
    try {
      return createResourceConfig(Path.of(System.getProperty("hop.modern.workflow.root", ".")));
    } catch (java.io.IOException e) {
      throw new IllegalStateException("invalid workflow document root", e);
    }
  }

  static ResourceConfig createResourceConfig(Path workflowRoot) throws java.io.IOException {
    return createResourceConfig(workflowRoot, new WorkflowExecutionAdapter());
  }

  public static ResourceConfig createResourceConfig(Path workflowRoot, WorkflowExecutionAdapter workflowExecutions)
      throws java.io.IOException {
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
    PipelineEditResource editResource =
        new PipelineEditResource(registry, store, new PipelineGraphAdapter());
    PipelineExecutionResource executionResource =
        new PipelineExecutionResource(registry, productContext);
    WorkflowDocumentRegistry workflowDocuments =
        new WorkflowDocumentRegistry(workflowRoot, variables, metadataProvider);
    return new ResourceConfig()
        .register(openResource)
        .register(configResource)
        .register(editResource)
        .register(executionResource)
        .register(new WorkflowDocumentOpenResource(workflowDocuments))
        .register(new WorkflowExecutionResource(workflowDocuments, workflowExecutions))
        .register(new JavaTimeObjectMapperProvider())
        .register(JacksonFeature.class);
  }

  private static final class JavaTimeObjectMapperProvider implements ContextResolver<ObjectMapper> {
    private final ObjectMapper mapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Override
    public ObjectMapper getContext(Class<?> type) {
      return mapper;
    }
  }
}
