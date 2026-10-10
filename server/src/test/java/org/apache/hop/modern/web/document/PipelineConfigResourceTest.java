/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.util.List;
import org.apache.hop.core.xml.XmlHandler;
import org.apache.hop.pipeline.transforms.rest.RestMeta;
import org.apache.hop.pipeline.transforms.rest.fields.HeaderField;
import org.apache.hop.pipeline.transforms.rest.fields.ParameterField;
import org.apache.hop.pipeline.transforms.rest.fields.MatrixParameterField;
import java.nio.file.Path;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.modern.web.ModernWebServer;
import org.apache.hop.metadata.serializer.json.ConfigJsonSerializer;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.pipeline.transforms.tableinput.TableInputMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PipelineConfigResourceTest {
  @TempDir Path tempDir;

  @Test
  void readsWritesAndRereadsRealTableInputConfig() {
    MemoryMetadataProvider metadataProvider = new MemoryMetadataProvider();
    PipelineDocumentRegistry registry = new PipelineDocumentRegistry();
    PipelineMeta pipeline = new PipelineMeta();
    TableInputMeta tableInput = new TableInputMeta();
    tableInput.setConnection("warehouse");
    tableInput.setSql("select 1");
    TransformMeta transform = new TransformMeta("TableInput", "source", tableInput);
    pipeline.addTransform(transform);
    PipelineDocument document =
        new PipelineDocument("doc-1", tempDir.resolve("table-input.hpl"), pipeline);
    registry.put(document);
    PipelineConfigResource resource = new PipelineConfigResource(registry, metadataProvider);

    Response read = resource.read("doc-1", new PipelineConfigResource.ReadRequest("source"));
    assertEquals(200, read.getStatus());
    PipelineConfigResource.ConfigResponse initial =
        (PipelineConfigResource.ConfigResponse) read.getEntity();
    assertEquals("source", initial.nodeId());
    assertEquals("TableInput", initial.pluginId());
    assertEquals("warehouse", initial.config().get("connection").asText());
    assertEquals("select 1", initial.config().get("sql").asText());

    ObjectNode changed = ((ObjectNode) initial.config()).deepCopy();
    changed.put("connection", "analytics");
    changed.put("sql", "select * from orders");
    Response write =
        resource.write(
            "doc-1", new PipelineConfigResource.WriteRequest("source", changed));
    assertEquals(200, write.getStatus());
    PipelineConfigResource.ConfigResponse written =
        (PipelineConfigResource.ConfigResponse) write.getEntity();
    assertEquals("analytics", written.config().get("connection").asText());
    assertEquals("select * from orders", written.config().get("sql").asText());
    assertEquals("analytics", ((TableInputMeta) transform.getTransform()).getConnection());
    assertEquals("select * from orders", ((TableInputMeta) transform.getTransform()).getSql());
    assertTrue(transform.hasChanged());
    assertTrue(pipeline.hasChanged());

    Response reread = resource.read("doc-1", new PipelineConfigResource.ReadRequest("source"));
    PipelineConfigResource.ConfigResponse rereadConfig =
        (PipelineConfigResource.ConfigResponse) reread.getEntity();
    assertEquals("analytics", rereadConfig.config().get("connection").asText());
    assertEquals("select * from orders", rereadConfig.config().get("sql").asText());
  }

  @Test
  void exposesRealParsedTableInputRuntimeClassAndTransport() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
    MemoryMetadataProvider metadataProvider = new MemoryMetadataProvider();
    Path fixture = tempDir.resolve("real-table-input.hpl");
    Files.writeString(
        fixture,
        """
        <pipeline>
          <info>
            <name>v4-table-input-proof</name>
            <name_sync_with_filename>N</name_sync_with_filename>
            <description/>
            <extended_description/>
            <pipeline_version/>
            <pipeline_status>0</pipeline_status>
            <created_user>-</created_user>
            <created_date>2026/09/25 00:00:00.000</created_date>
            <modified_user>-</modified_user>
            <modified_date>2026/09/25 00:00:00.000</modified_date>
          </info>
          <notepads/>
          <order/>
          <transform>
            <name>table-input</name>
            <type>TableInput</type>
            <description/>
            <distribute>Y</distribute>
            <custom_distribution/>
            <copies>1</copies>
            <partitioning>
              <method>none</method>
              <schema_name/>
            </partitioning>
            <connection>Warehouse</connection>
            <sql>SELECT id, name FROM customers</sql>
            <limit>0</limit>
            <lookup/>
            <execute_each_row>N</execute_each_row>
            <variables_active>N</variables_active>
            <lazy_conversion_active>N</lazy_conversion_active>
            <attributes/>
            <GUI>
              <xloc>160</xloc>
              <yloc>120</yloc>
            </GUI>
          </transform>
          <transform_error_handling/>
          <attributes/>
        </pipeline>
        """);

    PipelineMeta pipeline =
        new PipelineDocumentStore(metadataProvider, new Variables()).open(fixture);
    TransformMeta transform = pipeline.findTransform("table-input");
    assertTrue(transform != null, "real parse did not produce table-input TransformMeta");

    Object loaded = transform.getTransform();
    ObjectNode json = ConfigJsonSerializer.toJson(loaded, metadataProvider);
    String evidence =
        "loadedClass="
            + (loaded == null ? "null" : loaded.getClass().getName())
            + ", pluginId="
            + transform.getTransformPluginId()
            + ", json="
            + json;

    assertTrue(loaded instanceof TableInputMeta, evidence);
    TableInputMeta tableInput = (TableInputMeta) loaded;
    String getterEvidence =
        evidence
            + ", connection="
            + tableInput.getConnection()
            + ", sql="
            + tableInput.getSql();
    assertEquals("Warehouse", tableInput.getConnection(), getterEvidence);
    assertEquals("SELECT id, name FROM customers", tableInput.getSql(), getterEvidence);
    assertEquals("Warehouse", json.path("connection").asText(), getterEvidence);
    assertEquals("SELECT id, name FROM customers", json.path("sql").asText(), getterEvidence);
  }

  @Test
  void returnsFrozenClientErrors() {
    MemoryMetadataProvider metadataProvider = new MemoryMetadataProvider();
    PipelineDocumentRegistry registry = new PipelineDocumentRegistry();
    PipelineMeta pipeline = new PipelineMeta();
    TableInputMeta tableInput = new TableInputMeta();
    TransformMeta transform = new TransformMeta("TableInput", "source", tableInput);
    pipeline.addTransform(transform);
    registry.put(new PipelineDocument("doc-1", tempDir.resolve("errors.hpl"), pipeline));
    PipelineConfigResource resource = new PipelineConfigResource(registry, metadataProvider);

    assertError(
        resource.read("missing", new PipelineConfigResource.ReadRequest("source")),
        404,
        "document_not_found");
    assertError(
        resource.read("doc-1", new PipelineConfigResource.ReadRequest("missing")),
        422,
        "unknown_transform");
    assertError(
        resource.read("doc-1", new PipelineConfigResource.ReadRequest(" ")),
        400,
        "invalid_request");

    ObjectNode invalid = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    ObjectNode fields = invalid.putObject("fields");
    fields.putArray("field").addNull();
    assertError(
        resource.write(
            "doc-1", new PipelineConfigResource.WriteRequest("source", invalid)),
        422,
        "invalid_config");
  }


  @Test
  void restThreeGroupedRequestTablesSurviveConfigWriteAndHopXmlReopen() throws Exception {
    MemoryMetadataProvider provider = new MemoryMetadataProvider();
    PipelineDocumentRegistry registry = new PipelineDocumentRegistry();
    PipelineMeta pipeline = new PipelineMeta();
    RestMeta rest = new RestMeta();
    rest.setDefault();
    rest.setUrl("https://example.invalid/river");
    rest.setHeaderFields(List.of(
        new HeaderField("header_甲<&>", "X-Source"),
        new HeaderField("header_乙", "X-Second")));
    rest.setParameterFields(List.of(
        new ParameterField("query_甲", "region"),
        new ParameterField("query_乙&", "page")));
    rest.setMatrixParameterFields(List.of(
        new MatrixParameterField("matrix_甲", "zone"),
        new MatrixParameterField("matrix_乙", "stage")));
    TransformMeta transform = new TransformMeta("Rest", "rest-source", rest);
    pipeline.addTransform(transform);
    registry.put(new PipelineDocument("rest-doc", tempDir.resolve("rest.hpl"), pipeline));
    PipelineConfigResource resource = new PipelineConfigResource(registry, provider);

    Response read = resource.read("rest-doc", new PipelineConfigResource.ReadRequest("rest-source"));
    assertEquals(200, read.getStatus());
    PipelineConfigResource.ConfigResponse initial =
        (PipelineConfigResource.ConfigResponse) read.getEntity();
    assertEquals(2, initial.config().path("headers").path("header").size());
    assertEquals(2, initial.config().path("parameters").path("parameter").size());
    assertEquals(2, initial.config().path("matrixParameters").path("matrixParameter").size());
    assertEquals("header_甲<&>",
        initial.config().path("headers").path("header").get(0).path("field").asText());
    assertTrue(initial.descriptor().properties().stream().anyMatch(
        p -> p.key().equals("header") && p.groupKey().equals("headers")
            && !p.elementProperties().isEmpty()));
    assertTrue(initial.descriptor().properties().stream().anyMatch(
        p -> p.key().equals("parameter") && p.groupKey().equals("parameters")
            && !p.elementProperties().isEmpty()));
    assertTrue(initial.descriptor().properties().stream().anyMatch(
        p -> p.key().equals("matrixParameter") && p.groupKey().equals("matrixParameters")
            && !p.elementProperties().isEmpty()));

    ObjectNode changed = ((ObjectNode) initial.config()).deepCopy();
    ((ObjectNode) changed.path("headers").path("header").get(1))
        .put("name", "X-Updated");
    Response write = resource.write(
        "rest-doc", new PipelineConfigResource.WriteRequest("rest-source", changed));
    assertEquals(200, write.getStatus());
    PipelineConfigResource.ConfigResponse written =
        (PipelineConfigResource.ConfigResponse) write.getEntity();
    assertEquals("X-Updated",
        written.config().path("headers").path("header").get(1).path("name").asText());
    assertEquals(2, ((RestMeta) transform.getTransform()).getParameterFields().size());
    assertEquals(2, ((RestMeta) transform.getTransform()).getMatrixParameterFields().size());

    RestMeta reopened = new RestMeta();
    reopened.loadXml(
        XmlHandler.wrapLoadXmlString(((RestMeta) transform.getTransform()).getXml()), provider);
    ObjectNode reopenedJson = ConfigJsonSerializer.toJson(reopened, provider);
    assertEquals(written.config().path("headers").path("header"),
        reopenedJson.path("headers").path("header"));
    assertEquals(written.config().path("parameters").path("parameter"),
        reopenedJson.path("parameters").path("parameter"));
    assertEquals(written.config().path("matrixParameters").path("matrixParameter"),
        reopenedJson.path("matrixParameters").path("matrixParameter"));
  }

  private static void assertError(Response response, int status, String code) {
    assertEquals(status, response.getStatus());
    assertEquals(
        code, ((PipelineOpenResource.ErrorResponse) response.getEntity()).code());
  }
}
