package org.apache.hop.modern.server.files;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Read-only workspace file discovery for the browser's Open dialog. */
@Path("/api/files/pipelines")
@Produces(MediaType.APPLICATION_JSON)
public final class PipelineFilesResource {
  private static final int MAX_DEPTH = 8;
  private static final int MAX_FILES = 500;
  private final java.nio.file.Path workspace;

  public PipelineFilesResource() {
    this(System.getProperty("hop.modern.workspace"));
  }

  public PipelineFilesResource(String workspaceName) {
    this.workspace = workspaceName == null || workspaceName.isBlank()
        ? null : java.nio.file.Path.of(workspaceName);
  }

  @GET
  public Response list() {
    if (workspace == null) {
      return Response.status(503).entity(new Error("workspace_not_configured")).build();
    }
    try {
      java.nio.file.Path root = workspace.toRealPath();
      if (!Files.isDirectory(root)) {
        return Response.status(503).entity(new Error("workspace_unavailable")).build();
      }
      List<FileEntry> entries = new ArrayList<>();
      try (Stream<java.nio.file.Path> stream = Files.walk(root, MAX_DEPTH)) {
        stream.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
            .filter(p -> !Files.isSymbolicLink(p))
            .filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".hpl"))
            .forEach(p -> {
              java.nio.file.Path relative = root.relativize(p);
              // Never expose absolute host paths; a race may invalidate an entry after listing.
              entries.add(new FileEntry(relative.toString().replace('\\', '/'), p.getFileName().toString()));
            });
      }
      entries.sort(Comparator.comparing(FileEntry::path));
      if (entries.size() > MAX_FILES) entries = new ArrayList<>(entries.subList(0, MAX_FILES));
      return Response.ok(new FileList(entries, entries.size() == MAX_FILES)).build();
    } catch (IOException | SecurityException e) {
      return Response.status(503).entity(new Error("workspace_unavailable")).build();
    }
  }

  public record FileEntry(String path, String name) {}
  public record FileList(List<FileEntry> files, boolean limitReached) {}
  public record Error(String code) {}
}