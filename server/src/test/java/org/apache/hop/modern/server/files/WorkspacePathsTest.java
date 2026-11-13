package org.apache.hop.modern.server.files;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspacePathsTest {
  @TempDir Path temporary;

  @Test void allowsNestedFilesAndNewDestinations() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("workspace"));
    Path nested = Files.createDirectory(root.resolve("nested"));
    Path existing = Files.writeString(nested.resolve("flow.hpl"), "test");
    WorkspacePaths paths = new WorkspacePaths(root);
    assertEquals(existing.toRealPath(), paths.existingFile("nested/flow.hpl"));
    assertEquals(nested.resolve("new.hwf"), paths.destinationFile("nested/new.hwf"));
    assertEquals(root.resolve("new.hpl"), paths.destinationFile("new.hpl"));
    assertEquals(existing.toRealPath(), paths.existingFile("nested/flow.hpl"));
  }

  @Test void rejectsTraversalAndAbsolutePaths() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("workspace"));
    WorkspacePaths paths = new WorkspacePaths(root);
    assertThrows(IllegalArgumentException.class, () -> paths.destinationFile("../escape.hpl"));
    assertThrows(IllegalArgumentException.class, () -> paths.destinationFile(temporary.resolve("x.hpl").toString()));
    assertThrows(IllegalArgumentException.class, () -> paths.destinationFile("."));
    assertThrows(IllegalArgumentException.class, () -> paths.destinationFile(""));
    assertThrows(IllegalArgumentException.class, () -> paths.destinationFile(null));
    assertThrows(IllegalArgumentException.class, () -> paths.destinationFile("bad\\u0000name.hpl"));
  }

  @Test void rejectsSymlinkEscapeForReadAndWrite() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("workspace"));
    Path outside = Files.createDirectory(temporary.resolve("outside"));
    Path secret = Files.writeString(outside.resolve("secret.hpl"), "secret");
    Files.createSymbolicLink(root.resolve("link"), outside);
    Files.createSymbolicLink(root.resolve("file.hpl"), secret);
    WorkspacePaths paths = new WorkspacePaths(root);
    assertThrows(IllegalArgumentException.class, () -> paths.existingFile("file.hpl"));
    assertThrows(IllegalArgumentException.class, () -> paths.destinationFile("link/new.hpl"));
    assertThrows(IllegalArgumentException.class, () -> paths.destinationFile("file.hpl"));
  }

  @Test void rejectsMissingParentAndDirectoryTarget() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("workspace"));
    Files.createDirectory(root.resolve("folder"));
    WorkspacePaths paths = new WorkspacePaths(root);
    assertThrows(java.nio.file.NoSuchFileException.class,
        () -> paths.destinationFile("missing/new.hpl"));
    assertThrows(IllegalArgumentException.class, () -> paths.existingFile("folder"));
    assertThrows(IllegalArgumentException.class, () -> paths.destinationFile("folder"));
  }
}
