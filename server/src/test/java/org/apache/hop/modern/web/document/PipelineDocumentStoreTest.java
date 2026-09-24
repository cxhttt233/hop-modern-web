/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.pipeline.PipelineMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PipelineDocumentStoreTest {
  @TempDir Path tempDir;

  @Test
  void savesAndReloadsARealHplThroughHopModel() throws Exception {
    Variables variables = Variables.getADefaultVariableSpace();
    IHopMetadataProvider metadataProvider = mock(IHopMetadataProvider.class);
    PipelineDocumentStore store = new PipelineDocumentStore(metadataProvider, variables);
    Path file = tempDir.resolve("round-trip.hpl");

    PipelineMeta original = new PipelineMeta();
    original.setName("round-trip");
    original.setDescription("saved by modern web");

    store.save(file, original);

    assertTrue(Files.readString(file).contains("<pipeline>"));
    assertEquals(file.toString(), original.getFilename());
    assertFalse(original.hasChanged());

    PipelineMeta reloaded = store.open(file);
    assertEquals("round-trip", reloaded.getName());
    assertEquals("saved by modern web", reloaded.getDescription());
    assertEquals(file.toString(), reloaded.getFilename());
    assertFalse(reloaded.hasChanged());
  }
}
