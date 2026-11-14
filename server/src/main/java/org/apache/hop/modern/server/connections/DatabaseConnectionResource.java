package org.apache.hop.modern.server.connections;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Workspace-local, password-free connection drafts. Never attempts network access. */
@Path("/api/connections")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public final class DatabaseConnectionResource {
  private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9_-]{1,64}");
  private final String configuredWorkspace;
  private boolean authorized(String suppliedToken) { String token = System.getProperty("hop.modern.connections.token"); return token != null && !token.isBlank() && java.security.MessageDigest.isEqual(token.getBytes(java.nio.charset.StandardCharsets.UTF_8), (suppliedToken == null ? "" : suppliedToken).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
  public DatabaseConnectionResource() { this(System.getProperty("hop.modern.workspace")); }
  public DatabaseConnectionResource(String workspace) { this.configuredWorkspace = workspace; }

  public record Connection(String name, String type, String hostname, String databaseName, String port, String username) {}
  public record Error(String code, String message) {}
  private Response error(int status, String code) { return Response.status(status).entity(new Error(code, code)).build(); }
  private java.nio.file.Path directory() throws IOException {
    if (configuredWorkspace == null || configuredWorkspace.isBlank()) throw new IOException("workspace not configured");
    java.nio.file.Path root = java.nio.file.Path.of(configuredWorkspace).toRealPath();
    if (!Files.isDirectory(root)) throw new IOException("workspace not directory");
    java.nio.file.Path dir = root.resolve(".hop-modern-connections");
    if (Files.isSymbolicLink(dir)) throw new IOException("symlink storage not allowed");
    Files.createDirectories(dir);
    if (!dir.toRealPath().startsWith(root)) throw new IOException("storage escapes workspace");
    return dir;
  }
  private static Connection decode(java.nio.file.Path path) throws IOException {
    Properties p = new Properties();
    try (var in = Files.newInputStream(path)) { p.load(in); }
    return new Connection(p.getProperty("name", ""), p.getProperty("type", ""), p.getProperty("hostname", ""), p.getProperty("databaseName", ""), p.getProperty("port", ""), p.getProperty("username", ""));
  }
  @GET public Response list(@HeaderParam("X-Hop-Connection-Token") String suppliedToken) {
    if (!authorized(suppliedToken)) return error(403, "forbidden");
    try {
      List<Connection> result = new ArrayList<>();
      try (var stream = Files.list(directory())) {
        for (java.nio.file.Path p : stream.filter(x -> !Files.isSymbolicLink(x) && x.getFileName().toString().endsWith(".properties")).sorted().limit(200).toList()) result.add(decode(p));
      }
      return Response.ok(result).build();
    } catch (Exception e) { return error(503, "workspace_unavailable"); }
  }
  @GET @Path("/{name}") public Response get(@PathParam("name") String name, @HeaderParam("X-Hop-Connection-Token") String suppliedToken) {
    if (!authorized(suppliedToken)) return error(403, "forbidden");
    if (!NAME.matcher(name).matches()) return error(400, "invalid_name");
    try {
      java.nio.file.Path p = directory().resolve(name + ".properties");
      if (Files.isSymbolicLink(p)) return error(400, "invalid_path");
      if (!Files.isRegularFile(p)) return error(404, "not_found");
      return Response.ok(decode(p)).build();
    } catch (Exception e) { return error(503, "workspace_unavailable"); }
  }
  @PUT @Path("/{name}") public Response save(@PathParam("name") String name, @HeaderParam("X-Hop-Connection-Token") String suppliedToken, Connection input) {
    if (!authorized(suppliedToken)) return error(403, "forbidden");
    if (!NAME.matcher(name).matches() || input == null || !name.equals(input.name())) return error(400, "invalid_name");
    if (java.util.Arrays.asList(input.type(), input.hostname(), input.databaseName(), input.port(), input.username()).stream().anyMatch(Objects::isNull)) return error(400, "invalid_fields");
    if (input.type().length() > 64 || input.hostname().length() > 255 || input.databaseName().length() > 255 || input.port().length() > 8 || input.username().length() > 128) return error(400, "invalid_fields");
    try {
      java.nio.file.Path dir = directory(), target = dir.resolve(name + ".properties");
      if (Files.isSymbolicLink(target)) return error(400, "invalid_path");
      Properties p = new Properties();
      p.setProperty("name", name); p.setProperty("type", input.type()); p.setProperty("hostname", input.hostname()); p.setProperty("databaseName", input.databaseName()); p.setProperty("port", input.port()); p.setProperty("username", input.username());
      java.nio.file.Path temp = Files.createTempFile(dir, ".connection-", ".tmp");
      try {
        try (var out = Files.newOutputStream(temp)) { p.store(out, "Hop connection draft - no credentials"); }
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } finally { Files.deleteIfExists(temp); }
      return Response.ok(input).build();
    } catch (Exception e) { return error(503, "workspace_unavailable"); }
  }
}