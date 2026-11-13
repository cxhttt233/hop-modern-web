package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Security boundary tests for the read-only workflow preview endpoint. */
class WorkflowPreviewResourceTest {
  @TempDir Path root;

  private WorkflowPreviewResource resource() throws Exception {
    return new WorkflowPreviewResource(root, new MemoryMetadataProvider(),
        Variables.getADefaultVariableSpace());
  }

  @Test void rejectsMissingAndAbsolutePaths() throws Exception {
    var preview = resource();
    assertEquals(400, preview.preview(null).getStatus());
    assertEquals(400, preview.preview(new WorkflowPreviewResource.OpenRequest("")).getStatus());
    assertEquals(400, preview.preview(new WorkflowPreviewResource.OpenRequest(
        root.resolve("outside.hwf").toString())).getStatus());
    assertEquals(400, preview.preview(new WorkflowPreviewResource.OpenRequest("file:///etc/passwd")).getStatus());
  }

  @Test void rejectsTraversalAndDoesNotParseOtherFileTypes() throws Exception {
    var preview = resource();
    assertEquals(403, preview.preview(new WorkflowPreviewResource.OpenRequest("../outside.hwf")).getStatus());
    assertEquals(400, preview.preview(new WorkflowPreviewResource.OpenRequest("example.hpl")).getStatus());
    assertEquals(404, preview.preview(new WorkflowPreviewResource.OpenRequest("missing.hwf")).getStatus());
  }

  @Test void refusesSymlinkEscapeAndDoesNotMutateSource() throws Exception {
    Path external = Files.createTempFile("workflow-preview-external", ".hwf");
    try {
      Files.writeString(external, "<workflow><name>external</name></workflow>");
      Path link = root.resolve("link.hwf");
      try {
        Files.createSymbolicLink(link, external);
      } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
        return; // Some CI filesystems cannot create symlinks.
      }
      var response = resource().preview(new WorkflowPreviewResource.OpenRequest("link.hwf"));
      assertFalse(response.getStatus() >= 200 && response.getStatus() < 300);
      assertTrue(Files.readString(external).contains("external"));
    } finally {
      Files.deleteIfExists(external);
    }
  }
}
