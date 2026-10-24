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
            "CONCAT_FIELDS_REMOVE_SELECTED_FIELDS_CHANGES_OUTPUT_SCHEMA"),
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
            "GROUPBY_LINE_NUMBER_REQUIRES_PASS_ALL_ROWS_AND_FIELD_NAME"),
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
