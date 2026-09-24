/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.pipeline.PipelineMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PipelineGraphAdapterTest {
  @TempDir Path tempDir;

  @Test
  void opensRealHplAndProjectsBrowserGraphContract() throws Exception {
    IVariables variables = Variables.getADefaultVariableSpace();
    PipelineDocumentStore store =
        new PipelineDocumentStore(mock(IHopMetadataProvider.class), variables);
    Path file = tempDir.resolve("graph.hpl");

    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("graph-pipeline");
    store.save(file, pipeline);

    PipelineDocument document = new PipelineDocument("doc-1", file, store.open(file));
    PipelineGraphDocument graph = new PipelineGraphAdapter().project(document, "rev-1");

    assertEquals("doc-1", graph.id());
    assertEquals("graph", graph.name());
    assertEquals("rev-1", graph.revision());
    assertTrue(graph.nodes().isEmpty());
    assertTrue(graph.edges().isEmpty());
  }
}
