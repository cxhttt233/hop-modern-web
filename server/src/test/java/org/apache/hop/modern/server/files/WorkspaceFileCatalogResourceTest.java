package org.apache.hop.modern.server.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceFileCatalogResourceTest {
  @TempDir Path root;

  @Test
  void listsOnlyRequestedFileTypesWithRelativePaths() throws Exception {
    Files.createDirectories(root.resolve("nested"));
    Files.writeString(root.resolve("nested/Flow.HPL"), "pipeline");
    Files.writeString(root.resolve("nested/job.hwf"), "workflow");
    Files.writeString(root.resolve("notes.txt"), "text");
    WorkspaceFileCatalogResource resource = new WorkspaceFileCatalogResource(root.toString());
    Response pipelines = resource.pipelines(null);
    assertEquals(200, pipelines.getStatus());
    assertEquals(List.of("nested/Flow.HPL"), ((WorkspaceFileCatalogResource.FileListResponse) pipelines.getEntity()).paths());
    Response workflows = resource.workflows(null);
    assertEquals(List.of("nested/job.hwf"), ((WorkspaceFileCatalogResource.FileListResponse) workflows.getEntity()).paths());
  }

  @Test
  void missingWorkspaceIsExplicitlyUnavailable() {
    Response response = new WorkspaceFileCatalogResource((String) null).pipelines(null);
    assertEquals(503, response.getStatus());
  }

  @Test
  void malformedWorkspaceDoesNotPreventResourceConstruction() {
    WorkspaceFileCatalogResource resource = new WorkspaceFileCatalogResource("invalid\u0000workspace");
    Response response = resource.pipelines(null);
    assertEquals(503, response.getStatus());
    assertEquals("workspace_unavailable", ((WorkspaceFileCatalogResource.ErrorResponse) response.getEntity()).code());
  }

  @Test
  void doesNotFollowSymlinkFiles() throws Exception {
    Path target = Files.writeString(root.resolve("actual.hpl"), "pipeline");
    Files.createSymbolicLink(root.resolve("alias.hpl"), target);
    Response response = new WorkspaceFileCatalogResource(root.toString()).pipelines(null);
    assertEquals(List.of("actual.hpl"), ((WorkspaceFileCatalogResource.FileListResponse) response.getEntity()).paths());
  }
  @Test
  void filtersByCaseInsensitiveRelativePathAndRejectsLongSearch() throws Exception {
    Files.createDirectories(root.resolve("nested"));
    Files.writeString(root.resolve("nested/Flow.HPL"), "pipeline");
    Files.writeString(root.resolve("other.hpl"), "pipeline");
    WorkspaceFileCatalogResource resource = new WorkspaceFileCatalogResource(root.toString());
    Response response = resource.pipelines("NESTED/flow");
    assertEquals(200, response.getStatus());
    assertEquals(List.of("nested/Flow.HPL"), ((WorkspaceFileCatalogResource.FileListResponse) response.getEntity()).paths());
    assertEquals(400, resource.pipelines("x".repeat(129)).getStatus());
  }

}