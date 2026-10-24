package org.apache.hop.modern.web.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.database.Database;
import org.apache.hop.core.database.DatabaseMeta;
import org.apache.hop.core.database.DatabasePluginType;
import org.apache.hop.core.logging.LoggingObject;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.databases.h2.H2DatabaseMeta;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.modern.web.ModernWebServer;
import org.apache.hop.modern.web.ProductContext;
import org.apache.hop.modern.web.document.PipelineDocument;
import org.apache.hop.modern.web.document.PipelineDocumentRegistry;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.engine.IPipelineEngine;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.pipeline.transforms.tableinput.TableInputMeta;
import org.apache.hop.web.api.execution.ExecutionRegistry;
import org.apache.hop.web.api.execution.PipelineExecutionLifecycle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class PipelineExecutionH2StartTest {
  @BeforeAll
  static void initializeHop() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
    DatabasePluginType.getInstance().registerClassPathPlugin(H2DatabaseMeta.class);
  }

  @Test
  void tableInputRunsThroughAuthoritativeStartWithProductContextDatabase() throws Exception {
    IVariables variables = new Variables();
    MemoryMetadataProvider provider = new MemoryMetadataProvider();
    ProductContext context = new ProductContext(variables, provider);
    DatabaseMeta warehouse =
        new DatabaseMeta("Warehouse", "H2", "Native", "", "mem:p1b;DB_CLOSE_DELAY=-1", "", "", "");
    provider.getSerializer(DatabaseMeta.class).save(warehouse);

    try (Database db = new Database(new LoggingObject("p1b-h2"), variables, warehouse)) {
      db.connect();
      db.execStatement("CREATE TABLE P1B_PROOF(ID INT PRIMARY KEY, NAME VARCHAR(32))");
      db.execStatement("INSERT INTO P1B_PROOF VALUES(1, 'product-context')");
    }

    TableInputMeta input = new TableInputMeta();
    input.setConnection("Warehouse");
    input.setSql("SELECT ID, NAME FROM P1B_PROOF");
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("p1b-h2-start");
    pipeline.addTransform(new TransformMeta("TableInput", "source", input));

    PipelineDocumentRegistry documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("doc-h2", Path.of("p1b-h2.hpl"), pipeline));
    ExecutionRegistry<IPipelineEngine<PipelineMeta>> executions =
        new ExecutionRegistry<>(Duration.ofHours(1), 16);
    PipelineExecutionResource resource =
        new PipelineExecutionResource(documents, context, executions);

    var response = resource.start("doc-h2");
    assertEquals(202, response.getStatus());
    var accepted = (PipelineExecutionResource.ExecutionStatus) response.getEntity();
    IPipelineEngine<PipelineMeta> engine =
        executions.find(accepted.id()).orElseThrow().execution();

    assertSame(provider, engine.getMetadataProvider());
    DatabaseMeta resolved =
        DatabaseMeta.loadDatabase(
            engine.getMetadataProvider(),
            ((TableInputMeta) engine.getPipelineMeta().findTransform("source").getTransform())
                .getConnection());
    assertNotNull(resolved);
    assertEquals("H2", resolved.getPluginId());

    engine.waitUntilFinished();
    assertTrue(engine.isFinished());
    assertEquals(0, engine.getErrors());
    var completed =
        (PipelineExecutionResource.ExecutionStatus) resource.status(accepted.id()).getEntity();
    assertEquals("completed", completed.state());
  }

  @Test
  void startFailureReturns422AndRemovesRegisteredExecution() throws Exception {
    IVariables variables = new Variables();
    MemoryMetadataProvider provider = new MemoryMetadataProvider();
    ProductContext context = new ProductContext(variables, provider);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setName("controlled-start-failure");

    PipelineDocumentRegistry documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("doc-fail", Path.of("controlled-start-failure.hpl"), pipeline));
    ExecutionRegistry<IPipelineEngine<PipelineMeta>> executions =
        new ExecutionRegistry<>(Duration.ofHours(1), 16);
    PipelineExecutionResource resource =
        new PipelineExecutionResource(documents, context, executions);
    AtomicReference<String> attemptedExecutionId = new AtomicReference<>();

    try (MockedStatic<PipelineExecutionLifecycle> lifecycle =
        Mockito.mockStatic(PipelineExecutionLifecycle.class)) {
      lifecycle
          .when(() -> PipelineExecutionLifecycle.start(Mockito.anyString(), Mockito.same(executions)))
          .thenAnswer(
              invocation -> {
                attemptedExecutionId.set(invocation.getArgument(0));
                throw new org.apache.hop.core.exception.HopException("controlled start failure");
              });

      var response = resource.start("doc-fail");
      assertEquals(422, response.getStatus());
      var error = (PipelineExecutionResource.ErrorResponse) response.getEntity();
      assertEquals("execution_start_failed", error.code());
    }

    String executionId = attemptedExecutionId.get();
    assertNotNull(executionId);
    assertTrue(executions.find(executionId).isEmpty());
    var status = resource.status(executionId);
    assertEquals(404, status.getStatus());
    assertEquals("execution_not_found",
        ((PipelineExecutionResource.ErrorResponse) status.getEntity()).code());
  }

}
