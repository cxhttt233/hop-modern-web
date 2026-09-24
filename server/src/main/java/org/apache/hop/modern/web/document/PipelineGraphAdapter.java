/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web.document;

import java.util.Objects;
import org.apache.hop.pipeline.editor.PipelineGraphProjection;

/** Thin boundary from Hop's authoritative graph projection to the browser graph contract. */
public final class PipelineGraphAdapter {

  public PipelineGraphDocument project(PipelineDocument document, String revision) {
    Objects.requireNonNull(document, "document");
    Objects.requireNonNull(revision, "revision");

    PipelineGraphProjection.Graph graph = PipelineGraphProjection.project(document.pipeline());
    return new PipelineGraphDocument(
        document.id(),
        document.pipeline().getName(),
        revision,
        graph.nodes().stream().map(PipelineGraphAdapter::node).toList(),
        graph.edges().stream().map(PipelineGraphAdapter::edge).toList());
  }

  private static PipelineGraphDocument.Node node(PipelineGraphProjection.Node node) {
    return new PipelineGraphDocument.Node(
        node.id(),
        node.name(),
        node.kind(),
        node.pluginId(),
        node.pluginType(),
        node.x(),
        node.y());
  }

  private static PipelineGraphDocument.Edge edge(PipelineGraphProjection.Edge edge) {
    return new PipelineGraphDocument.Edge(
        edge.id(), edge.source(), edge.target(), edge.enabled());
  }
}
