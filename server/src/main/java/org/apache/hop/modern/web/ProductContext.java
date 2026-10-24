/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web;

import java.util.Objects;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.metadata.api.IHopMetadataProvider;

/**
 * Minimal product execution context seam.
 *
 * <p>This deliberately carries only the authoritative Hop variable space and metadata provider.
 * Project/environment/VFS/session lifecycle can bind richer state around this seam without making
 * execution resources invent their own context.
 */
public record ProductContext(IVariables variables, IHopMetadataProvider metadataProvider) {
  public ProductContext {
    Objects.requireNonNull(variables, "variables");
    Objects.requireNonNull(metadataProvider, "metadataProvider");
  }
}
