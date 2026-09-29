package org.apache.hop.modern.web.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.nio.file.Path;
import java.time.Duration;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.memory.MemoryMetadataProvider;
import org.apache.hop.modern.web.document.PipelineDocument;
import org.apache.hop.modern.web.document.PipelineDocumentRegistry;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.engine.IPipelineEngine;
import org.apache.hop.web.api.execution.ExecutionRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PipelineExecutionResourceSnapshotTest {
  @BeforeAll
  static void initializeHop() throws Exception {
    HopClientEnvironment.init();
  }

  @Test
  void runningExecutionOwnsSnapshotIndependentFromEditorDocument() {
    Variables variables = Variables.getADefaultVariableSpace();
    MemoryMetadataProvider metadataProvider = new MemoryMetadataProvider();
    PipelineMeta editorPipeline = new PipelineMeta();
    editorPipeline.setName("snapshot-before-edit");

    PipelineDocumentRegistry documents = new PipelineDocumentRegistry();
    PipelineDocument document =
        new PipelineDocument("doc-1", Path.of("snapshot-proof.hpl"), editorPipeline);
    documents.put(document);
    ExecutionRegistry<IPipelineEngine<PipelineMeta>> executions =
        new ExecutionRegistry<>(Duration.ofHours(1), 16);
    PipelineExecutionResource resource =
        new PipelineExecutionResource(documents, variables, metadataProvider, executions);

    var response = resource.start("doc-1");
    assertEquals(202, response.getStatus());
    var status = (PipelineExecutionResource.ExecutionStatus) response.getEntity();
    IPipelineEngine<PipelineMeta> engine = executions.find(status.id()).orElseThrow().execution();

    PipelineMeta executionSnapshot = engine.getPipelineMeta();
    assertNotSame(editorPipeline, executionSnapshot);
    assertEquals("snapshot-before-edit", executionSnapshot.getName());

    synchronized (document) {
      editorPipeline.setName("snapshot-after-edit");
    }

    assertEquals("snapshot-after-edit", editorPipeline.getName());
    assertEquals("snapshot-before-edit", executionSnapshot.getName());
  }
}
