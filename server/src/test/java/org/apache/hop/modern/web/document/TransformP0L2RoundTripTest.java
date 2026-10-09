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

  @Test void nestedAndSensitiveProductionDescriptorsStayTyped() throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    Descriptor json = descriptorFor(registry, "JsonInput");
    Property fileFilter = property(json, "file", "file", "type_filter");
    assertEquals(Shape.ENUM, fileFilter.shape());
    assertTrue(fileFilter.storeWithCode());
    assertFalse(fileFilter.storeWithName());
    assertFalse(fileFilter.options().isEmpty());
    assertEquals(Shape.LIST, property(json, "file").shape());
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
