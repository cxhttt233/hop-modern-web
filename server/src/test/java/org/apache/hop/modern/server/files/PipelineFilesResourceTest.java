package org.apache.hop.modern.server.files;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PipelineFilesResourceTest {
  @TempDir Path root;

  @Test void listsNestedPipelinesAndExcludesOtherFiles() throws Exception {
    Files.writeString(root.resolve("z.hpl"), "z");
    Path nested = Files.createDirectory(root.resolve("nested"));
    Files.writeString(nested.resolve("a.HPL"), "a");
    Files.writeString(nested.resolve("notes.txt"), "ignore");
    try (var response = new PipelineFilesResource(root.toString()).list()) {
      assertEquals(200, response.getStatus());
      var result = (PipelineFilesResource.FileList) response.getEntity();
      assertEquals(2, result.files().size());
      assertEquals("nested/a.HPL", result.files().get(0).path());
      assertEquals("z.hpl", result.files().get(1).path());
    }
  }

  @Test void missingWorkspaceReturns503() {
    try (var response = new PipelineFilesResource(null).list()) {
      assertEquals(503, response.getStatus());
    }
  }

  @Test void skipsSymbolicLinks() throws Exception {
    Path outside = Files.writeString(root.getParent().resolve("outside.hpl"), "outside");
    Files.createSymbolicLink(root.resolve("linked.hpl"), outside);
    try (var response = new PipelineFilesResource(root.toString()).list()) {
      var result = (PipelineFilesResource.FileList) response.getEntity();
      assertTrue(result.files().isEmpty());
    }
  }
}