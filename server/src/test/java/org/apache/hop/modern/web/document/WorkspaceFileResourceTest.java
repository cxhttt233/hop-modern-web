/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceFileResourceTest {
  @TempDir Path workspace;

  @Test
  void requiresExplicitWorkspace() {
    String previous = System.getProperty("hop.modern.workspace");
    try {
      System.clearProperty("hop.modern.workspace");
      assertEquals(503, new WorkspaceFileResource().pipelines().getStatus());
    } finally {
      restore(previous);
    }
  }

  @Test
  void listsOnlyPipelinesWithRelativePaths() throws Exception {
    Files.createDirectories(workspace.resolve("nested"));
    Files.writeString(workspace.resolve("nested/second.hpl"), "<pipeline/>");
    Files.writeString(workspace.resolve("first.HPL"), "<pipeline/>");
    Files.writeString(workspace.resolve("ignored.txt"), "not a pipeline");
    String previous = System.getProperty("hop.modern.workspace");
    try {
      System.setProperty("hop.modern.workspace", workspace.toString());
      var response = new WorkspaceFileResource().pipelines();
      assertEquals(200, response.getStatus());
      var body = (WorkspaceFileResource.FileListResponse) response.getEntity();
      assertFalse(body.truncated());
      assertEquals(2, body.files().size());
      assertEquals("first.HPL", body.files().get(0).path());
      assertEquals("nested/second.hpl", body.files().get(1).path());
      assertTrue(body.files().stream().noneMatch(f -> f.path().startsWith("/")));
    } finally {
      restore(previous);
    }
  }

  private static void restore(String previous) {
    if (previous == null) System.clearProperty("hop.modern.workspace");
    else System.setProperty("hop.modern.workspace", previous);
  }
}
