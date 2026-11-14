package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.json.ConfigJsonSerializer;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.modern.web.ModernWebServer;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.pipeline.transforms.sort.SortRowsMeta;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SortRows must use the native Hop serializer, not a UI-only JSON fixture.
 * This covers scalar editing alongside the native field-list descriptor.
 */
class SortRowsNativeRoundTripTest {
  @BeforeAll
  static void init() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void scalarConfigSurvivesNativeWriteSaveAndDiskReopen(@TempDir Path dir) throws Exception {
    var provider = new MemoryMetadataProvider();
    var meta = new SortRowsMeta();
    meta.setDefault();
    var pipeline = new PipelineMeta();
    pipeline.setName("sortrows-native-roundtrip");
    pipeline.addTransform(new TransformMeta("SortRows", "sort", meta));
    var store = new PipelineDocumentStore(provider, new Variables());
    Path file = dir.resolve("sortrows-native.hpl");
    store.save(file, pipeline);

    var opened = store.open(file);
    var registry = new PipelineDocumentRegistry();
    registry.put(new PipelineDocument("sort-doc", file, opened));
    var resource = new PipelineConfigResource(registry, provider);
    var read = (PipelineConfigResource.ConfigResponse) resource.read(
        "sort-doc", new PipelineConfigResource.ReadRequest("sort")).getEntity();
    assertEquals("SortRows", read.pluginId());
    assertEquals("/fields/field", read.descriptor().wireLists().get(0).listPointer());

    ObjectNode draft = ((ObjectNode) read.config()).deepCopy();
    draft.put("directory", "/tmp/hop-sortrows-native");
    draft.put("compress", true);
    assertEquals(200, resource.write("sort-doc",
        new PipelineConfigResource.WriteRequest("sort", draft)).getStatus());

    store.save(file, opened);
    var disk = store.open(file);
    var restored = (SortRowsMeta) disk.findTransform("sort").getTransform();
    var wire = ConfigJsonSerializer.toJson(restored, provider);
    assertEquals("/tmp/hop-sortrows-native", wire.path("directory").asText());
    assertTrue(wire.path("compress").asBoolean());
    assertTrue(wire.path("field").isMissingNode(),
        "native field list must not be duplicated at the JSON root");
  }
}