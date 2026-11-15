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
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
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

  /** A stale or path-substituted open document must not overwrite any disk file. */
  public static final class ExternalModificationException extends IOException {
    public ExternalModificationException(String message) { super(message); }
    public ExternalModificationException(String message, Throwable cause) { super(message, cause); }
  }

  /**
   * Save through directory descriptors anchored in a trusted workspace.
   *
   * Resolving a path and then using Files.move(path) is vulnerable if an
   * attacker swaps a parent directory for a symlink after an .hwf is opened.
   * SecureDirectoryStream refuses symlink traversal and all write operations
   * remain tied to the same opened parent directory descriptor.
   *
   * @return exactly the persisted UTF-8 bytes, suitable for the next revision.
   */
  public byte[] saveIfUnchanged(Path path, WorkflowMeta workflow, byte[] original,
      Path workspaceRoot) throws IOException, HopException {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(workflow, "workflow");
    Objects.requireNonNull(original, "original");
    requireWorkflowPath(path);
    // A serialization error must never affect the filesystem.
    byte[] replacement = workflow.getXml(variables).getBytes(StandardCharsets.UTF_8);
    Path target = path.toAbsolutePath().normalize();
    Path root = workspaceRoot == null
        ? target.getParent().toRealPath(LinkOption.NOFOLLOW_LINKS)
        : workspaceRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
    if (root == null || Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
        || !target.startsWith(root) || target.equals(root)) {
      throw new ExternalModificationException("workflow workspace path changed");
    }
    Path relative = root.relativize(target);
    java.util.List<SecureDirectoryStream<Path>> streams = new ArrayList<>();
    Path tempName = null;
    try (DirectoryStream<Path> directory = Files.newDirectoryStream(root)) {
      if (!(directory instanceof SecureDirectoryStream<Path> rootStream)) {
        // No unsafe path-based fallback for document saves on other providers.
        throw new IOException("SecureDirectoryStream is required for safe workflow saves");
      }
      SecureDirectoryStream<Path> current = rootStream;
      try {
        for (int i = 0; i < relative.getNameCount() - 1; ++i) {
          try {
            current = current.newDirectoryStream(relative.getName(i), LinkOption.NOFOLLOW_LINKS);
          } catch (IOException e) {
            throw new ExternalModificationException("workflow parent directory changed", e);
          }
          streams.add(current);
        }
        Path filename = relative.getFileName();
        checkOpenedRevision(current, filename, original);
        tempName = Path.of(".hop-workflow-" + UUID.randomUUID() + ".tmp");
        try (SeekableByteChannel output = current.newByteChannel(tempName,
            Set.<OpenOption>of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS))) {
          ByteBuffer buffer = ByteBuffer.wrap(replacement);
          while (buffer.hasRemaining()) output.write(buffer);
        }
        // Re-check after serializing/writing: a concurrent edit is a 409.
        checkOpenedRevision(current, filename, original);
        current.move(tempName, current, filename);
        tempName = null;
      } finally {
        if (tempName != null) {
          try { current.deleteFile(tempName); } catch (IOException ignored) { /* keep failure */ }
        }
        for (int i = streams.size() - 1; i >= 0; --i) streams.get(i).close();
      }
    }
    workflow.setFilename(path.toString());
    workflow.clearChanged();
    return replacement;
  }

  private static void checkOpenedRevision(SecureDirectoryStream<Path> parent,
      Path name, byte[] expected) throws IOException {
    try (SeekableByteChannel input = parent.newByteChannel(name,
        Set.<OpenOption>of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
      byte[] actual = Channels.newInputStream(input).readAllBytes();
      if (!Arrays.equals(expected, actual)) {
        throw new ExternalModificationException("workflow changed on disk");
      }
    } catch (ExternalModificationException e) {
      throw e;
    } catch (IOException e) {
      throw new ExternalModificationException("workflow file was replaced or removed", e);
    }
  }

  private static void requireWorkflowPath(Path path) {
    Path name = path.getFileName();
    if (name == null || !name.toString().toLowerCase(Locale.ROOT).endsWith(".hwf")) {
      throw new IllegalArgumentException("workflow path must end with .hwf");
    }
  }
}