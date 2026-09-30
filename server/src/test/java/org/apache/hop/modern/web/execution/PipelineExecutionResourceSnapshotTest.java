package org.apache.hop.modern.web.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.database.DatabaseMeta;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.modern.web.ModernWebServer;
import org.apache.hop.modern.web.ProductContext;
import org.apache.hop.modern.web.document.PipelineDocument;
import org.apache.hop.modern.web.document.PipelineDocumentRegistry;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.engine.IPipelineEngine;
import org.apache.hop.pipeline.engines.local.LocalPipelineEngine;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.pipeline.transforms.tableinput.TableInputMeta;
import org.apache.hop.web.api.execution.ExecutionRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PipelineExecutionResourceSnapshotTest {
  @BeforeAll
  static void initializeHop() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }


  @Test
  void executionSnapshotResolvesDatabaseMetadataFromInjectedProductContext() throws Exception {
    IVariables variables = new Variables();
    MemoryMetadataProvider metadataProvider = new MemoryMetadataProvider();
    DatabaseMeta warehouse = new DatabaseMeta();
    warehouse.setName("Warehouse");
    metadataProvider.getSerializer(DatabaseMeta.class).save(warehouse);

    TableInputMeta tableInput = new TableInputMeta();
    tableInput.setConnection("Warehouse");
    tableInput.setSql("select 1");
    PipelineMeta editorPipeline = new PipelineMeta();
    editorPipeline.setName("external-metadata-proof");
    editorPipeline.addTransform(new TransformMeta("TableInput", "source", tableInput));

    PipelineMeta executionSnapshot =
        new PipelineMeta(
            new ByteArrayInputStream(
                editorPipeline.getXml(variables).getBytes(StandardCharsets.UTF_8)),
            metadataProvider,
            variables);
    TableInputMeta snapshotInput =
        (TableInputMeta) executionSnapshot.findTransform("source").getTransform();

    LocalPipelineEngine engine = new LocalPipelineEngine(executionSnapshot, variables, null);
    engine.setMetadataProvider(metadataProvider);

    assertSame(metadataProvider, engine.getMetadataProvider());
    assertEquals("Warehouse", snapshotInput.getConnection());
    DatabaseMeta resolved =
        DatabaseMeta.loadDatabase(engine.getMetadataProvider(), snapshotInput.getConnection());
    assertNotNull(resolved);
    assertEquals("Warehouse", resolved.getName());
  }

  @Test
  void runningExecutionOwnsSnapshotIndependentFromEditorDocument() {
    IVariables variables = Variables.getADefaultVariableSpace();
    variables.setVariable("MODERN_WEB_CONTEXT_MARKER", "p1b-injected");
    MemoryMetadataProvider metadataProvider = new MemoryMetadataProvider();
    ProductContext productContext = new ProductContext(variables, metadataProvider);
    PipelineMeta editorPipeline = new PipelineMeta();
    editorPipeline.setName("snapshot-before-edit");

    PipelineDocumentRegistry documents = new PipelineDocumentRegistry();
    PipelineDocument document =
        new PipelineDocument("doc-1", Path.of("snapshot-proof.hpl"), editorPipeline);
    documents.put(document);
    ExecutionRegistry<IPipelineEngine<PipelineMeta>> executions =
        new ExecutionRegistry<>(Duration.ofHours(1), 16);
    PipelineExecutionResource resource =
        new PipelineExecutionResource(documents, productContext, executions);

    var response = resource.start("doc-1");
    assertEquals(202, response.getStatus());
    var status = (PipelineExecutionResource.ExecutionStatus) response.getEntity();
    IPipelineEngine<PipelineMeta> engine = executions.find(status.id()).orElseThrow().execution();

    assertSame(metadataProvider, engine.getMetadataProvider());
    assertEquals("p1b-injected", engine.getVariable("MODERN_WEB_CONTEXT_MARKER"));

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
