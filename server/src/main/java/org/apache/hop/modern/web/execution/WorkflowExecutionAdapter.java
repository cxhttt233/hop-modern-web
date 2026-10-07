package org.apache.hop.modern.web.execution;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.apache.hop.core.Result;
import org.apache.hop.workflow.WorkflowMeta;
import org.apache.hop.workflow.engines.local.LocalWorkflowEngine;

/** Product-owned async registry/adapter; Hop remains authoritative for workflow lifecycle. */
public final class WorkflowExecutionAdapter implements AutoCloseable {
  private final Map<String, Entry> executions = new ConcurrentHashMap<>();
  private final ExecutorService executor;
  private final Supplier<String> ids;

  public WorkflowExecutionAdapter() {
    this(Executors.newCachedThreadPool(), () -> UUID.randomUUID().toString());
  }

  WorkflowExecutionAdapter(ExecutorService executor, Supplier<String> ids) {
    this.executor = executor;
    this.ids = ids;
  }

  public String start(String owner, WorkflowMeta workflowMeta) {
    String id = ids.get();
    LocalWorkflowEngine engine = new LocalWorkflowEngine(workflowMeta);
    Entry entry = new Entry(owner, engine, Instant.now());
    if (executions.putIfAbsent(id, entry) != null) {
      throw new IllegalStateException("duplicate workflow execution id");
    }
    try {
      executor.submit(() -> {
        try {
          engine.startExecution();
        } finally {
          entry.completedAt = Instant.now();
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
    if (!terminal(entry.engine)) {
      entry.engine.stopExecution();
    }
    return Optional.of(snapshot(id, entry));
  }

  public boolean remove(String id) {
    Entry entry = executions.get(id);
    return entry != null && terminal(entry.engine) && executions.remove(id, entry);
  }

  int size() {
    return executions.size();
  }

  private static Snapshot snapshot(String id, Entry entry) {
    Result result = entry.engine.getResult();
    long errors = result == null ? 0 : result.getNrErrors();
    return new Snapshot(id, entry.owner, state(entry.engine, errors), entry.createdAt,
        entry.completedAt, errors, result != null && result.isStopped());
  }

  private static State state(LocalWorkflowEngine engine, long errors) {
    if (engine.isStopped()) return engine.isActive() ? State.HALTING : State.STOPPED;
    if (engine.isFinished()) return errors > 0 ? State.FAILED : State.FINISHED;
    if (engine.isActive()) return State.RUNNING;
    return State.STARTING;
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

    private Entry(String owner, LocalWorkflowEngine engine, Instant createdAt) {
      this.owner = owner;
      this.engine = engine;
      this.createdAt = createdAt;
    }
  }
}
