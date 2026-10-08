package org.apache.hop.modern.web.execution;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.apache.hop.core.Const;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.database.Database;
import org.apache.hop.core.database.DatabaseMeta;
import org.apache.hop.core.database.DatabasePluginType;
import org.apache.hop.core.logging.LoggingObject;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.core.vfs.HopVfs;
import org.apache.hop.databases.h2.H2DatabaseMeta;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.modern.web.ModernWebServer;
import org.apache.hop.modern.web.ProductContext;
import org.apache.hop.modern.web.document.PipelineDocument;
import org.apache.hop.modern.web.document.PipelineDocumentRegistry;
import org.apache.hop.modern.web.document.PipelineDocumentStore;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.engine.IPipelineEngine;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.pipeline.transforms.tableinput.TableInputMeta;
import org.apache.hop.web.api.execution.ExecutionRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Covers a saved and reopened .hpl, rather than a directly injected in-memory pipeline. */
class PipelineExecutionOpenedHplRegressionTest {
  @BeforeAll
  static void initializeHop() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
    DatabasePluginType.getInstance().registerClassPathPlugin(H2DatabaseMeta.class);
  }

  @Test
  void openedHplRetainsRelativeDirectoryAndParameterDefaultDuringH2Execution(@TempDir Path root)
      throws Exception {
    IVariables variables = new Variables();
    MemoryMetadataProvider provider = new MemoryMetadataProvider();
    DatabaseMeta warehouse =
        new DatabaseMeta(
            "Warehouse", "H2", "Native", "", "mem:p1_hpl_regression;DB_CLOSE_DELAY=-1", "", "", "");
    provider.getSerializer(DatabaseMeta.class).save(warehouse);

    try (Database db = new Database(new LoggingObject("p1-open-hpl"), variables, warehouse)) {
      db.connect();
      db.execStatement("CREATE TABLE P1_OPEN_HPL_ROWS(ID INT PRIMARY KEY)");
      db.execStatement("INSERT INTO P1_OPEN_HPL_ROWS VALUES(1)");
    }

    TableInputMeta input = new TableInputMeta();
    input.setConnection("Warehouse");
    input.setSql("SELECT ID FROM ${P1_TABLE}");
    PipelineMeta original = new PipelineMeta();
    original.setName("opened-hpl-with-parameter");
    original.addParameterDefinition(
        "P1_TABLE", "P1_OPEN_HPL_ROWS", "table for named parameter regression");
    original.addTransform(new TransformMeta("TableInput", "source", input));

    Path directory = Files.createDirectories(root.resolve("nested"));
    Path path = directory.resolve("opened-hpl-with-parameter.hpl");
    Files.writeString(directory.resolve("relative-resource.txt"), "relative fixture");
    PipelineDocumentStore store = new PipelineDocumentStore(provider, variables);
    store.save(path, original);
    PipelineMeta reopened = store.open(path);
    assertEquals("P1_OPEN_HPL_ROWS", reopened.getParameterDefault("P1_TABLE"));

    PipelineDocumentRegistry documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("opened-doc", path, reopened));
    ExecutionRegistry<IPipelineEngine<PipelineMeta>> executions =
        new ExecutionRegistry<>(Duration.ofHours(1), 16);
    PipelineExecutionResource resource =
        new PipelineExecutionResource(
            documents, new ProductContext(variables, provider), executions);

    var started = resource.start("opened-doc");
    assertEquals(202, started.getStatus());
    var status = (PipelineExecutionResource.ExecutionStatus) started.getEntity();
    IPipelineEngine<PipelineMeta> engine =
        executions.find(status.id()).orElseThrow().execution();

    assertEquals(path.toString(), engine.getPipelineMeta().getFilename());
    assertEquals(
        "opened-hpl-with-parameter.hpl",
        engine.getVariable(Const.INTERNAL_VARIABLE_PIPELINE_FILENAME_NAME));
    assertEquals(
        HopVfs.getFileObject(path.toString()).getName().getParent().getURI(),
        engine.getVariable(Const.INTERNAL_VARIABLE_PIPELINE_FILENAME_DIRECTORY));
    assertTrue(
        HopVfs.getFileObject(
                engine.resolve("${Internal.Pipeline.Filename.Directory}/relative-resource.txt"))
            .exists());
    assertArrayEquals(new String[] {"P1_TABLE"}, engine.listParameters());
    engine.waitUntilFinished();
    assertTrue(engine.isFinished());
    assertEquals("P1_OPEN_HPL_ROWS", engine.getVariable("P1_TABLE"));
    assertEquals(0, engine.getErrors());
  }
}
