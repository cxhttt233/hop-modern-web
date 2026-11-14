package org.apache.hop.modern.web;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.hop.core.database.DatabaseMeta;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.json.JsonMetadataProvider;

/** Password-free, workspace-scoped native Hop DatabaseMeta CRUD; no outbound connections. */
@Path("/api/native-connections")
@Produces(MediaType.APPLICATION_JSON)
public final class NativeDatabaseResource {
  public record Connection(String name, String type, String hostname, String databaseName,
                           String port, String username) {}
  private final String workspace;
  private final String token;

  public NativeDatabaseResource() {
    this(System.getProperty("hop.modern.workspace"), System.getProperty("hop.modern.connections.token"));
  }
  NativeDatabaseResource(String workspace, String token) {
    this.workspace = workspace;
    this.token = token;
  }
  private Response denied(String supplied) {
    if (token == null || token.isBlank()) return Response.status(503).entity(Map.of("error", "auth_not_configured")).build();
    if (supplied == null || !java.security.MessageDigest.isEqual(
        token.getBytes(java.nio.charset.StandardCharsets.UTF_8), supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
      return Response.status(403).entity(Map.of("error", "forbidden")).build();
    return null;
  }
  private JsonMetadataProvider provider() throws Exception {
    if (workspace == null || workspace.isBlank()) throw new IllegalStateException("workspace unavailable");
    var root = java.nio.file.Path.of(workspace).toRealPath();
    if (!Files.isDirectory(root) || Files.isSymbolicLink(root)) throw new IllegalStateException("workspace unavailable");
    var metadata = root.resolve("metadata");
    if (Files.exists(metadata, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(metadata)) throw new IllegalStateException("metadata symlink");
    Files.createDirectories(metadata);
    var rdbms = metadata.resolve("rdbms");
    if (Files.exists(rdbms, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(rdbms)) throw new IllegalStateException("rdbms symlink");
    var result = new JsonMetadataProvider();
    result.setBaseFolder(root.toString());
    return result;
  }
  private static boolean valid(String name) {
    return name != null && name.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,79}") && !name.equals(".") && !name.equals("..");
  }
  private static Connection safe(DatabaseMeta db) {
    return new Connection(db.getName(), db.getPluginId(), db.getHostname(), db.getDatabaseName(), db.getPort(), db.getUsername());
  }
  @GET
  public Response list(@HeaderParam("X-HMW-Connection-Token") String supplied) {
    Response error = denied(supplied); if (error != null) return error;
    try {
      var serializer = provider().getSerializer(DatabaseMeta.class);
      List<Connection> values = serializer.loadAll().stream().map(NativeDatabaseResource::safe).toList();
      return Response.ok(Map.of("connections", values)).build();
    } catch (Exception e) { return Response.status(503).entity(Map.of("error", "metadata_unavailable")).build(); }
  }
  @GET @Path("/{name}")
  public Response read(@PathParam("name") String name, @HeaderParam("X-HMW-Connection-Token") String supplied) {
    Response error = denied(supplied); if (error != null) return error;
    if (!valid(name)) return Response.status(400).build();
    try {
      var db = provider().getSerializer(DatabaseMeta.class).load(name);
      return db == null ? Response.status(404).build() : Response.ok(safe(db)).build();
    } catch (Exception e) { return Response.status(503).entity(Map.of("error", "metadata_unavailable")).build(); }
  }
  @PUT @Path("/{name}") @Consumes(MediaType.APPLICATION_JSON)
  public Response save(@PathParam("name") String name, @HeaderParam("X-HMW-Connection-Token") String supplied, Connection input) {
    Response error = denied(supplied); if (error != null) return error;
    if (!valid(name) || input == null || !Objects.equals(name, input.name()) ||
        input.type() == null || !input.type().matches("[A-Z][A-Z0-9_]{0,39}") ||
        input.hostname() == null || input.hostname().length() > 255 ||
        input.databaseName() == null || input.databaseName().length() > 255 ||
        input.port() == null || !input.port().matches("[0-9]{1,5}") ||
        input.username() == null || input.username().length() > 128) return Response.status(400).build();
    try {
      if (!DatabaseMeta.getIDatabaseMap().containsKey(input.type()))
        return Response.status(422).entity(Map.of("error", "unsupported_database_type")).build();
      var db = new DatabaseMeta();
      db.setName(name); db.setDatabaseType(input.type()); db.setHostname(input.hostname());
      db.setDBName(input.databaseName()); db.setPort(input.port()); db.setUsername(input.username());
      db.setPassword("");
      provider().getSerializer(DatabaseMeta.class).save(db);
      return Response.ok(safe(db)).build();
    } catch (Exception e) { return Response.status(503).entity(Map.of("error", "metadata_unavailable")).build(); }
  }
}