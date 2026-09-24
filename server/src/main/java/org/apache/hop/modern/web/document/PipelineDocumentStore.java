/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web.document;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.core.exception.HopMissingPluginsException;
import org.apache.hop.core.exception.HopXmlException;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.pipeline.PipelineMeta;

/** Thin file adapter that keeps Apache Hop authoritative for .hpl parsing and serialization. */
public final class PipelineDocumentStore {
  private final IHopMetadataProvider metadataProvider;
  private final IVariables variables;

  public PipelineDocumentStore(IHopMetadataProvider metadataProvider, IVariables variables) {
    this.metadataProvider = Objects.requireNonNull(metadataProvider, "metadataProvider");
    this.variables = Objects.requireNonNull(variables, "variables");
  }

  public PipelineMeta open(Path path)
      throws IOException, HopXmlException, HopMissingPluginsException {
    Objects.requireNonNull(path, "path");
    try (InputStream input = Files.newInputStream(path)) {
      PipelineMeta pipeline = new PipelineMeta(input, metadataProvider, variables);
      pipeline.setFilename(path.toString());
      return pipeline;
    }
  }

  public void save(Path path, PipelineMeta pipeline) throws IOException, HopException {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(pipeline, "pipeline");
    Files.writeString(
        path,
        pipeline.getXml(variables),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);
    pipeline.setFilename(path.toString());
    pipeline.clearChanged();
  }
}
