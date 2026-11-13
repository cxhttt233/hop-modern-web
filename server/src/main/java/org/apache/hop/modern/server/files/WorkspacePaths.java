package org.apache.hop.modern.server.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Resolves browser-supplied document names within a configured workspace.
 * This is a boundary check, not an authorization policy.
 */
public final class WorkspacePaths {
  private final Path root;

  public WorkspacePaths(Path workspaceRoot) throws IOException {
    Objects.requireNonNull(workspaceRoot, "workspaceRoot");
    this.root = workspaceRoot.toRealPath();
    if (!Files.isDirectory(root)) {
      throw new IllegalArgumentException("Workspace root must be a directory");
    }
  }

  /** Resolve an existing regular file without allowing symlink escapes. */
  public Path existingFile(String relativeName) throws IOException {
    Path candidate = lexicalCandidate(relativeName);
    Path real = candidate.toRealPath();
    ensureWithinRoot(real);
    if (!Files.isRegularFile(real)) {
      throw new IllegalArgumentException("Not a regular file");
    }
    return real;
  }

  /**
   * Resolve a destination file. Its parent must already exist, and is resolved
   * through symlinks before the final file name is appended.
   */
  public Path destinationFile(String relativeName) throws IOException {
    Path candidate = lexicalCandidate(relativeName);
    Path parent = candidate.getParent().toRealPath();
    if (!parent.startsWith(root)) {
      throw new IllegalArgumentException("Destination parent escapes workspace");
    }
    if (!Files.isDirectory(parent)) {
      throw new IllegalArgumentException("Destination parent is not a directory");
    }
    Path destination = parent.resolve(candidate.getFileName());
    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
      // Resolve existing symlinks, including dangling ones (which fail closed).
      destination = destination.toRealPath();
      ensureWithinRoot(destination);
      if (!Files.isRegularFile(destination)) {
        throw new IllegalArgumentException("Destination is not a regular file");
      }
    }
    return destination;
  }

  private Path lexicalCandidate(String name) {
    if (name == null || name.isBlank() || name.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("Document name is required");
    }
    Path input = Path.of(name);
    if (input.isAbsolute() || input.normalize().startsWith("..")) {
      throw new IllegalArgumentException("Document path escapes workspace");
    }
    Path candidate = root.resolve(input).normalize();
    ensureWithinRoot(candidate);
    if (candidate.equals(root)) {
      throw new IllegalArgumentException("Document path must identify a file");
    }
    return candidate;
  }

  private void ensureWithinRoot(Path path) {
    if (!path.startsWith(root) || path.equals(root)) {
      throw new IllegalArgumentException("Document path escapes workspace");
    }
  }
}
