/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web.document;

import java.util.List;

/** JSON-facing graph shape shared with the browser HopGraphDocument contract. */
public record PipelineGraphDocument(
    String id, String name, String revision, List<Node> nodes, List<Edge> edges) {

  public record Node(
      String id, String name, String kind, String pluginId, String pluginType, int x, int y) {}

  public record Edge(String id, String source, String target, boolean enabled) {}
}
