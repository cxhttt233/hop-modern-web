package org.apache.hop.modern.web.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.modern.web.ProductContext;
import org.apache.hop.modern.web.document.PipelineDocumentRegistry;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.engine.IPipelineEngine;
import org.apache.hop.web.api.execution.ExecutionRegistry;
import org.junit.jupiter.api.Test;

class PipelineExecutionCancelTest {
  @Test
  void cancelStopsRunningExecutionAndIsIdempotent() {
    ExecutionRegistry<IPipelineEngine<PipelineMeta>> executions =
        new ExecutionRegistry<>(Duration.ofHours(1), 16);
    @SuppressWarnings("unchecked")
    IPipelineEngine<PipelineMeta> engine = mock(IPipelineEngine.class);
    AtomicBoolean stopped = new AtomicBoolean(false);
    when(engine.isFinished()).thenReturn(false);
    when(engine.isStopped()).thenAnswer(ignored -> stopped.get());
    when(engine.getErrors()).thenReturn(0);
    org.mockito.Mockito.doAnswer(
            ignored -> {
              stopped.set(true);
              return null;
            })
        .when(engine)
        .stopAll();
    executions.register("exec-cancel", "doc-1", engine);

    PipelineExecutionResource resource =
        new PipelineExecutionResource(
            new PipelineDocumentRegistry(),
            new ProductContext(new Variables(), new MemoryMetadataProvider()),
            executions);

    var first = resource.cancel("exec-cancel");
    assertEquals(200, first.getStatus());
    var firstStatus = (PipelineExecutionResource.ExecutionStatus) first.getEntity();
    assertEquals("stopped", firstStatus.state());
    verify(engine, times(1)).stopAll();

    var second = resource.cancel("exec-cancel");
    assertEquals(200, second.getStatus());
    assertEquals(
        "stopped", ((PipelineExecutionResource.ExecutionStatus) second.getEntity()).state());
    verify(engine, times(1)).stopAll();
  }

  @Test
  void cancelUnknownExecutionUsesStatusNotFoundSemantics() {
    PipelineExecutionResource resource =
        new PipelineExecutionResource(
            new PipelineDocumentRegistry(),
            new ProductContext(new Variables(), new MemoryMetadataProvider()),
            new ExecutionRegistry<>(Duration.ofHours(1), 16));

    var response = resource.cancel("missing");
    assertEquals(404, response.getStatus());
    assertEquals(
        "execution_not_found",
        ((PipelineExecutionResource.ErrorResponse) response.getEntity()).code());
  }
}
