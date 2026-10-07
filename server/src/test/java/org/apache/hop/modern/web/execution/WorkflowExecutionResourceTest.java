package org.apache.hop.modern.web.execution;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.ws.rs.core.Response;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.hop.core.Result;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.workflow.WorkflowHopMeta;
import org.apache.hop.workflow.WorkflowMeta;
import org.apache.hop.workflow.action.ActionBase;
import org.apache.hop.workflow.action.ActionMeta;
import org.apache.hop.workflow.action.IAction;
import org.apache.hop.workflow.actions.start.ActionStart;
import org.junit.jupiter.api.Test;

class WorkflowExecutionResourceTest {
  @Test
  void httpLifecyclePreservesDocumentOwnerAndStopIsIdempotent() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    WorkflowMeta meta = blockingWorkflow(entered, release);
    try (WorkflowExecutionAdapter adapter = new WorkflowExecutionAdapter();
         WorkflowExecutionResource resource =
             new WorkflowExecutionResource(id -> "workflow-doc".equals(id) ? Optional.of(meta) : Optional.empty(), adapter)) {
      Response started = resource.start("workflow-doc");
      assertEquals(202, started.getStatus());
      var initial = (WorkflowExecutionResource.ExecutionStatus) started.getEntity();
      assertNotNull(initial.id());
      assertFalse(initial.id().contains("workflow-doc"));
      assertEquals("workflow-doc", initial.documentId());

      assertTrue(entered.await(30, TimeUnit.SECONDS));
      assertEquals("running", status(resource, initial.id()).state());

      assertEquals("halting", stop(resource, initial.id()).state());
      assertEquals("halting", stop(resource, initial.id()).state());

      release.countDown();
      var terminal = awaitState(resource, initial.id(), "stopped");
      assertTrue(terminal.stopped());
      assertEquals(0, terminal.errors());
      assertEquals("workflow-doc", terminal.documentId());
      assertEquals("stopped", stop(resource, initial.id()).state());
    } finally {
      release.countDown();
    }
  }

  @Test
  void exposesFinishedAndUnknownIdsWithoutCrossingDocumentBoundary() throws Exception {
    WorkflowMeta finished = new WorkflowMeta();
    finished.setName("finished");
    finished.addAction(new ActionMeta(new ActionStart("START")));
    try (WorkflowExecutionAdapter adapter = new WorkflowExecutionAdapter();
         WorkflowExecutionResource resource =
             new WorkflowExecutionResource(id -> "owned-doc".equals(id) ? Optional.of(finished) : Optional.empty(), adapter)) {
      assertEquals(404, resource.start("other-doc").getStatus());
      assertEquals(404, resource.status("missing").getStatus());
      assertEquals(404, resource.stop("missing").getStatus());

      var started = (WorkflowExecutionResource.ExecutionStatus) resource.start("owned-doc").getEntity();
      var terminal = awaitState(resource, started.id(), "finished");
      assertFalse(terminal.stopped());
      assertEquals(0, terminal.errors());
      assertEquals("owned-doc", terminal.documentId());
    }
  }

  @Test
  void rejectedSubmissionReturns422AndDoesNotLeakProductRegistry() {
    RejectingExecutor rejecting = new RejectingExecutor();
    WorkflowExecutionAdapter adapter =
        new WorkflowExecutionAdapter(rejecting, () -> "opaque-rejected-id");
    WorkflowMeta finished = new WorkflowMeta();
    finished.addAction(new ActionMeta(new ActionStart("START")));
    try (WorkflowExecutionResource resource =
        new WorkflowExecutionResource(id -> Optional.of(finished), adapter)) {
      Response response = resource.start("owned-doc");
      assertEquals(422, response.getStatus());
      var error = (WorkflowExecutionResource.ErrorResponse) response.getEntity();
      assertEquals("execution_start_failed", error.code());
      assertEquals(0, adapter.size());
      assertTrue(adapter.status("opaque-rejected-id").isEmpty());
    }
  }

  private static WorkflowExecutionResource.ExecutionStatus status(
      WorkflowExecutionResource resource, String id) {
    Response response = resource.status(id);
    assertEquals(200, response.getStatus());
    return (WorkflowExecutionResource.ExecutionStatus) response.getEntity();
  }

  private static WorkflowExecutionResource.ExecutionStatus stop(
      WorkflowExecutionResource resource, String id) {
    Response response = resource.stop(id);
    assertEquals(200, response.getStatus());
    return (WorkflowExecutionResource.ExecutionStatus) response.getEntity();
  }

  private static WorkflowExecutionResource.ExecutionStatus awaitState(
      WorkflowExecutionResource resource, String id, String expected) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      var value = status(resource, id);
      if (expected.equals(value.state())) return value;
      Thread.sleep(10);
    }
    fail("workflow HTTP lifecycle did not reach " + expected);
    return null;
  }

  private static WorkflowMeta blockingWorkflow(CountDownLatch entered, CountDownLatch release) {
    WorkflowMeta meta = new WorkflowMeta();
    ActionMeta start = new ActionMeta(new ActionStart("START"));
    ActionMeta block = new ActionMeta(new BlockingAction(entered, release));
    meta.addAction(start);
    meta.addAction(block);
    meta.addWorkflowHop(new WorkflowHopMeta(start, block));
    return meta;
  }

  private static final class BlockingAction extends ActionBase implements IAction {
    private final CountDownLatch entered;
    private final CountDownLatch release;
    BlockingAction(CountDownLatch entered, CountDownLatch release) {
      super("blocking", "", "BlockingAction");
      this.entered = entered;
      this.release = release;
    }
    @Override public Result execute(Result previous, int nr) throws HopException {
      entered.countDown();
      try {
        if (!release.await(30, TimeUnit.SECONDS)) throw new HopException("timeout");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new HopException(e);
      }
      Result result = previous == null ? new Result() : previous.clone();
      result.setResult(true);
      result.setNrErrors(0);
      result.setStopped(false);
      return result;
    }
  }

  private static final class RejectingExecutor extends AbstractExecutorService {
    private boolean shutdown;
    @Override public void shutdown() { shutdown = true; }
    @Override public java.util.List<Runnable> shutdownNow() { shutdown = true; return java.util.List.of(); }
    @Override public boolean isShutdown() { return shutdown; }
    @Override public boolean isTerminated() { return shutdown; }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
    @Override public void execute(Runnable command) { throw new RejectedExecutionException("rejected"); }
  }
}
