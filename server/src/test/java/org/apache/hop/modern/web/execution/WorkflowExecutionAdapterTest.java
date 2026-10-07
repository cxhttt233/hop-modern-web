package org.apache.hop.modern.web.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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

class WorkflowExecutionAdapterTest {
  @Test
  void ownsOpaqueIdAndPreservesRunningHaltingStoppedResult() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    WorkflowMeta meta = blockingWorkflow(entered, release);

    try (WorkflowExecutionAdapter adapter = new WorkflowExecutionAdapter()) {
      String id = adapter.start("document-7", meta);
      assertNotNull(id);
      assertFalse(id.contains("document-7"));
      assertTrue(entered.await(30, TimeUnit.SECONDS));
      assertEquals(WorkflowExecutionAdapter.State.RUNNING, adapter.status(id).orElseThrow().state());

      assertEquals(WorkflowExecutionAdapter.State.HALTING, adapter.stop(id).orElseThrow().state());
      assertEquals(WorkflowExecutionAdapter.State.HALTING, adapter.stop(id).orElseThrow().state());

      release.countDown();
      awaitState(adapter, id, WorkflowExecutionAdapter.State.STOPPED);
      WorkflowExecutionAdapter.Snapshot terminal = adapter.status(id).orElseThrow();
      assertTrue(terminal.stopped());
      assertEquals(0, terminal.errors());
      assertEquals(WorkflowExecutionAdapter.State.STOPPED, adapter.stop(id).orElseThrow().state());
      assertTrue(adapter.remove(id));
      assertTrue(adapter.status(id).isEmpty());
    } finally {
      release.countDown();
    }
  }

  @Test
  void exposesFinishedTerminalState() throws Exception {
    try (WorkflowExecutionAdapter adapter = new WorkflowExecutionAdapter()) {
      String id = adapter.start("document-8", finishedWorkflow());
      awaitState(adapter, id, WorkflowExecutionAdapter.State.FINISHED);
      WorkflowExecutionAdapter.Snapshot terminal = adapter.status(id).orElseThrow();
      assertFalse(terminal.stopped());
      assertEquals(0, terminal.errors());
      assertNotNull(terminal.completedAt());
    }
  }

  @Test
  void unknownIdIsStableAndStartRejectionDoesNotLeakRegistry() {
    var rejecting = new RejectingExecutor();
    try (WorkflowExecutionAdapter adapter =
        new WorkflowExecutionAdapter(rejecting, () -> "opaque-test-id")) {
      assertTrue(adapter.status("missing").isEmpty());
      assertTrue(adapter.stop("missing").isEmpty());
      assertThrows(RejectedExecutionException.class,
          () -> adapter.start("document-9", finishedWorkflow()));
      assertEquals(0, adapter.size());
    }
  }

  private static WorkflowMeta finishedWorkflow() {
    WorkflowMeta meta = new WorkflowMeta();
    meta.setName("finished");
    meta.addAction(new ActionMeta(new ActionStart("START")));
    return meta;
  }

  private static WorkflowMeta blockingWorkflow(CountDownLatch entered, CountDownLatch release) {
    WorkflowMeta meta = new WorkflowMeta();
    meta.setName("blocking");
    ActionMeta start = new ActionMeta(new ActionStart("START"));
    ActionMeta block = new ActionMeta(new BlockingAction(entered, release));
    meta.addAction(start);
    meta.addAction(block);
    meta.addWorkflowHop(new WorkflowHopMeta(start, block));
    return meta;
  }

  private static void awaitState(
      WorkflowExecutionAdapter adapter, String id, WorkflowExecutionAdapter.State expected)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      if (adapter.status(id).orElseThrow().state() == expected) return;
      Thread.sleep(10);
    }
    fail("workflow did not reach " + expected);
  }

  private static final class BlockingAction extends ActionBase implements IAction {
    private final CountDownLatch entered;
    private final CountDownLatch release;

    BlockingAction(CountDownLatch entered, CountDownLatch release) {
      super("blocking", "", "BlockingAction");
      this.entered = entered;
      this.release = release;
    }

    @Override
    public Result execute(Result previous, int nr) throws HopException {
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
