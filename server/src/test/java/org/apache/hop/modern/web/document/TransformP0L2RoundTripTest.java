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
  void checkSumPreconditionsMatchNativeCheckResultAndGroupedFieldWire() throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "CheckSum");
    Property selected = property(descriptor, "field");
    assertEquals(Shape.LIST, selected.shape());
    assertEquals("fields", selected.groupKey());
    assertTrue(selected.elementProperties().stream()
        .anyMatch(p -> p.key().equals("name") && p.shape() == Shape.STRING));
    assertEquals(Shape.STRING, property(descriptor, "resultfieldName").shape());
    assertEquals(List.of("CHECKSUM_RESULT_FIELD_REQUIRED",
            "CHECKSUM_SELECTED_FIELDS_REQUIRE_UPSTREAM_FIELDS"),
        descriptor.runtimePreconditions().stream().map(p -> p.code()).toList());
    assertEquals(List.of("/resultfieldName"),
        descriptor.runtimePreconditions().get(0).keyListPointers());
    assertEquals(List.of("/fields/field"),
        descriptor.runtimePreconditions().get(1).keyListPointers());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "CheckSum");
    assertNotNull(plugin);
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    Class<?> fieldType = meta.getClass().getClassLoader()
        .loadClass("org.apache.hop.pipeline.transforms.checksum.Field");
    var setFields = meta.getClass().getMethod("setFields", List.class);
    var setResultFieldName = meta.getClass().getMethod("setResultFieldName", String.class);
    var transform = new org.apache.hop.pipeline.transform.TransformMeta(
        "CheckSum", "checksum-node", meta);
    var upstream = new org.apache.hop.core.row.RowMeta();
    upstream.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("流量"));
    var variables = new org.apache.hop.core.variables.Variables();
    var provider = new MemoryMetadataProvider();
    List<org.apache.hop.core.ICheckResult> remarks = new ArrayList<>();

    setResultFieldName.invoke(meta, "digest");
    setFields.invoke(meta, List.of(fieldType.getConstructor(String.class).newInstance("流量")));
    meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
        upstream, new String[] {"source"}, new String[0], null, variables, provider);
    assertFalse(remarks.stream().anyMatch(
        r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR));
    remarks.clear();

    setFields.invoke(meta, List.of(fieldType.getConstructor(String.class).newInstance("缺失")));
    meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
        upstream, new String[] {"source"}, new String[0], null, variables, provider);
    assertTrue(remarks.stream().anyMatch(
        r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR));
    remarks.clear();

    setFields.invoke(meta, List.of(fieldType.getConstructor(String.class).newInstance("流量")));
    setResultFieldName.invoke(meta, "");
    meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
        upstream, new String[] {"source"}, new String[0], null, variables, provider);
    assertTrue(remarks.stream().anyMatch(
        r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR));
    System.out.println("PROD_P0_CHECKSUM_NATIVE_CHECK_PRECONDITIONS=PASS");
  }

  @Test
  void jsonInputSourceModeAndOutputFieldsPreconditionsMatchNativePluginCheck()
      throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "JsonInput");
    Property fields = descriptor.properties().stream()
        .filter(p -> p.key().equals("field") && p.groupKey().equals("fields"))
        .findFirst().orElseThrow();
    assertEquals(Shape.LIST, fields.shape());
    assertTrue(fields.elementProperties().stream()
        .anyMatch(p -> p.key().equals("path") && p.shape() == Shape.STRING));
    assertEquals(Shape.BOOLEAN, property(descriptor, "IsInFields").shape());
    assertEquals(Shape.STRING, property(descriptor, "valueField").shape());
    assertEquals(List.of("JSON_INPUT_OUTPUT_FIELDS_REQUIRED",
            "JSON_INPUT_FIELD_SOURCE_REQUIRES_INCOMING_HOP_AND_NAME"),
        descriptor.runtimePreconditions().stream().map(p -> p.code()).toList());
    assertEquals(List.of("/fields/field"),
        descriptor.runtimePreconditions().get(0).keyListPointers());
    assertEquals(List.of("/IsInFields", "/valueField"),
        descriptor.runtimePreconditions().get(1).keyListPointers());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "JsonInput");
    assertNotNull(plugin);
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    ClassLoader loader = meta.getClass().getClassLoader();
    Class<?> fieldType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.jsoninput.JsonInputField");
    Object output = fieldType.getConstructor(String.class).newInstance("流量");
    fieldType.getMethod("setPath", String.class).invoke(output, "$.flow");
    var setFields = meta.getClass().getMethod("setInputFields", List.class);
    var setInFields = meta.getClass().getMethod("setInFields", boolean.class);
    var setFieldValue = meta.getClass().getMethod("setFieldValue", String.class);
    var transform = new org.apache.hop.pipeline.transform.TransformMeta(
        "JsonInput", "json-node", meta);
    var row = new org.apache.hop.core.row.RowMeta();
    row.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("payload"));
    var variables = new org.apache.hop.core.variables.Variables();
    var provider = new MemoryMetadataProvider();
    List<org.apache.hop.core.ICheckResult> remarks = new ArrayList<>();

    setInFields.invoke(meta, true);
    setFieldValue.invoke(meta, "payload");
    setFields.invoke(meta, List.of(output));
    meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
        row, new String[] {"upstream"}, new String[0], null, variables, provider);
    assertFalse(remarks.stream().anyMatch(
        r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR));
    remarks.clear();

    setFields.invoke(meta, List.of());
    meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
        row, new String[] {"upstream"}, new String[0], null, variables, provider);
    assertTrue(remarks.stream().anyMatch(
        r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR));
    remarks.clear();

    setFields.invoke(meta, List.of(output));
    setFieldValue.invoke(meta, "");
    meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
        row, new String[] {"upstream"}, new String[0], null, variables, provider);
    assertTrue(remarks.stream().anyMatch(
        r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR));
    remarks.clear();

    setFieldValue.invoke(meta, "payload");
    meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
        row, new String[0], new String[0], null, variables, provider);
    assertTrue(remarks.stream().anyMatch(
        r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR));
    System.out.println("PROD_P0_JSONINPUT_SOURCE_MODE_NATIVE_CHECK=PASS");
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
  void tableInputSpecifiedFieldsPreconditionMatchesRealPluginMetadataBehavior()
      throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "TableInput");
    Property declaredFields = property(descriptor, "field");
    assertEquals(Shape.LIST, declaredFields.shape());
    assertEquals("fields", declaredFields.groupKey());
    assertTrue(declaredFields.elementProperties().stream()
        .anyMatch(p -> p.key().equals("name") && p.shape() == Shape.STRING));
    assertTrue(declaredFields.elementProperties().stream()
        .anyMatch(p -> p.key().equals("type") && p.shape() == Shape.NUMBER));
    assertEquals(Shape.BOOLEAN, property(descriptor, "specify_fields").shape());
    assertEquals(Shape.BOOLEAN, property(descriptor, "validate_specified_fields").shape());
    var precondition = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals("TABLE_INPUT_SPECIFY_FIELDS_REQUIRES_NAMED_OUTPUT_FIELD"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/fields/field"), precondition.keyListPointers());
    assertTrue(precondition.explanation().contains("non-empty name"));
    assertTrue(precondition.explanation().contains("validate_specified_fields"));

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "TableInput");
    assertNotNull(plugin);
    var meta = (org.apache.hop.pipeline.transforms.tableinput.TableInputMeta)
        registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    meta.setSpecifyFields(true);
    var unnamed = new org.apache.hop.pipeline.transforms.tableinput.TableInputField();
    unnamed.setName("");
    meta.setFields(List.of(unnamed));
    assertThrows(org.apache.hop.core.exception.HopTransformException.class,
        () -> meta.createSpecifiedRowMeta("TableInput", null));

    var named = new org.apache.hop.pipeline.transforms.tableinput.TableInputField();
    named.setName("流量");
    named.setType(org.apache.hop.core.row.IValueMeta.TYPE_NUMBER);
    named.setLength(18);
    meta.setFields(List.of(unnamed, named));
    var output = meta.createSpecifiedRowMeta("TableInput", null);
    assertEquals(1, output.size());
    assertEquals("流量", output.getValueMeta(0).getName());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_NUMBER,
        output.getValueMeta(0).getType());
    System.out.println("PROD_P0_TABLEINPUT_SPECIFIED_FIELDS_PRECONDITION=PASS");
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

  @Test
  void selectValuesStageSpecificFieldResolutionMatchesRealPluginAndCanonicalPaths()
      throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "SelectValues");
    assertEquals(Shape.OBJECT, property(descriptor, "fields").shape());
    for (String stage : List.of("field", "remove", "meta")) {
      Property list = property(descriptor, "fields", stage);
      assertEquals(Shape.LIST, list.shape(), stage);
      assertTrue(list.elementProperties().stream()
          .anyMatch(p -> p.key().equals("name") && p.shape() == Shape.STRING), stage);
    }
    var precondition = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals("SELECT_VALUES_STAGE_SPECIFIC_FIELD_RESOLUTION"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/fields/field", "/fields/remove", "/fields/meta"),
        precondition.keyListPointers());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "SelectValues");
    assertNotNull(plugin);
    var meta = (org.apache.hop.pipeline.transforms.selectvalues.SelectValuesMeta)
        registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    var first = new org.apache.hop.pipeline.transforms.selectvalues.SelectField();
    first.setName("input_甲");
    first.setRename("stage_甲");
    var second = new org.apache.hop.pipeline.transforms.selectvalues.SelectField();
    second.setName("input_乙");
    second.setRename("stage_乙");
    meta.getSelectOption().setSelectFields(List.of(first, second));
    var remove = new org.apache.hop.pipeline.transforms.selectvalues.DeleteField();
    remove.setName("stage_乙");
    meta.getSelectOption().setDeleteName(List.of(remove));
    var change = new org.apache.hop.pipeline.transforms.selectvalues.SelectMetadataChange();
    change.setName("stage_甲");
    change.setRename("final_甲");
    change.setStorageType(null);
    meta.getSelectOption().setMeta(List.of(change));

    org.apache.hop.core.row.IRowMeta row = new org.apache.hop.core.row.RowMeta();
    row.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("input_甲"));
    row.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("input_乙"));
    meta.getSelectFields(row, "SelectValues");
    assertEquals(2, row.size());
    assertEquals("stage_甲", row.getValueMeta(0).getName());
    assertEquals("stage_乙", row.getValueMeta(1).getName());
    meta.getDeleteFields(row);
    assertEquals(1, row.size());
    assertEquals("stage_甲", row.getValueMeta(0).getName());
    meta.getMetadataFields(row, "SelectValues", null);
    assertEquals(1, row.size());
    assertEquals("final_甲", row.getValueMeta(0).getName());
    System.out.println("PROD_P0_SELECTVALUES_STAGED_FIELD_RESOLUTION=PASS");
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
