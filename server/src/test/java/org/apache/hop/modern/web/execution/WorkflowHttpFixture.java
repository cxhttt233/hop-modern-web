package org.apache.hop.modern.web.execution;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.modern.web.ModernWebServer;
import org.apache.hop.workflow.WorkflowMeta;
import org.apache.hop.workflow.action.ActionMeta;
import org.apache.hop.workflow.actions.start.ActionStart;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;

/** Test-only real Grizzly/Jersey transport; never substituted for production registry. */
final class WorkflowHttpFixture implements AutoCloseable {
  static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
  final Path root;
  final WorkflowExecutionAdapter adapter;
  final HttpServer server;
  final URI base;

  WorkflowHttpFixture(Path root, WorkflowExecutionAdapter adapter) throws Exception {
    this.root = root;
    this.adapter = adapter;
    server = GrizzlyHttpServerFactory.createHttpServer(
        URI.create("http://127.0.0.1:0/"), ModernWebServer.createResourceConfig(root, adapter), false);
    server.start();
    base = URI.create("http://127.0.0.1:" + server.getListeners().iterator().next().getPort() + "/");
  }

  Path writeWorkflow(String name, boolean repeat) throws Exception {
    WorkflowMeta workflow = new WorkflowMeta();
    workflow.setName(name);
    ActionStart start = new ActionStart("START");
    if (repeat) {
      start.setRepeat(true);
      start.setSchedulerType(ActionStart.INTERVAL);
      start.setIntervalSeconds("10");
      start.setIntervalMinutes("0");
    }
    workflow.addAction(new ActionMeta(start));
    Path file = root.resolve(name);
    Files.writeString(file, workflow.getXml(Variables.getADefaultVariableSpace()));
    return file;
  }

  Opened open(String uri, String cookie) throws Exception {
    HttpResponse<String> response = post("api/v2/documents",
        JSON.writeValueAsString(new OpenRequest(uri)), cookie);
    assertEquals(201, response.statusCode(), response::body);
    String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
    return new Opened(JSON.readTree(response.body()), setCookie);
  }

  JsonNode awaitState(String id, String cookie, String wanted) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
    JsonNode last = null;
    while (System.nanoTime() < deadline) {
      HttpResponse<String> response = get("api/v2/executions/" + id, cookie);
      assertEquals(200, response.statusCode(), response::body);
      last = JSON.readTree(response.body());
      if (wanted.equals(last.path("state").asText())) return last;
      Thread.sleep(20);
    }
    fail("workflow did not reach " + wanted + ", last=" + last);
    return last;
  }

  HttpResponse<String> post(String path, String body, String cookie) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path));
    if (cookie != null) request.header("Cookie", cookie);
    if (body != null) request.header("Content-Type", "application/json");
    return HTTP.send(request.POST(body == null ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> get(String path, String cookie) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path));
    if (cookie != null) request.header("Cookie", cookie);
    return HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  @Override public void close() {
    server.shutdownNow();
    adapter.close();
  }

  record OpenRequest(String uri) {}
  record Opened(JsonNode json, String cookie) {}
}
