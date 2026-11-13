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
import java.nio.file.AtomicMoveNotSupportedException;
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

/** Read and write real .hwf files using Hop's authoritative workflow serializer. */
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
    // Fail before touching the destination if Hop cannot serialize the document.
    String xml = workflow.getXml(variables);
    Path target = path.toAbsolutePath().normalize();
    Path parent = target.getParent();
    if (parent == null || !Files.isDirectory(parent)) {
      throw new IOException("Workflow destination directory does not exist: " + target);
    }
    Path temporary = Files.createTempFile(parent, ".hop-workflow-", ".tmp");
    try {
      Files.writeString(temporary, xml, StandardCharsets.UTF_8);
      try {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        // Do not silently downgrade to a non-atomic replacement: preserve the original.
        throw new IOException("Atomic workflow save is not supported for " + target, e);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
    workflow.setFilename(path.toString());
    workflow.clearChanged();
  }

  private static void requireWorkflowPath(Path path) {
    Path name = path.getFileName();
    if (name == null || !name.toString().toLowerCase(Locale.ROOT).endsWith(".hwf")) {
      throw new IllegalArgumentException("workflow path must end with .hwf");
    }
  }
}