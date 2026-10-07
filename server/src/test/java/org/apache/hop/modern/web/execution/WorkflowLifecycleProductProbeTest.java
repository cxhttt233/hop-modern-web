package org.apache.hop.modern.web.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.hop.core.Result;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.workflow.WorkflowHopMeta;
import org.apache.hop.workflow.WorkflowMeta;
import org.apache.hop.workflow.action.ActionBase;
import org.apache.hop.workflow.action.ActionMeta;
import org.apache.hop.workflow.action.IAction;
import org.apache.hop.workflow.actions.start.ActionStart;
import org.apache.hop.workflow.engines.local.LocalWorkflowEngine;
import org.junit.jupiter.api.Test;

/**
 * Product-boundary proof for the future Modern Web workflow execution adapter.
 *
 * <p>The holder deliberately owns only asynchronous execution/lifetime. Hop owns WorkflowMeta,
 * LocalWorkflowEngine, lifecycle state and Result semantics. This is test support, not a production
 * endpoint.
 */
class WorkflowLifecycleProductProbeTest {
  @Test
  void productOwnedAsyncHolderPreservesHopWorkflowLifecycle() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    WorkflowMeta meta = new WorkflowMeta();
    meta.setName("modern-web-workflow-lifecycle-probe");
    ActionMeta start = new ActionMeta(new ActionStart("START"));
    ActionMeta block = new ActionMeta(new BlockingAction(entered, release));
    meta.addAction(start);
    meta.addAction(block);
    meta.addWorkflowHop(new WorkflowHopMeta(start, block));

    try (ProductExecutionHolder holder = new ProductExecutionHolder(meta)) {
      holder.start();
      assertTrue(entered.await(30, TimeUnit.SECONDS));
      assertEquals(new Status(true, false, false, "Running"), holder.status());

      holder.stop();
      assertEquals(new Status(true, true, false, "Halting"), holder.status());

      release.countDown();
      holder.awaitTerminal();

      assertEquals(new Status(false, true, false, "Stopped"), holder.status());
      assertTrue(holder.result().isStopped());
      assertEquals(0, holder.result().getNrErrors());
    } finally {
      release.countDown();
    }
  }

  private static final class ProductExecutionHolder implements AutoCloseable {
    private final LocalWorkflowEngine engine;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Future<?> future;

    ProductExecutionHolder(WorkflowMeta meta) {
      engine = new LocalWorkflowEngine(meta);
    }

    void start() {
      future = executor.submit(engine::startExecution);
    }

    void stop() {
      engine.stopExecution();
    }

    Status status() {
      return new Status(
          engine.isActive(),
          engine.isStopped(),
          engine.isFinished(),
          engine.getStatusDescription());
    }

    void awaitTerminal() throws Exception {
      future.get(60, TimeUnit.SECONDS);
    }

    Result result() {
      return engine.getResult();
    }

    @Override
    public void close() {
      engine.stopExecution();
      executor.shutdownNow();
    }
  }

  private record Status(boolean active, boolean stopped, boolean finished, String description) {}

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
        if (!release.await(60, TimeUnit.SECONDS)) {
          throw new HopException("workflow lifecycle probe timeout");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new HopException(e);
      }

      Result result = previous == null ? new Result() : previous.clone();
      result.setResult(true);
      result.setNrErrors(0);
      result.setStopped(false);
      assertFalse(result.isStopped());
      return result;
    }
  }
}
