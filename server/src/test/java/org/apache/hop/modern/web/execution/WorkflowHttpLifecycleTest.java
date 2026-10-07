package org.apache.hop.modern.web.execution;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.modern.web.ModernWebServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowHttpLifecycleTest {
  @TempDir Path root;

  @BeforeAll static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test void workflowLifecycleUsesRealHttp() throws Exception {
    try (WorkflowHttpFixture f = new WorkflowHttpFixture(root, new WorkflowExecutionAdapter())) {
      f.writeWorkflow("slow.hwf", true);
      var opened = f.open("slow.hwf", null);
      String doc = opened.json().path("docId").asText();
      assertFalse(doc.isBlank());
      var response = f.post("api/v2/documents/" + doc + "/executions", null, opened.cookie());
      assertEquals(202, response.statusCode(), response::body);
      var started = WorkflowHttpFixture.JSON.readTree(response.body());
      String id = started.path("id").asText();
      assertFalse(id.isBlank());
      assertFalse(id.contains(doc));
      assertEquals(doc, started.path("documentId").asText());
      f.awaitState(id, opened.cookie(), "running");
      assertEquals(200, f.post("api/v2/executions/" + id + "/stop", null, opened.cookie()).statusCode());
      var terminal = f.awaitState(id, opened.cookie(), "stopped");
      assertTrue(terminal.path("stopped").asBoolean());
      assertEquals(0, terminal.path("errors").asInt(-1));
      var secondStop = f.post("api/v2/executions/" + id + "/stop", null, opened.cookie());
      assertEquals("stopped", WorkflowHttpFixture.JSON.readTree(secondStop.body()).path("state").asText());

      f.writeWorkflow("quick.hwf", false);
      var quick = f.open("quick.hwf", opened.cookie());
      var fastStart = f.post("api/v2/documents/" + quick.json().path("docId").asText() +
          "/executions", null, opened.cookie());
      assertEquals(202, fastStart.statusCode(), fastStart::body);
      String fastId = WorkflowHttpFixture.JSON.readTree(fastStart.body()).path("id").asText();
      var finished = f.awaitState(fastId, opened.cookie(), "finished");
      assertFalse(finished.path("stopped").asBoolean());
      assertEquals(0, finished.path("errors").asInt(-1));
    }
  }
}
