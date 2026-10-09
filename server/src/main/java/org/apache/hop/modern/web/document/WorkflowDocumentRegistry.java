package org.apache.hop.modern.web.document;

import java.nio.file.Path;
import org.apache.hop.core.xml.XmlHandler;
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

  /**
   * Validate the workflow before allocating an owner session. Failed opens must not
   * create process-global sessions whose cookie was never returned to the client.
   */
  public OwnedOpened openOwned(String requestedSession, String uri) throws Exception {
    LoadedWorkflow loaded = validateAndLoad(uri);
    String owner = session(requestedSession);
    return new OwnedOpened(register(owner, loaded), owner);
  }

  /** Open under an already registered owner. */
  public Opened open(String owner, String uri) throws Exception {
    if (!sessions.containsKey(owner)) throw new IllegalArgumentException("unknown session");
    return register(owner, validateAndLoad(uri));
  }

  private LoadedWorkflow validateAndLoad(String uri) throws Exception {
    if (uri == null || !uri.endsWith(".hwf")) throw new IllegalArgumentException("expected .hwf uri");
    Path requested = resolveRequestedPath(uri);
    Path path = (requested.isAbsolute() ? requested : root.resolve(requested)).toRealPath();
    if (!path.startsWith(root) || !java.nio.file.Files.isRegularFile(path)) {
      throw new IllegalArgumentException("workflow is outside configured root");
    }
    WorkflowMeta meta = load(path);
    // Freeze the document as it was opened. Never reload a mutable disk path for execution.
    // Serialize before allocating a session so a failed snapshot cannot leak an owner.
    return new LoadedWorkflow(path, meta, meta.getXml(variables));
  }

  /**
   * Accept native filesystem paths (including POSIX names containing ':') and local file URIs.
   * Remote URI schemes and file authorities are never treated as filesystem paths.
   */
  private static Path resolveRequestedPath(String value) {
    if (value.regionMatches(true, 0, "file:", 0, 5)) {
      java.net.URI parsed = java.net.URI.create(value);
      if (!"file".equalsIgnoreCase(parsed.getScheme())
          || parsed.getRawAuthority() != null
          || parsed.getRawQuery() != null
          || parsed.getRawFragment() != null) {
        throw new IllegalArgumentException("only local file URIs are supported");
      }
      return Path.of(parsed);
    }
    int colon = value.indexOf(':');
    if (colon > 0 && value.substring(0, colon).matches("[A-Za-z][A-Za-z0-9+.-]*")) {
      String scheme = value.substring(0, colon).toLowerCase(java.util.Locale.ROOT);
      String suffix = value.substring(colon + 1);
      boolean windowsDrive = colon == 1 && Character.isLetter(value.charAt(0));
      if (!windowsDrive
          && (suffix.startsWith("//")
              || java.util.Set.of("http", "https", "ftp", "sftp", "s3", "gs", "hdfs",
                  "webdav", "jar", "zip").contains(scheme))) {
        throw new IllegalArgumentException("remote workflow URIs are not supported");
      }
    }
    return Path.of(value);
  }

  private Opened register(String owner, LoadedWorkflow loaded) {
    String id = UUID.randomUUID().toString();
    documents.put(id, new Entry(owner, loaded.path(), loaded.xml()));
    return new Opened(id, "workflow", loaded.meta().getName(),
        loaded.path().toUri().toString(), 0, false);
  }

  // Package-private observation only; no HTTP administration endpoint.
  int sessionCount() { return sessions.size(); }

  @Override
  public Optional<WorkflowMeta> executionSnapshot(String documentId) {
    return Optional.empty();
  }

  @Override
  public Optional<WorkflowMeta> executionSnapshot(String documentId, String owner) {
    Entry entry = documents.get(documentId);
    if (entry == null || !entry.owner.equals(owner)) return Optional.empty();
    try {
      // Reconstruct a detached graph per execution, preserving the opened document revision.
      // This cannot silently switch to newer .hwf bytes or fail if the file is removed.
      WorkflowMeta snapshot = new WorkflowMeta(
          XmlHandler.loadXmlString(entry.xml, WorkflowMeta.XML_TAG), metadata, variables);
      snapshot.setFilename(entry.path.toString());
      return Optional.of(snapshot);
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
  public record OwnedOpened(Opened document, String owner) {}
  private record LoadedWorkflow(Path path, WorkflowMeta meta, String xml) {}
  private record Entry(String owner, Path path, String xml) {}
}
