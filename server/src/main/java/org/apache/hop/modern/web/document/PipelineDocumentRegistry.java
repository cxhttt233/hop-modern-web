/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web.document;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Minimal in-memory lifetime for authoritative opened pipeline documents. */
public final class PipelineDocumentRegistry {
  private final ConcurrentMap<String, PipelineDocument> documents = new ConcurrentHashMap<>();

  public void put(PipelineDocument document) {
    PipelineDocument value = Objects.requireNonNull(document, "document");
    documents.put(value.id(), value);
  }

  public PipelineDocument get(String id) {
    return id == null ? null : documents.get(id);
  }
}
