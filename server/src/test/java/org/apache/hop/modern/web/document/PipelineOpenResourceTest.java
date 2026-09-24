/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.pipeline.PipelineMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PipelineOpenResourceTest {
  @TempDir Path tempDir;

  @Test
  void opensRealHplAsBrowserGraphWithStableIdAndContentRevision() throws Exception {
    IVariables variables = Variables.getADefaultVariableSpace();
    PipelineDocumentStore store =
        new PipelineDocumentStore(mock(IHopMetadataProvider.class), variables);
    PipelineOpenResource resource = new PipelineOpenResource(store, new PipelineGraphAdapter());
    Path file = tempDir.resolve("transport.hpl");

    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("transport-pipeline");
    store.save(file, pipeline);

    Response first = resource.open(new PipelineOpenResource.OpenRequest(file.toString()));
    Response second = resource.open(new PipelineOpenResource.OpenRequest(file.toString()));
    assertEquals(200, first.getStatus());
    PipelineGraphDocument firstGraph = (PipelineGraphDocument) first.getEntity();
    PipelineGraphDocument secondGraph = (PipelineGraphDocument) second.getEntity();
    assertEquals(firstGraph.id(), secondGraph.id());
    assertEquals(firstGraph.revision(), secondGraph.revision());
    assertNotNull(firstGraph.revision());
    assertEquals("transport", firstGraph.name());

    String json = new ObjectMapper().writeValueAsString(firstGraph);
    assertTrue(json.contains("\"id\":"));
    assertTrue(json.contains("\"name\":\"transport\""));
    assertTrue(json.contains("\"revision\":"));
    assertTrue(json.contains("\"nodes\":[]"));
    assertTrue(json.contains("\"edges\":[]"));

    Files.writeString(file, Files.readString(file) + "\n");
    Response changed = resource.open(new PipelineOpenResource.OpenRequest(file.toString()));
    PipelineGraphDocument changedGraph = (PipelineGraphDocument) changed.getEntity();
    assertEquals(firstGraph.id(), changedGraph.id());
    assertNotEquals(firstGraph.revision(), changedGraph.revision());
  }

  @Test
  void returnsJsonShapedClientErrors() {
    PipelineDocumentStore store =
        new PipelineDocumentStore(
            mock(IHopMetadataProvider.class), Variables.getADefaultVariableSpace());
    PipelineOpenResource resource = new PipelineOpenResource(store, new PipelineGraphAdapter());

    Response missingPath = resource.open(new PipelineOpenResource.OpenRequest(" "));
    assertEquals(400, missingPath.getStatus());
    assertEquals("invalid_request", ((PipelineOpenResource.ErrorResponse) missingPath.getEntity()).code());

    Response missingFile =
        resource.open(new PipelineOpenResource.OpenRequest(tempDir.resolve("missing.hpl").toString()));
    assertEquals(404, missingFile.getStatus());
    assertEquals("not_found", ((PipelineOpenResource.ErrorResponse) missingFile.getEntity()).code());
  }
}
