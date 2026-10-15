package org.apache.hop.modern.web.execution;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.function.Function;
import org.apache.hop.core.Result;
import org.apache.hop.workflow.WorkflowMeta;
import org.apache.hop.workflow.engines.local.LocalWorkflowEngine;

/** Product-owned async registry/adapter; Hop remains authoritative for workflow lifecycle. */
public final class WorkflowExecutionAdapter implements AutoCloseable {
  private final Map<String, Entry> executions = new ConcurrentHashMap<>();
  private final ExecutorService executor;
  private final Supplier<String> ids;
  private final Function<WorkflowMeta, LocalWorkflowEngine> engineFactory;

  public WorkflowExecutionAdapter() {
    this(Executors.newCachedThreadPool(), () -> UUID.randomUUID().toString());
  }

  WorkflowExecutionAdapter(ExecutorService executor, Supplier<String> ids) {
    this(executor, ids, LocalWorkflowEngine::new);
  }

  WorkflowExecutionAdapter(ExecutorService executor, Supplier<String> ids,
      Function<WorkflowMeta, LocalWorkflowEngine> engineFactory) {
    this.executor = executor;
    this.ids = ids;
    this.engineFactory = engineFactory;
  }

  public String start(String owner, WorkflowMeta workflowMeta) {
    String id = ids.get();
    LocalWorkflowEngine engine = engineFactory.apply(workflowMeta);
    Entry entry = new Entry(owner, engine, Instant.now());
    if (executions.putIfAbsent(id, entry) != null) {
      throw new IllegalStateException("duplicate workflow execution id");
    }
    try {
      executor.submit(() -> {
        try {
          engine.startExecution();
        } catch (RuntimeException | Error failure) {
          // A worker crash must not leave a permanently STARTING execution with zero errors.
          // Publish completion before the volatile crash flag so status cannot expose
          // a failed worker without its completion timestamp.
          entry.completedAt = Instant.now();
          entry.crashed = true;
          throw failure;
        } finally {
          if (entry.completedAt == null) entry.completedAt = Instant.now();
        }
      });
      return id;
    } catch (RuntimeException e) {
      executions.remove(id, entry);
      throw e;
    }
  }

  public Optional<Snapshot> status(String id) {
    Entry entry = executions.get(id);
    return entry == null ? Optional.empty() : Optional.of(snapshot(id, entry));
  }

  public Optional<Snapshot> stop(String id) {
    Entry entry = executions.get(id);
    if (entry == null) {
      return Optional.empty();
    }
    if (!terminal(entry)) {
      entry.engine.stopExecution();
    }
    return Optional.of(snapshot(id, entry));
  }

  public boolean remove(String id) {
    Entry entry = executions.get(id);
    // A queued or still-unwinding task must remain discoverable until its worker exits.
    // Engine flags alone can report stopped/inactive before a queued Runnable starts.
    return entry != null && entry.completedAt != null
        && terminal(entry) && executions.remove(id, entry);
  }

  int size() {
    return executions.size();
  }

  private static Snapshot snapshot(String id, Entry entry) {
    Result result = entry.engine.getResult();
    boolean crashed = entry.crashed;
    long errors = Math.max(result == null ? 0 : result.getNrErrors(), crashed ? 1 : 0);
    State current = crashed
        ? (entry.engine.isActive() ? State.HALTING : State.FAILED)
        : state(entry.engine, errors);
    return new Snapshot(id, entry.owner, current, entry.createdAt,
        entry.completedAt, errors, result != null && result.isStopped());
  }

  private static State state(LocalWorkflowEngine engine, long errors) {
    if (engine.isStopped()) return engine.isActive() ? State.HALTING : State.STOPPED;
    if (engine.isFinished()) return errors > 0 ? State.FAILED : State.FINISHED;
    if (engine.isActive()) return State.RUNNING;
    return State.STARTING;
  }

  private static boolean terminal(Entry entry) {
    return (entry.crashed && !entry.engine.isActive()) || terminal(entry.engine);
  }

  private static boolean terminal(LocalWorkflowEngine engine) {
    return (engine.isStopped() && !engine.isActive()) || engine.isFinished();
  }

  @Override
  public void close() {
    executions.values().forEach(entry -> entry.engine.stopExecution());
    executor.shutdownNow();
  }

  public enum State { STARTING, RUNNING, HALTING, STOPPED, FINISHED, FAILED }

  public record Snapshot(String id, String owner, State state, Instant createdAt,
      Instant completedAt, long errors, boolean stopped) {}

  private static final class Entry {
    private final String owner;
    private final LocalWorkflowEngine engine;
    private final Instant createdAt;
    private volatile Instant completedAt;
    private volatile boolean crashed;

    private Entry(String owner, LocalWorkflowEngine engine, Instant createdAt) {
      this.owner = owner;
      this.engine = engine;
      this.createdAt = createdAt;
    }
  }
}
