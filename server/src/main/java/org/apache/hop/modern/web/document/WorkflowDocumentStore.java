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
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Objects;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.core.exception.HopXmlException;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.workflow.WorkflowMeta;

/** Real .hwf persistence using the authoritative Hop workflow model and serializer. */
public final class WorkflowDocumentStore {
  private final IHopMetadataProvider metadataProvider;
  private final IVariables variables;

  public WorkflowDocumentStore(IHopMetadataProvider metadataProvider, IVariables variables) {
    this.metadataProvider = Objects.requireNonNull(metadataProvider, "metadataProvider");
    this.variables = Objects.requireNonNull(variables, "variables");
  }

  public WorkflowMeta open(Path path) throws IOException, HopXmlException {
    Objects.requireNonNull(path, "path");
    requireWorkflowPath(path);
    try (InputStream input = Files.newInputStream(path)) {
      WorkflowMeta workflow = new WorkflowMeta(input, metadataProvider, variables);
      workflow.setFilename(path.toString());
      return workflow;
    }
  }

  public void save(Path path, WorkflowMeta workflow) throws IOException, HopException {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(workflow, "workflow");
    requireWorkflowPath(path);
    // Serialize before touching the destination: never truncate on a Hop serializer failure.
    String xml = workflow.getXml(variables);
    Path target = path.toAbsolutePath().normalize();
    Path temporary = Files.createTempFile(target.getParent(), ".hop-workflow-", ".tmp");
    try {
      Files.writeString(temporary, xml, StandardCharsets.UTF_8);
      Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
    workflow.setFilename(path.toString());
    workflow.clearChanged();
  }

  private static void requireWorkflowPath(Path path) {
    if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".hwf")) {
      throw new IllegalArgumentException("workflow path must end with .hwf");
    }
  }
}
