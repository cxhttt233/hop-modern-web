/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowFilesResourceTest {
  @TempDir Path root;

  @Test
  void discoversOnlyWorkflowsAndReturnsRelativeSortedPaths() throws Exception {
    Files.createDirectories(root.resolve("nested"));
    Files.writeString(root.resolve("z.hwf"), "<workflow/>");
    Files.writeString(root.resolve("nested/a.HWF"), "<workflow/>");
    Files.writeString(root.resolve("nested/pipeline.hpl"), "<pipeline/>");
    try (Response response = new WorkflowFilesResource(root.toString()).list()) {
      assertEquals(200, response.getStatus());
      WorkflowFilesResource.FileListResponse result =
          (WorkflowFilesResource.FileListResponse) response.getEntity();
      assertEquals(java.util.List.of("nested/a.HWF", "z.hwf"), result.files());
      assertFalse(result.possiblyTruncated());
    }
  }

  @Test
  void missingWorkspaceFailsClosed() {
    try (Response response = new WorkflowFilesResource(null).list()) {
      assertEquals(503, response.getStatus());
      assertEquals("workspace_unavailable",
          ((WorkflowFilesResource.ErrorResponse) response.getEntity()).code());
    }
  }

  @Test
  void skipsSymlinksIncludingSymlinkedDirectories() throws Exception {
    Path outside = Files.createTempDirectory("outside-workflow");
    try {
      Files.writeString(outside.resolve("private.hwf"), "secret");
      try {
        Files.createSymbolicLink(root.resolve("link.hwf"), outside.resolve("private.hwf"));
        Files.createSymbolicLink(root.resolve("linked-folder"), outside);
      } catch (UnsupportedOperationException | java.nio.file.FileSystemException
          | SecurityException e) {
        org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlinks unavailable");
      }
      try (Response response = new WorkflowFilesResource(root.toString()).list()) {
        assertEquals(200, response.getStatus());
        assertTrue(((WorkflowFilesResource.FileListResponse) response.getEntity()).files().isEmpty());
      }
    } finally {
      Files.deleteIfExists(outside.resolve("private.hwf"));
      Files.deleteIfExists(outside);
    }
  }
}
