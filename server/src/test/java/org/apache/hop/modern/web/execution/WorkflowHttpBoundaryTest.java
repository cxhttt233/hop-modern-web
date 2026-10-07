package org.apache.hop.modern.web.execution;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.modern.web.ModernWebServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowHttpBoundaryTest {
  @TempDir Path root;

  @BeforeAll static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test void documentAndExecutionOwnershipEnforcedOverHttp() throws Exception {
    try (WorkflowHttpFixture f = new WorkflowHttpFixture(root, new WorkflowExecutionAdapter())) {
      f.writeWorkflow("a.hwf", true);
      f.writeWorkflow("b.hwf", false);
      var a = f.open("a.hwf", null);
      var b = f.open("b.hwf", null);
      assertNotEquals(a.cookie(), b.cookie());
      String docA = a.json().path("docId").asText();
      String docB = b.json().path("docId").asText();
      assertEquals(404, f.post("api/v2/documents/" + docA + "/executions", null, b.cookie()).statusCode());
      assertEquals(404, f.post("api/v2/documents/" + docB + "/executions", null, a.cookie()).statusCode());
      var started = f.post("api/v2/documents/" + docA + "/executions", null, a.cookie());
      assertEquals(202, started.statusCode(), started::body);
      String id = WorkflowHttpFixture.JSON.readTree(started.body()).path("id").asText();
      assertEquals(404, f.get("api/v2/executions/" + id, b.cookie()).statusCode());
      assertEquals(404, f.get("api/v2/executions/" + id, null).statusCode());
      assertEquals(404, f.post("api/v2/executions/" + id + "/stop", null, b.cookie()).statusCode());
      f.awaitState(id, a.cookie(), "running");
      assertEquals(200, f.post("api/v2/executions/" + id + "/stop", null, a.cookie()).statusCode());
      f.awaitState(id, a.cookie(), "stopped");
    }
  }

  @Test void missingDocumentsAndExecutionsReturnJson404() throws Exception {
    try (WorkflowHttpFixture f = new WorkflowHttpFixture(root, new WorkflowExecutionAdapter())) {
      var missing = f.post("api/v2/documents", "{\"uri\":\"missing.hwf\"}", null);
      assertEquals(404, missing.statusCode(), missing::body);
      assertEquals("document_not_found", WorkflowHttpFixture.JSON.readTree(missing.body()).path("code").asText());
      assertEquals(404, f.post("api/v2/documents/unknown/executions", null, null).statusCode());
      assertEquals(404, f.get("api/v2/executions/unknown", null).statusCode());
      assertEquals(404, f.post("api/v2/executions/unknown/stop", null, null).statusCode());
    }
  }

  @Test void failedSnapshotDoesNotRegisterAnExecution() throws Exception {
    try (WorkflowHttpFixture f = new WorkflowHttpFixture(root, new WorkflowExecutionAdapter())) {
      Path path = f.writeWorkflow("valid.hwf", false);
      var opened = f.open("valid.hwf", null);
      Files.writeString(path, "<workflow>broken");
      var response = f.post("api/v2/documents/" + opened.json().path("docId").asText() +
          "/executions", null, opened.cookie());
      assertEquals(422, response.statusCode(), response::body);
      assertEquals("execution_snapshot_failed", WorkflowHttpFixture.JSON.readTree(response.body()).path("code").asText());
      assertEquals(0, f.adapter.size());
    }
  }

  @Test void rejectedAsyncSubmissionIsRolledBack() throws Exception {
    WorkflowExecutionAdapter rejected =
        new WorkflowExecutionAdapter(new RejectingExecutor(), () -> "rejected-id");
    try (WorkflowHttpFixture f = new WorkflowHttpFixture(root, rejected)) {
      f.writeWorkflow("valid.hwf", false);
      var opened = f.open("valid.hwf", null);
      var response = f.post("api/v2/documents/" + opened.json().path("docId").asText() +
          "/executions", null, opened.cookie());
      assertEquals(422, response.statusCode(), response::body);
      assertEquals("execution_start_failed", WorkflowHttpFixture.JSON.readTree(response.body()).path("code").asText());
      assertEquals(0, f.adapter.size());
      assertEquals(404, f.get("api/v2/executions/rejected-id", opened.cookie()).statusCode());
    }
  }

  private static final class RejectingExecutor extends AbstractExecutorService {
    private boolean shutdown;
    @Override public void shutdown() { shutdown = true; }
    @Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
    @Override public boolean isShutdown() { return shutdown; }
    @Override public boolean isTerminated() { return shutdown; }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
    @Override public void execute(Runnable task) { throw new RejectedExecutionException("rejected"); }
  }
}
