/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web.document;

import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Minimal in-memory lifetime for authoritative opened pipeline documents. */
public final class PipelineDocumentRegistry {
  private final ConcurrentMap<String, PipelineDocument> documents = new ConcurrentHashMap<>();

  private final ConcurrentMap<String, byte[]> diskSnapshots = new ConcurrentHashMap<>();

  public void put(PipelineDocument document) {
    PipelineDocument value = Objects.requireNonNull(document, "document");
    try {
      diskSnapshots.put(value.id(), digest(Files.readAllBytes(value.path())));
    } catch (IOException e) {
      // An unreadable file must never be silently treated as safe to overwrite.
      diskSnapshots.remove(value.id());
    }
    documents.put(value.id(), value);
  }

  /** Check the exact on-disk bytes captured when the document was opened or last saved. */
  public boolean isUnchanged(PipelineDocument document) throws IOException {
    byte[] expected = diskSnapshots.get(document.id());
    return expected != null
        && Arrays.equals(expected, digest(Files.readAllBytes(document.path())));
  }

  public void refreshSnapshot(PipelineDocument document) throws IOException {
    diskSnapshots.put(document.id(), digest(Files.readAllBytes(document.path())));
  }

  private static byte[] digest(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  public PipelineDocument get(String id) {
    return id == null ? null : documents.get(id);
  }
}
