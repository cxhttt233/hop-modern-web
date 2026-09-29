package org.apache.hop.modern.web.document;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
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
  static final List<String> IDS=List.of("FilterRows","GroupBy","JsonInput","MergeJoin","ScriptValueMod","TableInput","TableOutput","InsertUpdate");
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
  static String canon(String x) throws Exception { return XmlHandler.formatNode(XmlHandler.wrapLoadXmlString(x)); }
}
