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
  @Test
  void dataGridDeclaredFieldFormatsAndPrecisionPropagateToNativeOutputMetadata()
      throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "DataGrid");
    Property fields = property(descriptor, "field");
    assertEquals(Shape.LIST, fields.shape());
    assertEquals("fields", fields.groupKey());
    for (String key : List.of("name", "type", "format", "currency", "group", "decimal")) {
      assertEquals(Shape.STRING, property(descriptor, "field", key).shape(), key);
    }
    for (String key : List.of("length", "precision")) {
      assertEquals(Shape.NUMBER, property(descriptor, "field", key).shape(), key);
    }
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "DATA_GRID_DECLARED_FIELD_FORMATS_PROPAGATE_TO_OUTPUT_METADATA"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/fields/field"), contract.keyListPointers());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "DataGrid");
    assertNotNull(plugin, "production DataGrid plugin missing");
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    Class<?> fieldClass = meta.getClass().getClassLoader().loadClass(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta");
    Object flow = fieldClass.getConstructor().newInstance();
    fieldClass.getMethod("setName", String.class).invoke(flow, "流量<&>雪");
    fieldClass.getMethod("setType", String.class).invoke(flow,
        org.apache.hop.core.row.value.ValueMetaFactory.getValueMetaName(
            org.apache.hop.core.row.IValueMeta.TYPE_NUMBER));
    fieldClass.getMethod("setLenght", int.class).invoke(flow, 18);
    fieldClass.getMethod("setPrecision", int.class).invoke(flow, 3);
    fieldClass.getMethod("setFormat", String.class).invoke(flow, "#,##0.000");
    fieldClass.getMethod("setCurrency", String.class).invoke(flow, "¥");
    fieldClass.getMethod("setGroup", String.class).invoke(flow, ",");
    fieldClass.getMethod("setDecimal", String.class).invoke(flow, ".");

    Object literalName = fieldClass.getConstructor().newInstance();
    fieldClass.getMethod("setName", String.class).invoke(literalName, "${GRID_NAME}");
    fieldClass.getMethod("setType", String.class).invoke(literalName,
        org.apache.hop.core.row.value.ValueMetaFactory.getValueMetaName(
            org.apache.hop.core.row.IValueMeta.TYPE_STRING));
    fieldClass.getMethod("setLenght", int.class).invoke(literalName, 64);
    meta.getClass().getMethod("setDataGridFields", List.class)
        .invoke(meta, List.of(flow, literalName));

    var variables = new org.apache.hop.core.variables.Variables();
    variables.setVariable("GRID_NAME", "解析后的名字");
    var row = new org.apache.hop.core.row.RowMeta();
    row.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("已有字段"));
    meta.getFields(row, "data-grid-output", null, null,
        variables, new MemoryMetadataProvider());

    assertEquals(3, row.size());
    assertEquals("已有字段", row.getValueMeta(0).getName());
    var number = row.getValueMeta(1);
    assertEquals("流量<&>雪", number.getName());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_NUMBER, number.getType());
    assertEquals(18, number.getLength());
    assertEquals(3, number.getPrecision());
    assertEquals("#,##0.000", number.getConversionMask());
    assertEquals("¥", number.getCurrencySymbol());
    assertEquals(",", number.getGroupingSymbol());
    assertEquals(".", number.getDecimalSymbol());
    assertEquals("data-grid-output", number.getOrigin());

    var string = row.getValueMeta(2);
    assertEquals("${GRID_NAME}", string.getName(),
        "DataGrid output names remain literal even when variables are defined");
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_STRING, string.getType());
    assertEquals(64, string.getLength());
    assertEquals("data-grid-output", string.getOrigin());
    System.out.println("PROD_P0_DATAGRID_DECLARED_FORMAT_OUTPUT_META=PASS");
  }

  @Test
  void dataGridUnknownDeclaredFieldTypesFallbackToStringAndUnnamedFieldsAreSkipped()
      throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "DataGrid");
    Property declaredFields = property(descriptor, "field");
    assertEquals(Shape.LIST, declaredFields.shape());
    assertEquals("fields", declaredFields.groupKey());
    assertEquals(Shape.STRING, property(descriptor, "field", "name").shape());
    assertEquals(Shape.STRING, property(descriptor, "field", "type").shape());
    var precondition = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals("DATA_GRID_UNKNOWN_FIELD_TYPES_FALLBACK_TO_STRING"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/fields/field"), precondition.keyListPointers());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "DataGrid");
    assertNotNull(plugin);
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    // Keep the fixture independent of the DataGrid artifact at compile time:
    // only the production P0 runtime installs the full 20-transform plugin set.
    Class<?> fieldClass = Class.forName(
        "org.apache.hop.pipeline.transforms.datagrid.DataGridFieldMeta",
        true, meta.getClass().getClassLoader());
    String[] names = {"测站流量", "未知类型字段", "未指定类型字段", ""};
    String[] types = {
        org.apache.hop.core.row.value.ValueMetaFactory.getValueMetaName(
            org.apache.hop.core.row.IValueMeta.TYPE_NUMBER),
        "NOT_A_HOP_VALUE_META_TYPE", null, "Number"
    };
    List<Object> fields = new ArrayList<>();
    for (int i = 0; i < names.length; i++) {
      Object field = fieldClass.getConstructor().newInstance();
      fieldClass.getMethod("setName", String.class).invoke(field, names[i]);
      fieldClass.getMethod("setType", String.class).invoke(field, (Object) types[i]);
      fields.add(field);
    }
    meta.getClass().getMethod("setDataGridFields", List.class).invoke(meta, fields);

    var output = new org.apache.hop.core.row.RowMeta();
    meta.getFields(output, "DataGrid", null, null,
        new org.apache.hop.core.variables.Variables(), new MemoryMetadataProvider());
    assertEquals(3, output.size(), "A field with an empty name is omitted");
    assertEquals("测站流量", output.getValueMeta(0).getName());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_NUMBER,
        output.getValueMeta(0).getType());
    assertEquals("未知类型字段", output.getValueMeta(1).getName());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_STRING,
        output.getValueMeta(1).getType(), "Unknown type silently falls back to STRING");
    assertEquals("未指定类型字段", output.getValueMeta(2).getName());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_STRING,
        output.getValueMeta(2).getType(), "Null type silently falls back to STRING");
    System.out.println("PROD_P0_DATAGRID_UNKNOWN_TYPE_NATIVE_FALLBACK=PASS");
  }

  @Test
  void concatFieldsTargetAndSelectedSourceNativeChecksRespectErrorWarningDistinction()
      throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "ConcatFields");
    assertEquals(Shape.STRING, property(descriptor, "ConcatFields", "targetFieldName").shape());
    assertEquals(Shape.NUMBER, property(descriptor, "ConcatFields", "targetFieldLength").shape());
    Property selected = property(descriptor, "field");
    assertEquals(Shape.LIST, selected.shape());
    assertEquals("fields", selected.groupKey());
    assertTrue(selected.elementProperties().stream()
        .anyMatch(p -> p.key().equals("name") && p.shape() == Shape.STRING));
    assertEquals(List.of(
            "CONCAT_FIELDS_TARGET_NAME_REQUIRED_LENGTH_WARNING",
            "CONCAT_FIELDS_SELECTED_SOURCE_FIELDS_REQUIRE_UPSTREAM_FIELDS",
            "CONCAT_FIELDS_REMOVE_SELECTED_FIELDS_CHANGES_OUTPUT_SCHEMA",
            "CONCAT_FIELDS_LITERAL_TARGET_NAME_AUTO_SUFFIXES_COLLISIONS"),
        descriptor.runtimePreconditions().stream().map(p -> p.code()).toList());
    assertEquals(List.of("/ConcatFields/targetFieldName", "/ConcatFields/targetFieldLength"),
        descriptor.runtimePreconditions().get(0).keyListPointers());
    assertEquals(List.of("/fields/field"),
        descriptor.runtimePreconditions().get(1).keyListPointers());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "ConcatFields");
    assertNotNull(plugin);
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    Object extra = meta.getClass().getMethod("getExtraFields").invoke(meta);
    var setTargetName = extra.getClass().getMethod("setTargetFieldName", String.class);
    var setTargetLength = extra.getClass().getMethod("setTargetFieldLength", int.class);
    var setOutputFields = meta.getClass().getMethod("setOutputFields", List.class);
    var transform = new org.apache.hop.pipeline.transform.TransformMeta(
        "ConcatFields", "concat-native", meta);
    var row = new org.apache.hop.core.row.RowMeta();
    row.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("字段甲"));
    row.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("字段乙"));
    var variables = new org.apache.hop.core.variables.Variables();
    var provider = new MemoryMetadataProvider();
    List<org.apache.hop.core.ICheckResult> remarks = new ArrayList<>();

    meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
        row, new String[] {"source"}, new String[0], null, variables, provider);
    assertEquals(1, remarks.stream().filter(
        r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR).count(),
        "Missing target name must be an ERROR");
    assertEquals(1, remarks.stream().filter(
        r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_WARNING).count(),
        "Zero target length must be a WARNING, not an ERROR");

    setTargetName.invoke(extra, "合并字段");
    remarks.clear();
    meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
        row, new String[] {"source"}, new String[0], null, variables, provider);
    assertFalse(remarks.stream().anyMatch(
        r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR),
        "An empty selection means all input fields, not a missing selection error");
    assertTrue(remarks.stream().anyMatch(
        r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_WARNING));

    setTargetLength.invoke(extra, 128);
    Class<?> fieldClass = meta.getClass().getClassLoader().loadClass(
        "org.apache.hop.pipeline.transforms.concatfields.ConcatField");
    Object selectedField = fieldClass.getConstructor().newInstance();
    var setSourceName = fieldClass.getMethod("setName", String.class);
    setSourceName.invoke(selectedField, "字段甲");
    setOutputFields.invoke(meta, List.of(selectedField));
    remarks.clear();
    meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
        row, new String[] {"source"}, new String[0], null, variables, provider);
    assertFalse(remarks.stream().anyMatch(
        r -> r.getType() != org.apache.hop.core.ICheckResult.TYPE_RESULT_OK),
        "Valid target, length and selected upstream field must pass native checks");

    setSourceName.invoke(selectedField, "不存在的字段");
    remarks.clear();
    meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
        row, new String[] {"source"}, new String[0], null, variables, provider);
    assertEquals(1, remarks.stream().filter(
        r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR).count(),
        "Unknown selected upstream field must be an ERROR");
    assertFalse(remarks.stream().anyMatch(
        r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_WARNING));
    System.out.println("PROD_P0_CONCATFIELDS_TARGET_AND_SOURCE_NATIVE_CHECK=PASS");
  }

  @Test
  void httpResultFieldNamesKeepLiteralBodyButResolveStatusTimingAndHeaders() throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "Http");
    assertEquals(Shape.OBJECT, property(descriptor, "result").shape());
    for (String key : List.of("name", "code", "response_time", "response_header")) {
      assertEquals(Shape.STRING, property(descriptor, "result", key).shape());
    }
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals("HTTP_RESULT_FIELD_NAMES_MIX_LITERAL_AND_RESOLVED_VARIABLES"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/result/name", "/result/code", "/result/response_time",
        "/result/response_header"), contract.keyListPointers());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "Http");
    assertNotNull(plugin, "production HTTP plugin missing");
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    Object result = meta.getClass().getMethod("getResultFields").invoke(meta);
    Class<?> resultType = result.getClass();
    resultType.getMethod("setFieldName", String.class).invoke(result, "${HTTP_BODY_FIELD}");
    resultType.getMethod("setResultCodeFieldName", String.class)
        .invoke(result, "${HTTP_STATUS_FIELD}");
    resultType.getMethod("setResponseTimeFieldName", String.class)
        .invoke(result, "${HTTP_TIME_FIELD}");
    resultType.getMethod("setResponseHeaderFieldName", String.class)
        .invoke(result, "${HTTP_HEADER_FIELD}");
    var variables = new org.apache.hop.core.variables.Variables();
    variables.setVariable("HTTP_BODY_FIELD", "响应正文");
    variables.setVariable("HTTP_STATUS_FIELD", "状态码<&>雪");
    variables.setVariable("HTTP_TIME_FIELD", "耗时毫秒");
    variables.setVariable("HTTP_HEADER_FIELD", "响应头");
    var provider = new MemoryMetadataProvider();
    var output = new org.apache.hop.core.row.RowMeta();
    output.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("上游字段"));
    meta.getFields(output, "http-native", null, null, variables, provider);
    assertEquals(5, output.size());
    assertEquals("${HTTP_BODY_FIELD}", output.getValueMeta(1).getName(),
        "HTTP body output name is literal, unlike the other result names");
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_STRING,
        output.getValueMeta(1).getType());
    assertEquals("状态码<&>雪", output.getValueMeta(2).getName());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_INTEGER,
        output.getValueMeta(2).getType());
    assertEquals("耗时毫秒", output.getValueMeta(3).getName());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_INTEGER,
        output.getValueMeta(3).getType());
    assertEquals("响应头", output.getValueMeta(4).getName());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_STRING,
        output.getValueMeta(4).getType());

    resultType.getMethod("setFieldName", String.class).invoke(result, "");
    var noBody = new org.apache.hop.core.row.RowMeta();
    meta.getFields(noBody, "http-native", null, null, variables, provider);
    assertEquals(3, noBody.size(), "Empty result.name must omit only the body column");
    assertEquals("状态码<&>雪", noBody.getValueMeta(0).getName());

    resultType.getMethod("setResultCodeFieldName", String.class).invoke(result, "");
    resultType.getMethod("setResponseTimeFieldName", String.class).invoke(result, "");
    resultType.getMethod("setResponseHeaderFieldName", String.class).invoke(result, "");
    var empty = new org.apache.hop.core.row.RowMeta();
    meta.getFields(empty, "http-native", null, null, variables, provider);
    assertEquals(0, empty.size(), "All empty result names must add no output fields");
    System.out.println("PROD_P0_HTTP_RESULT_FIELD_VARIABLE_RESOLUTION=PASS");
  }

  @Test
  void httpResultFieldsDistinguishRawEmptyNamesFromResolvedEmptyVariables()
      throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "Http");
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "HTTP_RESULT_FIELD_NAMES_MIX_LITERAL_AND_RESOLVED_VARIABLES"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/result/name", "/result/code", "/result/response_time",
        "/result/response_header"), contract.keyListPointers());
    assertTrue(contract.explanation().contains("before variable resolution"));
    assertTrue(contract.explanation().contains("after variable resolution"));

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "Http");
    assertNotNull(plugin, "production HTTP plugin missing");
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    Object result = meta.getClass().getMethod("getResultFields").invoke(meta);
    Class<?> resultType = result.getClass();
    resultType.getMethod("setFieldName", String.class).invoke(result, "${HTTP_EMPTY}");
    resultType.getMethod("setResultCodeFieldName", String.class)
        .invoke(result, "${HTTP_EMPTY}");
    resultType.getMethod("setResponseTimeFieldName", String.class)
        .invoke(result, "${HTTP_TIME}");
    resultType.getMethod("setResponseHeaderFieldName", String.class)
        .invoke(result, "${HTTP_EMPTY}");

    var variables = new org.apache.hop.core.variables.Variables();
    variables.setVariable("HTTP_EMPTY", "");
    variables.setVariable("HTTP_TIME", "耗时<&>雪");
    var provider = new MemoryMetadataProvider();
    var row = new org.apache.hop.core.row.RowMeta();
    row.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("上游字段"));
    meta.getFields(row, "http-empty", null, null, variables, provider);

    assertEquals(4, row.size(), "Resolved-empty header is omitted, but status is not");
    assertEquals("上游字段", row.getValueMeta(0).getName());
    assertEquals("${HTTP_EMPTY}", row.getValueMeta(1).getName(),
        "Body output name remains literal even when the variable resolves empty");
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_STRING,
        row.getValueMeta(1).getType());
    assertEquals("", row.getValueMeta(2).getName(),
        "Status raw non-empty placeholder is checked before variable resolution");
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_INTEGER,
        row.getValueMeta(2).getType());
    assertEquals("耗时<&>雪", row.getValueMeta(3).getName());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_INTEGER,
        row.getValueMeta(3).getType());

    resultType.getMethod("setFieldName", String.class).invoke(result, "");
    resultType.getMethod("setResultCodeFieldName", String.class).invoke(result, "");
    resultType.getMethod("setResponseTimeFieldName", String.class).invoke(result, "");
    var noResults = new org.apache.hop.core.row.RowMeta();
    noResults.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("上游字段"));
    meta.getFields(noResults, "http-empty", null, null, variables, provider);
    assertEquals(1, noResults.size(),
        "Raw-empty body/code/time and resolved-empty header add no columns");
    System.out.println("PROD_P0_HTTP_RAW_VS_RESOLVED_EMPTY_RESULT_NAMES=PASS");
  }

  @Test void httpDynamicUrlFieldNativeCheckFalsePositiveIsDocumentedByProductionDescriptor()
      throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "Http");
    assertEquals(Shape.BOOLEAN, property(descriptor, "urlInField").shape());
    assertEquals(Shape.STRING, property(descriptor, "urlField").shape());
    assertEquals(Shape.STRING, property(descriptor, "url").shape());
    var precondition = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals("HTTP_DYNAMIC_URL_NATIVE_CHECK_FALSE_POSITIVE"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/urlInField", "/urlField", "/url"),
        precondition.keyListPointers());
    assertTrue(precondition.explanation().contains("native-check false positive"));
    assertTrue(precondition.explanation().contains("incoming row field"));

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "Http");
    assertNotNull(plugin);
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    metadataField(meta.getClass(), "url").set(meta, "https://example.invalid/");
    metadataField(meta.getClass(), "urlInField").setBoolean(meta, false);
    var urlField = metadataField(meta.getClass(), "urlField");
    urlField.set(meta, "endpoint_甲");
    var transform = new org.apache.hop.pipeline.transform.TransformMeta(
        "Http", "http-node", meta);
    var row = new org.apache.hop.core.row.RowMeta();
    row.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("endpoint_甲"));
    var variables = new org.apache.hop.core.variables.Variables();
    var provider = new MemoryMetadataProvider();
    List<org.apache.hop.core.ICheckResult> remarks = new ArrayList<>();

    meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
        row, new String[] {"source"}, new String[0], null, variables, provider);
    assertEquals(2, remarks.size());
    assertTrue(remarks.stream().allMatch(
        x -> x.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_OK),
        "Static HTTP URL with an incoming stream must pass the native check");

    metadataField(meta.getClass(), "urlInField").setBoolean(meta, true);
    remarks.clear();
    meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
        row, new String[] {"source"}, new String[0], null, variables, provider);
    assertEquals(2, remarks.size());
    assertEquals(org.apache.hop.core.ICheckResult.TYPE_RESULT_OK, remarks.get(0).getType());
    assertEquals(org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR, remarks.get(1).getType(),
        "Native HttpMeta.check incorrectly marks a present URL field as ERROR");

    urlField.set(meta, "");
    remarks.clear();
    meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
        row, new String[] {"source"}, new String[0], null, variables, provider);
    assertEquals(2, remarks.size());
    assertEquals(org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR, remarks.get(1).getType(),
        "An empty URL field is a genuine native-check error");

    urlField.set(meta, "endpoint_甲");
    remarks.clear();
    meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
        row, new String[0], new String[0], null, variables, provider);
    assertEquals(org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR, remarks.get(0).getType(),
        "HTTP requires an incoming stream even with a valid URL field");
    System.out.println("PROD_P0_HTTP_DYNAMIC_URL_NATIVE_CHECK_COMPATIBILITY=PASS");
  }

  @Test
  void restBinaryResultFlagChangesNativeOutputMetadataType() throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "Rest");
    assertEquals(Shape.OBJECT, property(descriptor, "result").shape());
    assertEquals(Shape.STRING, property(descriptor, "result", "name").shape());
    assertEquals(Shape.BOOLEAN, property(descriptor, "result", "binary").shape());
    assertEquals(Shape.STRING, property(descriptor, "result", "code").shape());
    assertEquals(Shape.STRING, property(descriptor, "result", "response_time").shape());
    assertEquals(Shape.STRING, property(descriptor, "result", "response_header").shape());
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals("REST_BINARY_RESULT_FIELD_CHANGES_OUTPUT_ROW_TYPE"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/result/name", "/result/binary", "/result/code",
        "/result/response_time", "/result/response_header"), contract.keyListPointers());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "Rest");
    assertNotNull(plugin, "production REST plugin missing");
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    Object result = metadataField(meta.getClass(), "resultField").get(meta);
    result.getClass().getMethod("setFieldName", String.class)
        .invoke(result, "${REST_BODY_FIELD}");
    result.getClass().getMethod("setCode", String.class).invoke(result, "状态码");
    result.getClass().getMethod("setResponseTime", String.class).invoke(result, "耗时");
    result.getClass().getMethod("setResponseHeader", String.class)
        .invoke(result, "${REST_HEADER_FIELD}");
    var variables = new org.apache.hop.core.variables.Variables();
    variables.setVariable("REST_BODY_FIELD", "响应体_甲");
    variables.setVariable("REST_HEADER_FIELD", "响应头_乙");
    var provider = new MemoryMetadataProvider();

    result.getClass().getMethod("setBinary", boolean.class).invoke(result, true);
    var binaryRow = new org.apache.hop.core.row.RowMeta();
    binaryRow.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("上游字段"));
    meta.getFields(binaryRow, "rest-node", new org.apache.hop.core.row.IRowMeta[0],
        null, variables, provider);
    assertEquals(5, binaryRow.size());
    assertEquals("响应体_甲", binaryRow.getValueMeta(1).getName());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_BINARY,
        binaryRow.getValueMeta(1).getType());
    assertEquals("状态码", binaryRow.getValueMeta(2).getName());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_INTEGER,
        binaryRow.getValueMeta(2).getType());
    assertEquals("耗时", binaryRow.getValueMeta(3).getName());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_INTEGER,
        binaryRow.getValueMeta(3).getType());
    assertEquals("响应头_乙", binaryRow.getValueMeta(4).getName());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_STRING,
        binaryRow.getValueMeta(4).getType());

    result.getClass().getMethod("setBinary", boolean.class).invoke(result, false);
    var textRow = new org.apache.hop.core.row.RowMeta();
    meta.getFields(textRow, "rest-node", new org.apache.hop.core.row.IRowMeta[0],
        null, variables, provider);
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_STRING,
        textRow.getValueMeta(0).getType(),
        "Turning off binary must restore a STRING result body");
    result.getClass().getMethod("setFieldName", String.class).invoke(result, "");
    result.getClass().getMethod("setBinary", boolean.class).invoke(result, true);
    var noBodyRow = new org.apache.hop.core.row.RowMeta();
    meta.getFields(noBodyRow, "rest-node", new org.apache.hop.core.row.IRowMeta[0],
        null, variables, provider);
    assertEquals(3, noBodyRow.size(),
        "An empty result name omits the body column even in binary mode");
    assertEquals("状态码", noBodyRow.getValueMeta(0).getName());
    System.out.println("PROD_P0_REST_BINARY_RESULT_OUTPUT_SCHEMA=PASS");
  }

  @Test
  void restResultOutputNamesResolveVariablesAndOmitResolvedEmptyHeader() throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "Rest");
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "REST_RESULT_FIELD_NAMES_RESOLVE_VARIABLES_AND_OMIT_EMPTY_HEADER"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/result/name", "/result/code", "/result/response_time",
        "/result/response_header"), contract.keyListPointers());
    for (String key : List.of("name", "code", "response_time", "response_header")) {
      assertEquals(Shape.STRING, property(descriptor, "result", key).shape());
    }

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "Rest");
    assertNotNull(plugin, "production REST plugin missing");
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    Object result = metadataField(meta.getClass(), "resultField").get(meta);
    var resultClass = result.getClass();
    resultClass.getMethod("setFieldName", String.class).invoke(result, "${REST_BODY_NAME}");
    resultClass.getMethod("setCode", String.class).invoke(result, "${REST_STATUS_NAME}");
    resultClass.getMethod("setResponseTime", String.class).invoke(result, "${REST_TIME_NAME}");
    resultClass.getMethod("setResponseHeader", String.class).invoke(result, "${REST_HEADER_NAME}");
    resultClass.getMethod("setBinary", boolean.class).invoke(result, false);
    var variables = new org.apache.hop.core.variables.Variables();
    variables.setVariable("REST_BODY_NAME", "正文<&>雪");
    variables.setVariable("REST_STATUS_NAME", "状态_甲");
    variables.setVariable("REST_TIME_NAME", "耗时_乙");
    variables.setVariable("REST_HEADER_NAME", "响应头_丙");
    var provider = new MemoryMetadataProvider();

    var output = new org.apache.hop.core.row.RowMeta();
    output.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("上游"));
    meta.getFields(output, "rest-output", new org.apache.hop.core.row.IRowMeta[0],
        null, variables, provider);
    assertEquals(List.of("上游", "正文<&>雪", "状态_甲", "耗时_乙", "响应头_丙"),
        output.getValueMetaList().stream().map(v -> v.getName()).toList());
    assertEquals(List.of(org.apache.hop.core.row.IValueMeta.TYPE_STRING,
        org.apache.hop.core.row.IValueMeta.TYPE_INTEGER,
        org.apache.hop.core.row.IValueMeta.TYPE_INTEGER,
        org.apache.hop.core.row.IValueMeta.TYPE_STRING),
        output.getValueMetaList().subList(1, 5).stream().map(v -> v.getType()).toList());
    for (int i = 1; i < output.size(); i++) {
      assertEquals("rest-output", output.getValueMeta(i).getOrigin());
    }

    variables.setVariable("REST_HEADER_NAME", "");
    var noHeader = new org.apache.hop.core.row.RowMeta();
    meta.getFields(noHeader, "rest-output", new org.apache.hop.core.row.IRowMeta[0],
        null, variables, provider);
    assertEquals(List.of("正文<&>雪", "状态_甲", "耗时_乙"),
        noHeader.getValueMetaList().stream().map(v -> v.getName()).toList(),
        "REST resolves the response-header name before checking whether to add a column");

    resultClass.getMethod("setFieldName", String.class).invoke(result, "");
    resultClass.getMethod("setCode", String.class).invoke(result, "");
    resultClass.getMethod("setResponseTime", String.class).invoke(result, "");
    var none = new org.apache.hop.core.row.RowMeta();
    meta.getFields(none, "rest-output", new org.apache.hop.core.row.IRowMeta[0],
        null, variables, provider);
    assertEquals(0, none.size(), "Empty configured names and resolved empty header add no fields");
    System.out.println("PROD_P0_REST_RESULT_NAME_RESOLUTION_EMPTY_HEADER=PASS");
  }

  @Test void restDynamicMethodNativeCheckFalsePositiveIsDocumentedByProductionDescriptor()
      throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "Rest");
    assertEquals(Shape.BOOLEAN, property(descriptor, "dynamicMethod").shape());
    assertEquals(Shape.STRING, property(descriptor, "methodFieldName").shape());
    assertEquals(Shape.STRING, property(descriptor, "method").shape());
    var precondition = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals("REST_DYNAMIC_METHOD_NATIVE_CHECK_FALSE_POSITIVE"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/dynamicMethod", "/methodFieldName", "/method"),
        precondition.keyListPointers());
    assertTrue(precondition.explanation().contains("native-check false positive"));
    assertTrue(precondition.explanation().contains("incoming row field"));

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "Rest");
    assertNotNull(plugin);
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    metadataField(meta.getClass(), "url").set(meta, "https://example.invalid/");
    metadataField(meta.getClass(), "urlInField").setBoolean(meta, false);
    metadataField(meta.getClass(), "method").set(meta, "GET");
    metadataField(meta.getClass(), "dynamicMethod").setBoolean(meta, false);
    var methodField = metadataField(meta.getClass(), "methodFieldName");
    methodField.set(meta, "method_甲");
    var transform = new org.apache.hop.pipeline.transform.TransformMeta(
        "Rest", "rest-node", meta);
    var row = new org.apache.hop.core.row.RowMeta();
    row.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("method_甲"));
    var variables = new org.apache.hop.core.variables.Variables();
    var provider = new MemoryMetadataProvider();
    List<org.apache.hop.core.ICheckResult> remarks = new ArrayList<>();

    meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
        row, new String[] {"source"}, new String[0], null, variables, provider);
    assertEquals(3, remarks.size());
    assertTrue(remarks.stream().allMatch(
        x -> x.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_OK),
        "A valid static REST method and URL must pass the native check");

    metadataField(meta.getClass(), "dynamicMethod").setBoolean(meta, true);
    remarks.clear();
    meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
        row, new String[] {"source"}, new String[0], null, variables, provider);
    assertEquals(3, remarks.size());
    assertEquals(org.apache.hop.core.ICheckResult.TYPE_RESULT_OK, remarks.get(0).getType());
    assertEquals(org.apache.hop.core.ICheckResult.TYPE_RESULT_OK, remarks.get(1).getType());
    assertEquals(org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR, remarks.get(2).getType(),
        "Hop native RestMeta.check marks a present dynamic method field as ERROR");

    methodField.set(meta, "");
    remarks.clear();
    meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
        row, new String[] {"source"}, new String[0], null, variables, provider);
    assertEquals(3, remarks.size());
    assertEquals(org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR, remarks.get(2).getType(),
        "A missing dynamic method field is a genuine native-check error");
    System.out.println("PROD_P0_REST_DYNAMIC_METHOD_NATIVE_CHECK_COMPATIBILITY=PASS");
  }

  @Test
  void sortRowsNativeCheckRequiresKnownKeysAndExistingTemporaryDirectory() throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "SortRows");
    Property sortKeys = descriptor.properties().stream()
        .filter(p -> p.key().equals("field") && p.groupKey().equals("fields"))
        .findFirst().orElseThrow();
    assertEquals(Shape.LIST, sortKeys.shape());
    assertTrue(sortKeys.elementProperties().stream()
        .anyMatch(p -> p.key().equals("name") && p.shape() == Shape.STRING));
    assertEquals(Shape.STRING, property(descriptor, "directory").shape());
    var precondition = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals("SORT_ROWS_KEYS_AND_TEMP_DIRECTORY_NATIVE_CHECK"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/fields/field", "/directory"), precondition.keyListPointers());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "SortRows");
    assertNotNull(plugin);
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    Class<?> fieldClass = meta.getClass().getClassLoader().loadClass(
        "org.apache.hop.pipeline.transforms.sort.SortRowsField");
    Object key = fieldClass.getConstructor().newInstance();
    var setKeyName = fieldClass.getMethod("setFieldName", String.class);
    setKeyName.invoke(key, "水位");
    var setSortFields = meta.getClass().getMethod("setSortFields", List.class);
    var setDirectory = meta.getClass().getMethod("setDirectory", String.class);
    setSortFields.invoke(meta, List.of(key));
    String existingDirectory = System.getProperty("java.io.tmpdir");
    assertTrue(new java.io.File(existingDirectory).isDirectory());
    setDirectory.invoke(meta, existingDirectory);

    var upstream = new org.apache.hop.core.row.RowMeta();
    upstream.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("水位"));
    var transform = new org.apache.hop.pipeline.transform.TransformMeta(
        "SortRows", "sort-native", meta);
    var variables = new org.apache.hop.core.variables.Variables();
    var provider = new MemoryMetadataProvider();
    List<org.apache.hop.core.ICheckResult> remarks = new ArrayList<>();

    meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
        upstream, new String[] {"source"}, new String[0], null, variables, provider);
    assertFalse(remarks.stream().anyMatch(
        r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR),
        "Known sort key, upstream input and existing directory must pass native checks");

    setSortFields.invoke(meta, List.of());
    remarks.clear();
    meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
        upstream, new String[] {"source"}, new String[0], null, variables, provider);
    assertTrue(remarks.stream().anyMatch(
        r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR),
        "An empty sort key list is a native error");

    setSortFields.invoke(meta, List.of(key));
    setKeyName.invoke(key, "不存在的字段");
    remarks.clear();
    meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
        upstream, new String[] {"source"}, new String[0], null, variables, provider);
    assertTrue(remarks.stream().anyMatch(
        r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR),
        "Sort key must resolve against incoming row metadata");

    setKeyName.invoke(key, "水位");
    String missingDirectory = existingDirectory + java.io.File.separator
        + "hop-modern-web-sort-missing-" + java.util.UUID.randomUUID();
    assertFalse(new java.io.File(missingDirectory).exists());
    setDirectory.invoke(meta, missingDirectory);
    remarks.clear();
    meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
        upstream, new String[] {"source"}, new String[0], null, variables, provider);
    assertTrue(remarks.stream().anyMatch(
        r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR),
        "Temporary sort directory must exist");

    setDirectory.invoke(meta, existingDirectory);
    remarks.clear();
    meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
        upstream, new String[0], new String[0], null, variables, provider);
    assertTrue(remarks.stream().anyMatch(
        r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR),
        "Missing upstream hop must be reported by the native check");
    System.out.println("PROD_P0_SORTROWS_KEYS_TEMP_DIRECTORY_NATIVE_CHECK=PASS");
  }

  @Test
  void sortRowsGetFieldsPropagatesSortingCriteriaAndNormalizesSelectedStorage()
      throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "SortRows");
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "SORT_ROWS_GETFIELDS_PROPAGATES_SORT_FLAGS_AND_NORMALIZES_STORAGE"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/fields/field"), contract.keyListPointers());
    assertEquals(Shape.LIST, property(descriptor, "field").shape());
    assertEquals("fields", property(descriptor, "field").groupKey());
    for (String key : List.of("name", "ascending", "case_sensitive",
        "collator_enabled", "collator_strength", "presorted")) {
      assertNotNull(property(descriptor, "field", key));
    }

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "SortRows");
    assertNotNull(plugin, "production SortRows plugin missing");
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    Class<?> fieldType = meta.getClass().getClassLoader().loadClass(
        "org.apache.hop.pipeline.transforms.sort.SortRowsField");
    var constructor = fieldType.getConstructor(String.class, boolean.class,
        boolean.class, boolean.class, int.class, boolean.class);
    Object water = constructor.newInstance("水位", false, false, true, 2, false);
    Object station = constructor.newInstance("站点", true, true, false, 3, true);
    Object absent = constructor.newInstance("未输入的字段", true, true, false, 0, false);
    meta.getClass().getMethod("setSortFields", List.class)
        .invoke(meta, List.of(water, station, absent));

    var row = new org.apache.hop.core.row.RowMeta();
    var waterMeta = new org.apache.hop.core.row.value.ValueMetaString("水位");
    waterMeta.setStorageType(
        org.apache.hop.core.row.IValueMeta.STORAGE_TYPE_BINARY_STRING);
    waterMeta.setStorageMetadata(
        new org.apache.hop.core.row.value.ValueMetaString("原二进制存储"));
    var stationMeta = new org.apache.hop.core.row.value.ValueMetaString("站点");
    var otherMeta = new org.apache.hop.core.row.value.ValueMetaInteger("流量");
    row.addValueMeta(waterMeta);
    row.addValueMeta(stationMeta);
    row.addValueMeta(otherMeta);
    boolean otherDescending = otherMeta.isSortedDescending();
    boolean otherCaseInsensitive = otherMeta.isCaseInsensitive();
    boolean otherCollatorDisabled = otherMeta.isCollatorDisabled();
    int otherCollatorStrength = otherMeta.getCollatorStrength();
    int otherStorage = otherMeta.getStorageType();

    meta.getFields(row, "sort-metadata", new org.apache.hop.core.row.IRowMeta[0],
        null, new org.apache.hop.core.variables.Variables(),
        new MemoryMetadataProvider());

    assertEquals(3, row.size(), "SortRows must not append or drop output fields");
    assertEquals(List.of("水位", "站点", "流量"),
        List.of(row.getValueMeta(0).getName(), row.getValueMeta(1).getName(),
            row.getValueMeta(2).getName()));
    assertSame(waterMeta, row.getValueMeta(0),
        "Native SortRows modifies incoming IValueMeta in place");
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_STRING,
        waterMeta.getType());
    assertTrue(waterMeta.isSortedDescending());
    assertTrue(waterMeta.isCaseInsensitive());
    assertFalse(waterMeta.isCollatorDisabled());
    assertEquals(2, waterMeta.getCollatorStrength());
    assertEquals(org.apache.hop.core.row.IValueMeta.STORAGE_TYPE_NORMAL,
        waterMeta.getStorageType());
    assertNull(waterMeta.getStorageMetadata());

    assertFalse(stationMeta.isSortedDescending());
    assertFalse(stationMeta.isCaseInsensitive());
    assertTrue(stationMeta.isCollatorDisabled());
    assertEquals(3, stationMeta.getCollatorStrength());
    assertEquals(org.apache.hop.core.row.IValueMeta.STORAGE_TYPE_NORMAL,
        stationMeta.getStorageType());

    assertSame(otherMeta, row.getValueMeta(2));
    assertEquals(otherDescending, otherMeta.isSortedDescending());
    assertEquals(otherCaseInsensitive, otherMeta.isCaseInsensitive());
    assertEquals(otherCollatorDisabled, otherMeta.isCollatorDisabled());
    assertEquals(otherCollatorStrength, otherMeta.getCollatorStrength());
    assertEquals(otherStorage, otherMeta.getStorageType());
    System.out.println("PROD_P0_SORTROWS_OUTPUT_SORT_FLAGS_STORAGE=PASS");
  }

  @Test
  void streamLookupOutputTypesUseActualInfoRowOrConfiguredDefaultsAndDoNotHideMutation()
      throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "StreamLookup");
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "STREAM_LOOKUP_RETURN_TYPES_FOLLOW_INFO_ROW_OR_DEFAULT_TYPE"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/lookup/value"), contract.keyListPointers());
    assertEquals(Shape.OBJECT, property(descriptor, "lookup").shape());
    assertEquals(Shape.LIST, property(descriptor, "lookup", "value").shape());
    assertEquals(Shape.STRING, property(descriptor, "lookup", "value", "name").shape());
    assertEquals(Shape.STRING, property(descriptor, "lookup", "value", "rename").shape());
    assertEquals(Shape.NUMBER, property(descriptor, "lookup", "value", "type").shape());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "StreamLookup");
    assertNotNull(plugin);
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    Class<?> returnType = meta.getClass().getClassLoader().loadClass(
        "org.apache.hop.pipeline.transforms.streamlookup.StreamLookupMeta$ReturnValue");
    Object nameReturn = returnType.getConstructor().newInstance();
    returnType.getMethod("setValue", String.class).invoke(nameReturn, "站点名称");
    returnType.getMethod("setValueName", String.class).invoke(nameReturn, "名称别名<&>雪");
    returnType.getMethod("setValueDefaultType", int.class).invoke(
        nameReturn, org.apache.hop.core.row.IValueMeta.TYPE_INTEGER);
    Object flowReturn = returnType.getConstructor().newInstance();
    returnType.getMethod("setValue", String.class).invoke(flowReturn, "瞬时流量");
    returnType.getMethod("setValueName", String.class).invoke(flowReturn, "流量别名");
    returnType.getMethod("setValueDefaultType", int.class).invoke(
        flowReturn, org.apache.hop.core.row.IValueMeta.TYPE_STRING);
    Object lookup = meta.getClass().getMethod("getLookup").invoke(meta);
    lookup.getClass().getMethod("setReturnValues", List.class)
        .invoke(lookup, List.of(nameReturn, flowReturn));

    var info = new org.apache.hop.core.row.RowMeta();
    info.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("站点名称"));
    info.addValueMeta(new org.apache.hop.core.row.value.ValueMetaNumber("瞬时流量"));
    var output = new org.apache.hop.core.row.RowMeta();
    output.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("主流编号"));
    var variables = new org.apache.hop.core.variables.Variables();
    var provider = new MemoryMetadataProvider();
    meta.getFields(output, "streamlookup-output",
        new org.apache.hop.core.row.IRowMeta[] {info}, null, variables, provider);
    assertEquals(3, output.size());
    assertEquals("主流编号", output.getValueMeta(0).getName());
    assertEquals("名称别名<&>雪", output.getValueMeta(1).getName());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_STRING,
        output.getValueMeta(1).getType(), "real info-row type overrides default INTEGER");
    assertEquals("流量别名", output.getValueMeta(2).getName());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_NUMBER,
        output.getValueMeta(2).getType(), "real info-row type overrides default STRING");
    assertEquals("名称别名<&>雪", info.getValueMeta(0).getName(),
        "native getFields mutates shared lookup metadata, so schema callers must isolate it");
    assertEquals("流量别名", info.getValueMeta(1).getName());

    var fallback = new org.apache.hop.core.row.RowMeta();
    fallback.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("主流编号"));
    meta.getFields(fallback, "streamlookup-output", null, null, variables, provider);
    assertEquals(3, fallback.size());
    assertEquals("名称别名<&>雪", fallback.getValueMeta(1).getName());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_INTEGER,
        fallback.getValueMeta(1).getType(), "without info metadata use configured default");
    assertEquals("流量别名", fallback.getValueMeta(2).getName());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_STRING,
        fallback.getValueMeta(2).getType());

    var incompleteInfo = new org.apache.hop.core.row.RowMeta();
    incompleteInfo.addValueMeta(
        new org.apache.hop.core.row.value.ValueMetaString("站点名称"));
    var invalid = new org.apache.hop.core.row.RowMeta();
    assertThrows(org.apache.hop.core.exception.HopTransformException.class,
        () -> meta.getFields(invalid, "streamlookup-output",
            new org.apache.hop.core.row.IRowMeta[] {incompleteInfo},
            null, variables, provider),
        "missing lookup return field is an error, not a fallback to default type");
    System.out.println("PROD_P0_STREAMLOOKUP_INFO_TYPE_VS_DEFAULT_OUTPUT_SCHEMA=PASS");
  }

  @Test
  void streamLookupNativeChecksResolveTwoRowSchemasAndRequireLookupSourceAndTwoHops()
      throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "StreamLookup");
    assertEquals(Shape.OBJECT, property(descriptor, "lookup").shape());
    assertEquals(Shape.LIST, property(descriptor, "lookup", "key").shape());
    assertEquals(Shape.STRING, property(descriptor, "lookup", "key", "name").shape());
    assertEquals(Shape.STRING, property(descriptor, "lookup", "key", "field").shape());
    assertEquals(Shape.LIST, property(descriptor, "lookup", "value").shape());
    assertEquals(Shape.STRING, property(descriptor, "lookup", "value", "name").shape());
    var prerequisite = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals("STREAM_LOOKUP_TWO_INPUTS_AND_STAGE_SPECIFIC_FIELDS"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/from", "/lookup/key", "/lookup/value"),
        prerequisite.keyListPointers());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "StreamLookup");
    assertNotNull(plugin);
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    ClassLoader loader = meta.getClass().getClassLoader();
    Class<?> matchType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.streamlookup.StreamLookupMeta$MatchKey");
    Object match = matchType.getConstructor().newInstance();
    var setMainKey = matchType.getMethod("setKeyStream", String.class);
    var setLookupKey = matchType.getMethod("setKeyLookup", String.class);
    setMainKey.invoke(match, "主流编号");
    setLookupKey.invoke(match, "关联编号");

    Class<?> returnType = loader.loadClass(
        "org.apache.hop.pipeline.transforms.streamlookup.StreamLookupMeta$ReturnValue");
    Object returned = returnType.getConstructor().newInstance();
    var setReturnName = returnType.getMethod("setValue", String.class);
    setReturnName.invoke(returned, "站点名称");
    returnType.getMethod("setValueName", String.class).invoke(returned, "名称别名");

    Object lookup = meta.getClass().getMethod("getLookup").invoke(meta);
    lookup.getClass().getMethod("setMatchKeys", List.class).invoke(lookup, List.of(match));
    lookup.getClass().getMethod("setReturnValues", List.class)
        .invoke(lookup, List.of(returned));
    meta.getClass().getMethod("setSourceTransformName", String.class)
        .invoke(meta, "lookup-source");
    var infoStream = meta.getTransformIOMeta().getInfoStreams().getFirst();
    infoStream.setTransformMeta(new org.apache.hop.pipeline.transform.TransformMeta(
        "StreamLookup", "lookup-source", meta));

    var mainRow = new org.apache.hop.core.row.RowMeta();
    mainRow.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("主流编号"));
    var lookupRow = new org.apache.hop.core.row.RowMeta();
    lookupRow.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("关联编号"));
    lookupRow.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("站点名称"));
    var node = new org.apache.hop.pipeline.transform.TransformMeta(
        "StreamLookup", "stream-lookup-native", meta);
    var pipeline = new org.apache.hop.pipeline.PipelineMeta();
    var variables = new org.apache.hop.core.variables.Variables();
    var provider = new MemoryMetadataProvider();
    List<org.apache.hop.core.ICheckResult> remarks = new ArrayList<>();
    meta.check(remarks, pipeline, node, mainRow, new String[] {"main", "lookup"},
        new String[0], lookupRow, variables, provider);
    assertFalse(remarks.stream().anyMatch(
        r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR),
        "Selected lookup source and matching fields in both rows must pass native check");

    setMainKey.invoke(match, "主流不存在");
    remarks.clear();
    meta.check(remarks, pipeline, node, mainRow, new String[] {"main", "lookup"},
        new String[0], lookupRow, variables, provider);
    assertTrue(remarks.stream().anyMatch(
        r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR),
        "Main match key must resolve against main row, not lookup row");
    setMainKey.invoke(match, "主流编号");

    setLookupKey.invoke(match, "关联不存在");
    remarks.clear();
    meta.check(remarks, pipeline, node, mainRow, new String[] {"main", "lookup"},
        new String[0], lookupRow, variables, provider);
    assertTrue(remarks.stream().anyMatch(
        r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR),
        "Lookup match key must resolve against lookup row");
    setLookupKey.invoke(match, "关联编号");

    setReturnName.invoke(returned, "返回不存在");
    remarks.clear();
    meta.check(remarks, pipeline, node, mainRow, new String[] {"main", "lookup"},
        new String[0], lookupRow, variables, provider);
    assertTrue(remarks.stream().anyMatch(
        r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR),
        "Return field must resolve in lookup row; its alias is not a source field");
    setReturnName.invoke(returned, "站点名称");

    remarks.clear();
    meta.check(remarks, pipeline, node, mainRow, new String[] {"main"},
        new String[0], lookupRow, variables, provider);
    assertTrue(remarks.stream().anyMatch(
        r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR),
        "A single incoming hop cannot satisfy StreamLookup's two-stream prerequisite");

    infoStream.setTransformMeta(null);
    remarks.clear();
    meta.check(remarks, pipeline, node, mainRow, new String[] {"main", "lookup"},
        new String[0], lookupRow, variables, provider);
    assertTrue(remarks.stream().anyMatch(
        r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR),
        "A lookup row alone does not replace selecting a source transform");
    System.out.println("PROD_P0_STREAMLOOKUP_SPLIT_ROW_NATIVE_CHECK=PASS");
  }

  @Test void stringCutNativeInputFieldChecksDoNotRequireDistinctSources() throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "StringCut");
    Property fields = descriptor.properties().stream()
        .filter(p -> p.key().equals("field") && p.groupKey().equals("fields"))
        .findFirst().orElseThrow();
    assertEquals(Shape.LIST, fields.shape());
    assertTrue(fields.elementProperties().stream()
        .anyMatch(p -> p.key().equals("in_stream_name") && p.shape() == Shape.STRING));
    assertTrue(fields.elementProperties().stream()
        .anyMatch(p -> p.key().equals("out_stream_name") && p.shape() == Shape.STRING));
    var precondition = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals("STRING_CUT_SOURCE_FIELDS_REQUIRE_UPSTREAM_STRINGS"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/fields/field"), precondition.keyListPointers());
    assertTrue(precondition.explanation().contains("repeated source names"));

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "StringCut");
    assertNotNull(plugin);
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    Class<?> item = meta.getClass().getClassLoader().loadClass(
        "org.apache.hop.pipeline.transforms.stringcut.StringCutField");
    Object first = item.getConstructor().newInstance();
    var setName = item.getMethod("setFieldInStream", String.class);
    setName.invoke(first, "源字段");
    item.getMethod("setFieldOutStream", String.class).invoke(first, "新字段");
    item.getMethod("setCutFrom", String.class).invoke(first, "1");
    item.getMethod("setCutTo", String.class).invoke(first, "3");
    var setFields = meta.getClass().getMethod("setFields", List.class);
    setFields.invoke(meta, List.of(first));

    var row = new org.apache.hop.core.row.RowMeta();
    row.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("源字段"));
    row.addValueMeta(new org.apache.hop.core.row.value.ValueMetaNumber("数字字段"));
    var transform = new org.apache.hop.pipeline.transform.TransformMeta(
        "StringCut", "stringcut-node", meta);
    var variables = new org.apache.hop.core.variables.Variables();
    var provider = new MemoryMetadataProvider();
    List<org.apache.hop.core.ICheckResult> remarks = new ArrayList<>();
    meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
        row, new String[] {"source"}, new String[0], null, variables, provider);
    assertFalse(remarks.stream().anyMatch(
        r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR));

    Object duplicate = item.getConstructor().newInstance();
    setName.invoke(duplicate, "源字段");
    setFields.invoke(meta, List.of(first, duplicate));
    remarks.clear();
    meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
        row, new String[] {"source"}, new String[0], null, variables, provider);
    assertFalse(remarks.stream().anyMatch(
        r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR),
        "StringCut native check permits repeated source fields, unlike ReplaceString");

    setFields.invoke(meta, List.of(first));
    for (String invalidName : List.of("不存在", "数字字段", "")) {
      setName.invoke(first, invalidName);
      remarks.clear();
      meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
          row, new String[] {"source"}, new String[0], null, variables, provider);
      assertTrue(remarks.stream().anyMatch(
          r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR),
          "Expected native StringCut error for: " + invalidName);
    }
    setName.invoke(first, "源字段");
    remarks.clear();
    meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
        null, new String[] {"source"}, new String[0], null, variables, provider);
    assertTrue(remarks.stream().anyMatch(
        r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR));
    System.out.println("PROD_P0_STRINGCUT_NATIVE_STRING_INPUT_PRECONDITION=PASS");
  }

  @Test
  void stringCutOutputAliasesPreserveSourcesAndAppendFixedLengthColumnsInRuleOrder()
      throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "StringCut");
    assertEquals(Shape.LIST, property(descriptor, "field").shape());
    assertEquals(Shape.STRING, property(descriptor, "field", "out_stream_name").shape());
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "STRING_CUT_ALIAS_APPENDS_VARIABLE_RESOLVED_FIXED_LENGTH_STRING"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/fields/field"), contract.keyListPointers());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "StringCut");
    assertNotNull(plugin, "production StringCut plugin missing");
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    Class<?> item = meta.getClass().getClassLoader().loadClass(
        "org.apache.hop.pipeline.transforms.stringcut.StringCutField");
    var setInput = item.getMethod("setFieldInStream", String.class);
    var setAlias = item.getMethod("setFieldOutStream", String.class);
    var setRules = meta.getClass().getMethod("setFields", List.class);
    Object first = item.getConstructor().newInstance();
    Object second = item.getConstructor().newInstance();
    setInput.invoke(first, "源字段");
    setInput.invoke(second, "源字段");
    setAlias.invoke(first, "前段");
    setAlias.invoke(second, "${CUT_ALIAS}");
    setRules.invoke(meta, List.of(first, second));

    var variables = new org.apache.hop.core.variables.Variables();
    variables.setVariable("CUT_ALIAS", "截取结果_黄河");
    var provider = new MemoryMetadataProvider();
    var incoming = new org.apache.hop.core.row.RowMeta();
    incoming.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("源字段"));
    incoming.addValueMeta(new org.apache.hop.core.row.value.ValueMetaNumber("流量"));

    var aliased = new org.apache.hop.core.row.RowMeta();
    aliased.addRowMeta(incoming);
    meta.getFields(aliased, "string-cut", null, null, variables, provider);
    assertEquals(4, aliased.size(), "two aliases must append two columns in rule order");
    assertEquals(List.of("源字段", "流量", "前段", "截取结果_黄河"),
        aliased.getValueMetaList().stream().map(v -> v.getName()).toList());
    for (int i = 2; i < 4; i++) {
      assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_STRING,
          aliased.getValueMeta(i).getType());
      assertEquals(100, aliased.getValueMeta(i).getLength());
    }

    setAlias.invoke(first, "");
    var oneAlias = new org.apache.hop.core.row.RowMeta();
    oneAlias.addRowMeta(incoming);
    meta.getFields(oneAlias, "string-cut", null, null, variables, provider);
    assertEquals(3, oneAlias.size(), "blank alias must not append a column");
    assertEquals("源字段", oneAlias.getValueMeta(0).getName());
    assertEquals("截取结果_黄河", oneAlias.getValueMeta(2).getName());

    setAlias.invoke(second, "");
    var inPlace = new org.apache.hop.core.row.RowMeta();
    inPlace.addRowMeta(incoming);
    meta.getFields(inPlace, "string-cut", null, null, variables, provider);
    assertEquals(2, inPlace.size(), "two blank aliases must preserve incoming width");
    assertEquals("源字段", inPlace.getValueMeta(0).getName());
    assertEquals("流量", inPlace.getValueMeta(1).getName());
    System.out.println("PROD_P0_STRINGCUT_ALIAS_OUTPUT_SCHEMA=PASS");
  }

  @Test
  void stringCutFreshAliasMetadataDoesNotInheritSourceEncodingAndInPlaceNormalizesStorage()
      throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "StringCut");
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "STRING_CUT_ALIAS_FRESH_METADATA_AND_INPLACE_STORAGE_NORMALIZATION"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/fields/field"), contract.keyListPointers());
    assertEquals(Shape.STRING, property(descriptor, "field", "in_stream_name").shape());
    assertEquals(Shape.STRING, property(descriptor, "field", "out_stream_name").shape());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "StringCut");
    assertNotNull(plugin, "production StringCut plugin missing");
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    Class<?> item = meta.getClass().getClassLoader().loadClass(
        "org.apache.hop.pipeline.transforms.stringcut.StringCutField");
    Object append = item.getConstructor().newInstance();
    item.getMethod("setFieldInStream", String.class).invoke(append, "编码源");
    item.getMethod("setFieldOutStream", String.class).invoke(append, "${CUT_ALIAS}");
    Object inPlace = item.getConstructor().newInstance();
    item.getMethod("setFieldInStream", String.class).invoke(inPlace, "原地截取");
    item.getMethod("setFieldOutStream", String.class).invoke(inPlace, "");
    var setRules = meta.getClass().getMethod("setFields", List.class);
    setRules.invoke(meta, List.of(append, inPlace));

    var source = new org.apache.hop.core.row.value.ValueMetaString("编码源");
    source.setStringEncoding("UTF-16LE");
    var changed = new org.apache.hop.core.row.value.ValueMetaString("原地截取");
    changed.setStorageType(org.apache.hop.core.row.IValueMeta.STORAGE_TYPE_BINARY_STRING);
    var untouched = new org.apache.hop.core.row.value.ValueMetaString("旁路字段");
    untouched.setStorageType(org.apache.hop.core.row.IValueMeta.STORAGE_TYPE_BINARY_STRING);
    var row = new org.apache.hop.core.row.RowMeta();
    row.addValueMeta(source);
    row.addValueMeta(changed);
    row.addValueMeta(untouched);

    var variables = new org.apache.hop.core.variables.Variables();
    variables.setVariable("CUT_ALIAS", "截取<&黄河>");
    meta.getFields(row, "string-cut-metadata", null, null,
        variables, new MemoryMetadataProvider());
    assertEquals(4, row.size());
    assertEquals(List.of("编码源", "原地截取", "旁路字段", "截取<&黄河>"),
        row.getValueMetaList().stream().map(v -> v.getName()).toList());
    var output = row.getValueMeta(3);
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_STRING, output.getType());
    assertEquals(100, output.getLength());
    assertEquals("string-cut-metadata", output.getOrigin());
    assertEquals(new org.apache.hop.core.row.value.ValueMetaString("fresh").getStringEncoding(),
        output.getStringEncoding(), "alias uses fresh string metadata, not the source encoding");
    assertNotEquals("UTF-16LE", output.getStringEncoding());
    assertEquals("UTF-16LE", row.getValueMeta(0).getStringEncoding());
    assertEquals(org.apache.hop.core.row.IValueMeta.STORAGE_TYPE_NORMAL,
        row.getValueMeta(1).getStorageType(), "in-place cut normalizes storage");
    assertEquals(org.apache.hop.core.row.IValueMeta.STORAGE_TYPE_BINARY_STRING,
        row.getValueMeta(2).getStorageType(), "unrelated storage remains untouched");

    Object missing = item.getConstructor().newInstance();
    item.getMethod("setFieldInStream", String.class).invoke(missing, "不存在的源");
    item.getMethod("setFieldOutStream", String.class).invoke(missing, "新字段");
    setRules.invoke(meta, List.of(missing));
    var missingRow = new org.apache.hop.core.row.RowMeta();
    missingRow.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("原字段"));
    meta.getFields(missingRow, "string-cut-metadata", null, null,
        variables, new MemoryMetadataProvider());
    assertEquals(List.of("原字段", "新字段"),
        missingRow.getValueMetaList().stream().map(v -> v.getName()).toList(),
        "native getFields still declares alias even when native check would reject missing source");
    System.out.println("PROD_P0_STRINGCUT_FRESH_ALIAS_ENCODING_INPLACE_STORAGE=PASS");
  }

  @Test void replaceStringNativeSourceFieldPreconditions() throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "ReplaceString");
    Property fields = descriptor.properties().stream()
        .filter(p -> p.key().equals("field") && p.groupKey().equals("fields"))
        .findFirst().orElseThrow();
    assertEquals(Shape.LIST, fields.shape());
    assertTrue(fields.elementProperties().stream()
        .anyMatch(p -> p.key().equals("in_stream_name") && p.shape() == Shape.STRING));
    assertEquals(List.of("/fields/field"),
        descriptor.runtimePreconditions().stream()
            .filter(p -> p.code().equals("REPLACE_STRING_SOURCE_FIELDS_MUST_BE_DISTINCT_STRINGS"))
            .findFirst().orElseThrow().keyListPointers());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "ReplaceString");
    assertNotNull(plugin);
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    Class<?> item = meta.getClass().getClassLoader().loadClass(
        "org.apache.hop.pipeline.transforms.replacestring.ReplaceStringMeta$RSField");
    Object field = item.getConstructor().newInstance();
    var setName = item.getMethod("setFieldInStream", String.class);
    setName.invoke(field, "流量");
    item.getMethod("setFieldOutStream", String.class).invoke(field, "别名");
    var setFields = meta.getClass().getMethod("setFields", List.class);
    setFields.invoke(meta, List.of(field));
    var row = new org.apache.hop.core.row.RowMeta();
    row.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("流量"));
    row.addValueMeta(new org.apache.hop.core.row.value.ValueMetaNumber("数值"));
    var transform = new org.apache.hop.pipeline.transform.TransformMeta(
        "ReplaceString", "replace-node", meta);
    var variables = new org.apache.hop.core.variables.Variables();
    var provider = new MemoryMetadataProvider();
    List<org.apache.hop.core.ICheckResult> remarks = new ArrayList<>();
    meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
        row, new String[] {"source"}, new String[0], null, variables, provider);
    assertFalse(remarks.stream().anyMatch(
        r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR));
    for (String badName : List.of("缺失", "数值")) {
      remarks.clear();
      setName.invoke(field, badName);
      meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
          row, new String[] {"source"}, new String[0], null, variables, provider);
      assertTrue(remarks.stream().anyMatch(
          r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR));
    }
    remarks.clear();
    setName.invoke(field, "流量");
    Object duplicate = item.getConstructor().newInstance();
    setName.invoke(duplicate, "流量");
    setFields.invoke(meta, List.of(field, duplicate));
    meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
        row, new String[] {"source"}, new String[0], null, variables, provider);
    assertTrue(remarks.stream().anyMatch(
        r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR));
    System.out.println("PROD_P0_REPLACESTRING_SOURCE_STRING_PRECONDITION=PASS");
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
            "CHECKSUM_SELECTED_FIELDS_REQUIRE_UPSTREAM_FIELDS",
            "CHECKSUM_RESULT_NAME_COLLISION_AND_RESOLVED_EMPTY",
            "CHECKSUM_RESULT_ROW_TYPE_DEPENDS_ON_ALGORITHM_AND_ENCODING"),
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
  void checkSumAlgorithmAndResultEncodingDetermineNativeOutputRowType() throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "CheckSum");
    assertEquals(Shape.ENUM, property(descriptor, "checksumtype").shape());
    assertEquals(Shape.ENUM, property(descriptor, "resultType").shape());
    assertEquals(Shape.STRING, property(descriptor, "resultfieldName").shape());
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "CHECKSUM_RESULT_ROW_TYPE_DEPENDS_ON_ALGORITHM_AND_ENCODING"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/checksumtype", "/resultType", "/resultfieldName"),
        contract.keyListPointers());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "CheckSum");
    assertNotNull(plugin, "production CheckSum plugin missing");
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    ClassLoader loader = meta.getClass().getClassLoader();
    Class<?> algorithmClass = loader.loadClass(
        "org.apache.hop.pipeline.transforms.checksum.CheckSumMeta$CheckSumType");
    Class<?> resultClass = loader.loadClass(
        "org.apache.hop.pipeline.transforms.checksum.CheckSumMeta$ResultType");
    var setAlgorithm = meta.getClass().getMethod("setCheckSumType", algorithmClass);
    var setResult = meta.getClass().getMethod("setResultType", resultClass);
    var setName = meta.getClass().getMethod("setResultFieldName", String.class);
    setName.invoke(meta, "${CHECKSUM_RESULT_COLUMN}");
    var variables = new org.apache.hop.core.variables.Variables();
    variables.setVariable("CHECKSUM_RESULT_COLUMN", "摘要_雪<&>");
    var provider = new MemoryMetadataProvider();

    String[][] cases = {
        {"CRC32", "BINARY"}, {"ADLER32", "HEXADECIMAL"},
        {"MD5", "BINARY"}, {"SHA256", "BINARY"},
        {"SHA256", "HEXADECIMAL"}, {"SHA256", "STRING"}
    };
    int[] types = {
        org.apache.hop.core.row.IValueMeta.TYPE_INTEGER,
        org.apache.hop.core.row.IValueMeta.TYPE_INTEGER,
        org.apache.hop.core.row.IValueMeta.TYPE_BINARY,
        org.apache.hop.core.row.IValueMeta.TYPE_BINARY,
        org.apache.hop.core.row.IValueMeta.TYPE_STRING,
        org.apache.hop.core.row.IValueMeta.TYPE_STRING
    };
    for (int i = 0; i < cases.length; i++) {
      final String algorithm = cases[i][0];
      final String encoding = cases[i][1];
      Object algorithmValue = java.util.Arrays.stream(algorithmClass.getEnumConstants())
          .filter(v -> ((Enum<?>) v).name().equals(algorithm)).findFirst().orElseThrow();
      Object encodingValue = java.util.Arrays.stream(resultClass.getEnumConstants())
          .filter(v -> ((Enum<?>) v).name().equals(encoding)).findFirst().orElseThrow();
      setAlgorithm.invoke(meta, algorithmValue);
      setResult.invoke(meta, encodingValue);
      var row = new org.apache.hop.core.row.RowMeta();
      row.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("upstream"));
      meta.getFields(row, "checksum-node", new org.apache.hop.core.row.IRowMeta[0],
          null, variables, provider);
      assertEquals(2, row.size(), algorithm + "/" + encoding);
      assertEquals("摘要_雪<&>", row.getValueMeta(1).getName());
      assertEquals(types[i], row.getValueMeta(1).getType(), algorithm + "/" + encoding);
      if (algorithm.equals("MD5") && encoding.equals("BINARY")) {
        assertEquals(16, row.getValueMeta(1).getLength(), "MD5 binary length");
      }
    }
    setName.invoke(meta, "");
    var unnamed = new org.apache.hop.core.row.RowMeta();
    meta.getFields(unnamed, "checksum-node", new org.apache.hop.core.row.IRowMeta[0],
        null, variables, provider);
    assertEquals(0, unnamed.size(), "Missing result field name omits the output column");
    System.out.println("PROD_P0_CHECKSUM_ALGORITHM_RESULT_OUTPUT_TYPE=PASS");
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
            "JSON_INPUT_FIELD_SOURCE_REQUIRES_INCOMING_HOP_AND_NAME",
            "JSON_INPUT_SOURCE_REMOVAL_AND_AUXILIARY_OUTPUT_COLUMNS"),
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
  void execSqlNativeInputModeRequiresMatchingIncomingHopTopology() throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "ExecSql");
    assertEquals(Shape.BOOLEAN, property(descriptor, "execute_each_row").shape());
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals("EXEC_SQL_INPUT_MODE_REQUIRES_MATCHING_HOP_TOPOLOGY"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/execute_each_row"), contract.keyListPointers());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "ExecSql");
    assertNotNull(plugin);
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    meta.getClass().getMethod("setConnection", String.class)
        .invoke(meta, "missing-t3-execsql-test-connection");
    var setEachRow = meta.getClass().getMethod("setExecutedEachInputRow", boolean.class);
    var consumesInput = meta.getClass().getMethod("consumesMainInput");
    var canStartWithoutInput = meta.getClass().getMethod("canStartWithoutInput");
    var transform = new org.apache.hop.pipeline.transform.TransformMeta(
        "ExecSql", "execsql-topology", meta);
    var pipeline = new org.apache.hop.pipeline.PipelineMeta();
    var variables = new org.apache.hop.core.variables.Variables();
    var provider = new MemoryMetadataProvider();
    List<org.apache.hop.core.ICheckResult> remarks = new ArrayList<>();
    String onceOnlyError = org.apache.hop.i18n.BaseMessages.getString(
        meta.getClass(), "ExecSqlMeta.CheckResult.SQLOnlyExecutedOnce");
    String onceOnlyOk = org.apache.hop.i18n.BaseMessages.getString(
        meta.getClass(), "ExecSqlMeta.CheckResult.InputReceivedOKForSQLOnlyExecuteOnce");
    String perRowError = org.apache.hop.i18n.BaseMessages.getString(
        meta.getClass(), "ExecSqlMeta.CheckResult.NoInputReceivedError");
    String perRowOk = org.apache.hop.i18n.BaseMessages.getString(
        meta.getClass(), "ExecSqlMeta.CheckResult.TransformReceivingInfoOK");

    setEachRow.invoke(meta, false);
    assertEquals(false, consumesInput.invoke(meta));
    assertEquals(true, canStartWithoutInput.invoke(meta));
    meta.check(remarks, pipeline, transform, null, new String[0],
        new String[0], null, variables, provider);
    assertTrue(remarks.stream().anyMatch(x -> x.getText().equals(onceOnlyOk)
        && x.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_OK));
    remarks.clear();
    meta.check(remarks, pipeline, transform, null, new String[] {"upstream"},
        new String[0], null, variables, provider);
    assertTrue(remarks.stream().anyMatch(x -> x.getText().equals(onceOnlyError)
        && x.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR));

    setEachRow.invoke(meta, true);
    assertEquals(true, consumesInput.invoke(meta));
    assertEquals(false, canStartWithoutInput.invoke(meta));
    remarks.clear();
    meta.check(remarks, pipeline, transform, null, new String[0],
        new String[0], null, variables, provider);
    assertTrue(remarks.stream().anyMatch(x -> x.getText().equals(perRowError)
        && x.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR));
    remarks.clear();
    meta.check(remarks, pipeline, transform, null, new String[] {"upstream"},
        new String[0], null, variables, provider);
    assertTrue(remarks.stream().anyMatch(x -> x.getText().equals(perRowOk)
        && x.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_OK));
    System.out.println("PROD_P0_EXECSQL_INPUT_MODE_NATIVE_CHECK=PASS");
  }

  @Test
  void execSqlResultCounterColumnsUseFixedOrderIntegerTypesAndLiteralNames() throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "ExecSql");
    for (String field : List.of("update_field", "insert_field", "delete_field", "read_field")) {
      assertEquals(Shape.STRING, property(descriptor, field).shape());
    }
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "EXEC_SQL_RESULT_COUNTER_FIELDS_APPEND_FIXED_ORDER_INTEGERS"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/update_field", "/insert_field", "/delete_field", "/read_field"),
        contract.keyListPointers());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "ExecSql");
    assertNotNull(plugin, "production ExecSql plugin missing");
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    // Reflection keeps the standalone server independent of the P0-only SQL plugin jar.
    meta.getClass().getMethod("setUpdateField", String.class).invoke(meta, "更新行数");
    meta.getClass().getMethod("setInsertField", String.class)
        .invoke(meta, "${SQL_INSERT_COUNTER}");
    meta.getClass().getMethod("setDeleteField", String.class).invoke(meta, "删除行数");
    meta.getClass().getMethod("setReadField", String.class).invoke(meta, "读取行数");

    var variables = new org.apache.hop.core.variables.Variables();
    variables.setVariable("SQL_INSERT_COUNTER", "变量解析后的名称");
    var provider = new MemoryMetadataProvider();
    var incoming = new org.apache.hop.core.row.RowMeta();
    incoming.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("输入值"));
    var output = new org.apache.hop.core.row.RowMeta();
    output.addRowMeta(incoming);
    meta.getFields(output, "execsql-result", null, null, variables, provider);
    assertEquals(5, output.size());
    assertEquals("输入值", output.getValueMeta(0).getName());
    assertEquals(List.of("更新行数", "${SQL_INSERT_COUNTER}", "删除行数", "读取行数"),
        List.of(output.getValueMeta(1).getName(), output.getValueMeta(2).getName(),
            output.getValueMeta(3).getName(), output.getValueMeta(4).getName()),
        "native getFields appends counters in update/insert/delete/read order without variable resolution");
    for (int index = 1; index < output.size(); index++) {
      assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_INTEGER,
          output.getValueMeta(index).getType());
      assertEquals(org.apache.hop.core.row.IValueMeta.DEFAULT_INTEGER_LENGTH,
          output.getValueMeta(index).getLength());
    }

    meta.getClass().getMethod("setInsertField", String.class).invoke(meta, "");
    meta.getClass().getMethod("setDeleteField", String.class).invoke(meta, "");
    meta.getClass().getMethod("setReadField", String.class).invoke(meta, "");
    var oneCounter = new org.apache.hop.core.row.RowMeta();
    meta.getFields(oneCounter, "execsql-result", null, null, variables, provider);
    assertEquals(1, oneCounter.size(), "blank configured names must not emit counters");
    assertEquals("更新行数", oneCounter.getValueMeta(0).getName());

    meta.getClass().getMethod("setUpdateField", String.class).invoke(meta, "");
    var noCounters = new org.apache.hop.core.row.RowMeta();
    meta.getFields(noCounters, "execsql-result", null, null, variables, provider);
    assertEquals(0, noCounters.size());
    System.out.println("PROD_P0_EXECSQL_RESULT_COUNTER_OUTPUT_SCHEMA=PASS");
  }

  @Test
  void groupByMissingGroupingKeysAreSilentlyOmittedByNativeMetadataProjection()
      throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "GroupBy");
    Property grouping = descriptor.properties().stream()
        .filter(p -> p.key().equals("field") && p.groupKey().equals("group"))
        .findFirst().orElseThrow();
    assertEquals(Shape.LIST, grouping.shape());
    assertTrue(grouping.elementProperties().stream()
        .anyMatch(p -> p.key().equals("name") && p.shape() == Shape.STRING));
    var requirement = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals("GROUPBY_GROUPING_KEYS_REQUIRE_UPSTREAM_FIELDS"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/group/field"), requirement.keyListPointers());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "GroupBy");
    assertNotNull(plugin);
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    Class<?> groupingClass = meta.getClass().getClassLoader().loadClass(
        "org.apache.hop.pipeline.transforms.groupby.GroupingField");
    Object key = groupingClass.getConstructor(String.class).newInstance("地区");
    meta.getClass().getMethod("setGroupingFields", List.class).invoke(meta, List.of(key));

    var input = new org.apache.hop.core.row.RowMeta();
    input.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("地区"));
    input.addValueMeta(new org.apache.hop.core.row.value.ValueMetaInteger("数量"));
    var variables = new org.apache.hop.core.variables.Variables();
    var provider = new MemoryMetadataProvider();
    var validOutput = new org.apache.hop.core.row.RowMeta();
    validOutput.addRowMeta(input);
    meta.getFields(validOutput, "groupby-native", null, null, variables, provider);
    assertEquals(1, validOutput.size());
    assertEquals("地区", validOutput.getValueMeta(0).getName());

    groupingClass.getMethod("setName", String.class).invoke(key, "不存在的分组字段");
    var invalidOutput = new org.apache.hop.core.row.RowMeta();
    invalidOutput.addRowMeta(input);
    meta.getFields(invalidOutput, "groupby-native", null, null, variables, provider);
    assertEquals(0, invalidOutput.size(),
        "Native getFields silently drops a missing grouping key when all_rows=false");

    List<org.apache.hop.core.ICheckResult> remarks = new ArrayList<>();
    meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(),
        new org.apache.hop.pipeline.transform.TransformMeta("GroupBy", "groupby-native", meta),
        input, new String[] {"source"}, new String[0], null, variables, provider);
    assertFalse(remarks.stream().anyMatch(
        x -> x.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR),
        "Native GroupByMeta.check only checks the input hop, not grouping key existence");
    System.out.println("PROD_P0_GROUPBY_MISSING_GROUP_KEY_METADATA_GAP=PASS");
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
            "GROUPBY_GROUPING_KEYS_REQUIRE_UPSTREAM_FIELDS",
            "GROUPBY_AGGREGATE_SUBJECT_REQUIRES_INPUT_FIELD",
            "GROUPBY_LINE_NUMBER_REQUIRES_PASS_ALL_ROWS_AND_FIELD_NAME",
            "GROUPBY_AGGREGATE_OUTPUT_TYPES_FOLLOW_OPERATION_AND_SUBJECT"),
        descriptor.runtimePreconditions().stream().map(p -> p.code()).toList());
    var precondition = descriptor.runtimePreconditions().get(2);
    assertEquals(List.of("/fields/field"), precondition.keyListPointers());
    assertTrue(precondition.explanation().contains("COUNT_ANY"));
    assertTrue(precondition.explanation().contains("upstream row metadata"));
    System.out.println("PROD_P0_GROUPBY_AGGREGATE_SUBJECT_PRECONDITION=PASS");
  }

  @Test
  void tableInputNamedSqlParametersResolveOnlyAgainstIncomingRowMetadata() throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "TableInput");
    assertEquals(Shape.BOOLEAN, property(descriptor, "use_named_parameters").shape());
    assertEquals(TextEditorHint.SQL, property(descriptor, "sql").textEditorHint());
    assertEquals(Shape.STRING, property(descriptor, "sql_from_file").shape());
    assertEquals(Shape.STRING, property(descriptor, "lookup").shape());
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals("TABLE_INPUT_NAMED_SQL_PARAMETERS_REQUIRE_UPSTREAM_FIELDS"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/use_named_parameters", "/sql", "/sql_from_file", "/lookup"),
        contract.keyListPointers());

    String sqlWithEscapes = "SELECT '{literal}' AS quoted, ${SCHEMA}.orders "
        + "WHERE id={订单号} AND parent={订单号} /* {ignored} */";
    var parsed = org.apache.hop.pipeline.transforms.tableinput.TableInputSql.parse(sqlWithEscapes);
    assertEquals(List.of("订单号", "订单号"), parsed.getNamedParameters());
    assertEquals("SELECT '{literal}' AS quoted, ${SCHEMA}.orders "
        + "WHERE id=? AND parent=? /* {ignored} */", parsed.getJdbcSql());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "TableInput");
    assertNotNull(plugin);
    var meta = (org.apache.hop.pipeline.transforms.tableinput.TableInputMeta)
        registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    meta.setConnection("t3-missing-tableinput-connection");
    meta.setSql("SELECT id FROM orders WHERE id = {订单号}");
    meta.setUseNamedParameters(true);
    var transform = new org.apache.hop.pipeline.transform.TransformMeta(
        "TableInput", "tableinput-named-sql", meta);
    var pipeline = new org.apache.hop.pipeline.PipelineMeta();
    var variables = new org.apache.hop.core.variables.Variables();
    var provider = new MemoryMetadataProvider();
    List<org.apache.hop.core.ICheckResult> remarks = new ArrayList<>();
    String needsIncoming = org.apache.hop.i18n.BaseMessages.getString(
        meta.getClass(), "TableInputMeta.CheckResult.NamedParametersNeedIncoming");
    String namedOk = org.apache.hop.i18n.BaseMessages.getString(
        meta.getClass(), "TableInputMeta.CheckResult.NamedParametersOk", "1");
    String namedMissing = org.apache.hop.i18n.BaseMessages.getString(
        meta.getClass(), "TableInputMeta.CheckResult.NamedParametersMissing", "订单号");

    meta.check(remarks, pipeline, transform, null, new String[0],
        new String[0], null, variables, provider);
    assertTrue(remarks.stream().anyMatch(r -> r.getText().equals(needsIncoming)
        && r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR),
        "Named SQL fields require incoming rows even without a database connection");

    var matching = new org.apache.hop.core.row.RowMeta();
    matching.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("订单号"));
    remarks.clear();
    meta.check(remarks, pipeline, transform, matching, new String[] {"source"},
        new String[0], null, variables, provider);
    assertTrue(remarks.stream().anyMatch(r -> r.getText().equals(namedOk)
        && r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_OK),
        "The native check accepts a named SQL parameter found in incoming row metadata");

    var mismatching = new org.apache.hop.core.row.RowMeta();
    mismatching.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("other"));
    remarks.clear();
    meta.check(remarks, pipeline, transform, mismatching, new String[] {"source"},
        new String[0], null, variables, provider);
    assertTrue(remarks.stream().anyMatch(r -> r.getText().equals(namedMissing)
        && r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR),
        "The native check rejects a named SQL parameter absent from incoming row metadata");

    meta.setUseNamedParameters(false);
    remarks.clear();
    meta.check(remarks, pipeline, transform, null, new String[0],
        new String[0], null, variables, provider);
    assertFalse(remarks.stream().anyMatch(r -> r.getText().equals(needsIncoming)),
        "Disabled named binding must not require incoming rows for brace literals");
    assertEquals(meta.getSql(),
        org.apache.hop.pipeline.transforms.tableinput.TableInputSql.prepare(
            false, meta.getSql(), null, null).getJdbcSql());
    System.out.println("PROD_P0_TABLEINPUT_NAMED_SQL_SOURCE_BINDING=PASS");
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
  void tableInputSpecifiedJdbcMappingUsesResolvedNamesAndValidatesTypesOnlyWhenEnabled()
      throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "TableInput");
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "TABLE_INPUT_SPECIFIED_FIELDS_MAP_JDBC_COLUMNS_BY_RESOLVED_NAME"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/specify_fields", "/validate_specified_fields", "/fields/field"),
        contract.keyListPointers());
    assertEquals(Shape.LIST, property(descriptor, "field").shape());
    assertEquals("fields", property(descriptor, "field").groupKey());
    assertEquals(Shape.STRING, property(descriptor, "field", "name").shape());
    assertEquals(Shape.NUMBER, property(descriptor, "field", "type").shape());
    assertEquals(Shape.BOOLEAN, property(descriptor, "specify_fields").shape());
    assertEquals(Shape.BOOLEAN, property(descriptor, "validate_specified_fields").shape());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "TableInput");
    assertNotNull(plugin);
    var meta = (org.apache.hop.pipeline.transforms.tableinput.TableInputMeta)
        registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    meta.setSpecifyFields(true);
    meta.setValidateSpecifiedFields(true);
    var second = new org.apache.hop.pipeline.transforms.tableinput.TableInputField();
    second.setName("${T3_JDBC_SECOND}");
    second.setType(org.apache.hop.core.row.IValueMeta.TYPE_INTEGER);
    second.setLength(11);
    var unnamed = new org.apache.hop.pipeline.transforms.tableinput.TableInputField();
    unnamed.setName("");
    var first = new org.apache.hop.pipeline.transforms.tableinput.TableInputField();
    first.setName("甲列");
    first.setType(org.apache.hop.core.row.IValueMeta.TYPE_STRING);
    first.setLength(80);
    meta.setFields(List.of(second, unnamed, first));

    var variables = new org.apache.hop.core.variables.Variables();
    variables.setVariable("T3_JDBC_SECOND", "乙列<&>");
    var output = new org.apache.hop.core.row.RowMeta();
    output.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("incoming-parameter"));
    meta.getFields(output, "tableinput-specified", null, null, variables,
        new MemoryMetadataProvider());
    assertEquals(2, output.size(), "specified output replaces incoming parameter metadata");
    assertEquals("乙列<&>", output.getValueMeta(0).getName());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_INTEGER,
        output.getValueMeta(0).getType());
    assertEquals(11, output.getValueMeta(0).getLength());
    assertEquals("甲列", output.getValueMeta(1).getName());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_STRING,
        output.getValueMeta(1).getType());
    assertEquals(80, output.getValueMeta(1).getLength());

    var jdbc = new org.apache.hop.core.row.RowMeta();
    jdbc.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("甲列"));
    jdbc.addValueMeta(new org.apache.hop.core.row.value.ValueMetaInteger("乙列<&>"));
    jdbc.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("未选择列"));
    assertArrayEquals(new int[] {1, 0}, meta.createSpecifiedMapping(jdbc, output, true),
        "JDBC column positions are mapped by resolved name in configured output order");

    var wrongTypes = new org.apache.hop.core.row.RowMeta();
    wrongTypes.addValueMeta(new org.apache.hop.core.row.value.ValueMetaInteger("甲列"));
    wrongTypes.addValueMeta(new org.apache.hop.core.row.value.ValueMetaInteger("乙列<&>"));
    assertArrayEquals(new int[] {1, 0},
        meta.createSpecifiedMapping(wrongTypes, output, false),
        "disabled type validation permits later value conversion");
    assertThrows(org.apache.hop.core.exception.HopException.class,
        () -> meta.createSpecifiedMapping(wrongTypes, output, true));

    var missing = new org.apache.hop.core.row.RowMeta();
    missing.addValueMeta(new org.apache.hop.core.row.value.ValueMetaInteger("乙列<&>"));
    assertThrows(org.apache.hop.core.exception.HopException.class,
        () -> meta.createSpecifiedMapping(missing, output, false),
        "missing JDBC columns fail even when type validation is disabled");
    System.out.println("PROD_P0_TABLEINPUT_SPECIFIED_JDBC_NAME_MAPPING=PASS");
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
  void tableOutputGeneratedKeyFieldMustBeNamedToPreserveOutputRowSchema() throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "TableOutput");
    assertEquals(Shape.BOOLEAN, property(descriptor, "return_keys").shape());
    assertEquals(Shape.STRING, property(descriptor, "return_field").shape());
    var precondition = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "TABLE_OUTPUT_RETURN_KEYS_REQUIRES_RETURN_FIELD_FOR_ROW_SCHEMA"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/return_keys", "/return_field"), precondition.keyListPointers());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "TableOutput");
    assertNotNull(plugin, "production TableOutput plugin missing");
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    // Reflection keeps the standalone server test independent of the TableOutput
    // artifact: the pinned source-derived P0 runtime supplies the real plugin.
    meta.getClass().getMethod("setReturningGeneratedKeys", boolean.class).invoke(meta, true);
    meta.getClass().getMethod("setGeneratedKeyField", String.class).invoke(meta, "");
    var variables = new org.apache.hop.core.variables.Variables();
    var provider = new MemoryMetadataProvider();

    var unnamed = new org.apache.hop.core.row.RowMeta();
    unnamed.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("source"));
    meta.getFields(unnamed, "TableOutput", null, null, variables, provider);
    assertEquals(1, unnamed.size(),
        "With return_keys=true and an empty return_field, native getFields omits the key");

    variables.setVariable("OUTPUT_KEY_FIELD", "主键<&>雪");
    meta.getClass().getMethod("setGeneratedKeyField", String.class)
        .invoke(meta, "${OUTPUT_KEY_FIELD}");
    var named = new org.apache.hop.core.row.RowMeta();
    named.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("source"));
    meta.getFields(named, "TableOutput", null, null, variables, provider);
    assertEquals(2, named.size());
    assertEquals("主键<&>雪", named.getValueMeta(1).getName());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_INTEGER,
        named.getValueMeta(1).getType());

    meta.getClass().getMethod("setReturningGeneratedKeys", boolean.class).invoke(meta, false);
    var disabled = new org.apache.hop.core.row.RowMeta();
    disabled.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("source"));
    meta.getFields(disabled, "TableOutput", null, null, variables, provider);
    assertEquals(1, disabled.size(), "Disabled generated keys must not add a field");
    System.out.println("PROD_P0_TABLEOUTPUT_GENERATED_KEY_ROW_SCHEMA=PASS");
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

  @Test
  void selectValuesMetadataStageChangesTypeAndFormatsOnlyMatchedPostSelectionField()
      throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "SelectValues");
    assertEquals(Shape.LIST, property(descriptor, "fields", "meta").shape());
    for (String key : List.of("name", "rename", "type", "conversion_mask",
        "encoding", "decimal_symbol", "grouping_symbol")) {
      assertEquals(Shape.STRING, property(descriptor, "fields", "meta", key).shape(), key);
    }
    assertEquals(Shape.NUMBER, property(descriptor, "fields", "meta", "length").shape());
    assertEquals(Shape.NUMBER, property(descriptor, "fields", "meta", "precision").shape());
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "SELECT_VALUES_METADATA_CHANGES_TYPE_AND_FORMAT_AFTER_SELECTION"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/fields/meta"), contract.keyListPointers());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "SelectValues");
    assertNotNull(plugin, "production SelectValues plugin missing");
    var meta = (org.apache.hop.pipeline.transforms.selectvalues.SelectValuesMeta)
        registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    var selected = new org.apache.hop.pipeline.transforms.selectvalues.SelectField();
    selected.setName("原始测站");
    selected.setRename("测站<&>雪");
    var retained = new org.apache.hop.pipeline.transforms.selectvalues.SelectField();
    retained.setName("保留列");
    meta.getSelectOption().setSelectFields(List.of(selected, retained));

    var change = new org.apache.hop.pipeline.transforms.selectvalues.SelectMetadataChange();
    change.setName("测站<&>雪");
    change.setRename("流量_数值");
    change.setType(org.apache.hop.core.row.value.ValueMetaFactory.getValueMetaName(
        org.apache.hop.core.row.IValueMeta.TYPE_NUMBER));
    change.setLength(18);
    change.setPrecision(3);
    change.setConversionMask("#,##0.000");
    change.setDecimalSymbol(".");
    change.setGroupingSymbol(",");
    change.setEncoding("UTF-16LE");
    change.setStorageType(null);
    meta.getSelectOption().setMeta(List.of(change));

    var row = new org.apache.hop.core.row.RowMeta();
    var source = new org.apache.hop.core.row.value.ValueMetaString("原始测站");
    source.setStorageType(org.apache.hop.core.row.IValueMeta.STORAGE_TYPE_BINARY_STRING);
    row.addValueMeta(source);
    row.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("保留列"));
    meta.getFields(row, "select-metadata", null, null,
        new org.apache.hop.core.variables.Variables(), new MemoryMetadataProvider());

    assertEquals(2, row.size(), "metadata conversion must not add output columns");
    var number = row.getValueMeta(0);
    assertEquals("流量_数值", number.getName());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_NUMBER, number.getType());
    assertEquals(org.apache.hop.core.row.IValueMeta.STORAGE_TYPE_NORMAL,
        number.getStorageType(), "type conversion normalizes binary-string storage");
    assertEquals(18, number.getLength());
    assertEquals(3, number.getPrecision());
    assertEquals("#,##0.000", number.getConversionMask());
    assertEquals(".", number.getDecimalSymbol());
    assertEquals(",", number.getGroupingSymbol());
    assertEquals("UTF-16LE", number.getStringEncoding());
    assertEquals("select-metadata", number.getOrigin());
    assertEquals("保留列", row.getValueMeta(1).getName());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_STRING,
        row.getValueMeta(1).getType(), "unmatched column must retain original type");
    System.out.println("PROD_P0_SELECTVALUES_METADATA_TYPE_FORMAT_OUTPUT=PASS");
  }

  @Test
  void filterRowsNestedConditionReferencesMatchNativeOrphanFieldChecks()
      throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "FilterRows");
    assertEquals(Shape.OBJECT, property(descriptor, "compare", "condition").shape());
    assertEquals(Shape.STRING, property(descriptor, "compare", "condition", "leftvalue").shape());
    assertEquals(Shape.STRING, property(descriptor, "compare", "condition", "rightvalue").shape());
    // Nested native Condition children are not exposed as a descriptor LIST today.
    var precondition = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "FILTER_ROWS_NESTED_CONDITION_FIELDS_REQUIRE_UPSTREAM_FIELDS"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/compare/condition"), precondition.keyListPointers());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "FilterRows");
    assertNotNull(plugin);
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    var root = new org.apache.hop.core.Condition();
    root.addCondition(new org.apache.hop.core.Condition(
        "水位", org.apache.hop.core.Condition.Function.EQUAL, null, null));
    var nested = new org.apache.hop.core.Condition();
    nested.addCondition(new org.apache.hop.core.Condition(
        "流量", org.apache.hop.core.Condition.Function.EQUAL, "阈值", null));
    nested.addCondition(new org.apache.hop.core.Condition(
        "字段乙", org.apache.hop.core.Condition.Function.EQUAL, null, null));
    root.addCondition(nested);
    meta.getClass().getMethod("setCondition", org.apache.hop.core.Condition.class)
        .invoke(meta, root);

    var upstream = new org.apache.hop.core.row.RowMeta();
    for (String field : List.of("水位", "流量", "阈值", "字段乙")) {
      upstream.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString(field));
    }
    var orphanMethod = meta.getClass().getMethod("getOrphanFields",
        org.apache.hop.core.Condition.class, org.apache.hop.core.row.IRowMeta.class);
    assertEquals(List.of(), orphanMethod.invoke(meta, root, upstream));
    var transform = new org.apache.hop.pipeline.transform.TransformMeta(
        "FilterRows", "filter-native", meta);
    var variables = new org.apache.hop.core.variables.Variables();
    var provider = new MemoryMetadataProvider();
    List<org.apache.hop.core.ICheckResult> remarks = new ArrayList<>();
    meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
        upstream, new String[] {"source"}, new String[0], null, variables, provider);
    assertFalse(remarks.stream().anyMatch(
        r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR),
        "All nested left and right field references resolve against upstream metadata");

    nested.getCondition(1).setLeftValueName("缺失的深层字段");
    assertEquals(List.of("缺失的深层字段"), orphanMethod.invoke(meta, root, upstream));
    remarks.clear();
    meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
        upstream, new String[] {"source"}, new String[0], null, variables, provider);
    assertTrue(remarks.stream().anyMatch(
        r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR),
        "An unknown field in a nested child must fail native validation");

    nested.getCondition(1).setLeftValueName("字段乙");
    nested.getCondition(0).setRightValueName("缺失右字段");
    assertEquals(List.of("缺失右字段"), orphanMethod.invoke(meta, root, upstream));
    nested.getCondition(0).setRightValueName("阈值");
    meta.getClass().getMethod("setCondition", org.apache.hop.core.Condition.class)
        .invoke(meta, new org.apache.hop.core.Condition());
    remarks.clear();
    meta.check(remarks, new org.apache.hop.pipeline.PipelineMeta(), transform,
        upstream, new String[] {"source"}, new String[0], null, variables, provider);
    assertTrue(remarks.stream().anyMatch(
        r -> r.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR),
        "An empty condition is a native error even when input fields exist");
    System.out.println("PROD_P0_FILTERROWS_NESTED_FIELD_REFERENCES=PASS");
  }

  @Test
  void filterRowsConditionOperandsResetDescendingSortFlagsWithoutChangingOtherFields()
      throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "FilterRows");
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "FILTER_ROWS_CONDITION_FIELDS_RESET_DESCENDING_SORT_METADATA"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/compare/condition"), contract.keyListPointers());
    assertEquals(Shape.OBJECT, property(descriptor, "compare", "condition").shape());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "FilterRows");
    assertNotNull(plugin, "production FilterRows plugin missing");
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    var condition = new org.apache.hop.core.Condition(
        "水位<&>", org.apache.hop.core.Condition.Function.LARGER, "阈值", null);
    meta.getClass().getMethod("setCondition", org.apache.hop.core.Condition.class)
        .invoke(meta, condition);

    var row = new org.apache.hop.core.row.RowMeta();
    var left = new org.apache.hop.core.row.value.ValueMetaNumber("水位<&>");
    var right = new org.apache.hop.core.row.value.ValueMetaNumber("阈值");
    var untouched = new org.apache.hop.core.row.value.ValueMetaString("未参与条件");
    left.setSortedDescending(true);
    right.setSortedDescending(true);
    untouched.setSortedDescending(true);
    left.setCaseInsensitive(true);
    row.addValueMeta(left);
    row.addValueMeta(right);
    row.addValueMeta(untouched);

    meta.getFields(row, "filter-metadata", null, null,
        new org.apache.hop.core.variables.Variables(), new MemoryMetadataProvider());
    assertEquals(3, row.size(), "FilterRows must not add or drop output fields");
    assertEquals(List.of("水位<&>", "阈值", "未参与条件"),
        row.getValueMetaList().stream().map(v -> v.getName()).toList());
    assertSame(left, row.getValueMeta(0), "native getFields mutates existing metadata");
    assertSame(right, row.getValueMeta(1));
    assertSame(untouched, row.getValueMeta(2));
    assertFalse(left.isSortedDescending(), "left operand must not retain descending sort");
    assertFalse(right.isSortedDescending(), "right field operand must not retain descending sort");
    assertTrue(untouched.isSortedDescending(), "unreferenced field must preserve its sort flag");
    assertTrue(left.isCaseInsensitive(), "other metadata flags must remain unchanged");
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_NUMBER, left.getType());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_STRING, untouched.getType());

    meta.getClass().getMethod("setCondition", org.apache.hop.core.Condition.class)
        .invoke(meta, new org.apache.hop.core.Condition());
    var noCondition = new org.apache.hop.core.row.RowMeta();
    var preserved = new org.apache.hop.core.row.value.ValueMetaString("阈值");
    preserved.setSortedDescending(true);
    noCondition.addValueMeta(preserved);
    meta.getFields(noCondition, "filter-empty", null, null,
        new org.apache.hop.core.variables.Variables(), new MemoryMetadataProvider());
    assertTrue(preserved.isSortedDescending(),
        "without condition operands getFields must not clear unrelated sort metadata");
    System.out.println("PROD_P0_FILTERROWS_CONDITION_SORT_FLAG_NORMALIZATION=PASS");
  }

  @Test
  void mergeJoinInfoRowOutputRetainsOrderedTypesAndSuffixesDuplicateNamesWithoutSourceMutation()
      throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "MergeJoin");
    assertEquals(Shape.STRING, property(descriptor, "transform1").shape());
    assertEquals(Shape.STRING, property(descriptor, "transform2").shape());
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "MERGE_JOIN_INFO_ROWS_APPEND_FIELDS_AND_RENAME_DUPLICATE_NAMES"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/transform1", "/transform2"), contract.keyListPointers());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "MergeJoin");
    assertNotNull(plugin, "production MergeJoin plugin missing");
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();

    var left = new org.apache.hop.core.row.RowMeta();
    left.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("站点"));
    left.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("共享<&>雪"));
    left.addValueMeta(new org.apache.hop.core.row.value.ValueMetaInteger("共享<&>雪_1"));
    left.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("左侧流量"));
    var right = new org.apache.hop.core.row.RowMeta();
    right.addValueMeta(new org.apache.hop.core.row.value.ValueMetaNumber("共享<&>雪"));
    right.addValueMeta(new org.apache.hop.core.row.value.ValueMetaInteger("站点"));
    right.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("右侧流量"));

    var variables = new org.apache.hop.core.variables.Variables();
    var provider = new MemoryMetadataProvider();
    var output = new org.apache.hop.core.row.RowMeta();
    meta.getFields(output, "merge-join-schema",
        new org.apache.hop.core.row.IRowMeta[] {left, right},
        null, variables, provider);

    assertArrayEquals(new String[] {"站点", "共享<&>雪", "共享<&>雪_1",
        "左侧流量", "共享<&>雪_2", "站点_1", "右侧流量"},
        output.getFieldNames(), "info stream order and smallest free suffix must survive");
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_NUMBER,
        output.getValueMeta(4).getType(), "renamed right-side field keeps its type");
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_INTEGER,
        output.getValueMeta(5).getType());
    assertEquals("merge-join-schema", output.getValueMeta(4).getOrigin());
    assertEquals("merge-join-schema", output.getValueMeta(5).getOrigin());
    assertNotSame(right.getValueMeta(0), output.getValueMeta(4),
        "native RowMeta clones only the colliding field before renaming it");
    assertArrayEquals(new String[] {"共享<&>雪", "站点", "右侧流量"},
        right.getFieldNames(), "output suffixing must not rename source info metadata");

    var oneSide = new org.apache.hop.core.row.RowMeta();
    meta.getFields(oneSide, "merge-join-schema",
        new org.apache.hop.core.row.IRowMeta[] {null, right},
        null, variables, provider);
    assertArrayEquals(right.getFieldNames(), oneSide.getFieldNames(),
        "null info metadata must not invent fields");
    var missingInfo = new org.apache.hop.core.row.RowMeta();
    meta.getFields(missingInfo, "merge-join-schema", null,
        null, variables, provider);
    assertEquals(0, missingInfo.size());
    System.out.println("PROD_P0_MERGEJOIN_INFO_ROW_DUPLICATE_OUTPUT_SCHEMA=PASS");
  }

  @Test
  void mergeJoinNativeCheckDoesNotValidateJoinTypeOrUnequalKeyArity()
      throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "MergeJoin");
    assertEquals(Shape.STRING, property(descriptor, "join_type").shape());
    List<Property> groupedKeys = descriptor.properties().stream()
        .filter(p -> p.key().equals("key")).toList();
    assertEquals(List.of("keys_1", "keys_2"),
        groupedKeys.stream().map(Property::groupKey).toList());
    assertTrue(groupedKeys.stream().allMatch(p -> p.shape() == Shape.LIST));
    var precondition = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "MERGE_JOIN_NATIVE_CHECK_DOES_NOT_VALIDATE_JOIN_TYPE_OR_KEY_ARITY"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/join_type", "/keys_1/key", "/keys_2/key",
        "/transform1", "/transform2"), precondition.keyListPointers());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "MergeJoin");
    assertNotNull(plugin);
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    Class<?> metaClass = meta.getClass();
    assertArrayEquals(new String[] {"INNER", "LEFT OUTER", "RIGHT OUTER", "FULL OUTER"},
        (String[]) metaClass.getField("joinTypes").get(null));
    assertEquals(2, meta.getTransformIOMeta().getInfoStreams().size());

    metaClass.getMethod("setJoinType", String.class).invoke(meta, "INVALID_JOIN");
    metaClass.getMethod("setKeyFields1", List.class)
        .invoke(meta, List.of("left_a", "left_b"));
    metaClass.getMethod("setKeyFields2", List.class)
        .invoke(meta, List.of("right_a"));
    List<org.apache.hop.core.ICheckResult> remarks = new ArrayList<>();
    var pipeline = new org.apache.hop.pipeline.PipelineMeta();
    var transform = new org.apache.hop.pipeline.transform.TransformMeta(
        "MergeJoin", "join-native", meta);
    var upstream = new org.apache.hop.core.row.RowMeta();
    var variables = new org.apache.hop.core.variables.Variables();
    var provider = new MemoryMetadataProvider();
    meta.check(remarks, pipeline, transform, upstream,
        new String[] {"left", "right"}, new String[0], null, variables, provider);
    assertEquals(1, remarks.size());
    assertEquals(org.apache.hop.core.ICheckResult.TYPE_RESULT_WARNING,
        remarks.get(0).getType(),
        "Native check fails to reject invalid join type and unequal key arity");

    metaClass.getMethod("setJoinType", String.class).invoke(meta, "INNER");
    metaClass.getMethod("setKeyFields1", List.class).invoke(meta, List.of("left_a"));
    remarks.clear();
    meta.check(remarks, pipeline, transform, upstream,
        new String[] {"left", "right"}, new String[0], null, variables, provider);
    assertEquals(1, remarks.size());
    assertEquals(org.apache.hop.core.ICheckResult.TYPE_RESULT_WARNING,
        remarks.get(0).getType(),
        "Native check also returns the same warning for valid join type and key arity");
    System.out.println("PROD_P0_MERGEJOIN_UNCHECKED_JOIN_TYPE_KEY_ARITY=PASS");
  }

  @Test
  void uniqueRowsHashSetNativeCheckIgnoresMissingSelectedComparisonFields() throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "UniqueRowsByHashSet");
    Property compareFields = descriptor.properties().stream()
        .filter(p -> p.key().equals("field") && "fields".equals(p.groupKey()))
        .findFirst().orElseThrow();
    assertEquals(Shape.LIST, compareFields.shape());
    assertTrue(compareFields.elementProperties().stream()
        .anyMatch(p -> p.key().equals("name") && p.shape() == Shape.STRING));
    assertEquals(Shape.BOOLEAN, property(descriptor, "store_values").shape());
    var requirement = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "UNIQUE_ROWS_HASHSET_COMPARE_FIELDS_REQUIRE_INPUT_ROW_FIELDS"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/fields/field"), requirement.keyListPointers());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "UniqueRowsByHashSet");
    assertNotNull(plugin);
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    Class<?> compareFieldType = meta.getClass().getClassLoader().loadClass(
        "org.apache.hop.pipeline.transforms.uniquerowsbyhashset.UniqueRowsByHashSetMeta$CompareField");
    var input = new org.apache.hop.core.row.RowMeta();
    input.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("水位&流量"));
    var variables = new org.apache.hop.core.variables.Variables();
    var provider = new MemoryMetadataProvider();
    var pipeline = new org.apache.hop.pipeline.PipelineMeta();
    var transform = new org.apache.hop.pipeline.transform.TransformMeta(
        "UniqueRowsByHashSet", "unique-hash-native", meta);
    List<org.apache.hop.core.ICheckResult> remarks = new ArrayList<>();

    Object present = compareFieldType.getConstructor(String.class).newInstance("水位&流量");
    meta.getClass().getMethod("setCompareFields", List.class)
        .invoke(meta, List.of(present));
    meta.check(remarks, pipeline, transform, input,
        new String[] {"source"}, new String[0], null, variables, provider);
    assertFalse(remarks.stream().anyMatch(
        x -> x.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR));
    assertEquals(0, input.indexOfValue("水位&流量"));

    Object missing = compareFieldType.getConstructor(String.class).newInstance("未接入字段");
    meta.getClass().getMethod("setCompareFields", List.class)
        .invoke(meta, List.of(missing));
    remarks.clear();
    meta.check(remarks, pipeline, transform, input,
        new String[] {"source"}, new String[0], null, variables, provider);
    assertFalse(remarks.stream().anyMatch(
        x -> x.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR),
        "Native check accepts missing comparison columns when an input hop exists");
    assertEquals(-1, input.indexOfValue("未接入字段"),
        "The runtime resolves comparison columns against this incoming row");

    remarks.clear();
    meta.check(remarks, pipeline, transform, input,
        new String[0], new String[0], null, variables, provider);
    assertTrue(remarks.stream().anyMatch(
        x -> x.getType() == org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR),
        "Native check does reject a missing input hop");
    System.out.println("PROD_P0_UNIQUEROWS_HASHSET_MISSING_COMPARE_FIELD_NATIVE_GAP=PASS");
  }

  @Test
  void concatFieldsRemoveSelectedFieldsProjectsNativeOutputSchemaForSelectedAndEmptyLists()
      throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "ConcatFields");
    assertEquals(Shape.BOOLEAN,
        property(descriptor, "ConcatFields", "removeSelectedFields").shape());
    assertEquals(Shape.STRING,
        property(descriptor, "ConcatFields", "targetFieldName").shape());
    Property selected = descriptor.properties().stream()
        .filter(p -> p.key().equals("field") && "fields".equals(p.groupKey()))
        .findFirst().orElseThrow();
    assertEquals(Shape.LIST, selected.shape());
    var requirement = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "CONCAT_FIELDS_REMOVE_SELECTED_FIELDS_CHANGES_OUTPUT_SCHEMA"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/ConcatFields/removeSelectedFields", "/fields/field",
        "/ConcatFields/targetFieldName"), requirement.keyListPointers());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "ConcatFields");
    assertNotNull(plugin);
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    Object extra = meta.getClass().getMethod("getExtraFields").invoke(meta);
    extra.getClass().getMethod("setTargetFieldName", String.class)
        .invoke(extra, "合并<&>字段");
    Class<?> fieldClass = meta.getClass().getClassLoader().loadClass(
        "org.apache.hop.pipeline.transforms.concatfields.ConcatField");
    Object selectedField = fieldClass.getConstructor().newInstance();
    fieldClass.getMethod("setName", String.class).invoke(selectedField, "订单<&>号");
    meta.getClass().getMethod("setOutputFields", List.class)
        .invoke(meta, List.of(selectedField));

    var variables = new org.apache.hop.core.variables.Variables();
    var provider = new MemoryMetadataProvider();
    var kept = new org.apache.hop.core.row.RowMeta();
    kept.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("订单<&>号"));
    kept.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("地区"));
    kept.addValueMeta(new org.apache.hop.core.row.value.ValueMetaInteger("数量"));
    meta.getFields(kept, "concat-native", null, null, variables, provider);
    assertEquals(List.of("订单<&>号", "地区", "数量", "合并<&>字段"),
        kept.getValueMetaList().stream().map(v -> v.getName()).toList());

    extra.getClass().getMethod("setRemoveSelectedFields", boolean.class)
        .invoke(extra, true);
    var removed = new org.apache.hop.core.row.RowMeta();
    removed.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("订单<&>号"));
    removed.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("地区"));
    removed.addValueMeta(new org.apache.hop.core.row.value.ValueMetaInteger("数量"));
    meta.getFields(removed, "concat-native", null, null, variables, provider);
    assertEquals(List.of("地区", "数量", "合并<&>字段"),
        removed.getValueMetaList().stream().map(v -> v.getName()).toList());

    meta.getClass().getMethod("setOutputFields", List.class)
        .invoke(meta, List.of());
    var cleared = new org.apache.hop.core.row.RowMeta();
    cleared.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("订单<&>号"));
    cleared.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("地区"));
    cleared.addValueMeta(new org.apache.hop.core.row.value.ValueMetaInteger("数量"));
    meta.getFields(cleared, "concat-native", null, null, variables, provider);
    assertEquals(1, cleared.size());
    assertEquals("合并<&>字段", cleared.getValueMeta(0).getName());
    assertEquals(org.apache.hop.core.row.value.ValueMetaString.TYPE_STRING,
        cleared.getValueMeta(0).getType());
    System.out.println("PROD_P0_CONCATFIELDS_REMOVE_SELECTED_OUTPUT_SCHEMA=PASS");
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
  void checkSumResultNameCollisionAndResolvedEmptySurviveRealHplDiskReopen(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "CheckSum");
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "CHECKSUM_RESULT_NAME_COLLISION_AND_RESOLVED_EMPTY"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/resultfieldName"), contract.keyListPointers());
    assertEquals(Shape.STRING, property(descriptor, "resultfieldName").shape());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "CheckSum");
    assertNotNull(plugin, "production CheckSum plugin missing");
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();

    var provider = new MemoryMetadataProvider();
    var variables = new org.apache.hop.core.variables.Variables();
    variables.setVariable("CHECKSUM_TARGET", "摘要<&>雪");
    PipelineDocumentStore store = new PipelineDocumentStore(provider, variables);
    java.nio.file.Path hpl = tempDir.resolve("checksum-result-name.hpl");
    org.apache.hop.pipeline.PipelineMeta pipeline = new org.apache.hop.pipeline.PipelineMeta();
    pipeline.setName("checksum-result-name");
    pipeline.addTransform(new org.apache.hop.pipeline.transform.TransformMeta(
        "CheckSum", "checksum-node", meta));
    store.save(hpl, pipeline);
    org.apache.hop.pipeline.PipelineMeta loaded = store.open(hpl);
    PipelineDocumentRegistry documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("checksum-name-doc", hpl, loaded));
    PipelineConfigResource resource = new PipelineConfigResource(documents, provider);
    jakarta.ws.rs.core.Response read = resource.read(
        "checksum-name-doc", new PipelineConfigResource.ReadRequest("checksum-node"));
    assertEquals(200, read.getStatus());
    PipelineConfigResource.ConfigResponse initial =
        (PipelineConfigResource.ConfigResponse) read.getEntity();
    com.fasterxml.jackson.databind.node.ObjectNode changed =
        ((com.fasterxml.jackson.databind.node.ObjectNode) initial.config()).deepCopy();
    changed.put("resultfieldName", "${CHECKSUM_TARGET}");
    changed.put("checksumtype", "CRC32");
    jakarta.ws.rs.core.Response write = resource.write(
        "checksum-name-doc", new PipelineConfigResource.WriteRequest("checksum-node", changed));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    org.apache.hop.pipeline.PipelineMeta disk = store.open(hpl);
    org.apache.hop.pipeline.transform.TransformMeta transform =
        disk.findTransform("checksum-node");
    assertNotNull(transform);
    ITransformMeta nativeMeta = transform.getTransform();
    com.fasterxml.jackson.databind.JsonNode wire =
        org.apache.hop.metadata.serializer.json.ConfigJsonSerializer.toJson(nativeMeta, provider);
    assertEquals("${CHECKSUM_TARGET}", wire.at("/resultfieldName").asText());
    assertEquals("CRC32", wire.at("/checksumtype").asText());

    var row = new org.apache.hop.core.row.RowMeta();
    row.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("摘要<&>雪"));
    row.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("摘要<&>雪_1"));
    row.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("保留"));
    nativeMeta.getFields(row, "checksum-node", new org.apache.hop.core.row.IRowMeta[0],
        null, variables, provider);
    assertEquals(List.of("摘要<&>雪", "摘要<&>雪_1", "保留", "摘要<&>雪_2"),
        row.getValueMetaList().stream().map(v -> v.getName()).toList());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_INTEGER, row.getValueMeta(3).getType());
    assertEquals("checksum-node", row.getValueMeta(3).getOrigin());

    variables.setVariable("CHECKSUM_TARGET", "");
    var resolvedEmpty = new org.apache.hop.core.row.RowMeta();
    resolvedEmpty.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("保留"));
    nativeMeta.getFields(resolvedEmpty, "checksum-node",
        new org.apache.hop.core.row.IRowMeta[0], null, variables, provider);
    assertEquals(2, resolvedEmpty.size(),
        "nonempty configured expression is checked before variable resolution");
    assertEquals("", resolvedEmpty.getValueMeta(1).getName());

    nativeMeta.getClass().getMethod("setResultFieldName", String.class)
        .invoke(nativeMeta, "");
    var configuredEmpty = new org.apache.hop.core.row.RowMeta();
    nativeMeta.getFields(configuredEmpty, "checksum-node",
        new org.apache.hop.core.row.IRowMeta[0], null, variables, provider);
    assertEquals(0, configuredEmpty.size(),
        "empty configured name must not append a result field");
    System.out.println("PROD_P0_CHECKSUM_RESOLVED_NAME_COLLISION_DISK_REOPEN=PASS");
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
  void jsonInputSourceRemovalAndAuxiliaryOutputSchemaSurviveRealHplDiskReopen(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(plugins, "JsonInput");
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals("JSON_INPUT_SOURCE_REMOVAL_AND_AUXILIARY_OUTPUT_COLUMNS"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/IsInFields", "/removeSourceField", "/valueField",
        "/fields/field", "/include", "/include_field", "/rownum", "/rownum_field"),
        contract.keyListPointers());
    for (String key : List.of("IsInFields", "removeSourceField", "include", "rownum")) {
      assertEquals(Shape.BOOLEAN, property(descriptor, key).shape(), key);
    }
    assertEquals(Shape.LIST, property(descriptor, "field").shape());
    assertEquals("fields", property(descriptor, "field").groupKey());

    IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "JsonInput");
    assertNotNull(plugin);
    ITransformMeta meta = plugins.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    Class<?> fieldType = meta.getClass().getClassLoader().loadClass(
        "org.apache.hop.pipeline.transforms.jsoninput.JsonInputField");
    Object extracted = fieldType.getConstructor(String.class).newInstance("${EXTRACTED}");
    fieldType.getMethod("setPath", String.class).invoke(extracted, "$.data['雪<&>']");
    fieldType.getMethod("setType", int.class).invoke(extracted,
        org.apache.hop.core.row.IValueMeta.TYPE_INTEGER);
    fieldType.getMethod("setLength", int.class).invoke(extracted, 12);
    fieldType.getMethod("setPrecision", int.class).invoke(extracted, 0);
    meta.getClass().getMethod("setInputFields", List.class).invoke(meta, List.of(extracted));

    var provider = new MemoryMetadataProvider();
    var variables = new org.apache.hop.core.variables.Variables();
    variables.setVariable("EXTRACTED", "提取<&>雪");
    variables.setVariable("FILE_COL", "源文件<&>");
    variables.setVariable("ROW_COL", "行号雪");
    PipelineDocumentStore store = new PipelineDocumentStore(provider, variables);
    java.nio.file.Path hpl = tempDir.resolve("jsoninput-aux-output.hpl");
    var pipeline = new org.apache.hop.pipeline.PipelineMeta();
    pipeline.setName("jsoninput-aux-output");
    pipeline.addTransform(new org.apache.hop.pipeline.transform.TransformMeta(
        "JsonInput", "json-aux", meta));
    store.save(hpl, pipeline);
    var loaded = store.open(hpl);
    PipelineDocumentRegistry documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("json-aux-doc", hpl, loaded));
    PipelineConfigResource resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("json-aux-doc",
        new PipelineConfigResource.ReadRequest("json-aux"));
    assertEquals(200, read.getStatus());
    var initial = (PipelineConfigResource.ConfigResponse) read.getEntity();
    var changed = ((com.fasterxml.jackson.databind.node.ObjectNode) initial.config()).deepCopy();
    changed.put("IsInFields", true);
    changed.put("valueField", "payload");
    changed.put("removeSourceField", true);
    changed.put("include", true);
    changed.put("include_field", "${FILE_COL}");
    changed.put("rownum", true);
    changed.put("rownum_field", "${ROW_COL}");
    var write = resource.write("json-aux-doc",
        new PipelineConfigResource.WriteRequest("json-aux", changed));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    var reopened = store.open(hpl);
    var diskTransform = reopened.findTransform("json-aux");
    assertNotNull(diskTransform);
    var disk = org.apache.hop.metadata.serializer.json.ConfigJsonSerializer.toJson(
        diskTransform.getTransform(), provider);
    assertTrue(disk.path("IsInFields").booleanValue());
    assertTrue(disk.path("removeSourceField").booleanValue());
    assertTrue(disk.path("include").booleanValue());
    assertTrue(disk.path("rownum").booleanValue());
    assertEquals("payload", disk.path("valueField").asText());
    assertEquals("${FILE_COL}", disk.path("include_field").asText());
    assertEquals("${ROW_COL}", disk.path("rownum_field").asText());
    assertEquals("${EXTRACTED}", disk.at("/fields/field/0/name").asText());

    var row = new org.apache.hop.core.row.RowMeta();
    row.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("payload"));
    row.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("保留"));
    diskTransform.getTransform().getFields(
        row, "json-aux", null, null, variables, provider);
    assertEquals(4, row.size());
    assertEquals("保留", row.getValueMeta(0).getName());
    assertEquals("提取<&>雪", row.getValueMeta(1).getName());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_INTEGER,
        row.getValueMeta(1).getType());
    assertEquals(12, row.getValueMeta(1).getLength());
    assertEquals("源文件<&>", row.getValueMeta(2).getName());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_STRING,
        row.getValueMeta(2).getType());
    assertEquals(250, row.getValueMeta(2).getLength());
    assertEquals("行号雪", row.getValueMeta(3).getName());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_INTEGER,
        row.getValueMeta(3).getType());
    assertEquals(org.apache.hop.core.row.IValueMeta.DEFAULT_INTEGER_LENGTH,
        row.getValueMeta(3).getLength());
    System.out.println("PROD_P0_JSONINPUT_SOURCE_REMOVAL_AUX_OUTPUT_DISK_REOPEN=PASS");
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

  @Test
  void groupByAggregateOutputTypesFollowNativeOperationAndSubjectMetadata() throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "GroupBy");
    Property aggregationFields = descriptor.properties().stream()
        .filter(p -> p.key().equals("field") && p.groupKey().equals("fields"))
        .findFirst().orElseThrow();
    assertEquals(Shape.LIST, aggregationFields.shape());
    assertEquals(Shape.STRING, aggregationFields.elementProperties().stream()
        .filter(p -> p.key().equals("type")).findFirst().orElseThrow().shape());
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "GROUPBY_AGGREGATE_OUTPUT_TYPES_FOLLOW_OPERATION_AND_SUBJECT"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/fields/field", "/group/field", "/all_rows"),
        contract.keyListPointers());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "GroupBy");
    assertNotNull(plugin, "production GroupBy plugin missing");
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    ClassLoader loader = meta.getClass().getClassLoader();
    Class<?> groupingClass = loader.loadClass(
        "org.apache.hop.pipeline.transforms.groupby.GroupingField");
    Object grouping = groupingClass.getConstructor(String.class).newInstance("地区");
    meta.getClass().getMethod("setGroupingFields", List.class)
        .invoke(meta, List.of(grouping));

    Class<?> aggregationClass = loader.loadClass(
        "org.apache.hop.pipeline.transforms.groupby.Aggregation");
    var setField = aggregationClass.getMethod("setField", String.class);
    var setSubject = aggregationClass.getMethod("setSubject", String.class);
    var setTypeLabel = aggregationClass.getMethod("setTypeLabel", String.class);
    String[][] rules = {
        {"任意计数", null, "COUNT_ANY"},
        {"总行数", "整数", "COUNT_ALL"},
        {"字符求和", "类别", "SUM"},
        {"累计均值", "整数", "CUM_AVG"},
        {"首次日期", "事件日期", "FIRST"},
        {"拼接类别", "类别", "CONCAT_COMMA"},
        {"标准差", "流量", "STD_DEV"},
        {"不存在的聚合", "缺失字段", "SUM"}
    };
    List<Object> aggregates = new ArrayList<>();
    for (String[] rule : rules) {
      Object aggregate = aggregationClass.getConstructor().newInstance();
      setField.invoke(aggregate, rule[0]);
      setSubject.invoke(aggregate, rule[1]);
      setTypeLabel.invoke(aggregate, rule[2]);
      aggregates.add(aggregate);
    }
    meta.getClass().getMethod("setAggregations", List.class).invoke(meta, aggregates);
    meta.getClass().getMethod("setPassAllRows", boolean.class).invoke(meta, false);

    var incoming = new org.apache.hop.core.row.RowMeta();
    incoming.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("地区"));
    incoming.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("类别"));
    incoming.addValueMeta(new org.apache.hop.core.row.value.ValueMetaInteger("整数"));
    incoming.addValueMeta(new org.apache.hop.core.row.value.ValueMetaNumber("流量"));
    var date = new org.apache.hop.core.row.value.ValueMetaDate("事件日期");
    date.setConversionMask("yyyy-MM-dd");
    incoming.addValueMeta(date);
    var variables = new org.apache.hop.core.variables.Variables();
    var provider = new MemoryMetadataProvider();

    var grouped = new org.apache.hop.core.row.RowMeta();
    grouped.addRowMeta(incoming);
    meta.getFields(grouped, "groupby-aggregate-output", null, null, variables, provider);
    assertEquals(8, grouped.size(), "one grouping key and seven valid aggregates");
    assertEquals("地区", grouped.getValueMeta(0).getName());
    String[] expectedNames = {
        "任意计数", "总行数", "字符求和", "累计均值",
        "首次日期", "拼接类别", "标准差"
    };
    int[] expectedTypes = {
        org.apache.hop.core.row.IValueMeta.TYPE_INTEGER,
        org.apache.hop.core.row.IValueMeta.TYPE_INTEGER,
        org.apache.hop.core.row.IValueMeta.TYPE_NUMBER,
        org.apache.hop.core.row.IValueMeta.TYPE_NUMBER,
        org.apache.hop.core.row.IValueMeta.TYPE_DATE,
        org.apache.hop.core.row.IValueMeta.TYPE_STRING,
        org.apache.hop.core.row.IValueMeta.TYPE_NUMBER
    };
    for (int i = 0; i < expectedNames.length; i++) {
      assertEquals(expectedNames[i], grouped.getValueMeta(i + 1).getName());
      assertEquals(expectedTypes[i], grouped.getValueMeta(i + 1).getType(),
          "native aggregate output type: " + expectedNames[i]);
    }
    assertEquals(org.apache.hop.core.row.IValueMeta.DEFAULT_INTEGER_LENGTH,
        grouped.getValueMeta(1).getLength());
    assertEquals(0, grouped.getValueMeta(1).getPrecision());
    assertEquals("yyyy-MM-dd", grouped.getValueMeta(5).getConversionMask());
    assertEquals(-1, grouped.indexOfValue("不存在的聚合"),
        "missing subjects are omitted from projected metadata");

    meta.getClass().getMethod("setPassAllRows", boolean.class).invoke(meta, true);
    var passThrough = new org.apache.hop.core.row.RowMeta();
    passThrough.addRowMeta(incoming);
    meta.getFields(passThrough, "groupby-aggregate-output", null, null, variables, provider);
    assertEquals(incoming.size() + expectedNames.length, passThrough.size());
    for (int i = 0; i < incoming.size(); i++) {
      assertEquals(incoming.getValueMeta(i).getName(),
          passThrough.getValueMeta(i).getName());
    }
    for (int i = 0; i < expectedNames.length; i++) {
      assertEquals(expectedNames[i], passThrough.getValueMeta(incoming.size() + i).getName());
    }
    System.out.println("PROD_P0_GROUPBY_AGGREGATE_OUTPUT_TYPES=PASS");
  }

  @Test
  void groupByLineNumberOutputMetadataRequiresAllRowsFlagAndNonemptyName() throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "GroupBy");
    assertEquals(Shape.BOOLEAN, property(descriptor, "all_rows").shape());
    assertEquals(Shape.BOOLEAN, property(descriptor, "add_linenr").shape());
    assertEquals(Shape.STRING, property(descriptor, "linenr_fieldname").shape());
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals("GROUPBY_LINE_NUMBER_REQUIRES_PASS_ALL_ROWS_AND_FIELD_NAME"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/all_rows", "/add_linenr", "/linenr_fieldname"),
        contract.keyListPointers());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "GroupBy");
    assertNotNull(plugin, "production GroupBy plugin missing");
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    var setAllRows = meta.getClass().getMethod("setPassAllRows", boolean.class);
    var setAddLine = meta.getClass().getMethod("setAddingLineNrInGroup", boolean.class);
    var setLineName = meta.getClass().getMethod("setLineNrInGroupField", String.class);
    var variables = new org.apache.hop.core.variables.Variables();
    var provider = new MemoryMetadataProvider();
    var incoming = new org.apache.hop.core.row.RowMeta();
    incoming.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("流域"));
    incoming.addValueMeta(new org.apache.hop.core.row.value.ValueMetaNumber("流量"));

    setAllRows.invoke(meta, true);
    setAddLine.invoke(meta, false);
    setLineName.invoke(meta, "组内序号");
    var disabled = new org.apache.hop.core.row.RowMeta();
    disabled.addRowMeta(incoming);
    meta.getFields(disabled, "groupby-line-number", null, null, variables, provider);
    assertEquals(2, disabled.size(), "add_linenr=false must preserve only input fields");

    setAddLine.invoke(meta, true);
    setLineName.invoke(meta, "");
    var unnamed = new org.apache.hop.core.row.RowMeta();
    unnamed.addRowMeta(incoming);
    meta.getFields(unnamed, "groupby-line-number", null, null, variables, provider);
    assertEquals(2, unnamed.size(), "blank linenr_fieldname must not append a field");

    setLineName.invoke(meta, "组内序号");
    var enabled = new org.apache.hop.core.row.RowMeta();
    enabled.addRowMeta(incoming);
    meta.getFields(enabled, "groupby-line-number", null, null, variables, provider);
    assertEquals(3, enabled.size());
    assertEquals("流域", enabled.getValueMeta(0).getName());
    assertEquals("流量", enabled.getValueMeta(1).getName());
    assertEquals("组内序号", enabled.getValueMeta(2).getName());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_INTEGER,
        enabled.getValueMeta(2).getType());

    setAllRows.invoke(meta, false);
    var groupedOnly = new org.apache.hop.core.row.RowMeta();
    groupedOnly.addRowMeta(incoming);
    meta.getFields(groupedOnly, "groupby-line-number", null, null, variables, provider);
    assertEquals(-1, groupedOnly.indexOfValue("组内序号"),
        "all_rows=false must suppress group line number even if add_linenr=true");
    System.out.println("PROD_P0_GROUPBY_LINE_NUMBER_OUTPUT_SCHEMA=PASS");
  }

  @Test
  void replaceStringOutputAliasAddsVariableResolvedStringColumnWithoutDroppingSource()
      throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "ReplaceString");
    assertEquals(Shape.LIST, property(descriptor, "field").shape());
    assertEquals(Shape.STRING, property(descriptor, "field", "out_stream_name").shape());
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "REPLACE_STRING_OUTPUT_ALIAS_APPENDS_VARIABLE_RESOLVED_STRING_FIELD"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/fields/field"), contract.keyListPointers());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "ReplaceString");
    assertNotNull(plugin, "production ReplaceString plugin missing");
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    Class<?> item = meta.getClass().getClassLoader().loadClass(
        "org.apache.hop.pipeline.transforms.replacestring.ReplaceStringMeta$RSField");
    Object rule = item.getConstructor().newInstance();
    item.getMethod("setFieldInStream", String.class).invoke(rule, "上游原字段");
    var setAlias = item.getMethod("setFieldOutStream", String.class);
    var setRules = meta.getClass().getMethod("setFields", List.class);
    setRules.invoke(meta, List.of(rule));
    var variables = new org.apache.hop.core.variables.Variables();
    variables.setVariable("RS_OUTPUT_ALIAS", "替换结果_黄河");
    var provider = new MemoryMetadataProvider();
    var incoming = new org.apache.hop.core.row.RowMeta();
    incoming.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("上游原字段"));
    incoming.addValueMeta(new org.apache.hop.core.row.value.ValueMetaNumber("流量"));

    setAlias.invoke(rule, "${RS_OUTPUT_ALIAS}");
    var aliased = new org.apache.hop.core.row.RowMeta();
    aliased.addRowMeta(incoming);
    meta.getFields(aliased, "replace-string", null, null, variables, provider);
    assertEquals(3, aliased.size(), "an alias must append rather than replace the source");
    assertEquals("上游原字段", aliased.getValueMeta(0).getName());
    assertEquals("流量", aliased.getValueMeta(1).getName());
    assertEquals("替换结果_黄河", aliased.getValueMeta(2).getName());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_STRING,
        aliased.getValueMeta(2).getType());

    setAlias.invoke(rule, "");
    var inPlace = new org.apache.hop.core.row.RowMeta();
    inPlace.addRowMeta(incoming);
    meta.getFields(inPlace, "replace-string", null, null, variables, provider);
    assertEquals(2, inPlace.size(), "blank alias must not add an output column");
    assertEquals("上游原字段", inPlace.getValueMeta(0).getName());

    setAlias.invoke(rule, "上游原字段");
    var duplicate = new org.apache.hop.core.row.RowMeta();
    duplicate.addRowMeta(incoming);
    meta.getFields(duplicate, "replace-string", null, null, variables, provider);
    assertEquals(3, duplicate.size(),
        "native metadata projection appends a column even when the alias collides");
    assertEquals("上游原字段_1", duplicate.getValueMeta(2).getName(),
        "Hop RowMeta disambiguates a colliding appended alias with a numeric suffix");
    System.out.println("PROD_P0_REPLACESTRING_ALIAS_OUTPUT_SCHEMA=PASS");
  }

  @Test
  void replaceStringOutputInheritsSourceEncodingAndInPlaceRuleNormalizesStorage()
      throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "ReplaceString");
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "REPLACE_STRING_OUTPUT_ENCODING_FOLLOWS_SOURCE_AND_INPLACE_NORMALIZES_STORAGE"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/fields/field"), contract.keyListPointers());
    assertEquals(Shape.STRING, property(descriptor, "field", "in_stream_name").shape());
    assertEquals(Shape.STRING, property(descriptor, "field", "out_stream_name").shape());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "ReplaceString");
    assertNotNull(plugin, "production ReplaceString plugin missing");
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    Class<?> item = meta.getClass().getClassLoader().loadClass(
        "org.apache.hop.pipeline.transforms.replacestring.ReplaceStringMeta$RSField");
    Object append = item.getConstructor().newInstance();
    item.getMethod("setFieldInStream", String.class).invoke(append, "编码源");
    item.getMethod("setFieldOutStream", String.class).invoke(append, "编码副本");
    Object inPlace = item.getConstructor().newInstance();
    item.getMethod("setFieldInStream", String.class).invoke(inPlace, "原地替换");
    item.getMethod("setFieldOutStream", String.class).invoke(inPlace, "");
    var setRules = meta.getClass().getMethod("setFields", List.class);
    setRules.invoke(meta, List.of(append, inPlace));

    var source = new org.apache.hop.core.row.value.ValueMetaString("编码源");
    source.setStringEncoding("UTF-16LE");
    var changed = new org.apache.hop.core.row.value.ValueMetaString("原地替换");
    changed.setStorageType(org.apache.hop.core.row.IValueMeta.STORAGE_TYPE_BINARY_STRING);
    var untouched = new org.apache.hop.core.row.value.ValueMetaString("其他字段");
    untouched.setStorageType(org.apache.hop.core.row.IValueMeta.STORAGE_TYPE_BINARY_STRING);
    var row = new org.apache.hop.core.row.RowMeta();
    row.addValueMeta(source);
    row.addValueMeta(changed);
    row.addValueMeta(untouched);

    meta.getFields(row, "replace-encoding", null, null,
        new org.apache.hop.core.variables.Variables(), new MemoryMetadataProvider());
    assertEquals(4, row.size());
    assertEquals(List.of("编码源", "原地替换", "其他字段", "编码副本"),
        List.of(row.getValueMeta(0).getName(), row.getValueMeta(1).getName(),
            row.getValueMeta(2).getName(), row.getValueMeta(3).getName()));
    assertEquals("UTF-16LE", row.getValueMeta(3).getStringEncoding(),
        "appended STRING field inherits source encoding");
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_STRING, row.getValueMeta(3).getType());
    assertEquals("replace-encoding", row.getValueMeta(3).getOrigin());
    assertEquals(org.apache.hop.core.row.IValueMeta.STORAGE_TYPE_NORMAL,
        row.getValueMeta(1).getStorageType(),
        "in-place replacement normalizes storage without changing the field position");
    assertEquals(org.apache.hop.core.row.IValueMeta.STORAGE_TYPE_BINARY_STRING,
        row.getValueMeta(2).getStorageType(), "unrelated field storage must remain unchanged");

    Object missing = item.getConstructor().newInstance();
    item.getMethod("setFieldInStream", String.class).invoke(missing, "不存在的输入");
    item.getMethod("setFieldOutStream", String.class).invoke(missing, "");
    setRules.invoke(meta, List.of(missing));
    var missingRow = new org.apache.hop.core.row.RowMeta();
    missingRow.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("仍然存在"));
    meta.getFields(missingRow, "replace-encoding", null, null,
        new org.apache.hop.core.variables.Variables(), new MemoryMetadataProvider());
    assertEquals(1, missingRow.size(),
        "native metadata projection skips missing in-place fields; check() validates them");
    System.out.println("PROD_P0_REPLACESTRING_ENCODING_STORAGE_OUTPUT_META=PASS");
  }

  @Test
  void selectValuesUnspecifiedFieldsFollowSelectedOrderThenAlphabeticalExtras() throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "SelectValues");
    assertEquals(Shape.OBJECT, property(descriptor, "fields").shape());
    assertEquals(Shape.BOOLEAN, property(descriptor, "fields", "select_unspecified").shape());
    assertEquals(Shape.LIST, property(descriptor, "fields", "field").shape());
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "SELECT_VALUES_UNSPECIFIED_FIELDS_SORT_AFTER_EXPLICIT_SELECTION"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/fields/select_unspecified", "/fields/field"),
        contract.keyListPointers());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "SelectValues");
    assertNotNull(plugin, "production SelectValues plugin missing");
    var meta = (org.apache.hop.pipeline.transforms.selectvalues.SelectValuesMeta)
        registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    var selected = new org.apache.hop.pipeline.transforms.selectvalues.SelectField();
    selected.setName("黄河流量");
    selected.setRename("选中_流量");
    meta.getSelectOption().setSelectFields(List.of(selected));

    var input = new org.apache.hop.core.row.RowMeta();
    for (String field : List.of("zeta", "beta", "黄河流量", "alpha")) {
      input.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString(field));
    }
    var variables = new org.apache.hop.core.variables.Variables();
    var provider = new MemoryMetadataProvider();

    meta.getSelectOption().setSelectingAndSortingUnspecifiedFields(true);
    var withExtras = new org.apache.hop.core.row.RowMeta();
    withExtras.addRowMeta(input);
    meta.getFields(withExtras, "select-values", null, null, variables, provider);
    assertEquals(4, withExtras.size());
    assertEquals(List.of("选中_流量", "alpha", "beta", "zeta"),
        List.of(withExtras.getValueMeta(0).getName(), withExtras.getValueMeta(1).getName(),
            withExtras.getValueMeta(2).getName(), withExtras.getValueMeta(3).getName()),
        "explicit selection comes first; unspecified fields are alphabetized");

    meta.getSelectOption().setSelectingAndSortingUnspecifiedFields(false);
    var selectedOnly = new org.apache.hop.core.row.RowMeta();
    selectedOnly.addRowMeta(input);
    meta.getFields(selectedOnly, "select-values", null, null, variables, provider);
    assertEquals(1, selectedOnly.size());
    assertEquals("选中_流量", selectedOnly.getValueMeta(0).getName());

    meta.getSelectOption().setSelectFields(List.of());
    meta.getSelectOption().setSelectingAndSortingUnspecifiedFields(true);
    var noExplicitSelection = new org.apache.hop.core.row.RowMeta();
    noExplicitSelection.addRowMeta(input);
    meta.getFields(noExplicitSelection, "select-values", null, null, variables, provider);
    assertEquals(4, noExplicitSelection.size());
    assertEquals(List.of("zeta", "beta", "黄河流量", "alpha"),
        List.of(noExplicitSelection.getValueMeta(0).getName(),
            noExplicitSelection.getValueMeta(1).getName(),
            noExplicitSelection.getValueMeta(2).getName(),
            noExplicitSelection.getValueMeta(3).getName()),
        "without an explicit select list, the sort-unspecified flag is a no-op");
    System.out.println("PROD_P0_SELECTVALUES_UNSPECIFIED_OUTPUT_ORDER=PASS");
  }

  @Test
  void scriptValueModOutputReplacementPrefersNameThenFallsBackToRename() throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "ScriptValueMod");
    // The producer descriptor is a root LIST with groupKey=fields; the Hop JSON wire
    // wraps it at /fields/field. It is not a nested descriptor OBJECT.
    assertEquals(Shape.LIST, property(descriptor, "field").shape());
    assertEquals("fields", property(descriptor, "field").groupKey());
    assertEquals(Shape.STRING, property(descriptor, "field", "name").shape());
    assertEquals(Shape.STRING, property(descriptor, "field", "rename").shape());
    assertEquals(Shape.BOOLEAN, property(descriptor, "field", "replace").shape());
    assertEquals(Shape.NUMBER, property(descriptor, "field", "type").shape());
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals("SCRIPT_VALUE_MOD_REPLACE_TARGET_FALLS_BACK_TO_RENAME"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/fields/field"), contract.keyListPointers());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "ScriptValueMod");
    assertNotNull(plugin, "production JavaScript transform plugin missing");
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    // Reflect on the production plugin to keep the standalone server and v2 jobs
    // independent of the P0-only javascript plugin Maven dependency.
    Class<?> fieldType = java.util.Arrays.stream(meta.getClass().getDeclaredClasses())
        .filter(c -> c.getSimpleName().equals("ScriptField")).findFirst().orElseThrow();
    Field scriptFields = metadataField(meta.getClass(), "scriptFields");
    var provider = new MemoryMetadataProvider();
    var variables = new org.apache.hop.core.variables.Variables();

    scriptFields.set(meta, List.of(newScriptField(fieldType, "脚本结果", "新增列",
        org.apache.hop.core.row.IValueMeta.TYPE_INTEGER, 9, 0, false)));
    var appended = new org.apache.hop.core.row.RowMeta();
    appended.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("输入值"));
    appended.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("候选别名"));
    meta.getFields(appended, "javascript", null, null, variables, provider);
    assertEquals(3, appended.size());
    assertEquals("新增列", appended.getValueMeta(2).getName());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_INTEGER,
        appended.getValueMeta(2).getType());
    assertEquals(9, appended.getValueMeta(2).getLength());

    scriptFields.set(meta, List.of(newScriptField(fieldType, "不存在的脚本名", "候选别名",
        org.apache.hop.core.row.IValueMeta.TYPE_NUMBER, 14, 3, true)));
    var fallbackRow = new org.apache.hop.core.row.RowMeta();
    fallbackRow.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("输入值"));
    fallbackRow.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("候选别名"));
    meta.getFields(fallbackRow, "javascript", null, null, variables, provider);
    assertEquals(2, fallbackRow.size(), "replacement must not append a field");
    assertEquals("候选别名", fallbackRow.getValueMeta(1).getName());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_NUMBER,
        fallbackRow.getValueMeta(1).getType());
    assertEquals(14, fallbackRow.getValueMeta(1).getLength());
    assertEquals(3, fallbackRow.getValueMeta(1).getPrecision());

    scriptFields.set(meta, List.of(newScriptField(fieldType, "输入值", "候选别名",
        org.apache.hop.core.row.IValueMeta.TYPE_INTEGER, 7, 0, true)));
    var preferred = new org.apache.hop.core.row.RowMeta();
    preferred.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("输入值"));
    preferred.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("候选别名"));
    meta.getFields(preferred, "javascript", null, null, variables, provider);
    assertEquals(2, preferred.size());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_INTEGER,
        preferred.getValueMeta(0).getType(), "name wins over rename");
    assertEquals(7, preferred.getValueMeta(0).getLength());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_STRING,
        preferred.getValueMeta(1).getType(), "fallback alias must remain unchanged");

    scriptFields.set(meta, List.of(newScriptField(fieldType, "完全不存在", "也不存在",
        org.apache.hop.core.row.IValueMeta.TYPE_INTEGER, 7, 0, true)));
    var invalid = new org.apache.hop.core.row.RowMeta();
    invalid.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("输入值"));
    assertThrows(org.apache.hop.core.exception.HopTransformException.class,
        () -> meta.getFields(invalid, "javascript", null, null, variables, provider));
    System.out.println("PROD_P0_SCRIPTVALUE_REPLACE_NAME_RENAME_OUTPUT_SCHEMA=PASS");
  }

  @Test
  void scriptValueModLiteralAliasesAndEmptySourceNameSurviveRealHplDiskReopen(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(plugins, "ScriptValueMod");
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "SCRIPT_VALUE_MOD_LITERAL_ALIAS_COLLISION_AND_EMPTY_NAME_SKIP"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/fields/field"), contract.keyListPointers());
    assertEquals(Shape.LIST, property(descriptor, "field").shape());
    assertEquals("fields", property(descriptor, "field").groupKey());
    assertEquals(Shape.STRING, property(descriptor, "field", "rename").shape());
    assertEquals(Shape.BOOLEAN, property(descriptor, "field", "replace").shape());

    IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "ScriptValueMod");
    assertNotNull(plugin, "production ScriptValueMod plugin missing");
    ITransformMeta meta = plugins.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    Class<?> fieldType = java.util.Arrays.stream(meta.getClass().getDeclaredClasses())
        .filter(c -> c.getSimpleName().equals("ScriptField")).findFirst().orElseThrow();
    Field fields = metadataField(meta.getClass(), "scriptFields");
    fields.set(meta, List.of(
        newScriptField(fieldType, "", "不可输出的别名", 
            org.apache.hop.core.row.IValueMeta.TYPE_INTEGER, 5, 0, false),
        newScriptField(fieldType, "jsOne", "字段<&>雪",
            org.apache.hop.core.row.IValueMeta.TYPE_INTEGER, 9, 0, false),
        newScriptField(fieldType, "jsTwo", "字段<&>雪",
            org.apache.hop.core.row.IValueMeta.TYPE_NUMBER, 12, 2, false),
        newScriptField(fieldType, "jsThree", "${JS_ALIAS}",
            org.apache.hop.core.row.IValueMeta.TYPE_STRING, 17, 0, false),
        newScriptField(fieldType, "jsFour", "",
            org.apache.hop.core.row.IValueMeta.TYPE_STRING, 11, 0, false)));

    var provider = new MemoryMetadataProvider();
    var variables = new org.apache.hop.core.variables.Variables();
    variables.setVariable("JS_ALIAS", "变量解析后的名字");
    PipelineDocumentStore store = new PipelineDocumentStore(provider, variables);
    java.nio.file.Path hpl = tempDir.resolve("script-value-alias-metadata.hpl");
    var pipeline = new org.apache.hop.pipeline.PipelineMeta();
    pipeline.setName("script-value-alias-metadata");
    pipeline.addTransform(new org.apache.hop.pipeline.transform.TransformMeta(
        "ScriptValueMod", "script-node", meta));
    store.save(hpl, pipeline);
    var reopened = store.open(hpl);
    var diskTransform = reopened.findTransform("script-node");
    assertNotNull(diskTransform);
    var documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("script-alias-doc", hpl, reopened));
    var resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("script-alias-doc",
        new PipelineConfigResource.ReadRequest("script-node"));
    assertEquals(200, read.getStatus());
    var wire = ((PipelineConfigResource.ConfigResponse) read.getEntity()).config();
    assertTrue(wire.at("/fields/field").isArray());
    assertEquals(5, wire.at("/fields/field").size());
    assertEquals("字段<&>雪", wire.at("/fields/field/1/rename").asText());
    assertEquals("${JS_ALIAS}", wire.at("/fields/field/3/rename").asText());
    assertTrue(wire.at("/fields/field/2/replace").isBoolean());
    assertFalse(wire.at("/fields/field/2/replace").booleanValue());

    var row = new org.apache.hop.core.row.RowMeta();
    row.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("字段<&>雪"));
    row.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("字段<&>雪_1"));
    row.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("上游"));
    diskTransform.getTransform().getFields(
        row, "script-node", null, null, variables, provider);
    assertEquals(List.of("字段<&>雪", "字段<&>雪_1", "上游", "字段<&>雪_2",
        "字段<&>雪_3", "${JS_ALIAS}", "jsFour"),
        row.getValueMetaList().stream().map(v -> v.getName()).toList());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_INTEGER,
        row.getValueMeta(3).getType());
    assertEquals(9, row.getValueMeta(3).getLength());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_NUMBER,
        row.getValueMeta(4).getType());
    assertEquals(12, row.getValueMeta(4).getLength());
    assertEquals(2, row.getValueMeta(4).getPrecision());
    assertEquals(17, row.getValueMeta(5).getLength());
    assertEquals(11, row.getValueMeta(6).getLength());
    assertEquals("字段<&>雪", row.getValueMeta(0).getName(),
        "duplicate aliases must not rename the original input field");
    System.out.println("PROD_P0_SCRIPTVALUE_LITERAL_ALIAS_COLLISION_DISK_REOPEN=PASS");
  }

  @Test
  void concatFieldsLiteralTargetNameAutoSuffixesCollisionAndPreservesConfiguredLength()
      throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "ConcatFields");
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "CONCAT_FIELDS_LITERAL_TARGET_NAME_AUTO_SUFFIXES_COLLISIONS"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/ConcatFields/targetFieldName", "/ConcatFields/targetFieldLength"),
        contract.keyListPointers());
    assertEquals(Shape.STRING, property(descriptor, "ConcatFields", "targetFieldName").shape());
    assertEquals(Shape.NUMBER, property(descriptor, "ConcatFields", "targetFieldLength").shape());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "ConcatFields");
    assertNotNull(plugin);
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    Object extra = meta.getClass().getMethod("getExtraFields").invoke(meta);
    String literal = "${CONCAT_TARGET}";
    extra.getClass().getMethod("setTargetFieldName", String.class).invoke(extra, literal);
    extra.getClass().getMethod("setTargetFieldLength", int.class).invoke(extra, 47);
    extra.getClass().getMethod("setRemoveSelectedFields", boolean.class).invoke(extra, false);

    var variables = new org.apache.hop.core.variables.Variables();
    variables.setVariable("CONCAT_TARGET", "已解析的目标<&>");
    var row = new org.apache.hop.core.row.RowMeta();
    row.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("来源<&>列"));
    var existing = new org.apache.hop.core.row.value.ValueMetaString(literal);
    existing.setLength(12);
    row.addValueMeta(existing);

    meta.getFields(row, "concat-compat", null, null, variables, new MemoryMetadataProvider());
    assertEquals(List.of("来源<&>列", literal, literal + "_1"),
        row.getValueMetaList().stream().map(v -> v.getName()).toList(),
        "Native getFields leaves the target name literal, while RowMeta suffixes collisions");
    assertEquals(12, row.getValueMeta(1).getLength(),
        "The existing same-named field must not be overwritten");
    assertEquals(47, row.getValueMeta(2).getLength(),
        "The appended STRING column must use configured targetFieldLength");
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_STRING,
        row.getValueMeta(2).getType());
    assertEquals("concat-compat", row.getValueMeta(2).getOrigin());
    System.out.println("PROD_P0_CONCATFIELDS_LITERAL_DUPLICATE_TARGET_METADATA=PASS");
  }

  @Test
  void groupByLineNumberLiteralNameAndCollisionSurviveRealHplDiskReopen(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "GroupBy");
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "GROUPBY_LINE_NUMBER_REQUIRES_PASS_ALL_ROWS_AND_FIELD_NAME"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/all_rows", "/add_linenr", "/linenr_fieldname"),
        contract.keyListPointers());
    assertTrue(contract.explanation().contains("literally"));
    assertEquals(Shape.BOOLEAN, property(descriptor, "all_rows").shape());
    assertEquals(Shape.BOOLEAN, property(descriptor, "add_linenr").shape());
    assertEquals(Shape.STRING, property(descriptor, "linenr_fieldname").shape());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "GroupBy");
    assertNotNull(plugin, "production GroupBy plugin missing");
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    var provider = new MemoryMetadataProvider();
    var variables = new org.apache.hop.core.variables.Variables();
    variables.setVariable("GROUP_LINE", "解析后的列");
    PipelineDocumentStore store = new PipelineDocumentStore(provider, variables);
    java.nio.file.Path hpl = tempDir.resolve("groupby-line-number-literal.hpl");
    org.apache.hop.pipeline.PipelineMeta pipeline = new org.apache.hop.pipeline.PipelineMeta();
    pipeline.setName("groupby-line-number-literal");
    pipeline.addTransform(new org.apache.hop.pipeline.transform.TransformMeta(
        "GroupBy", "group-line-node", meta));
    store.save(hpl, pipeline);
    org.apache.hop.pipeline.PipelineMeta loaded = store.open(hpl);

    PipelineDocumentRegistry documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("group-line-doc", hpl, loaded));
    PipelineConfigResource resource = new PipelineConfigResource(documents, provider);
    jakarta.ws.rs.core.Response read = resource.read(
        "group-line-doc", new PipelineConfigResource.ReadRequest("group-line-node"));
    assertEquals(200, read.getStatus());
    PipelineConfigResource.ConfigResponse initial =
        (PipelineConfigResource.ConfigResponse) read.getEntity();
    com.fasterxml.jackson.databind.node.ObjectNode changed =
        ((com.fasterxml.jackson.databind.node.ObjectNode) initial.config()).deepCopy();
    changed.put("all_rows", true);
    changed.put("add_linenr", true);
    changed.put("linenr_fieldname", "${GROUP_LINE}");
    jakarta.ws.rs.core.Response write = resource.write(
        "group-line-doc",
        new PipelineConfigResource.WriteRequest("group-line-node", changed));
    assertEquals(200, write.getStatus());
    store.save(hpl, loaded);

    org.apache.hop.pipeline.PipelineMeta reopened = store.open(hpl);
    org.apache.hop.pipeline.transform.TransformMeta diskTransform =
        reopened.findTransform("group-line-node");
    assertNotNull(diskTransform);
    com.fasterxml.jackson.databind.JsonNode disk =
        org.apache.hop.metadata.serializer.json.ConfigJsonSerializer.toJson(
            diskTransform.getTransform(), provider);
    assertTrue(disk.at("/all_rows").isBoolean());
    assertTrue(disk.at("/all_rows").booleanValue());
    assertTrue(disk.at("/add_linenr").isBoolean());
    assertTrue(disk.at("/add_linenr").booleanValue());
    assertEquals("${GROUP_LINE}", disk.at("/linenr_fieldname").asText());

    ITransformMeta reopenedMeta = diskTransform.getTransform();
    var row = new org.apache.hop.core.row.RowMeta();
    row.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("${GROUP_LINE}"));
    row.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("普通<&>雪"));
    reopenedMeta.getFields(row, "group-line-node", null, null, variables, provider);
    assertEquals(List.of("${GROUP_LINE}", "普通<&>雪", "${GROUP_LINE}_1"),
        row.getValueMetaList().stream().map(v -> v.getName()).toList(),
        "The native line-number name is literal; RowMeta suffixes the collision");
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_INTEGER,
        row.getValueMeta(2).getType());
    assertEquals(org.apache.hop.core.row.IValueMeta.DEFAULT_INTEGER_LENGTH,
        row.getValueMeta(2).getLength());
    assertEquals(0, row.getValueMeta(2).getPrecision());
    assertEquals("group-line-node", row.getValueMeta(2).getOrigin());

    reopenedMeta.getClass().getMethod("setAddingLineNrInGroup", boolean.class)
        .invoke(reopenedMeta, false);
    var disabled = new org.apache.hop.core.row.RowMeta();
    disabled.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("普通<&>雪"));
    reopenedMeta.getFields(disabled, "group-line-node", null, null, variables, provider);
    assertEquals(1, disabled.size(), "add_linenr=false must omit the line-number column");

    reopenedMeta.getClass().getMethod("setAddingLineNrInGroup", boolean.class)
        .invoke(reopenedMeta, true);
    reopenedMeta.getClass().getMethod("setLineNrInGroupField", String.class)
        .invoke(reopenedMeta, "");
    var emptyName = new org.apache.hop.core.row.RowMeta();
    emptyName.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("普通<&>雪"));
    reopenedMeta.getFields(emptyName, "group-line-node", null, null, variables, provider);
    assertEquals(1, emptyName.size(), "An empty configured name must omit the line-number column");
    System.out.println("PROD_P0_GROUPBY_LITERAL_LINE_NUMBER_DISK_REOPEN=PASS");
  }

  @Test
  void tableOutputGeneratedKeyVariableCollisionAndEmptyExpressionSurviveRealHplDiskReopen(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "TableOutput");
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "TABLE_OUTPUT_GENERATED_KEY_RESOLVED_COLLISION_AND_EMPTY_EXPRESSION"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/return_keys", "/return_field"), contract.keyListPointers());
    assertEquals(Shape.BOOLEAN, property(descriptor, "return_keys").shape());
    assertEquals(Shape.STRING, property(descriptor, "return_field").shape());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "TableOutput");
    assertNotNull(plugin);
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    meta.getClass().getMethod("setReturningGeneratedKeys", boolean.class).invoke(meta, true);
    meta.getClass().getMethod("setGeneratedKeyField", String.class)
        .invoke(meta, "${GENERATED_KEY_NAME}");
    var provider = new MemoryMetadataProvider();
    var variables = new org.apache.hop.core.variables.Variables();
    variables.setVariable("GENERATED_KEY_NAME", "主键<&>雪");
    PipelineDocumentStore store = new PipelineDocumentStore(provider, variables);
    java.nio.file.Path hpl = tempDir.resolve("table-output-generated-key.hpl");
    var pipeline = new org.apache.hop.pipeline.PipelineMeta();
    pipeline.setName("table-output-generated-key");
    pipeline.addTransform(new org.apache.hop.pipeline.transform.TransformMeta(
        "TableOutput", "table-output-key-node", meta));
    store.save(hpl, pipeline);

    var loaded = store.open(hpl);
    PipelineDocumentRegistry documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("table-output-key-doc", hpl, loaded));
    PipelineConfigResource resource = new PipelineConfigResource(documents, provider);
    jakarta.ws.rs.core.Response read = resource.read(
        "table-output-key-doc", new PipelineConfigResource.ReadRequest("table-output-key-node"));
    assertEquals(200, read.getStatus());
    var initial = (PipelineConfigResource.ConfigResponse) read.getEntity();
    assertTrue(initial.config().at("/return_keys").booleanValue());
    assertEquals("${GENERATED_KEY_NAME}", initial.config().at("/return_field").asText());

    // Native plugin metadata and HPL disk persistence are the scope of this test.
    // TableOutput config/write currently returns 422 for a default connection-less
    // config; do not mask that separate serializer limitation as an edit success.
    var disk = store.open(hpl);
    var diskTransform = disk.findTransform("table-output-key-node");
    assertNotNull(diskTransform);
    ITransformMeta nativeMeta = diskTransform.getTransform();
    var wire = org.apache.hop.metadata.serializer.json.ConfigJsonSerializer.toJson(
        nativeMeta, provider);
    assertTrue(wire.at("/return_keys").isBoolean());
    assertTrue(wire.at("/return_keys").booleanValue());
    assertEquals("${GENERATED_KEY_NAME}", wire.at("/return_field").asText());

    var row = new org.apache.hop.core.row.RowMeta();
    row.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("主键<&>雪"));
    row.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("主键<&>雪_1"));
    row.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("保留"));
    nativeMeta.getFields(row, "table-output-key-node", null, null, variables, provider);
    assertEquals(List.of("主键<&>雪", "主键<&>雪_1", "保留", "主键<&>雪_2"),
        row.getValueMetaList().stream().map(v -> v.getName()).toList());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_INTEGER, row.getValueMeta(3).getType());
    assertEquals("table-output-key-node", row.getValueMeta(3).getOrigin());

    variables.setVariable("GENERATED_KEY_NAME", "");
    var resolvedEmpty = new org.apache.hop.core.row.RowMeta();
    nativeMeta.getFields(resolvedEmpty, "table-output-key-node", null, null,
        variables, provider);
    assertEquals(1, resolvedEmpty.size(),
        "a nonempty variable expression is checked before resolution");
    assertEquals("", resolvedEmpty.getValueMeta(0).getName());
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_INTEGER,
        resolvedEmpty.getValueMeta(0).getType());

    nativeMeta.getClass().getMethod("setGeneratedKeyField", String.class)
        .invoke(nativeMeta, "");
    var rawEmpty = new org.apache.hop.core.row.RowMeta();
    nativeMeta.getFields(rawEmpty, "table-output-key-node", null, null, variables, provider);
    assertEquals(0, rawEmpty.size(), "literal empty return_field adds no column");
    nativeMeta.getClass().getMethod("setGeneratedKeyField", String.class)
        .invoke(nativeMeta, "${GENERATED_KEY_NAME}");
    nativeMeta.getClass().getMethod("setReturningGeneratedKeys", boolean.class)
        .invoke(nativeMeta, false);
    var disabled = new org.apache.hop.core.row.RowMeta();
    nativeMeta.getFields(disabled, "table-output-key-node", null, null, variables, provider);
    assertEquals(0, disabled.size(), "return_keys=false adds no column");
    System.out.println("PROD_P0_TABLEOUTPUT_GENERATED_KEY_COLLISION_DISK_REOPEN=PASS");
  }

  @Test
  void tableOutputDynamicTableNameMetadataSurvivesRealHplDiskReopen(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(registry, "TableOutput");
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "TABLE_OUTPUT_DYNAMIC_TABLE_NAME_FIELD_CONTROLS_INSERT_PROJECTION"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/tablename_in_field", "/tablename_field", "/tablename_in_table",
        "/specify_fields"), contract.keyListPointers());
    assertEquals(Shape.BOOLEAN, property(descriptor, "tablename_in_field").shape());
    assertEquals(Shape.STRING, property(descriptor, "tablename_field").shape());
    assertEquals(Shape.BOOLEAN, property(descriptor, "tablename_in_table").shape());
    assertEquals(Shape.BOOLEAN, property(descriptor, "specify_fields").shape());

    IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, "TableOutput");
    assertNotNull(plugin, "production TableOutput plugin missing");
    ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    meta.getClass().getMethod("setTableNameInField", boolean.class).invoke(meta, true);
    meta.getClass().getMethod("setTableNameField", String.class)
        .invoke(meta, "${DYNAMIC_TABLE_FIELD}");
    meta.getClass().getMethod("setTableNameInTable", boolean.class).invoke(meta, false);
    meta.getClass().getMethod("setSpecifyFields", boolean.class).invoke(meta, false);

    var provider = new MemoryMetadataProvider();
    var variables = new org.apache.hop.core.variables.Variables();
    variables.setVariable("DYNAMIC_TABLE_FIELD", "动态表名<&>雪");
    PipelineDocumentStore store = new PipelineDocumentStore(provider, variables);
    java.nio.file.Path hpl = tempDir.resolve("table-output-dynamic-name.hpl");
    org.apache.hop.pipeline.PipelineMeta pipeline = new org.apache.hop.pipeline.PipelineMeta();
    pipeline.setName("table-output-dynamic-name");
    pipeline.addTransform(new org.apache.hop.pipeline.transform.TransformMeta(
        "TableOutput", "table-output-node", meta));
    store.save(hpl, pipeline);

    org.apache.hop.pipeline.PipelineMeta reopened = store.open(hpl);
    org.apache.hop.pipeline.transform.TransformMeta diskTransform =
        reopened.findTransform("table-output-node");
    assertNotNull(diskTransform);
    PipelineDocumentRegistry documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("table-output-doc", hpl, reopened));
    PipelineConfigResource resource = new PipelineConfigResource(documents, provider);
    jakarta.ws.rs.core.Response read = resource.read(
        "table-output-doc", new PipelineConfigResource.ReadRequest("table-output-node"));
    assertEquals(200, read.getStatus());
    PipelineConfigResource.ConfigResponse response =
        (PipelineConfigResource.ConfigResponse) read.getEntity();
    com.fasterxml.jackson.databind.JsonNode wire = response.config();
    assertTrue(wire.at("/tablename_in_field").isBoolean());
    assertTrue(wire.at("/tablename_in_field").booleanValue());
    assertEquals("${DYNAMIC_TABLE_FIELD}", wire.at("/tablename_field").asText());
    assertTrue(wire.at("/tablename_in_table").isBoolean());
    assertFalse(wire.at("/tablename_in_table").booleanValue());
    assertTrue(wire.at("/specify_fields").isBoolean());
    assertFalse(wire.at("/specify_fields").booleanValue());

    ITransformMeta nativeMeta = diskTransform.getTransform();
    assertEquals("${DYNAMIC_TABLE_FIELD}",
        nativeMeta.getClass().getMethod("getTableNameField").invoke(nativeMeta));
    assertEquals("动态表名<&>雪", variables.resolve(
        (String) nativeMeta.getClass().getMethod("getTableNameField").invoke(nativeMeta)));
    var row = new org.apache.hop.core.row.RowMeta();
    row.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("动态表名<&>雪"));
    row.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("业务值"));
    nativeMeta.getFields(row, "table-output-node", null, null, variables, provider);
    assertEquals(List.of("动态表名<&>雪", "业务值"),
        row.getValueMetaList().stream().map(v -> v.getName()).toList(),
        "TableOutput's internal SQL insert projection must not remove downstream fields");

    // This checks native metadata and HPL persistence, not a live database INSERT.
    // The actual SQL-row omission branch is in TableOutput's runtime, not getFields().
    System.out.println("PROD_P0_TABLEOUTPUT_DYNAMIC_TABLE_NAME_DISK_METADATA=PASS");
  }

  @Test
  void restCustomMethodTokensAndBodyRulesSurviveRealHplConfigEdit(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(plugins, "Rest");
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "REST_CUSTOM_METHOD_TOKENS_PRESERVE_CASE_AND_BODY_RULES"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/method", "/dynamicMethod", "/methodFieldName"),
        contract.keyListPointers());
    assertEquals(Shape.STRING, property(descriptor, "method").shape());
    assertEquals(Shape.BOOLEAN, property(descriptor, "dynamicMethod").shape());
    assertEquals(Shape.STRING, property(descriptor, "methodFieldName").shape());

    IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "Rest");
    assertNotNull(plugin);
    ITransformMeta original = plugins.loadClass(plugin, ITransformMeta.class);
    original.setDefault();
    Class<?> nativeType = original.getClass();
    nativeType.getMethod("setUrl", String.class)
        .invoke(original, "https://example.invalid/river");
    nativeType.getMethod("setMethod", String.class).invoke(original, "GET");

    // The native API recognizes known verbs without turning custom methods into an enum.
    var normalize = nativeType.getMethod("normalizeMethod", String.class);
    var validToken = nativeType.getMethod("isValidMethodToken", String.class);
    var activeBody = nativeType.getMethod("isActiveBody", String.class);
    var activeParameters = nativeType.getMethod("isActiveParameters", String.class);
    assertEquals("PATCH", normalize.invoke(null, "  pAtCh  "));
    assertEquals("pUrGe", normalize.invoke(null, "  pUrGe  "));
    assertEquals("M-SEARCH", normalize.invoke(null, " M-SEARCH "));
    assertTrue((Boolean) validToken.invoke(null, "pUrGe"));
    assertTrue((Boolean) validToken.invoke(null, "M-SEARCH"));
    assertFalse((Boolean) validToken.invoke(null, "GET /"));
    assertFalse((Boolean) validToken.invoke(null, "GET\r\n"));
    assertFalse((Boolean) validToken.invoke(null, ""));
    assertFalse((Boolean) activeBody.invoke(null, "GET"));
    assertFalse((Boolean) activeBody.invoke(null, "HEAD"));
    assertFalse((Boolean) activeBody.invoke(null, "OPTIONS"));
    assertTrue((Boolean) activeBody.invoke(null, "POST"));
    assertTrue((Boolean) activeBody.invoke(null, "pUrGe"));
    assertFalse((Boolean) activeParameters.invoke(null, "HEAD"));
    assertFalse((Boolean) activeParameters.invoke(null, "OPTIONS"));
    assertTrue((Boolean) activeParameters.invoke(null, "GET"));
    assertTrue((Boolean) activeParameters.invoke(null, "pUrGe"));

    var provider = new MemoryMetadataProvider();
    var variables = new org.apache.hop.core.variables.Variables();
    PipelineDocumentStore store = new PipelineDocumentStore(provider, variables);
    java.nio.file.Path hpl = tempDir.resolve("rest-custom-method-compat.hpl");
    var pipeline = new org.apache.hop.pipeline.PipelineMeta();
    pipeline.setName("rest-custom-method-compat");
    pipeline.addTransform(new org.apache.hop.pipeline.transform.TransformMeta(
        "Rest", "rest-custom-method-node", original));
    store.save(hpl, pipeline);

    var loaded = store.open(hpl);
    PipelineDocumentRegistry documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("rest-custom-method-doc", hpl, loaded));
    PipelineConfigResource resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("rest-custom-method-doc",
        new PipelineConfigResource.ReadRequest("rest-custom-method-node"));
    assertEquals(200, read.getStatus());
    var initial = (PipelineConfigResource.ConfigResponse) read.getEntity();
    assertEquals("GET", initial.config().at("/method").asText());

    var changed = ((com.fasterxml.jackson.databind.node.ObjectNode) initial.config()).deepCopy();
    changed.put("method", "pUrGe");
    var write = resource.write("rest-custom-method-doc",
        new PipelineConfigResource.WriteRequest("rest-custom-method-node", changed));
    assertEquals(200, write.getStatus(), "custom REST verb must survive JSON editing");
    var written = (PipelineConfigResource.ConfigResponse) write.getEntity();
    assertEquals("pUrGe", written.config().at("/method").asText());

    store.save(hpl, loaded);
    var reopened = store.open(hpl);
    ITransformMeta diskMeta = reopened.findTransform("rest-custom-method-node").getTransform();
    var diskWire = org.apache.hop.metadata.serializer.json.ConfigJsonSerializer.toJson(
        diskMeta, provider);
    assertTrue(diskWire.at("/method").isTextual());
    assertEquals("pUrGe", diskWire.at("/method").asText());
    assertEquals("pUrGe", normalize.invoke(null, diskWire.at("/method").asText()));
    System.out.println("PROD_P0_REST_CUSTOM_METHOD_TOKEN_DISK_REOPEN=PASS");
  }

  @Test
  void restBasicAuthInvertedFlagSurvivesConfigWriteAndLegacyXmlReopen(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(plugins, "Rest");
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals("REST_BASIC_AUTH_PREEMPTIVE_FLAG_IS_INVERTED"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/non_preemptive_basic_auth"), contract.keyListPointers());
    assertEquals(Shape.BOOLEAN, property(descriptor, "non_preemptive_basic_auth").shape());

    IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "Rest");
    assertNotNull(plugin);
    ITransformMeta original = plugins.loadClass(plugin, ITransformMeta.class);
    original.setDefault();
    original.getClass().getMethod("setUrl", String.class)
        .invoke(original, "https://example.invalid/river");
    assertTrue((Boolean) original.getClass().getMethod("isPreemptive").invoke(original),
        "native default sends Basic credentials preemptively when configured");

    var provider = new MemoryMetadataProvider();
    var variables = new org.apache.hop.core.variables.Variables();
    PipelineDocumentStore store = new PipelineDocumentStore(provider, variables);
    java.nio.file.Path hpl = tempDir.resolve("rest-basic-auth-compat.hpl");
    var pipeline = new org.apache.hop.pipeline.PipelineMeta();
    pipeline.setName("rest-basic-auth-compat");
    pipeline.addTransform(new org.apache.hop.pipeline.transform.TransformMeta(
        "Rest", "rest-auth-node", original));
    store.save(hpl, pipeline);

    var loaded = store.open(hpl);
    PipelineDocumentRegistry documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("rest-auth-doc", hpl, loaded));
    PipelineConfigResource resource = new PipelineConfigResource(documents, provider);
    var read = resource.read(
        "rest-auth-doc", new PipelineConfigResource.ReadRequest("rest-auth-node"));
    assertEquals(200, read.getStatus());
    var initial = (PipelineConfigResource.ConfigResponse) read.getEntity();
    assertTrue(initial.config().at("/non_preemptive_basic_auth").isBoolean());
    assertFalse(initial.config().at("/non_preemptive_basic_auth").booleanValue());

    var changed = ((com.fasterxml.jackson.databind.node.ObjectNode) initial.config()).deepCopy();
    changed.put("non_preemptive_basic_auth", true);
    var write = resource.write(
        "rest-auth-doc", new PipelineConfigResource.WriteRequest("rest-auth-node", changed));
    assertEquals(200, write.getStatus(), "real REST JSON config edit must succeed");
    var written = (PipelineConfigResource.ConfigResponse) write.getEntity();
    assertTrue(written.config().at("/non_preemptive_basic_auth").isBoolean());
    assertTrue(written.config().at("/non_preemptive_basic_auth").booleanValue());
    ITransformMeta edited = loaded.findTransform("rest-auth-node").getTransform();
    assertFalse((Boolean) edited.getClass().getMethod("isPreemptive").invoke(edited),
        "true wire flag means wait for a 401 challenge, not preemptive authentication");

    store.save(hpl, loaded);
    var reopened = store.open(hpl);
    ITransformMeta diskMeta = reopened.findTransform("rest-auth-node").getTransform();
    var diskWire = org.apache.hop.metadata.serializer.json.ConfigJsonSerializer.toJson(
        diskMeta, provider);
    assertTrue(diskWire.at("/non_preemptive_basic_auth").isBoolean());
    assertTrue(diskWire.at("/non_preemptive_basic_auth").booleanValue());
    assertFalse((Boolean) diskMeta.getClass().getMethod("isPreemptive").invoke(diskMeta));

    // Old pipelines persisted a no-op <preemptive>N</preemptive> tag.
    // Removing the new inverted flag must retain the historical preemptive default.
    String currentXml = original.getXml();
    String legacyXml = currentXml.replaceFirst(
        "(?s)<non_preemptive_basic_auth>[^<]*</non_preemptive_basic_auth>",
        "<preemptive>N</preemptive>");
    assertNotEquals(currentXml, legacyXml,
        "native XML fixture must contain the new inverted wire flag");
    ITransformMeta legacy = plugins.loadClass(plugin, ITransformMeta.class);
    legacy.setDefault();
    legacy.loadXml(XmlHandler.wrapLoadXmlString(legacyXml), provider);
    assertTrue((Boolean) legacy.getClass().getMethod("isPreemptive").invoke(legacy),
        "legacy no-op preemptive=N must not reverse existing authentication behavior");
    var legacyWire = org.apache.hop.metadata.serializer.json.ConfigJsonSerializer.toJson(
        legacy, provider);
    assertFalse(legacyWire.at("/non_preemptive_basic_auth").booleanValue());
    System.out.println("PROD_P0_REST_BASIC_AUTH_INVERTED_FLAG_DISK_REOPEN=PASS");
  }

  @Test
  void restConnectionBaseUrlJoiningAndAbsoluteOverrideSurviveRealHplConfigEdit(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(plugins, "Rest");
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "REST_CONNECTION_BASE_URL_JOINS_RELATIVE_PATHS_AND_ABSOLUTE_OVERRIDE"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/connection_name", "/url", "/urlInField", "/urlField"),
        contract.keyListPointers());
    assertEquals(Shape.STRING, property(descriptor, "connection_name").shape());
    assertEquals(Shape.STRING, property(descriptor, "url").shape());
    assertEquals(Shape.BOOLEAN, property(descriptor, "urlInField").shape());
    assertEquals(Shape.STRING, property(descriptor, "urlField").shape());

    IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "Rest");
    assertNotNull(plugin);
    ITransformMeta original = plugins.loadClass(plugin, ITransformMeta.class);
    original.setDefault();
    original.getClass().getMethod("setUrl", String.class)
        .invoke(original, "/initial?source=1&kind=river");

    // Exercise the actual fixed-Hop-Core Rest URL resolver without sending network requests.
    Class<?> restRuntime = original.getClass().getClassLoader()
        .loadClass("org.apache.hop.pipeline.transforms.rest.Rest");
    var join = restRuntime.getDeclaredMethod(
        "resolveAgainstBase", String.class, String.class);
    join.setAccessible(true);
    String base = "https://api.example.invalid/v1/";
    String expected = "https://api.example.invalid/v1/stations";
    assertEquals(expected, join.invoke(null, base, "/stations"));
    assertEquals(expected, join.invoke(null, base, "stations"));
    assertEquals(expected, join.invoke(null, "https://api.example.invalid/v1", "/stations"));
    assertEquals(expected, join.invoke(null, "https://api.example.invalid/v1", "stations"));
    assertEquals(base, join.invoke(null, base, ""));
    assertEquals("https://other.example.invalid/absolute",
        join.invoke(null, base, "https://other.example.invalid/absolute"));
    assertEquals("https://api.example.invalid/v1/localhost:8080/path",
        join.invoke(null, base, "localhost:8080/path"));
    assertEquals("/stations", join.invoke(null, "", "/stations"));

    var provider = new MemoryMetadataProvider();
    var variables = new org.apache.hop.core.variables.Variables();
    PipelineDocumentStore store = new PipelineDocumentStore(provider, variables);
    java.nio.file.Path hpl = tempDir.resolve("rest-base-url-compat.hpl");
    var pipeline = new org.apache.hop.pipeline.PipelineMeta();
    pipeline.setName("rest-base-url-compat");
    pipeline.addTransform(new org.apache.hop.pipeline.transform.TransformMeta(
        "Rest", "rest-url-node", original));
    store.save(hpl, pipeline);

    var loaded = store.open(hpl);
    PipelineDocumentRegistry documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("rest-url-doc", hpl, loaded));
    PipelineConfigResource resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("rest-url-doc",
        new PipelineConfigResource.ReadRequest("rest-url-node"));
    assertEquals(200, read.getStatus());
    var initial = (PipelineConfigResource.ConfigResponse) read.getEntity();
    assertEquals("/initial?source=1&kind=river", initial.config().at("/url").asText());

    var changed = ((com.fasterxml.jackson.databind.node.ObjectNode) initial.config()).deepCopy();
    changed.put("url", "/stations/水位?code=雪&limit=2");
    var write = resource.write("rest-url-doc",
        new PipelineConfigResource.WriteRequest("rest-url-node", changed));
    assertEquals(200, write.getStatus(), "native REST URL edit must succeed");
    var written = (PipelineConfigResource.ConfigResponse) write.getEntity();
    assertEquals("/stations/水位?code=雪&limit=2", written.config().at("/url").asText());

    store.save(hpl, loaded);
    var reopened = store.open(hpl);
    ITransformMeta diskMeta = reopened.findTransform("rest-url-node").getTransform();
    var diskWire = org.apache.hop.metadata.serializer.json.ConfigJsonSerializer.toJson(
        diskMeta, provider);
    assertTrue(diskWire.at("/url").isTextual());
    assertEquals("/stations/水位?code=雪&limit=2", diskWire.at("/url").asText());
    assertEquals("https://api.example.invalid/v1/stations/水位?code=雪&limit=2",
        join.invoke(null, base, diskWire.at("/url").asText()));
    System.out.println("PROD_P0_REST_BASE_URL_JOIN_DISK_REOPEN=PASS");
  }

  @Test
  void httpRepeatedResponseHeadersUseJsonArraysAndHeaderConfigSurvivesRealHplEdit(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(plugins, "Http");
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "HTTP_REPEATED_RESPONSE_HEADER_NAMES_SERIALIZE_AS_JSON_ARRAY"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/result/response_header"), contract.keyListPointers());
    assertEquals(Shape.OBJECT, property(descriptor, "result").shape());
    assertEquals(Shape.STRING, property(descriptor, "result", "response_header").shape());

    IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "Http");
    assertNotNull(plugin);
    ITransformMeta original = plugins.loadClass(plugin, ITransformMeta.class);
    original.setDefault();
    original.getClass().getMethod("setUrl", String.class)
        .invoke(original, "https://example.invalid/river?name=雪");

    // Exercise the native HTTP response-header serializer without a network request.
    // Keep HttpClient 5 classes reflective: they belong to the pinned P0 plugin runtime.
    ClassLoader loader = original.getClass().getClassLoader();
    Class<?> headerType = loader.loadClass("org.apache.hc.core5.http.Header");
    Class<?> basicHeader = loader.loadClass("org.apache.hc.core5.http.message.BasicHeader");
    var headerConstructor = basicHeader.getConstructor(String.class, Object.class);
    Object headers = java.lang.reflect.Array.newInstance(headerType, 5);
    java.lang.reflect.Array.set(headers, 0,
        headerConstructor.newInstance("X-River", "黄河<&>"));
    java.lang.reflect.Array.set(headers, 1,
        headerConstructor.newInstance("X-Single", "once"));
    java.lang.reflect.Array.set(headers, 2,
        headerConstructor.newInstance("X-River", "雪"));
    java.lang.reflect.Array.set(headers, 3,
        headerConstructor.newInstance("X-River", "third"));
    java.lang.reflect.Array.set(headers, 4,
        headerConstructor.newInstance("x-river", "lowercase"));
    Class<?> httpRuntime = loader.loadClass("org.apache.hop.pipeline.transforms.http.Http");
    var extract = httpRuntime.getDeclaredMethod("extractHeaderString", headers.getClass());
    extract.setAccessible(true);
    String payload = (String) extract.invoke(null, headers);
    var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
    assertTrue(json.path("X-River").isArray(),
        "exact repeated header names must become a JSON array");
    assertEquals(3, json.path("X-River").size());
    assertEquals("黄河<&>", json.path("X-River").get(0).asText());
    assertEquals("雪", json.path("X-River").get(1).asText());
    assertEquals("third", json.path("X-River").get(2).asText());
    assertEquals("once", json.path("X-Single").asText(),
        "a single occurrence remains a JSON string");
    assertEquals("lowercase", json.path("x-river").asText(),
        "native serializer preserves exact header-name case");

    var provider = new MemoryMetadataProvider();
    var variables = new org.apache.hop.core.variables.Variables();
    variables.setVariable("HTTP_HEADER_OUTPUT", "响应头<&雪>");
    PipelineDocumentStore store = new PipelineDocumentStore(provider, variables);
    java.nio.file.Path hpl = tempDir.resolve("http-header-result-compat.hpl");
    var pipeline = new org.apache.hop.pipeline.PipelineMeta();
    pipeline.setName("http-header-result-compat");
    pipeline.addTransform(new org.apache.hop.pipeline.transform.TransformMeta(
        "Http", "http-header-node", original));
    store.save(hpl, pipeline);

    var loaded = store.open(hpl);
    PipelineDocumentRegistry documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("http-header-doc", hpl, loaded));
    PipelineConfigResource resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("http-header-doc",
        new PipelineConfigResource.ReadRequest("http-header-node"));
    assertEquals(200, read.getStatus());
    var initial = (PipelineConfigResource.ConfigResponse) read.getEntity();
    assertTrue(initial.config().at("/result/response_header").isTextual());
    var changed = ((com.fasterxml.jackson.databind.node.ObjectNode) initial.config()).deepCopy();
    assertTrue(changed.path("result").isObject());
    ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("result"))
        .put("response_header", "${HTTP_HEADER_OUTPUT}");
    var write = resource.write("http-header-doc",
        new PipelineConfigResource.WriteRequest("http-header-node", changed));
    assertEquals(200, write.getStatus(), "real Http nested result JSON edit must succeed");
    var written = (PipelineConfigResource.ConfigResponse) write.getEntity();
    assertEquals("${HTTP_HEADER_OUTPUT}",
        written.config().at("/result/response_header").asText());

    store.save(hpl, loaded);
    var reopened = store.open(hpl);
    ITransformMeta diskMeta = reopened.findTransform("http-header-node").getTransform();
    var diskWire = org.apache.hop.metadata.serializer.json.ConfigJsonSerializer.toJson(
        diskMeta, provider);
    assertEquals("${HTTP_HEADER_OUTPUT}",
        diskWire.at("/result/response_header").asText());
    var output = new org.apache.hop.core.row.RowMeta();
    output.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("上游字段"));
    diskMeta.getFields(output, "http-header-node", null, null, variables, provider);
    int headerIndex = output.indexOfValue("响应头<&雪>");
    assertTrue(headerIndex >= 0, "response-header output field must resolve its variable");
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_STRING,
        output.getValueMeta(headerIndex).getType());
    System.out.println("PROD_P0_HTTP_DUPLICATE_HEADER_JSON_ARRAY_DISK_REOPEN=PASS");
  }

  @Test
  void restMatrixParametersUsePathEncodingAndPrecedeOrderedQueryParameters()
      throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(plugins, "Rest");
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "REST_MATRIX_PARAMETERS_USE_PATH_ENCODING_BEFORE_QUERY"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/url", "/matrixParameters/matrixParameter",
        "/parameters/parameter"), contract.keyListPointers());
    assertEquals(Shape.LIST, property(descriptor, "matrixParameter").shape());
    assertEquals("matrixParameters", property(descriptor, "matrixParameter").groupKey());
    assertEquals(Shape.LIST, property(descriptor, "parameter").shape());
    assertEquals("parameters", property(descriptor, "parameter").groupKey());

    IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "Rest");
    assertNotNull(plugin);
    ITransformMeta original = plugins.loadClass(plugin, ITransformMeta.class);
    original.setDefault();
    ClassLoader loader = original.getClass().getClassLoader();
    Class<?> runtimeType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.Rest");
    var encodePath = runtimeType.getDeclaredMethod("encodePathValue", String.class);
    var encodeQuery = runtimeType.getDeclaredMethod("encodeQueryValue", String.class);
    encodePath.setAccessible(true);
    encodeQuery.setAccessible(true);
    assertEquals("A%20B%2BC%2F%E9%9B%AA", encodePath.invoke(null, "A B+C/雪"));
    assertEquals("A+B%2BC%2F%E9%9B%AA", encodeQuery.invoke(null, "A B+C/雪"));

    // Exercise the real native Rest URL builder without making an HTTP request.
    Class<?> dataType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.RestData");
    Object data = dataType.getConstructor().newInstance();
    var incoming = new org.apache.hop.core.row.RowMeta();
    incoming.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("站点"));
    incoming.addValueMeta(new org.apache.hop.core.row.value.ValueMetaString("条件"));
    dataType.getField("inputRowMeta").set(data, incoming);
    dataType.getField("useMatrixParams").setBoolean(data, true);
    dataType.getField("nrMatrixParams").setInt(data, 2);
    dataType.getField("indexOfMatrixParamFields").set(data, new int[] {0, 1});
    dataType.getField("matrixParamNames").set(data, new String[] {"station", "zone"});
    dataType.getField("useParams").setBoolean(data, true);
    dataType.getField("nrParams").setInt(data, 1);
    dataType.getField("indexOfParamFields").set(data, new int[] {1});
    dataType.getField("paramNames").set(data, new String[] {"search"});

    var pipelineMeta = new org.apache.hop.pipeline.PipelineMeta();
    pipelineMeta.setName("rest-matrix-encoding");
    var transform = new org.apache.hop.pipeline.transform.TransformMeta(
        "Rest", "rest-parameter-node", original);
    pipelineMeta.addTransform(transform); // BaseTransform.dispatch resolves its node from the graph.
    var ctor = runtimeType.getConstructor(
        org.apache.hop.pipeline.transform.TransformMeta.class,
        original.getClass(), dataType, int.class,
        org.apache.hop.pipeline.PipelineMeta.class,
        org.apache.hop.pipeline.Pipeline.class);
    Object nativeRest = ctor.newInstance(transform, original, data, 0, pipelineMeta, null);
    var build = runtimeType.getDeclaredMethod("buildRequestUri", String.class, Object[].class);
    build.setAccessible(true);
    String url = (String) build.invoke(nativeRest,
        "https://api.example.invalid/v1/stations?existing=1",
        (Object) new Object[] {"A B+C/雪", "q value+雪"});
    java.net.URI parsed = java.net.URI.create(url);
    String rawPath = parsed.getRawPath();
    assertTrue(rawPath.contains(";station=A%20B%2BC%2F%E9%9B%AA"), url);
    assertTrue(rawPath.contains(";zone=q%20value%2B%E9%9B%AA"), url);
    assertTrue(rawPath.indexOf(";station=") < rawPath.indexOf(";zone="), url);
    assertTrue(url.indexOf(";zone=") < url.indexOf("?"), url);
    String rawQuery = parsed.getRawQuery();
    assertTrue(rawQuery.startsWith("existing=1&"), url);
    String search = java.util.Arrays.stream(rawQuery.split("&"))
        .filter(part -> part.startsWith("search="))
        .findFirst().orElseThrow().substring("search=".length());
    assertEquals("q value+雪", java.net.URLDecoder.decode(
        search, java.nio.charset.StandardCharsets.UTF_8));
    System.out.println("PROD_P0_REST_MATRIX_PATH_VS_QUERY_ENCODING=PASS");
  }

  @Test
  void restSplitPathUsesJsonPathForFormRequestsAndXPathForXmlAfterRealHplEdit(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(plugins, "Rest");
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals(
            "REST_SPLIT_PATH_JSONPATH_OVERRIDES_REQUEST_TYPE_XPATH_REQUIRES_XML"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/applicationType", "/resultSplitPath", "/result/name"),
        contract.keyListPointers());
    assertEquals(Shape.STRING, property(descriptor, "applicationType").shape());
    assertEquals(Shape.STRING, property(descriptor, "resultSplitPath").shape());
    assertEquals(Shape.STRING, property(descriptor, "result", "name").shape());

    IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "Rest");
    assertNotNull(plugin);
    ITransformMeta original = plugins.loadClass(plugin, ITransformMeta.class);
    original.setDefault();
    original.getClass().getMethod("setUrl", String.class)
        .invoke(original, "https://example.invalid/split?station=雪");
    Object result = original.getClass().getMethod("getResultField").invoke(original);
    result.getClass().getMethod("setFieldName", String.class)
        .invoke(result, "响应<&雪>");

    var provider = new MemoryMetadataProvider();
    var variables = new org.apache.hop.core.variables.Variables();
    PipelineDocumentStore store = new PipelineDocumentStore(provider, variables);
    java.nio.file.Path hpl = tempDir.resolve("rest-split-json-xpath.hpl");
    var pipeline = new org.apache.hop.pipeline.PipelineMeta();
    pipeline.setName("rest-split-json-xpath");
    pipeline.addTransform(new org.apache.hop.pipeline.transform.TransformMeta(
        "Rest", "rest-split-node", original));
    store.save(hpl, pipeline);

    String[] types = {"FORM URLENCODED", "XML", "TEXT PLAIN"};
    String[] paths = {"$.items[*]", "/root/item", "/root/item"};
    String json = "{\\"items\\":[{\\"name\\":\\"黄河<&>\\"},{\\"name\\":\\"雪\\"}]}";
    String xml = "<root><item>黄河&lt;&amp;&gt;</item><item>雪</item></root>";
    String[] bodies = {json, xml, xml};
    for (int index = 0; index < types.length; index++) {
      var loaded = store.open(hpl);
      PipelineDocumentRegistry documents = new PipelineDocumentRegistry();
      documents.put(new PipelineDocument("rest-split-doc", hpl, loaded));
      PipelineConfigResource resource = new PipelineConfigResource(documents, provider);
      var read = resource.read("rest-split-doc",
          new PipelineConfigResource.ReadRequest("rest-split-node"));
      assertEquals(200, read.getStatus());
      var changed = ((com.fasterxml.jackson.databind.node.ObjectNode)
          ((PipelineConfigResource.ConfigResponse) read.getEntity()).config()).deepCopy();
      changed.put("applicationType", types[index]);
      changed.put("resultSplitPath", paths[index]);
      var write = resource.write("rest-split-doc",
          new PipelineConfigResource.WriteRequest("rest-split-node", changed));
      assertEquals(200, write.getStatus(), "split-path drafts remain editable");
      store.save(hpl, loaded);

      var reopened = store.open(hpl);
      ITransformMeta diskMeta = reopened.findTransform("rest-split-node").getTransform();
      var wire = org.apache.hop.metadata.serializer.json.ConfigJsonSerializer.toJson(
          diskMeta, provider);
      assertEquals(types[index], wire.at("/applicationType").asText());
      assertEquals(paths[index], wire.at("/resultSplitPath").asText());
      assertEquals("响应<&雪>", wire.at("/result/name").asText());
      assertTrue(wire.at("/resultSplitPath").isTextual());

      ClassLoader loader = diskMeta.getClass().getClassLoader();
      Class<?> runtimeType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.Rest");
      Class<?> dataType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.RestData");
      var ctor = runtimeType.getConstructor(
          org.apache.hop.pipeline.transform.TransformMeta.class,
          diskMeta.getClass(), dataType, int.class,
          org.apache.hop.pipeline.PipelineMeta.class,
          org.apache.hop.pipeline.Pipeline.class);
      Object nativeRest = ctor.newInstance(
          reopened.findTransform("rest-split-node"), diskMeta,
          dataType.getConstructor().newInstance(), 0, reopened,
          org.mockito.Mockito.spy(new org.apache.hop.pipeline.engines.local.LocalPipelineEngine()));
      runtimeType.getMethod("setMetadataProvider",
          org.apache.hop.metadata.api.IHopMetadataProvider.class)
          .invoke(nativeRest, provider);
      assertTrue((Boolean) runtimeType.getMethod("init").invoke(nativeRest));
      var split = runtimeType.getDeclaredMethod("splitResultItems", String.class, String.class);
      split.setAccessible(true);

      if (index == 2) {
        var thrown = assertThrows(java.lang.reflect.InvocationTargetException.class,
            () -> split.invoke(nativeRest, bodies[2], paths[2]));
        assertTrue(thrown.getCause() instanceof org.apache.hop.core.exception.HopException);
        assertTrue(thrown.getCause().getMessage().contains("JsonPath (starting with $)"),
            "plain-text request type plus XPath must fail when splitting a response");
      } else {
        @SuppressWarnings("unchecked")
        List<String> pieces = (List<String>) split.invoke(nativeRest, bodies[index], paths[index]);
        assertEquals(2, pieces.size(), "ordered split fragments survive native parsing");
        if (index == 0) {
          var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
          assertEquals("黄河<&>", mapper.readTree(pieces.get(0)).path("name").asText());
          assertEquals("雪", mapper.readTree(pieces.get(1)).path("name").asText());
        } else {
          assertTrue(pieces.get(0).contains("黄河<&>"));
          assertTrue(pieces.get(1).contains("雪"));
        }
      }
    }
    System.out.println("PROD_P0_REST_SPLIT_JSONPATH_XPATH_DISK_REOPEN=PASS");
  }

  @Test
  void restStreamingSplitPathRejectsBeforeHttpAfterRealHplConfigEdit(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(plugins, "Rest");
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals("REST_STREAMING_SPLIT_PATH_REJECTED_BEFORE_HTTP"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/streamingEnabled", "/resultSplitPath", "/result/binary",
        "/paginationEnabled"), contract.keyListPointers());
    assertEquals(Shape.BOOLEAN, property(descriptor, "streamingEnabled").shape());
    assertEquals(Shape.STRING, property(descriptor, "resultSplitPath").shape());
    assertEquals(Shape.BOOLEAN, property(descriptor, "paginationEnabled").shape());
    assertEquals(Shape.BOOLEAN, property(descriptor, "result", "binary").shape());

    IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "Rest");
    assertNotNull(plugin);
    ITransformMeta original = plugins.loadClass(plugin, ITransformMeta.class);
    original.setDefault();
    original.getClass().getMethod("setUrl", String.class)
        .invoke(original, "https://example.invalid/river?station=雪");
    Object result = original.getClass().getMethod("getResultField").invoke(original);
    result.getClass().getMethod("setFieldName", String.class)
        .invoke(result, "响应<&雪>");

    var provider = new MemoryMetadataProvider();
    var variables = new org.apache.hop.core.variables.Variables();
    PipelineDocumentStore store = new PipelineDocumentStore(provider, variables);
    java.nio.file.Path hpl = tempDir.resolve("rest-streaming-split-path-guard.hpl");
    var pipeline = new org.apache.hop.pipeline.PipelineMeta();
    pipeline.setName("rest-streaming-split-path-guard");
    pipeline.addTransform(new org.apache.hop.pipeline.transform.TransformMeta(
        "Rest", "rest-stream-node", original));
    store.save(hpl, pipeline);

    var loaded = store.open(hpl);
    PipelineDocumentRegistry documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("rest-stream-doc", hpl, loaded));
    PipelineConfigResource resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("rest-stream-doc",
        new PipelineConfigResource.ReadRequest("rest-stream-node"));
    assertEquals(200, read.getStatus());
    var initial = (PipelineConfigResource.ConfigResponse) read.getEntity();
    assertTrue(initial.config().at("/streamingEnabled").isBoolean());
    assertFalse(initial.config().at("/streamingEnabled").booleanValue());

    var changed = ((com.fasterxml.jackson.databind.node.ObjectNode) initial.config()).deepCopy();
    changed.put("streamingEnabled", true);
    changed.put("resultSplitPath", "$.measurements[*]");
    changed.put("paginationEnabled", false);
    ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("result"))
        .put("binary", false);
    var write = resource.write("rest-stream-doc",
        new PipelineConfigResource.WriteRequest("rest-stream-node", changed));
    assertEquals(200, write.getStatus(),
        "streaming/split-path conflict must remain an editable draft");
    var written = (PipelineConfigResource.ConfigResponse) write.getEntity();
    assertTrue(written.config().at("/streamingEnabled").booleanValue());
    assertEquals("$.measurements[*]", written.config().at("/resultSplitPath").asText());

    store.save(hpl, loaded);
    var reopened = store.open(hpl);
    ITransformMeta diskMeta = reopened.findTransform("rest-stream-node").getTransform();
    var wire = org.apache.hop.metadata.serializer.json.ConfigJsonSerializer.toJson(
        diskMeta, provider);
    assertTrue(wire.at("/streamingEnabled").isBoolean());
    assertTrue(wire.at("/streamingEnabled").booleanValue());
    assertEquals("$.measurements[*]", wire.at("/resultSplitPath").asText());
    assertFalse(wire.at("/paginationEnabled").booleanValue());
    assertFalse(wire.at("/result/binary").booleanValue());
    assertEquals("响应<&雪>", wire.at("/result/name").asText());

    ClassLoader loader = diskMeta.getClass().getClassLoader();
    Class<?> runtimeType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.Rest");
    Class<?> dataType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.RestData");
    var ctor = runtimeType.getConstructor(
        org.apache.hop.pipeline.transform.TransformMeta.class,
        diskMeta.getClass(), dataType, int.class,
        org.apache.hop.pipeline.PipelineMeta.class,
        org.apache.hop.pipeline.Pipeline.class);
    Object nativeRest = ctor.newInstance(
        reopened.findTransform("rest-stream-node"), diskMeta,
        dataType.getConstructor().newInstance(), 0, reopened,
        org.mockito.Mockito.spy(new org.apache.hop.pipeline.engines.local.LocalPipelineEngine()));
    runtimeType.getMethod("setMetadataProvider",
        org.apache.hop.metadata.api.IHopMetadataProvider.class)
        .invoke(nativeRest, provider);
    assertTrue((Boolean) runtimeType.getMethod("init").invoke(nativeRest),
        "native init accepts the persisted editable draft before processing rows");

    var thrown = assertThrows(java.lang.reflect.InvocationTargetException.class,
        () -> runtimeType.getMethod("processRow").invoke(nativeRest));
    assertTrue(thrown.getCause() instanceof org.apache.hop.core.exception.HopException);
    assertTrue(thrown.getCause().getMessage().contains("result split path"),
        "native first-row guard must reject streaming plus a complete-document split path");
    System.out.println("PROD_P0_REST_STREAMING_SPLIT_PATH_PRE_HTTP_DISK_REOPEN=PASS");
  }

  @Test
  void restStreamingBinaryResultRejectsBeforeHttpAfterRealHplConfigEdit(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(plugins, "Rest");
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals("REST_STREAMING_BINARY_RESULT_REJECTED_BEFORE_HTTP"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/streamingEnabled", "/result/binary", "/paginationEnabled",
        "/resultSplitPath"), contract.keyListPointers());
    assertEquals(Shape.BOOLEAN, property(descriptor, "streamingEnabled").shape());
    assertEquals(Shape.BOOLEAN, property(descriptor, "result", "binary").shape());
    assertEquals(Shape.BOOLEAN, property(descriptor, "paginationEnabled").shape());
    assertEquals(Shape.STRING, property(descriptor, "resultSplitPath").shape());

    IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "Rest");
    assertNotNull(plugin);
    ITransformMeta original = plugins.loadClass(plugin, ITransformMeta.class);
    original.setDefault();
    original.getClass().getMethod("setUrl", String.class)
        .invoke(original, "https://example.invalid/binary?station=雪");
    Object result = original.getClass().getMethod("getResultField").invoke(original);
    result.getClass().getMethod("setFieldName", String.class)
        .invoke(result, "原始字节<&雪>");

    var provider = new MemoryMetadataProvider();
    var variables = new org.apache.hop.core.variables.Variables();
    PipelineDocumentStore store = new PipelineDocumentStore(provider, variables);
    java.nio.file.Path hpl = tempDir.resolve("rest-streaming-binary-pre-http.hpl");
    var pipeline = new org.apache.hop.pipeline.PipelineMeta();
    pipeline.setName("rest-streaming-binary-pre-http");
    pipeline.addTransform(new org.apache.hop.pipeline.transform.TransformMeta(
        "Rest", "rest-binary-stream-node", original));
    store.save(hpl, pipeline);

    var loaded = store.open(hpl);
    PipelineDocumentRegistry documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("rest-binary-stream-doc", hpl, loaded));
    PipelineConfigResource resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("rest-binary-stream-doc",
        new PipelineConfigResource.ReadRequest("rest-binary-stream-node"));
    assertEquals(200, read.getStatus());
    var initial = (PipelineConfigResource.ConfigResponse) read.getEntity();
    assertFalse(initial.config().at("/streamingEnabled").booleanValue());
    assertFalse(initial.config().at("/result/binary").booleanValue());

    var changed = ((com.fasterxml.jackson.databind.node.ObjectNode) initial.config()).deepCopy();
    changed.put("streamingEnabled", true);
    changed.put("paginationEnabled", false);
    changed.put("resultSplitPath", "");
    ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("result"))
        .put("binary", true);
    var write = resource.write("rest-binary-stream-doc",
        new PipelineConfigResource.WriteRequest("rest-binary-stream-node", changed));
    assertEquals(200, write.getStatus(),
        "runtime-incompatible streaming/binary options remain editable draft values");
    var written = (PipelineConfigResource.ConfigResponse) write.getEntity();
    assertTrue(written.config().at("/streamingEnabled").booleanValue());
    assertTrue(written.config().at("/result/binary").booleanValue());
    assertFalse(written.config().at("/paginationEnabled").booleanValue());
    assertEquals("", written.config().at("/resultSplitPath").asText());

    store.save(hpl, loaded);
    var reopened = store.open(hpl);
    ITransformMeta diskMeta = reopened.findTransform("rest-binary-stream-node").getTransform();
    var wire = org.apache.hop.metadata.serializer.json.ConfigJsonSerializer.toJson(
        diskMeta, provider);
    assertTrue(wire.at("/streamingEnabled").isBoolean());
    assertTrue(wire.at("/streamingEnabled").booleanValue());
    assertTrue(wire.at("/result/binary").isBoolean());
    assertTrue(wire.at("/result/binary").booleanValue());
    assertFalse(wire.at("/paginationEnabled").booleanValue());
    assertEquals("", wire.at("/resultSplitPath").asText());
    assertEquals("原始字节<&雪>", wire.at("/result/name").asText());

    var projected = new org.apache.hop.core.row.RowMeta();
    diskMeta.getFields(projected, "rest-binary-stream-node", null, null, variables, provider);
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_BINARY,
        projected.getValueMeta(0).getType(),
        "getFields still projects BINARY before native first-row incompatibility check");

    ClassLoader loader = diskMeta.getClass().getClassLoader();
    Class<?> runtimeType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.Rest");
    Class<?> dataType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.RestData");
    var ctor = runtimeType.getConstructor(
        org.apache.hop.pipeline.transform.TransformMeta.class,
        diskMeta.getClass(), dataType, int.class,
        org.apache.hop.pipeline.PipelineMeta.class,
        org.apache.hop.pipeline.Pipeline.class);
    Object nativeRest = ctor.newInstance(
        reopened.findTransform("rest-binary-stream-node"), diskMeta,
        dataType.getConstructor().newInstance(), 0, reopened,
        org.mockito.Mockito.spy(new org.apache.hop.pipeline.engines.local.LocalPipelineEngine()));
    runtimeType.getMethod("setMetadataProvider",
        org.apache.hop.metadata.api.IHopMetadataProvider.class)
        .invoke(nativeRest, provider);
    assertTrue((Boolean) runtimeType.getMethod("init").invoke(nativeRest),
        "native init accepts binary streaming when pagination is disabled");

    var thrown = assertThrows(java.lang.reflect.InvocationTargetException.class,
        () -> runtimeType.getMethod("processRow").invoke(nativeRest));
    assertTrue(thrown.getCause() instanceof org.apache.hop.core.exception.HopException);
    assertTrue(thrown.getCause().getMessage().contains("a binary result field"),
        "first-row guard must reject binary streaming before HTTP rather than corrupt bytes");
    System.out.println("PROD_P0_REST_STREAMING_BINARY_PRE_HTTP_DISK_REOPEN=PASS");
  }

  @Test
  void restPaginationRejectsBinaryResultAtNativeInitAfterRealHplConfigEdit(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(plugins, "Rest");
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals("REST_PAGINATION_REJECTS_BINARY_RESULT_AT_INIT"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/paginationEnabled", "/result/binary", "/result/name"),
        contract.keyListPointers());
    assertEquals(Shape.BOOLEAN, property(descriptor, "paginationEnabled").shape());
    assertEquals(Shape.BOOLEAN, property(descriptor, "result", "binary").shape());

    IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "Rest");
    assertNotNull(plugin);
    ITransformMeta original = plugins.loadClass(plugin, ITransformMeta.class);
    original.setDefault();
    original.getClass().getMethod("setUrl", String.class)
        .invoke(original, "https://example.invalid/river?station=雪");
    Object resultField = original.getClass().getMethod("getResultField").invoke(original);
    resultField.getClass().getMethod("setFieldName", String.class)
        .invoke(resultField, "响应<&雪>");

    var provider = new MemoryMetadataProvider();
    var variables = new org.apache.hop.core.variables.Variables();
    PipelineDocumentStore store = new PipelineDocumentStore(provider, variables);
    java.nio.file.Path hpl = tempDir.resolve("rest-pagination-binary-guard.hpl");
    var pipeline = new org.apache.hop.pipeline.PipelineMeta();
    pipeline.setName("rest-pagination-binary-guard");
    pipeline.addTransform(new org.apache.hop.pipeline.transform.TransformMeta(
        "Rest", "rest-paging-node", original));
    store.save(hpl, pipeline);

    var loaded = store.open(hpl);
    PipelineDocumentRegistry documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("rest-paging-doc", hpl, loaded));
    PipelineConfigResource resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("rest-paging-doc",
        new PipelineConfigResource.ReadRequest("rest-paging-node"));
    assertEquals(200, read.getStatus());
    var initial = (PipelineConfigResource.ConfigResponse) read.getEntity();
    assertTrue(initial.config().at("/paginationEnabled").isBoolean());
    assertFalse(initial.config().at("/paginationEnabled").booleanValue());
    assertTrue(initial.config().at("/result/binary").isBoolean());
    assertFalse(initial.config().at("/result/binary").booleanValue());

    var changed = ((com.fasterxml.jackson.databind.node.ObjectNode) initial.config()).deepCopy();
    changed.put("paginationEnabled", true);
    ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("result"))
        .put("binary", true);
    var write = resource.write("rest-paging-doc",
        new PipelineConfigResource.WriteRequest("rest-paging-node", changed));
    assertEquals(200, write.getStatus(),
        "the incompatible runtime pair remains an editable draft, not a save-time rejection");
    var written = (PipelineConfigResource.ConfigResponse) write.getEntity();
    assertTrue(written.config().at("/paginationEnabled").isBoolean());
    assertTrue(written.config().at("/paginationEnabled").booleanValue());
    assertTrue(written.config().at("/result/binary").isBoolean());
    assertTrue(written.config().at("/result/binary").booleanValue());

    store.save(hpl, loaded);
    var reopened = store.open(hpl);
    ITransformMeta diskMeta = reopened.findTransform("rest-paging-node").getTransform();
    var diskWire = org.apache.hop.metadata.serializer.json.ConfigJsonSerializer.toJson(
        diskMeta, provider);
    assertTrue(diskWire.at("/paginationEnabled").booleanValue());
    assertTrue(diskWire.at("/result/binary").booleanValue());
    assertEquals("响应<&雪>", diskWire.at("/result/name").asText());

    var projected = new org.apache.hop.core.row.RowMeta();
    diskMeta.getFields(projected, "rest-paging-node", null, null, variables, provider);
    assertEquals(org.apache.hop.core.row.IValueMeta.TYPE_BINARY,
        projected.getValueMeta(0).getType(),
        "getFields projects BINARY even though the runtime rejects pagination with binary");

    ClassLoader loader = diskMeta.getClass().getClassLoader();
    Class<?> runtimeType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.Rest");
    Class<?> dataType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.RestData");
    var ctor = runtimeType.getConstructor(
        org.apache.hop.pipeline.transform.TransformMeta.class,
        diskMeta.getClass(), dataType, int.class,
        org.apache.hop.pipeline.PipelineMeta.class,
        org.apache.hop.pipeline.Pipeline.class);
    Object nativeRest = ctor.newInstance(
        reopened.findTransform("rest-paging-node"), diskMeta,
        dataType.getConstructor().newInstance(), 0, reopened,
        org.mockito.Mockito.spy(new org.apache.hop.pipeline.engines.local.LocalPipelineEngine()));
    runtimeType.getMethod("setMetadataProvider",
        org.apache.hop.metadata.api.IHopMetadataProvider.class)
        .invoke(nativeRest, provider);
    assertFalse((Boolean) runtimeType.getMethod("init").invoke(nativeRest),
        "native Rest.init must reject the persisted binary + pagination combination");

    diskMeta.getClass().getMethod("setPaginationEnabled", boolean.class)
        .invoke(diskMeta, false);
    Object allowedRest = ctor.newInstance(
        reopened.findTransform("rest-paging-node"), diskMeta,
        dataType.getConstructor().newInstance(), 0, reopened,
        org.mockito.Mockito.spy(new org.apache.hop.pipeline.engines.local.LocalPipelineEngine()));
    runtimeType.getMethod("setMetadataProvider",
        org.apache.hop.metadata.api.IHopMetadataProvider.class)
        .invoke(allowedRest, provider);
    assertTrue((Boolean) runtimeType.getMethod("init").invoke(allowedRest),
        "binary responses without pagination must remain valid");
    System.out.println("PROD_P0_REST_PAGINATION_BINARY_INIT_GUARD_DISK_REOPEN=PASS");
  }

  @Test
  void restPaginationWithoutPagingConnectionFallsBackAfterRealHplConfigEdit(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(plugins, "Rest");
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals("REST_PAGINATION_WITHOUT_PAGING_CONNECTION_FALLS_BACK"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/paginationEnabled", "/connection_name"),
        contract.keyListPointers());
    assertEquals(Shape.BOOLEAN, property(descriptor, "paginationEnabled").shape());

    IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "Rest");
    assertNotNull(plugin);
    ITransformMeta original = plugins.loadClass(plugin, ITransformMeta.class);
    original.setDefault();
    original.getClass().getMethod("setUrl", String.class)
        .invoke(original, "https://example.invalid/stations?river=雪");
    Object result = original.getClass().getMethod("getResultField").invoke(original);
    result.getClass().getMethod("setFieldName", String.class)
        .invoke(result, "响应<&雪>");

    var provider = new MemoryMetadataProvider();
    var variables = new org.apache.hop.core.variables.Variables();
    PipelineDocumentStore store = new PipelineDocumentStore(provider, variables);
    java.nio.file.Path hpl = tempDir.resolve("rest-paging-no-connection.hpl");
    var pipeline = new org.apache.hop.pipeline.PipelineMeta();
    pipeline.setName("rest-paging-no-connection");
    pipeline.addTransform(new org.apache.hop.pipeline.transform.TransformMeta(
        "Rest", "rest-no-connection-node", original));
    store.save(hpl, pipeline);

    var loaded = store.open(hpl);
    PipelineDocumentRegistry documents = new PipelineDocumentRegistry();
    documents.put(new PipelineDocument("rest-no-connection-doc", hpl, loaded));
    PipelineConfigResource resource = new PipelineConfigResource(documents, provider);
    var read = resource.read("rest-no-connection-doc",
        new PipelineConfigResource.ReadRequest("rest-no-connection-node"));
    assertEquals(200, read.getStatus());
    var initial = (PipelineConfigResource.ConfigResponse) read.getEntity();
    assertFalse(initial.config().at("/paginationEnabled").booleanValue());

    var edited = ((com.fasterxml.jackson.databind.node.ObjectNode) initial.config()).deepCopy();
    edited.put("paginationEnabled", true);
    var write = resource.write("rest-no-connection-doc",
        new PipelineConfigResource.WriteRequest("rest-no-connection-node", edited));
    assertEquals(200, write.getStatus(),
        "a paging flag without a connection is an editable draft, not an invalid save");
    var written = (PipelineConfigResource.ConfigResponse) write.getEntity();
    assertTrue(written.config().at("/paginationEnabled").isBoolean());
    assertTrue(written.config().at("/paginationEnabled").booleanValue());

    store.save(hpl, loaded);
    var reopened = store.open(hpl);
    ITransformMeta diskMeta = reopened.findTransform("rest-no-connection-node").getTransform();
    var wire = org.apache.hop.metadata.serializer.json.ConfigJsonSerializer.toJson(
        diskMeta, provider);
    assertTrue(wire.at("/paginationEnabled").booleanValue());
    assertEquals("响应<&雪>", wire.at("/result/name").asText());
    Object connectionName = diskMeta.getClass().getMethod("getConnectionName").invoke(diskMeta);
    assertTrue(connectionName == null || ((String) connectionName).isBlank(),
        "no REST connection was supplied by this pipeline");

    ClassLoader loader = diskMeta.getClass().getClassLoader();
    Class<?> runtimeType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.Rest");
    Class<?> dataType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.RestData");
    var ctor = runtimeType.getConstructor(
        org.apache.hop.pipeline.transform.TransformMeta.class,
        diskMeta.getClass(), dataType, int.class,
        org.apache.hop.pipeline.PipelineMeta.class,
        org.apache.hop.pipeline.Pipeline.class);
    Object nativeRest = ctor.newInstance(
        reopened.findTransform("rest-no-connection-node"), diskMeta,
        dataType.getConstructor().newInstance(), 0, reopened,
        org.mockito.Mockito.spy(new org.apache.hop.pipeline.engines.local.LocalPipelineEngine()));
    runtimeType.getMethod("setMetadataProvider",
        org.apache.hop.metadata.api.IHopMetadataProvider.class)
        .invoke(nativeRest, provider);
    assertTrue((Boolean) runtimeType.getMethod("init").invoke(nativeRest),
        "native Rest.init accepts a textual response with paging enabled");
    var supportsPaging = runtimeType.getDeclaredMethod("supportsPaging");
    supportsPaging.setAccessible(true);
    assertFalse((Boolean) supportsPaging.invoke(nativeRest),
        "without a loaded REST connection, the native runtime uses legacy single-request flow");
    System.out.println("PROD_P0_REST_PAGINATION_WITHOUT_CONNECTION_FALLBACK=PASS");
  }


  @Test
  void restFieldDrivenOptionsWithoutInputHopFailBeforeHttpAfterRealHplConfigEdit(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
    PluginRegistry plugins = PluginRegistry.getInstance();
    Descriptor descriptor = descriptorFor(plugins, "Rest");
    var contract = descriptor.runtimePreconditions().stream()
        .filter(p -> p.code().equals("REST_FIELD_DRIVEN_OPTIONS_REQUIRE_INPUT_HOP"))
        .findFirst().orElseThrow();
    assertEquals(List.of("/urlInField", "/urlField", "/dynamicMethod",
        "/methodFieldName", "/bodyField", "/headers/header",
        "/parameters/parameter", "/matrixParameters/matrixParameter"),
        contract.keyListPointers());
    assertEquals(Shape.BOOLEAN, property(descriptor, "urlInField").shape());
    assertEquals(Shape.BOOLEAN, property(descriptor, "dynamicMethod").shape());
    assertEquals(Shape.STRING, property(descriptor, "urlField").shape());
    assertEquals(Shape.STRING, property(descriptor, "methodFieldName").shape());
    assertEquals(Shape.STRING, property(descriptor, "bodyField").shape());
    assertEquals(Shape.LIST, property(descriptor, "header").shape());
    assertEquals("headers", property(descriptor, "header").groupKey());
    assertEquals(Shape.LIST, property(descriptor, "parameter").shape());
    assertEquals("parameters", property(descriptor, "parameter").groupKey());
    assertEquals(Shape.LIST, property(descriptor, "matrixParameter").shape());
    assertEquals("matrixParameters", property(descriptor, "matrixParameter").groupKey());

    IPlugin plugin = plugins.findPluginWithId(TransformPluginType.class, "Rest");
    assertNotNull(plugin);
    String[][] scenarios = {
        {"urlInField", "Accept URL from field"},
        {"dynamicMethod", "Get Method from field"},
        {"bodyField", "Body field"}
    };
    for (int index = 0; index < scenarios.length; index++) {
      String option = scenarios[index][0];
      String expectedNativeOption = scenarios[index][1];
      ITransformMeta original = plugins.loadClass(plugin, ITransformMeta.class);
      original.setDefault();
      original.getClass().getMethod("setUrl", String.class)
          .invoke(original, "https://example.invalid/river?station=雪");
      Object result = original.getClass().getMethod("getResultField").invoke(original);
      result.getClass().getMethod("setFieldName", String.class)
          .invoke(result, "响应<&雪>");

      var provider = new MemoryMetadataProvider();
      var variables = new org.apache.hop.core.variables.Variables();
      PipelineDocumentStore store = new PipelineDocumentStore(provider, variables);
      java.nio.file.Path hpl = tempDir.resolve("rest-no-input-" + index + ".hpl");
      var pipeline = new org.apache.hop.pipeline.PipelineMeta();
      pipeline.setName("rest-no-input-" + index);
      pipeline.addTransform(new org.apache.hop.pipeline.transform.TransformMeta(
          "Rest", "rest-no-input-node", original));
      store.save(hpl, pipeline);

      var loaded = store.open(hpl);
      PipelineDocumentRegistry documents = new PipelineDocumentRegistry();
      String documentId = "rest-no-input-doc-" + index;
      documents.put(new PipelineDocument(documentId, hpl, loaded));
      PipelineConfigResource resource = new PipelineConfigResource(documents, provider);
      var read = resource.read(documentId,
          new PipelineConfigResource.ReadRequest("rest-no-input-node"));
      assertEquals(200, read.getStatus());
      var initial = (PipelineConfigResource.ConfigResponse) read.getEntity();
      var edited = ((com.fasterxml.jackson.databind.node.ObjectNode) initial.config()).deepCopy();
      switch (option) {
        case "urlInField" -> {
          edited.put("urlInField", true);
          edited.put("urlField", "URL来源<&雪>");
        }
        case "dynamicMethod" -> {
          edited.put("dynamicMethod", true);
          edited.put("methodFieldName", "方法来源<&雪>");
        }
        case "bodyField" -> edited.put("bodyField", "请求体来源<&雪>");
        default -> throw new AssertionError("unexpected REST option " + option);
      }
      var write = resource.write(documentId,
          new PipelineConfigResource.WriteRequest("rest-no-input-node", edited));
      assertEquals(200, write.getStatus(), option + " must remain an editable draft");
      store.save(hpl, loaded);
      var reopened = store.open(hpl);
      ITransformMeta diskMeta = reopened.findTransform("rest-no-input-node").getTransform();
      var wire = org.apache.hop.metadata.serializer.json.ConfigJsonSerializer.toJson(
          diskMeta, provider);
      assertEquals("响应<&雪>", wire.at("/result/name").asText());
      if ("urlInField".equals(option)) {
        assertTrue(wire.at("/urlInField").isBoolean());
        assertTrue(wire.at("/urlInField").booleanValue());
        assertEquals("URL来源<&雪>", wire.at("/urlField").asText());
      } else if ("dynamicMethod".equals(option)) {
        assertTrue(wire.at("/dynamicMethod").isBoolean());
        assertTrue(wire.at("/dynamicMethod").booleanValue());
        assertEquals("方法来源<&雪>", wire.at("/methodFieldName").asText());
      } else {
        assertTrue(wire.at("/bodyField").isTextual());
        assertEquals("请求体来源<&雪>", wire.at("/bodyField").asText());
        assertEquals("GET", wire.at("/method").asText(),
            "native input-hop guard applies to bodyField even for GET");
      }

      ClassLoader loader = diskMeta.getClass().getClassLoader();
      Class<?> runtimeType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.Rest");
      Class<?> dataType = loader.loadClass("org.apache.hop.pipeline.transforms.rest.RestData");
      var ctor = runtimeType.getConstructor(
          org.apache.hop.pipeline.transform.TransformMeta.class,
          diskMeta.getClass(), dataType, int.class,
          org.apache.hop.pipeline.PipelineMeta.class,
          org.apache.hop.pipeline.Pipeline.class);
      Object nativeRest = ctor.newInstance(
          reopened.findTransform("rest-no-input-node"), diskMeta,
          dataType.getConstructor().newInstance(), 0, reopened,
          org.mockito.Mockito.spy(new org.apache.hop.pipeline.engines.local.LocalPipelineEngine()));
      runtimeType.getMethod("setMetadataProvider",
          org.apache.hop.metadata.api.IHopMetadataProvider.class)
          .invoke(nativeRest, provider);
      assertTrue((Boolean) runtimeType.getMethod("init").invoke(nativeRest),
          "native init should accept an editable REST draft with no input hop");
      var thrown = assertThrows(java.lang.reflect.InvocationTargetException.class,
          () -> runtimeType.getMethod("processRow").invoke(nativeRest),
          option + " should be rejected before any HTTP request");
      assertTrue(thrown.getCause() instanceof org.apache.hop.core.exception.HopException);
      assertTrue(thrown.getCause().getMessage().contains(expectedNativeOption),
          "native guard must identify the specific field-driven option: " + option);
      assertTrue(thrown.getCause().getMessage().contains("no input hop"),
          "native guard must identify the missing incoming graph hop");
    }
    System.out.println("PROD_P0_REST_NO_INPUT_FIELD_OPTIONS_PRE_HTTP_DISK_REOPEN=PASS");
  }

  private static Object newScriptField(Class<?> type, String name, String rename,
      int valueType, int length, int precision, boolean replace) throws Exception {
    Object field = type.getDeclaredConstructor().newInstance();
    type.getMethod("setName", String.class).invoke(field, name);
    type.getMethod("setRename", String.class).invoke(field, rename);
    type.getMethod("setType", int.class).invoke(field, valueType);
    type.getMethod("setLength", int.class).invoke(field, length);
    type.getMethod("setPrecision", int.class).invoke(field, precision);
    type.getMethod("setReplace", boolean.class).invoke(field, replace);
    return field;
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
