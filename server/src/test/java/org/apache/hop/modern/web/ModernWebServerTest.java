/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.database.DatabaseMeta;
import org.apache.hop.core.database.DatabasePluginType;
import org.apache.hop.core.encryption.HopTwoWayPasswordEncoder;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.databases.h2.H2DatabaseMeta;
import org.apache.hop.metadata.serializer.json.JsonMetadataProvider;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.modern.web.document.PipelineOpenResource;
import org.glassfish.jersey.server.ResourceConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModernWebServerTest {
  @Test
  void bindsLoopbackAndRegistersPipelineOpenResource() {
    assertEquals("127.0.0.1", ModernWebServer.DEFAULT_URI.getHost());
    ResourceConfig config = ModernWebServer.createResourceConfig();
    assertTrue(
        config.getInstances().stream().anyMatch(PipelineOpenResource.class::isInstance),
        "the provisional host must expose the frozen pipeline open resource");
  }

  @Test
  void configuredHopMetadataFolderLoadsPersistedDatabaseConnection(@TempDir Path metadataDir)
      throws Exception {
    HopClientEnvironment.init();
    DatabasePluginType.getInstance().registerClassPathPlugin(H2DatabaseMeta.class);
    var variables = new Variables();
    var writer = new JsonMetadataProvider(
        new HopTwoWayPasswordEncoder(), metadataDir.toString(), variables);
    var warehouse = new DatabaseMeta(
        "Warehouse", "H2", "Native", "", "mem:metadata-proof;DB_CLOSE_DELAY=-1", "", "", "");
    writer.getSerializer(DatabaseMeta.class).save(warehouse);

    var selected = ModernWebServer.selectMetadataProvider(variables, metadataDir.toString());
    assertInstanceOf(JsonMetadataProvider.class, selected);
    var loaded = selected.getSerializer(DatabaseMeta.class).load("Warehouse");
    assertNotNull(loaded, "configured project connection must be available to the executor");
    assertEquals("Warehouse", loaded.getName());
    assertEquals("H2", loaded.getPluginId());
  }

  @Test
  void invalidConfiguredMetadataFolderFailsClosedRatherThanRunningWithEmptyConnections(
      @TempDir Path existingDirectory) {
    var variables = new Variables();
    assertThrows(IllegalStateException.class, () ->
        ModernWebServer.selectMetadataProvider(
            variables, existingDirectory.resolve("missing").toString()));
    assertInstanceOf(MemoryMetadataProvider.class,
        ModernWebServer.selectMetadataProvider(variables, null));
  }

}
