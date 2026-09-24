/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.hop.modern.web.document.PipelineOpenResource;
import org.glassfish.jersey.server.ResourceConfig;
import org.junit.jupiter.api.Test;

class ModernWebServerTest {
  @Test
  void bindsLoopbackAndRegistersPipelineOpenResource() {
    assertEquals("127.0.0.1", ModernWebServer.DEFAULT_URI.getHost());
    ResourceConfig config = ModernWebServer.createResourceConfig();
    assertTrue(
        config.getInstances().stream().anyMatch(PipelineOpenResource.class::isInstance),
        "the provisional host must expose the frozen pipeline open resource");
  }
}
