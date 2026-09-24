/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web.document;

import java.nio.file.Path;
import java.util.Objects;
import org.apache.hop.pipeline.PipelineMeta;

/** Session-friendly carrier for an opened pipeline without duplicating Hop's document model. */
public record PipelineDocument(String id, Path path, PipelineMeta pipeline) {
  public PipelineDocument {
    if (Objects.requireNonNull(id, "id").isBlank()) {
      throw new IllegalArgumentException("id must not be blank");
    }
    path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    pipeline = Objects.requireNonNull(pipeline, "pipeline");
  }
}
