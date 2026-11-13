package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceFileCatalogResourceTest {
  @TempDir Path root;

  @Test
  void listsOnlyRequestedNativeFilesWithRelativeNames() throws Exception {
    Files.createDirectories(root.resolve("nested"));
    Files.writeString(root.resolve("nested/a.HPL"), "pipeline");
    Files.writeString(root.resolve("nested/b.hwf"), "workflow");
    Files.writeString(root.resolve("ignore.txt"), "other");
    WorkspaceFileCatalogResource resource = new WorkspaceFileCatalogResource(root.toString());
    Response pipelines = resource.pipelines();
    Response workflows = resource.workflows();
    assertEquals(200, pipelines.getStatus());
    assertEquals(List.of("nested/a.HPL"),
        ((WorkspaceFileCatalogResource.CatalogResult) pipelines.getEntity()).files());
    assertEquals(List.of("nested/b.hwf"),
        ((WorkspaceFileCatalogResource.CatalogResult) workflows.getEntity()).files());
  }

  @Test
  void rejectsMissingWorkspace() {
    WorkspaceFileCatalogResource resource = new WorkspaceFileCatalogResource(null);
    assertEquals(503, resource.pipelines().getStatus());
  }

  @Test
  void excludesSymlinkToExternalFile() throws Exception {
    Path outside = Files.createTempFile("catalog-outside-", ".hpl");
    try {
      Files.createSymbolicLink(root.resolve("external.hpl"), outside);
      WorkspaceFileCatalogResource resource = new WorkspaceFileCatalogResource(root.toString());
      var result = (WorkspaceFileCatalogResource.CatalogResult) resource.pipelines().getEntity();
      assertFalse(result.files().contains("external.hpl"));
    } finally {
      Files.deleteIfExists(outside);
    }
  }

  @Test
  void capsResults() throws Exception {
    for (int i = 0; i < 510; i++) {
      Files.writeString(root.resolve(String.format("pipeline-%03d.hpl", i)), "x");
    }
    var resource = new WorkspaceFileCatalogResource(root.toString());
    var result = (WorkspaceFileCatalogResource.CatalogResult) resource.pipelines().getEntity();
    assertEquals(500, result.files().size());
    assertTrue(result.files().get(0).startsWith("pipeline-"));
  }
}