package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PipelineWorkspaceBoundaryTest {
  @TempDir Path temp;

  @Test
  void resolvesRelativePipelineWithinWorkspace() throws Exception {
    Path workspace = Files.createDirectory(temp.resolve("workspace"));
    Path pipeline = Files.writeString(workspace.resolve("example.hpl"), "<pipeline/>");
    String previous = System.getProperty("hop.modern.workspace");
    try {
      System.setProperty("hop.modern.workspace", workspace.toString());
      assertEquals(pipeline.toRealPath(), PipelineOpenResource.resolveDocumentPath("example.hpl"));
      assertEquals(pipeline.toRealPath(), PipelineOpenResource.resolveDocumentPath(pipeline.toString()));
    } finally {
      restore(previous);
    }
  }

  @Test
  void rejectsTraversalAndAbsolutePathsOutsideWorkspace() throws Exception {
    Path workspace = Files.createDirectory(temp.resolve("workspace"));
    Path outside = Files.writeString(temp.resolve("outside.hpl"), "<pipeline/>");
    String previous = System.getProperty("hop.modern.workspace");
    try {
      System.setProperty("hop.modern.workspace", workspace.toString());
      assertThrows(IllegalArgumentException.class,
          () -> PipelineOpenResource.resolveDocumentPath("../outside.hpl"));
      assertThrows(IllegalArgumentException.class,
          () -> PipelineOpenResource.resolveDocumentPath(outside.toString()));
    } finally {
      restore(previous);
    }
  }

  @Test
  void rejectsSymlinkOutsideWorkspace() throws Exception {
    Path workspace = Files.createDirectory(temp.resolve("workspace"));
    Path outside = Files.writeString(temp.resolve("outside.hpl"), "<pipeline/>");
    Files.createSymbolicLink(workspace.resolve("link.hpl"), outside);
    String previous = System.getProperty("hop.modern.workspace");
    try {
      System.setProperty("hop.modern.workspace", workspace.toString());
      assertThrows(IllegalArgumentException.class,
          () -> PipelineOpenResource.resolveDocumentPath("link.hpl"));
    } finally {
      restore(previous);
    }
  }

  private static void restore(String previous) {
    if (previous == null) {
      System.clearProperty("hop.modern.workspace");
    } else {
      System.setProperty("hop.modern.workspace", previous);
    }
  }
}
