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

  @Test void openedSnapshotExecutesEvenIfDiskWorkflowIsCorrupted() throws Exception {
    try (WorkflowHttpFixture f = new WorkflowHttpFixture(root, new WorkflowExecutionAdapter())) {
      Path path = f.writeWorkflow("valid.hwf", false);
      var opened = f.open("valid.hwf", null);
      // The execution must use the pinned open-time XML, not reload mutable disk bytes.
      Files.writeString(path, "<workflow>broken");
      var response = f.post("api/v2/documents/" + opened.json().path("docId").asText() +
          "/executions", null, opened.cookie());
      assertEquals(202, response.statusCode(), response::body);
      String id = WorkflowHttpFixture.JSON.readTree(response.body()).path("id").asText();
      assertFalse(id.isBlank(), "accepted execution must return an id");
      var finished = f.awaitState(id, opened.cookie(), "finished");
      assertEquals(0, finished.path("errors").asInt(), finished::toString);
    }
  }

  @Test void localRunOptionsAreAcceptedAndUnsupportedOptionsRejectedOverHttp() throws Exception {
    try (WorkflowHttpFixture f = new WorkflowHttpFixture(root, new WorkflowExecutionAdapter())) {
      f.writeWorkflow("options.hwf", false);
      var opened = f.open("options.hwf", null);
      String url = "api/v2/documents/" + opened.json().path("docId").asText() + "/executions";

      var malformed = f.post(url, "{broken", opened.cookie());
      assertEquals(400, malformed.statusCode(), malformed::body);
      assertEquals("invalid_run_options",
          WorkflowHttpFixture.JSON.readTree(malformed.body()).path("code").asText());

      var nonObject = f.post(url, "[]", opened.cookie());
      assertEquals(400, nonObject.statusCode(), nonObject::body);
      assertEquals("invalid_run_options",
          WorkflowHttpFixture.JSON.readTree(nonObject.body()).path("code").asText());

      // Build JSON with the same mapper as the HTTP fixture; avoid hand-escaped payloads.
      var unsupportedOptions = WorkflowHttpFixture.JSON.createObjectNode().put("logLevel", "DEBUG");
      var unsupported = f.post(url, unsupportedOptions.toString(), opened.cookie());
      assertEquals(422, unsupported.statusCode(), unsupported::body);
      assertEquals("unsupported_run_options",
          WorkflowHttpFixture.JSON.readTree(unsupported.body()).path("code").asText());

      var localOptions = WorkflowHttpFixture.JSON.createObjectNode().put("runConfiguration", "LOCAL");
      var accepted = f.post(url, localOptions.toString(), opened.cookie());
      assertEquals(202, accepted.statusCode(), accepted::body);
      String executionId = WorkflowHttpFixture.JSON.readTree(accepted.body()).path("id").asText();
      assertFalse(executionId.isBlank());
      var finished = f.awaitState(executionId, opened.cookie(), "finished");
      assertEquals(0, finished.path("errors").asInt(-1), finished::toString);
    }
  }

  @Test void queuedWorkflowStartTimeReflectsWorkerStartOverHttp() throws Exception {
    var releaseWorker = new java.util.concurrent.CountDownLatch(1);
    var occupied = new java.util.concurrent.CountDownLatch(1);
    var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    executor.submit(() -> {
      occupied.countDown();
      try {
        if (!releaseWorker.await(15, TimeUnit.SECONDS)) {
          throw new IllegalStateException("test worker gate timed out");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    try {
      assertTrue(occupied.await(5, TimeUnit.SECONDS));
      try (WorkflowHttpFixture f = new WorkflowHttpFixture(root,
          new WorkflowExecutionAdapter(executor, () -> "start-time-test-id"))) {
        f.writeWorkflow("start-time.hwf", false);
        var opened = f.open("start-time.hwf", null);
        String url = "api/v2/documents/" + opened.json().path("docId").asText() + "/executions";
        var response = f.post(url, null, opened.cookie());
        assertEquals(202, response.statusCode(), response::body);
        var accepted = WorkflowHttpFixture.JSON.readTree(response.body());
        assertTrue(accepted.path("startTime").isNull(),
            "queued workflow has not started in Hop yet");
        String id = accepted.path("id").asText();
        var queued = WorkflowHttpFixture.JSON.readTree(
            f.get("api/v2/executions/" + id, opened.cookie()).body());
        assertEquals("starting", queued.path("state").asText());
        assertTrue(queued.path("startTime").isNull());
        releaseWorker.countDown();
        var finished = f.awaitState(id, opened.cookie(), "finished");
        assertTrue(finished.path("startTime").isTextual(),
            "actual worker start must be visible once Hop runs");
      }
    } finally {
      releaseWorker.countDown();
      executor.shutdownNow();
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


  @Test void canonicalFileUriReopensOverRealHttpAndStartsWorkflow() throws Exception {
    try (WorkflowHttpFixture f = new WorkflowHttpFixture(root, new WorkflowExecutionAdapter())) {
      Path file = f.writeWorkflow("canonical-reopen.hwf", false);
      var first = f.open("canonical-reopen.hwf", null);
      var reopened = f.open(file.toUri().toString(), first.cookie());
      assertEquals(first.cookie(), reopened.cookie(), "same owner must be preserved");
      assertEquals(file.toUri().toString(), reopened.json().path("uri").asText());
      String id = reopened.json().path("docId").asText();
      var started = f.post("api/v2/documents/" + id + "/executions", null, reopened.cookie());
      assertEquals(202, started.statusCode(), started::body);
      String executionId = WorkflowHttpFixture.JSON.readTree(started.body()).path("id").asText();
      assertFalse(executionId.isBlank());
      assertEquals(200, f.get("api/v2/executions/" + executionId, reopened.cookie()).statusCode());
      assertEquals("finished", f.awaitState(executionId, reopened.cookie(), "finished").path("state").asText());
    }
  }

  @Test void malformedWorkflowOpenDoesNotIssueOwnerCookie() throws Exception {
    try (WorkflowHttpFixture f = new WorkflowHttpFixture(root, new WorkflowExecutionAdapter())) {
      Files.writeString(root.resolve("malformed-open.hwf"), "<workflow>broken");
      var failed = f.post("api/v2/documents",
          WorkflowHttpFixture.JSON.writeValueAsString(
              new WorkflowHttpFixture.OpenRequest("malformed-open.hwf")), null);
      assertEquals(422, failed.statusCode(), failed::body);
      assertEquals("document_open_failed",
          WorkflowHttpFixture.JSON.readTree(failed.body()).path("code").asText());
      assertTrue(failed.headers().firstValue("Set-Cookie").isEmpty(),
          "a failed XML parse must not create an owner cookie");
      f.writeWorkflow("recovery.hwf", false);
      var recovered = f.open("recovery.hwf", null);
      assertFalse(recovered.cookie().isBlank());
    }
  }

  @Test void remoteUriSchemesAndFileAuthoritiesRejectWithoutSessionCookie() throws Exception {
    try (WorkflowHttpFixture f = new WorkflowHttpFixture(root, new WorkflowExecutionAdapter())) {
      for (String uri : List.of("https://example.invalid/workflow.hwf",
          "s3://bucket/workflow.hwf", "file://remotehost/tmp/workflow.hwf",
          "file:///tmp/workflow.hwf?query=1")) {
        var response = f.post("api/v2/documents",
            WorkflowHttpFixture.JSON.writeValueAsString(new WorkflowHttpFixture.OpenRequest(uri)), null);
        assertEquals(400, response.statusCode(), () -> uri + ": " + response.body());
        assertEquals("invalid_document",
            WorkflowHttpFixture.JSON.readTree(response.body()).path("code").asText(), uri);
        assertTrue(response.headers().firstValue("Set-Cookie").isEmpty(), uri);
      }
    }
  }

  @Test void nativeColonPathAndStaticSymlinkEscapeRespectRootOverHttp() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        !System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win"));
    try (WorkflowHttpFixture f = new WorkflowHttpFixture(root, new WorkflowExecutionAdapter())) {
      f.writeWorkflow("native:colon.hwf", false);
      var opened = f.open("native:colon.hwf", null);
      assertEquals("workflow", opened.json().path("kind").asText());
      Path outside = Files.createTempFile("workflow-outside-", ".hwf");
      try {
        Files.createSymbolicLink(root.resolve("escape.hwf"), outside);
        var response = f.post("api/v2/documents",
            WorkflowHttpFixture.JSON.writeValueAsString(
                new WorkflowHttpFixture.OpenRequest("escape.hwf")), null);
        assertEquals(400, response.statusCode(), response::body);
        assertEquals("invalid_document",
            WorkflowHttpFixture.JSON.readTree(response.body()).path("code").asText());
        assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());
      } finally {
        Files.deleteIfExists(outside);
      }
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
