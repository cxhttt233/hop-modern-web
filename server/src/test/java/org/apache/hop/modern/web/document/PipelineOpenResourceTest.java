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
import java.util.List;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.TransformMeta;
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
  void movesOpenedPipelineThroughAuthoritativeHopEditor() throws Exception {
    IVariables variables = Variables.getADefaultVariableSpace();
    PipelineDocumentStore store =
        new PipelineDocumentStore(mock(IHopMetadataProvider.class), variables);
    PipelineGraphAdapter graphAdapter = new PipelineGraphAdapter();
    PipelineDocumentRegistry registry = new PipelineDocumentRegistry();
    PipelineOpenResource resource = new PipelineOpenResource(store, graphAdapter, registry);
    Path file = tempDir.resolve("move.hpl");

    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("move-pipeline");
    store.save(file, pipeline);

    Response opened = resource.open(new PipelineOpenResource.OpenRequest(file.toString()));
    assertEquals(200, opened.getStatus());
    PipelineGraphDocument openedGraph = (PipelineGraphDocument) opened.getEntity();
    PipelineDocument authoritative = registry.get(openedGraph.id());
    assertNotNull(authoritative);

    TransformMeta transform = new TransformMeta();
    transform.setName("first");
    transform.setTransformPluginId("ModernWebTestTransform");
    transform.setLocation(10, 20);
    authoritative.pipeline().addTransform(transform);
    authoritative.pipeline().clearUndo();

    Response firstMove =
        resource.move(
            openedGraph.id(),
            new PipelineOpenResource.MoveRequest(List.of("first"), 5, 6));
    assertEquals(200, firstMove.getStatus());
    PipelineGraphDocument firstMovedGraph = (PipelineGraphDocument) firstMove.getEntity();
    PipelineGraphDocument.Node firstMovedNode =
        firstMovedGraph.nodes().stream().filter(node -> node.id().equals("first")).findFirst().orElseThrow();
    assertEquals(15, firstMovedNode.x());
    assertEquals(26, firstMovedNode.y());
    assertNotNull(authoritative.pipeline().previousUndo());

    Response secondMove =
        resource.move(
            openedGraph.id(),
            new PipelineOpenResource.MoveRequest(List.of("first"), 5, 6));
    assertEquals(200, secondMove.getStatus());
    PipelineGraphDocument secondMovedGraph = (PipelineGraphDocument) secondMove.getEntity();
    PipelineGraphDocument.Node secondMovedNode =
        secondMovedGraph.nodes().stream().filter(node -> node.id().equals("first")).findFirst().orElseThrow();
    assertEquals(20, secondMovedNode.x());
    assertEquals(32, secondMovedNode.y());
    assertNotEquals(firstMovedGraph.revision(), secondMovedGraph.revision());

    Response missingDocument =
        resource.move(
            "missing",
            new PipelineOpenResource.MoveRequest(List.of("first"), 1, 1));
    assertEquals(404, missingDocument.getStatus());
    assertEquals(
        "document_not_found",
        ((PipelineOpenResource.ErrorResponse) missingDocument.getEntity()).code());

    Response missingTransform =
        resource.move(
            openedGraph.id(),
            new PipelineOpenResource.MoveRequest(List.of("missing"), 1, 1));
    assertEquals(422, missingTransform.getStatus());
    assertEquals(
        "unknown_transform",
        ((PipelineOpenResource.ErrorResponse) missingTransform.getEntity()).code());
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
