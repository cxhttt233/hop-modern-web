/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.plugins.IPlugin;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.plugins.TransformPluginType;
import org.apache.hop.modern.web.ModernWebServer;
import org.apache.hop.pipeline.transform.ITransformMeta;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TransformCatalogProbeTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  private static final Set<String> PROD_P0 =
      Set.of(
          "CheckSum",
          "ConcatFields",
          "DataGrid",
          "ExecSql",
          "FilterRows",
          "GroupBy",
          "Http",
          "InsertUpdate",
          "JsonInput",
          "MergeJoin",
          "ReplaceString",
          "Rest",
          "ScriptValueMod",
          "SelectValues",
          "SetVariable",
          "StreamLookup",
          "StringCut",
          "TableInput",
          "TableOutput",
          "UniqueRowsByHashSet");

  @BeforeAll
  static void initializeHop() throws Exception {
    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
  }

  @Test
  void enumeratesRuntimeTransformCatalogWithoutSilentSkips() throws Exception {
    PluginRegistry registry = PluginRegistry.getInstance();
    List<IPlugin> plugins = registry.getPlugins(TransformPluginType.class);
    assertFalse(plugins.isEmpty(), "runtime Transform catalog must not be empty");

    List<Map<String, Object>> rows = new ArrayList<>();
    Set<String> discoveredIds = new LinkedHashSet<>();

    for (IPlugin plugin : plugins) {
      String primaryId = plugin.getIds()[0];
      discoveredIds.add(primaryId);

      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", primaryId);
      row.put("aliases", Arrays.asList(plugin.getIds()));
      row.put("name", plugin.getName());
      row.put("category", plugin.getCategory());
      row.put("mainType", plugin.getMainType() == null ? null : plugin.getMainType().getName());

      try {
        ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
        row.put("instantiate", "PASS");
        AutomaticConfigDescriptor.Descriptor descriptor =
            AutomaticConfigDescriptor.describe(meta.getClass());
        row.put("metaClass", meta.getClass().getName());
        row.put("l1", "PASS");
        Map<String, Long> shapes =
            descriptor.properties().stream()
                .collect(
                    Collectors.groupingBy(
                        p -> p.shape().name(), TreeMap::new, Collectors.counting()));
        row.put("descriptorPropertyCount", descriptor.properties().size());
        row.put("descriptorShapes", shapes);
        row.put("descriptor", descriptor);
        row.put("failure", null);
      } catch (Exception e) {
        row.put("instantiate", "FAIL");
        row.put("l1", "FAIL");
        row.put("failure", e.getClass().getName() + ": " + String.valueOf(e.getMessage()));
      }

      rows.add(row);
    }

    Set<String> rowIds =
        rows.stream().map(row -> String.valueOf(row.get("id"))).collect(Collectors.toSet());
    assertEquals(discoveredIds, rowIds, "discovered Transform ids must equal emitted matrix row ids");

    Set<String> p0Missing = new LinkedHashSet<>(PROD_P0);
    p0Missing.removeAll(discoveredIds);

    System.out.println("TRANSFORM_CATALOG_COUNT=" + rows.size());
    System.out.println(
        "TRANSFORM_CATALOG_JSON=" + JSON.writerWithDefaultPrettyPrinter().writeValueAsString(rows));
    System.out.println(
        "PROD_P0_DISCOVERED=" + (PROD_P0.size() - p0Missing.size()) + "/" + PROD_P0.size());
    System.out.println("PROD_P0_MISSING=" + p0Missing);

    String descriptorFixture = System.getProperty("p0DescriptorFixture");
    if (descriptorFixture != null && !descriptorFixture.isBlank()) {
      List<Map<String, Object>> fixtureRows =
          rows.stream()
              .filter(row -> PROD_P0.contains(String.valueOf(row.get("id"))))
              .sorted(java.util.Comparator.comparing(row -> String.valueOf(row.get("id"))))
              .toList();
      Path fixturePath = Path.of(descriptorFixture);
      if (fixturePath.getParent() != null) Files.createDirectories(fixturePath.getParent());
      JSON.writerWithDefaultPrettyPrinter().writeValue(fixturePath.toFile(), fixtureRows);
      System.out.println("PROD_P0_DESCRIPTOR_FIXTURE=" + fixturePath.toAbsolutePath());
    }

    List<Map<String, Object>> p0Rows =
        rows.stream()
            .filter(row -> PROD_P0.contains(String.valueOf(row.get("id"))))
            .sorted(
                java.util.Comparator.comparing(
                    row -> String.valueOf(row.get("id"))))
            .toList();
    for (Map<String, Object> row : p0Rows) {
      System.out.println(
          "PROD_P0_STATUS="
              + row.get("id")
              + " instantiate="
              + row.get("instantiate")
              + " l1="
              + row.get("l1")
              + " failure="
              + row.get("failure"));
    }

    boolean p0AllPass =
        p0Missing.isEmpty()
            && p0Rows.size() == PROD_P0.size()
            && p0Rows.stream()
                .allMatch(
                    row ->
                        "PASS".equals(row.get("instantiate"))
                            && "PASS".equals(row.get("l1")));
    System.out.println("PROD_P0_L0_L1_PASS=" + p0Rows.size() + "/" + PROD_P0.size());
    System.out.println("CATALOG_INCOMPLETE=" + (!p0Missing.isEmpty()));

    if (Boolean.getBoolean("prodP0Expected")) {
      assertTrue(p0Missing.isEmpty(), "production P0 transforms missing from runtime: " + p0Missing);
      assertEquals(PROD_P0.size(), p0Rows.size(), "production P0 runtime row count");
      assertTrue(p0AllPass, "all production P0 transforms must instantiate and pass L1");
    }
  }
}
