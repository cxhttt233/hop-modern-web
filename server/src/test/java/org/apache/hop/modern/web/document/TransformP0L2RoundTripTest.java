package org.apache.hop.modern.web.document;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.apache.hop.modern.web.document.AutomaticConfigDescriptor.Descriptor;
import org.apache.hop.modern.web.document.AutomaticConfigDescriptor.Property;
import org.apache.hop.modern.web.document.AutomaticConfigDescriptor.Shape;
import org.apache.hop.modern.web.document.AutomaticConfigDescriptor.TextEditorHint;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.plugins.*;
import org.apache.hop.core.xml.XmlHandler;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.modern.web.ModernWebServer;
import org.apache.hop.pipeline.transform.ITransformMeta;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
@EnabledIfSystemProperty(named = "prodP0Expected", matches = "true")
class TransformP0L2RoundTripTest {
  static final List<String> IDS=List.of("CheckSum","ConcatFields","DataGrid","ExecSql","FilterRows","GroupBy","Http","InsertUpdate","JsonInput","MergeJoin","ReplaceString","Rest","ScriptValueMod","SelectValues","SetVariable","StreamLookup","StringCut","TableInput","TableOutput","UniqueRowsByHashSet");
  @BeforeAll static void init() throws Exception { HopClientEnvironment.init(); ModernWebServer.initializePipelinePlugins(); }
  @Test void roundTrips() throws Exception {
    PluginRegistry r=PluginRegistry.getInstance(); int pass=0;
    for(String id:IDS){
      IPlugin p=r.findPluginWithId(TransformPluginType.class,id); assertNotNull(p,"missing "+id);
      try {
        ITransformMeta a=r.loadClass(p,ITransformMeta.class); a.setDefault(); String x=a.getXml();
        ITransformMeta b=r.loadClass(p,ITransformMeta.class); b.loadXml(XmlHandler.wrapLoadXmlString(x),new MemoryMetadataProvider());
        assertEquals(canon(x),canon(b.getXml()),"changed "+id); pass++;
        System.out.println("PROD_P0_L2_STATUS="+id+" status=PASS failure=null");
      } catch(Exception|AssertionError e) {
        System.out.println("PROD_P0_L2_STATUS="+id+" status=FAIL failure="+e.getClass().getName()+": "+e.getMessage()); throw e;
      }
    }
    assertEquals(IDS.size(),pass); System.out.println("PROD_P0_L2_BATCH_PASS="+pass+"/"+IDS.size());
  }
  @Test void tableInputEditedSqlSurvivesRealXmlReopenWithoutChangingOtherFields() throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "TableInput");
    assertNotNull(plugin, "production TableInput plugin missing");
    Descriptor descriptor = AutomaticConfigDescriptor.describe(
        registry.loadClass(plugin, ITransformMeta.class).getClass());
    Property sqlProperty = property(descriptor, "sql");
    assertEquals(Shape.STRING, sqlProperty.shape());
    assertEquals(TextEditorHint.SQL, sqlProperty.textEditorHint());
    String[] sqlCases = {
        "SELECT \"quoted\", '雪/&<>', ${ID}\\nFROM tab\\nWHERE path = 'C:\\\\data'",
        "-- 变量 ${HOP_VAR} 与 Unicode 河流\\nSELECT '单引号''重复', '\\\\', '<&>'\\n"
    };
    for (String sql : sqlCases) {
      ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
      meta.setDefault();
      Field field = metadataField(meta.getClass(), sqlProperty.javaField());
      Object previousSql = field.get(meta);
      String originalXml = canon(meta.getXml());
      field.set(meta, sql);
      String editedXml = meta.getXml();
      assertNotNull(XmlHandler.wrapLoadXmlString(editedXml), "edited XML must remain parseable");
      ITransformMeta reopened = registry.loadClass(plugin, ITransformMeta.class);
      reopened.loadXml(XmlHandler.wrapLoadXmlString(editedXml), new MemoryMetadataProvider());
      Field reopenedField = metadataField(reopened.getClass(), sqlProperty.javaField());
      String actualSql = (String) reopenedField.get(reopened);
      assertArrayEquals(sql.getBytes(StandardCharsets.UTF_8),
          actualSql.getBytes(StandardCharsets.UTF_8), "SQL bytes changed across real Hop XML reopen");
      reopenedField.set(reopened, previousSql);
      assertEquals(originalXml, canon(reopened.getXml()),
          "editing only SQL changed unrelated production XML properties");
    }
    System.out.println("PROD_P0_TABLEINPUT_SQL_XML_REOPEN_UTF8=PASS cases=" + sqlCases.length);
  }

  @Test
  void groupByAggregateSubjectPreconditionExemptsCountAnyAndUsesCanonicalGroupedList()
      throws Exception {
    Descriptor descriptor = descriptorFor(PluginRegistry.getInstance(), "GroupBy");
    Property aggregations = descriptor.properties().stream()
        .filter(p -> p.key().equals("field") && p.groupKey().equals("fields"))
        .findFirst().orElseThrow();
    assertEquals(Shape.LIST, aggregations.shape());
    assertTrue(aggregations.elementProperties().stream()
        .anyMatch(p -> p.key().equals("subject") && p.shape() == Shape.STRING));
    assertEquals(List.of("GROUP_KEYS_CONTIGUOUS",
            "GROUPBY_AGGREGATE_SUBJECT_REQUIRES_INPUT_FIELD"),
        descriptor.runtimePreconditions().stream().map(p -> p.code()).toList());
    var precondition = descriptor.runtimePreconditions().get(1);
    assertEquals(List.of("/fields/field"), precondition.keyListPointers());
    assertTrue(precondition.explanation().contains("COUNT_ANY"));
    assertTrue(precondition.explanation().contains("upstream row metadata"));
    System.out.println("PROD_P0_GROUPBY_AGGREGATE_SUBJECT_PRECONDITION=PASS");
  }

  @Test
  void tableOutputExplicitFieldMappingPreconditionMatchesProductionGroupedDescriptor()
      throws Exception {
    Descriptor descriptor = descriptorFor(PluginRegistry.getInstance(), "TableOutput");
    Property mapping = property(descriptor, "field");
    assertEquals(Shape.LIST, mapping.shape());
    assertEquals("fields", mapping.groupKey());
    assertEquals(List.of("stream_name", "column_name"),
        mapping.elementProperties().stream().map(Property::key).toList());
    var precondition = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals("TABLE_OUTPUT_SPECIFY_FIELDS_REQUIRES_INPUT_STREAM_FIELDS"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/fields/field"), precondition.keyListPointers());
    assertTrue(precondition.explanation().contains("specify_fields"));
    System.out.println("PROD_P0_TABLEOUTPUT_INPUT_MAPPING_PRECONDITION=PASS");
  }

  @Test void nestedAndSensitiveProductionDescriptorsStayTyped() throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor json = descriptorFor(registry, "JsonInput");
    Property fileFilter = property(json, "file", "file", "type_filter");
    assertEquals(Shape.ENUM, fileFilter.shape());
    assertTrue(fileFilter.storeWithCode());
    assertFalse(fileFilter.storeWithName());
    assertFalse(fileFilter.options().isEmpty());
    assertEquals(Shape.OBJECT, property(json, "file").shape());
    assertEquals(Shape.LIST, property(json, "file", "file").shape());
    Descriptor filter = descriptorFor(registry, "FilterRows");
    assertEquals(Shape.ENUM, property(filter, "compare", "condition", "operator").shape());
    assertEquals(Shape.ENUM, property(filter, "compare", "condition", "function").shape());
    assertEquals(Shape.LIST, property(descriptorFor(registry, "ScriptValueMod"), "jsScript").shape());
    assertEquals(TextEditorHint.SCRIPT, property(descriptorFor(registry, "ScriptValueMod"),
        "jsScript", "jsScript_script").textEditorHint());
    assertEquals(TextEditorHint.SQL, property(descriptorFor(registry, "ExecSql"), "sql").textEditorHint());
    assertEquals(TextEditorHint.NONE,
        property(descriptorFor(registry, "UniqueRowsByHashSet"), "error_description").textEditorHint());
    int sensitive = 0, nestedEnums = 0, listElements = 0;
    for (String id : IDS) {
      Descriptor descriptor = descriptorFor(registry, id);
      List<Property> all = new ArrayList<>();
      collectProperties(descriptor.properties(), all);
      for (Property p : all) {
        if (p.sensitive()) {
          sensitive++;
          assertEquals(TextEditorHint.NONE, p.textEditorHint(), id + "." + p.key());
        }
        if (p.shape() == Shape.ENUM) {
          assertFalse(p.options().isEmpty(), id + "." + p.key() + " enum has no options");
          p.options().forEach(o -> { assertNotNull(o.label()); assertNotNull(o.value()); });
          nestedEnums++;
        }
        if (p.shape() == Shape.LIST && !p.elementProperties().isEmpty()) listElements++;
      }
    }
    assertTrue(sensitive >= 3, "production password metadata disappeared");
    assertTrue(nestedEnums >= 4, "production enum metadata disappeared");
    assertTrue(listElements >= 10, "production nested LIST metadata disappeared");
    System.out.println("PROD_P0_NESTED_SENSITIVE_JAVA_CONTRACT=PASS sensitive=" + sensitive
        + " enums=" + nestedEnums + " listElements=" + listElements);
  }


  @Test
  void checkSumEnumCodesSurviveConfigWriteAndRealHplDiskReopen(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "CheckSum");
    assertNotNull(plugin, "production CheckSum plugin missing");

    ITransformMeta meta = plugins.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    Descriptor descriptor = AutomaticConfigDescriptor.describe(meta.getClass());
    Property checksumType = property(descriptor, "checksumtype");
    Property resultType = property(descriptor, "resultType");
    assertEquals(Shape.ENUM, checksumType.shape());
    assertEquals(Shape.ENUM, resultType.shape());
    assertTrue(checksumType.storeWithCode());
    assertTrue(resultType.storeWithCode());
    assertTrue(checksumType.options().stream().anyMatch(
        o -> o.label().equals("SHA256") && o.value().equals("SHA-256")));
    assertTrue(resultType.options().stream().anyMatch(
        o -> o.label().equals("HEXADECIMAL") && o.value().equals("hexadecimal")));

    org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider provider =
        new org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider();
    org.apache.hop.core.variables.Variables variables =
        new org.apache.hop.core.variables.Variables();
    PipelineDocumentStore store = new PipelineDocumentStore(provider, variables);
    java.nio.file.Path hpl = tempDir.resolve("checksum-enum-roundtrip.hpl");
    org.apache.hop.pipeline.PipelineMeta pipeline = new org.apache.hop.pipeline.PipelineMeta();
    pipeline.setName("checksum-enum-roundtrip");
    pipeline.addTransform(new org.apache.hop.pipeline.transform.TransformMeta(
        "CheckSum", "checksum-node", meta));
    store.save(hpl, pipeline);
    org.apache.hop.pipeline.PipelineMeta loaded = store.open(hpl);

    PipelineDocumentRegistry documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("checksum-doc", hpl, loaded));
    PipelineConfigResource resource = new PipelineConfigResource(documents, provider);
    jakarta.ws.rs.core.Response read = resource.read(
        "checksum-doc", new PipelineConfigResource.ReadRequest("checksum-node"));
    assertEquals(200, read.getStatus());
    PipelineConfigResource.ConfigResponse initial =
        (PipelineConfigResource.ConfigResponse) read.getEntity();
    assertEquals("CRC32", initial.config().path("checksumtype").asText());
    assertEquals("string", initial.config().path("resultType").asText());

    com.fasterxml.jackson.databind.node.ObjectNode changed =
        ((com.fasterxml.jackson.databind.node.ObjectNode) initial.config()).deepCopy();
    changed.put("checksumtype", "SHA-256");
    changed.put("resultType", "hexadecimal");
    changed.put("separator", "|雪<&>");
    jakarta.ws.rs.core.Response write = resource.write(
        "checksum-doc", new PipelineConfigResource.WriteRequest("checksum-node", changed));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    org.apache.hop.pipeline.PipelineMeta diskReopened = store.open(hpl);
    org.apache.hop.pipeline.transform.TransformMeta diskTransform =
        diskReopened.findTransform("checksum-node");
    assertNotNull(diskTransform);
    com.fasterxml.jackson.databind.JsonNode diskJson =
        org.apache.hop.metadata.serializer.json.ConfigJsonSerializer.toJson(
            diskTransform.getTransform(), provider);
    assertEquals("SHA-256", diskJson.path("checksumtype").asText());
    assertEquals("hexadecimal", diskJson.path("resultType").asText());
    assertEquals("|雪<&>", diskJson.path("separator").asText());
  }


  @Test
  void concatFieldsNestedObjectAndNumericWireSurviveRealHplDiskReopen(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "ConcatFields");
    assertNotNull(plugin, "production ConcatFields plugin missing");
    ITransformMeta meta = plugins.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();

    Descriptor descriptor = AutomaticConfigDescriptor.describe(meta.getClass());
    assertEquals(Shape.OBJECT, property(descriptor, "ConcatFields").shape());
    assertEquals(Shape.NUMBER,
        property(descriptor, "ConcatFields", "targetFieldLength").shape());
    assertEquals(Shape.BOOLEAN,
        property(descriptor, "ConcatFields", "removeSelectedFields").shape());
    assertEquals(Shape.LIST, property(descriptor, "field").shape());
    assertEquals("fields", property(descriptor, "field").groupKey());
    assertEquals(Shape.NUMBER, property(descriptor, "field", "length").shape());

    org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider provider =
        new org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider();
    org.apache.hop.core.variables.Variables variables =
        new org.apache.hop.core.variables.Variables();
    PipelineDocumentStore store = new PipelineDocumentStore(provider, variables);
    java.nio.file.Path hpl = tempDir.resolve("concatfields-nested-wire.hpl");
    org.apache.hop.pipeline.PipelineMeta pipeline = new org.apache.hop.pipeline.PipelineMeta();
    pipeline.setName("concatfields-nested-wire");
    pipeline.addTransform(new org.apache.hop.pipeline.transform.TransformMeta(
        "ConcatFields", "concat-node", meta));
    store.save(hpl, pipeline);
    org.apache.hop.pipeline.PipelineMeta loaded = store.open(hpl);

    PipelineDocumentRegistry documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("concat-doc", hpl, loaded));
    PipelineConfigResource resource = new PipelineConfigResource(documents, provider);
    jakarta.ws.rs.core.Response read = resource.read(
        "concat-doc", new PipelineConfigResource.ReadRequest("concat-node"));
    assertEquals(200, read.getStatus());
    PipelineConfigResource.ConfigResponse initial =
        (PipelineConfigResource.ConfigResponse) read.getEntity();
    com.fasterxml.jackson.databind.node.ObjectNode changed =
        ((com.fasterxml.jackson.databind.node.ObjectNode) initial.config()).deepCopy();
    com.fasterxml.jackson.databind.node.ObjectNode extra =
        (com.fasterxml.jackson.databind.node.ObjectNode) changed.path("ConcatFields");
    assertNotNull(extra, "native ExtraFields object is required");
    extra.put("targetFieldName", "合并<&>雪");
    extra.put("targetFieldLength", 137);
    extra.put("removeSelectedFields", true);
    changed.put("separator", "|雪<&>");
    changed.put("force_enclosure", true);
    com.fasterxml.jackson.databind.node.ArrayNode fields =
        changed.putObject("fields").putArray("field");
    com.fasterxml.jackson.databind.node.ObjectNode first = fields.addObject();
    first.put("name", "source<&>雪");
    first.put("type", "String");
    first.put("length", 29);
    first.put("precision", 3);

    jakarta.ws.rs.core.Response write = resource.write(
        "concat-doc", new PipelineConfigResource.WriteRequest("concat-node", changed));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    org.apache.hop.pipeline.PipelineMeta reopened = store.open(hpl);
    org.apache.hop.pipeline.transform.TransformMeta diskTransform =
        reopened.findTransform("concat-node");
    assertNotNull(diskTransform);
    com.fasterxml.jackson.databind.JsonNode disk =
        org.apache.hop.metadata.serializer.json.ConfigJsonSerializer.toJson(
            diskTransform.getTransform(), provider);
    assertEquals("合并<&>雪", disk.path("ConcatFields").path("targetFieldName").asText());
    assertTrue(disk.path("ConcatFields").path("targetFieldLength").isIntegralNumber());
    assertEquals(137, disk.path("ConcatFields").path("targetFieldLength").intValue());
    assertTrue(disk.path("ConcatFields").path("removeSelectedFields").isBoolean());
    assertTrue(disk.path("ConcatFields").path("removeSelectedFields").booleanValue());
    assertEquals("|雪<&>", disk.path("separator").asText());
    assertTrue(disk.path("force_enclosure").booleanValue());
    com.fasterxml.jackson.databind.JsonNode diskFields = disk.path("fields").path("field");
    assertTrue(diskFields.isArray());
    assertEquals(1, diskFields.size());
    assertEquals("source<&>雪", diskFields.get(0).path("name").asText());
    assertTrue(diskFields.get(0).path("length").isIntegralNumber());
    assertEquals(29, diskFields.get(0).path("length").intValue());
    assertEquals(3, diskFields.get(0).path("precision").intValue());
  }


  @Test
  void streamLookupTwoNestedListsAndDuplicateLeafNamesSurviveRealHplDiskReopen(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "StreamLookup");
    assertNotNull(plugin, "production StreamLookup plugin missing");
    ITransformMeta meta = plugins.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();

    Descriptor descriptor = AutomaticConfigDescriptor.describe(meta.getClass());
    assertEquals(Shape.OBJECT, property(descriptor, "lookup").shape());
    assertEquals(Shape.LIST, property(descriptor, "lookup", "key").shape());
    assertEquals(Shape.LIST, property(descriptor, "lookup", "value").shape());
    assertEquals(Shape.STRING, property(descriptor, "lookup", "key", "name").shape());
    assertEquals(Shape.STRING, property(descriptor, "lookup", "value", "name").shape());
    assertEquals(Shape.STRING, property(descriptor, "lookup", "key", "field").shape());
    assertEquals(Shape.STRING, property(descriptor, "lookup", "value", "rename").shape());

    org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider provider =
        new org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider();
    org.apache.hop.core.variables.Variables variables =
        new org.apache.hop.core.variables.Variables();
    PipelineDocumentStore store = new PipelineDocumentStore(provider, variables);
    java.nio.file.Path hpl = tempDir.resolve("streamlookup-nested-lists.hpl");
    org.apache.hop.pipeline.PipelineMeta pipeline = new org.apache.hop.pipeline.PipelineMeta();
    pipeline.setName("streamlookup-nested-lists");
    pipeline.addTransform(new org.apache.hop.pipeline.transform.TransformMeta(
        "StreamLookup", "streamlookup-node", meta));
    store.save(hpl, pipeline);
    org.apache.hop.pipeline.PipelineMeta loaded = store.open(hpl);

    PipelineDocumentRegistry documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("streamlookup-doc", hpl, loaded));
    PipelineConfigResource resource = new PipelineConfigResource(documents, provider);
    jakarta.ws.rs.core.Response read = resource.read(
        "streamlookup-doc", new PipelineConfigResource.ReadRequest("streamlookup-node"));
    assertEquals(200, read.getStatus());
    PipelineConfigResource.ConfigResponse initial =
        (PipelineConfigResource.ConfigResponse) read.getEntity();
    com.fasterxml.jackson.databind.node.ObjectNode changed =
        ((com.fasterxml.jackson.databind.node.ObjectNode) initial.config()).deepCopy();
    changed.put("input_sorted", true);
    changed.put("preserve_memory", false);

    com.fasterxml.jackson.databind.node.ObjectNode lookup =
        (com.fasterxml.jackson.databind.node.ObjectNode) changed.path("lookup");
    assertNotNull(lookup, "native StreamLookup lookup object is required");
    com.fasterxml.jackson.databind.node.ArrayNode keys = lookup.putArray("key");
    keys.addObject().put("name", "主键<&>雪").put("field", "查找A");
    keys.addObject().put("name", "secondary").put("field", "查找B");
    com.fasterxml.jackson.databind.node.ArrayNode values = lookup.putArray("value");
    values.addObject().put("name", "返回<&>雪").put("rename", "结果<&>雪")
        .put("default", "未命中<&>");
    values.addObject().put("name", "another").put("rename", "备用")
        .put("default", "fallback");

    jakarta.ws.rs.core.Response write = resource.write(
        "streamlookup-doc",
        new PipelineConfigResource.WriteRequest("streamlookup-node", changed));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    org.apache.hop.pipeline.PipelineMeta reopened = store.open(hpl);
    org.apache.hop.pipeline.transform.TransformMeta diskTransform =
        reopened.findTransform("streamlookup-node");
    assertNotNull(diskTransform);
    com.fasterxml.jackson.databind.JsonNode disk =
        org.apache.hop.metadata.serializer.json.ConfigJsonSerializer.toJson(
            diskTransform.getTransform(), provider);
    assertTrue(disk.path("input_sorted").isBoolean());
    assertTrue(disk.path("input_sorted").booleanValue());
    assertTrue(disk.path("preserve_memory").isBoolean());
    assertFalse(disk.path("preserve_memory").booleanValue());
    assertEquals(2, disk.at("/lookup/key").size());
    assertEquals(2, disk.at("/lookup/value").size());
    assertEquals("主键<&>雪", disk.at("/lookup/key/0/name").asText());
    assertEquals("查找A", disk.at("/lookup/key/0/field").asText());
    assertEquals("secondary", disk.at("/lookup/key/1/name").asText());
    assertEquals("查找B", disk.at("/lookup/key/1/field").asText());
    assertEquals("返回<&>雪", disk.at("/lookup/value/0/name").asText());
    assertEquals("结果<&>雪", disk.at("/lookup/value/0/rename").asText());
    assertEquals("未命中<&>", disk.at("/lookup/value/0/default").asText());
    assertEquals("another", disk.at("/lookup/value/1/name").asText());
    assertEquals("备用", disk.at("/lookup/value/1/rename").asText());
    assertEquals("fallback", disk.at("/lookup/value/1/default").asText());
  }


  @Test
  void jsonInputOrderedJsonPathFieldsSurviveRealHplDiskReopen(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "JsonInput");
    assertNotNull(plugin, "production JsonInput plugin missing");
    ITransformMeta meta = plugins.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();

    Descriptor descriptor = AutomaticConfigDescriptor.describe(meta.getClass());
    assertEquals(Shape.LIST, property(descriptor, "field").shape());
    assertEquals("fields", property(descriptor, "field").groupKey());
    assertEquals(Shape.STRING, property(descriptor, "field", "path").shape());
    assertEquals(Shape.NUMBER, property(descriptor, "field", "length").shape());
    assertEquals(Shape.BOOLEAN, property(descriptor, "field", "repeat").shape());

    org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider provider =
        new org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider();
    PipelineDocumentStore store = new PipelineDocumentStore(
        provider, new org.apache.hop.core.variables.Variables());
    java.nio.file.Path hpl = tempDir.resolve("jsoninput-jsonpath-fields.hpl");
    org.apache.hop.pipeline.PipelineMeta pipeline = new org.apache.hop.pipeline.PipelineMeta();
    pipeline.setName("jsoninput-jsonpath-fields");
    pipeline.addTransform(new org.apache.hop.pipeline.transform.TransformMeta(
        "JsonInput", "jsoninput-node", meta));
    store.save(hpl, pipeline);
    org.apache.hop.pipeline.PipelineMeta loaded = store.open(hpl);

    PipelineDocumentRegistry documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("jsoninput-doc", hpl, loaded));
    PipelineConfigResource resource = new PipelineConfigResource(documents, provider);
    jakarta.ws.rs.core.Response read = resource.read(
        "jsoninput-doc", new PipelineConfigResource.ReadRequest("jsoninput-node"));
    assertEquals(200, read.getStatus());
    PipelineConfigResource.ConfigResponse initial =
        (PipelineConfigResource.ConfigResponse) read.getEntity();
    com.fasterxml.jackson.databind.node.ObjectNode changed =
        ((com.fasterxml.jackson.databind.node.ObjectNode) initial.config()).deepCopy();
    changed.put("defaultPathLeafToNull", true);
    changed.put("ignoreMissingPath", false);
    com.fasterxml.jackson.databind.node.ArrayNode fields =
        changed.putObject("fields").putArray("field");
    fields.addObject()
        .put("name", "名称<&>雪")
        .put("path", "$.items[0]['雪<&>']")
        .put("length", 257)
        .put("repeat", true);
    fields.addObject()
        .put("name", "金额")
        .put("path", "$.items[1].amount")
        .put("length", 19)
        .put("repeat", false);

    jakarta.ws.rs.core.Response write = resource.write(
        "jsoninput-doc", new PipelineConfigResource.WriteRequest("jsoninput-node", changed));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    org.apache.hop.pipeline.PipelineMeta reopened = store.open(hpl);
    org.apache.hop.pipeline.transform.TransformMeta diskTransform =
        reopened.findTransform("jsoninput-node");
    assertNotNull(diskTransform);
    com.fasterxml.jackson.databind.JsonNode disk =
        org.apache.hop.metadata.serializer.json.ConfigJsonSerializer.toJson(
            diskTransform.getTransform(), provider);
    assertTrue(disk.path("defaultPathLeafToNull").isBoolean());
    assertTrue(disk.path("defaultPathLeafToNull").booleanValue());
    assertTrue(disk.path("ignoreMissingPath").isBoolean());
    assertFalse(disk.path("ignoreMissingPath").booleanValue());
    com.fasterxml.jackson.databind.JsonNode diskFields = disk.at("/fields/field");
    assertTrue(diskFields.isArray());
    assertEquals(2, diskFields.size());
    assertEquals("名称<&>雪", disk.at("/fields/field/0/name").asText());
    assertEquals("$.items[0]['雪<&>']", disk.at("/fields/field/0/path").asText());
    assertTrue(disk.at("/fields/field/0/length").isIntegralNumber());
    assertEquals(257, disk.at("/fields/field/0/length").intValue());
    assertTrue(disk.at("/fields/field/0/repeat").booleanValue());
    assertEquals("金额", disk.at("/fields/field/1/name").asText());
    assertEquals("$.items[1].amount", disk.at("/fields/field/1/path").asText());
    assertEquals(19, disk.at("/fields/field/1/length").intValue());
    assertTrue(disk.at("/fields/field/1/repeat").isBoolean());
    assertFalse(disk.at("/fields/field/1/repeat").booleanValue());
  }

  private static Descriptor descriptorFor(PluginRegistry registry, String id) throws Exception {
    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, id);
    assertNotNull(plugin, "missing production plugin " + id);
    return AutomaticConfigDescriptor.describe(
        registry.loadClass(plugin, ITransformMeta.class).getClass());
  }

  private static Property property(Descriptor descriptor, String... keys) {
    List<Property> current = descriptor.properties();
    Property found = null;
    for (String key : keys) {
      Property match = null;
      for (Property candidate : current) if (candidate.key().equals(key)) { match = candidate; break; }
      assertNotNull(match, "missing production property " + String.join(".", keys));
      found = match;
      current = found.shape() == Shape.LIST ? found.elementProperties() : found.children();
    }
    return found;
  }

  private static Field metadataField(Class<?> type, String javaField) throws Exception {
    for (Class<?> current = type; current != null; current = current.getSuperclass()) {
      try { Field field = current.getDeclaredField(javaField); field.setAccessible(true); return field; }
      catch (NoSuchFieldException ignored) { /* look in the inherited Meta class */ }
    }
    throw new NoSuchFieldException(type.getName() + "." + javaField);
  }

  private static void collectProperties(List<Property> source, List<Property> output) {
    for (Property p : source) {
      output.add(p);
      collectProperties(p.children(), output);
      collectProperties(p.elementProperties(), output);
    }
  }
  static String canon(String x) throws Exception { return XmlHandler.formatNode(XmlHandler.wrapLoadXmlString(x)); }
}
