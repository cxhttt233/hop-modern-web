/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.apache.hop.pipeline.PipelineMeta;
import org.junit.jupiter.api.Test;

class PipelineDocumentTest {
  @Test
  void retainsHopModelAndNormalizesPath() {
    PipelineMeta pipeline = new PipelineMeta();
    PipelineDocument document =
        new PipelineDocument("pipeline-1", Path.of("pipelines", "..", "sample.hpl"), pipeline);

    assertEquals("pipeline-1", document.id());
    assertEquals(Path.of("sample.hpl").toAbsolutePath().normalize(), document.path());
    assertSame(pipeline, document.pipeline());
  }

  @Test
  void rejectsBlankDocumentId() {
    PipelineMeta pipeline = new PipelineMeta();
    assertThrows(
        IllegalArgumentException.class,
        () -> new PipelineDocument(" ", Path.of("sample.hpl"), pipeline));
  }
}
