/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.modern.web.ModernWebServer;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.pipeline.transforms.tableinput.TableInputMeta;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PipelineEditResourceTest {
  @BeforeAll
  static void plugins() throws Exception {
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void authoritativeEditsSaveToRegisteredPathAndReopen(@TempDir Path temp) throws Exception {
    MemoryMetadataProvider metadata = new MemoryMetadataProvider();
    Variables variables = new Variables();
    PipelineDocumentStore store = new PipelineDocumentStore(metadata, variables);
    Path path = temp.resolve("edit-save.hpl");

    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("edit-save");
    TransformMeta first = new TransformMeta("TableInput", "first", new TableInputMeta());
    first.setLocation(10, 20);
    pipeline.addTransform(first);
    store.save(path, pipeline);

    PipelineDocument document = new PipelineDocument("doc", path, store.open(path));
    PipelineDocumentRegistry registry = new PipelineDocumentRegistry();
    registry.put(document);
    PipelineEditResource resource =
        new PipelineEditResource(registry, store, new PipelineGraphAdapter());

    assertEquals(
        200,
        resource
            .add("doc", new PipelineEditResource.AddRequest("second", "TableInput", 30, 40))
            .getStatus());
    assertEquals(
        200,
        resource
            .connect("doc", new PipelineEditResource.ConnectRequest("first", "second"))
            .getStatus());
    assertEquals(1, document.pipeline().nrPipelineHops());

    assertEquals(200, resource.undo("doc").getStatus());
    assertEquals(0, document.pipeline().nrPipelineHops());
    assertEquals(200, resource.redo("doc").getStatus());
    assertEquals(1, document.pipeline().nrPipelineHops());

    assertEquals(
        200,
        resource.delete("doc", new PipelineEditResource.NodeRequest("second")).getStatus());
    assertEquals(null, document.pipeline().findTransform("second"));
    assertEquals(200, resource.undo("doc").getStatus());
    assertNotNull(document.pipeline().findTransform("second"));
    assertEquals(1, document.pipeline().nrPipelineHops());

    assertEquals(200, resource.save("doc").getStatus());

    PipelineMeta reopened = store.open(path);
    assertNotNull(reopened.findTransform("first"));
    assertNotNull(reopened.findTransform("second"));
    assertEquals(30, reopened.findTransform("second").getLocation().x);
    assertEquals(40, reopened.findTransform("second").getLocation().y);
    assertEquals(1, reopened.nrPipelineHops());
    assertEquals("first", reopened.getPipelineHop(0).getFromTransform().getName());
    assertEquals("second", reopened.getPipelineHop(0).getToTransform().getName());
    assertEquals(false, reopened.hasChanged());
  }
}
