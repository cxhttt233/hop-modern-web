package org.apache.hop.modern.web.document;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.modern.web.execution.WorkflowExecutionResource.WorkflowDocumentSource;
import org.apache.hop.workflow.WorkflowMeta;

/** Product-owned workflow document ownership and detached execution snapshots. */
public final class WorkflowDocumentRegistry implements WorkflowDocumentSource {
  private final Path root;
  private final IVariables variables;
  private final IHopMetadataProvider metadata;
  private final Map<String, Entry> documents = new ConcurrentHashMap<>();
  private final Map<String, Boolean> sessions = new ConcurrentHashMap<>();

  public WorkflowDocumentRegistry(Path root, IVariables variables, IHopMetadataProvider metadata)
      throws java.io.IOException {
    this.root = root.toRealPath();
    this.variables = variables;
    this.metadata = metadata;
  }

  public String session(String requested) {
    if (requested != null && sessions.containsKey(requested)) return requested;
    String id = UUID.randomUUID().toString();
    sessions.put(id, true);
    return id;
  }

  public Opened open(String owner, String uri) throws Exception {
    if (!sessions.containsKey(owner)) throw new IllegalArgumentException("unknown session");
    if (uri == null || !uri.endsWith(".hwf")) throw new IllegalArgumentException("expected .hwf uri");
    Path requested = Path.of(uri);
    Path path = (requested.isAbsolute() ? requested : root.resolve(requested)).toRealPath();
    if (!path.startsWith(root) || !java.nio.file.Files.isRegularFile(path)) {
      throw new IllegalArgumentException("workflow is outside configured root");
    }
    WorkflowMeta meta = load(path);
    String id = UUID.randomUUID().toString();
    documents.put(id, new Entry(owner, path));
    return new Opened(id, "workflow", meta.getName(), path.toUri().toString(), 0, false);
  }

  @Override
  public Optional<WorkflowMeta> executionSnapshot(String documentId) {
    return Optional.empty();
  }

  @Override
  public Optional<WorkflowMeta> executionSnapshot(String documentId, String owner) {
    Entry entry = documents.get(documentId);
    if (entry == null || !entry.owner.equals(owner)) return Optional.empty();
    try {
      return Optional.of(load(entry.path));
    } catch (Exception e) {
      throw new IllegalStateException("workflow snapshot failed", e);
    }
  }

  @Override
  public boolean owns(String documentId, String owner) {
    Entry entry = documents.get(documentId);
    return entry != null && entry.owner.equals(owner);
  }

  private WorkflowMeta load(Path path) throws Exception {
    if (!path.toRealPath().startsWith(root)) throw new IllegalArgumentException("path escaped root");
    return new WorkflowMeta(variables, path.toString(), metadata);
  }

  public record Opened(String docId, String kind, String name, String uri, int revision,
                       boolean changed) {}
  private record Entry(String owner, Path path) {}
}
