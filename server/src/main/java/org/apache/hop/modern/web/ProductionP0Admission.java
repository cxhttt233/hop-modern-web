/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.hop.core.Const;
import org.apache.hop.core.HopClientEnvironment;
import org.apache.hop.core.plugins.IPlugin;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.plugins.TransformPluginType;
import org.apache.hop.pipeline.transform.ITransformMeta;

/**
 * Deployment preflight: prove P0 transforms are discovered and instantiated
 * from the packaged plugin directory, not a Maven test-scope shortcut.
 * No listener is opened and no pipeline is executed.
 */
public final class ProductionP0Admission {
  private static final Set<String> REQUIRED = Set.of(
      "CheckSum", "ConcatFields", "DataGrid", "ExecSql", "FilterRows",
      "GroupBy", "Http", "InsertUpdate", "JsonInput", "MergeJoin",
      "ReplaceString", "Rest", "ScriptValueMod", "SelectValues", "SetVariable",
      "StreamLookup", "StringCut", "TableInput", "TableOutput", "UniqueRowsByHashSet");

  private ProductionP0Admission() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 0) {
      throw new IllegalArgumentException("usage: ProductionP0Admission (no arguments)");
    }
    String configured = System.getProperty(Const.HOP_PLUGIN_BASE_FOLDERS);
    if (configured == null || configured.isBlank() || configured.contains(",")) {
      throw new IllegalStateException("one explicit HOP_PLUGIN_BASE_FOLDERS path is required");
    }
    Path root = Path.of(configured).toRealPath();
    if (!Files.isDirectory(root)) {
      throw new IllegalStateException("plugin root is not a directory: " + root);
    }

    HopClientEnvironment.init();
    ModernWebServer.initializePipelinePlugins();
    PluginRegistry registry = PluginRegistry.getInstance();
    List<String> failures = new ArrayList<>();
    int discovered = 0;
    int instantiated = 0;
    for (String id : REQUIRED.stream().sorted().toList()) {
      IPlugin plugin = registry.findPluginWithId(TransformPluginType.class, id);
      if (plugin == null) {
        failures.add(id + ": missing");
        continue;
      }
      discovered++;
      try {
        if (plugin.getPluginDirectory() == null) {
          throw new IllegalStateException("native/test-classpath plugin, not packaged");
        }
        Path pluginFolder = Path.of(plugin.getPluginDirectory().toURI()).toRealPath();
        if (!pluginFolder.startsWith(root)) {
          throw new IllegalStateException("plugin outside distribution: " + pluginFolder);
        }
        ITransformMeta meta = registry.loadClass(plugin, ITransformMeta.class);
        if (meta == null) {
          throw new IllegalStateException("null transform metadata");
        }
        instantiated++;
      } catch (Exception e) {
        failures.add(id + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
      }
    }
    System.out.println("PRODUCTION_P0_DISCOVERED=" + discovered + "/" + REQUIRED.size());
    System.out.println("PRODUCTION_P0_PACKAGED_INSTANTIATED=" + instantiated + "/" + REQUIRED.size());
    if (!failures.isEmpty()) {
      throw new IllegalStateException("P0 distribution admission failed: " + failures);
    }
  }
}
