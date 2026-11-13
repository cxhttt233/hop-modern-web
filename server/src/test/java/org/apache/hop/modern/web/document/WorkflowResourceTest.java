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
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.workflow.WorkflowMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowResourceTest {
  @TempDir Path tempDir;

  @Test
  void openEditSaveReopenPreservesWorkflowDescription() throws Exception {
    var variables = Variables.getADefaultVariableSpace();
    var store = new WorkflowDocumentStore(mock(IHopMetadataProvider.class), variables);
    Path path = tempDir.resolve("round-trip.hwf");
    WorkflowMeta workflow = new WorkflowMeta();
    workflow.setName("round-trip");
    workflow.setDescription("before");
    store.save(path, workflow);
    var resource = new WorkflowResource(store);

    var opened = (WorkflowResource.Snapshot) resource.open(
        new WorkflowResource.OpenRequest(path.toString())).getEntity();
    assertEquals("before", opened.description());
    var edited = (WorkflowResource.Snapshot) resource.edit(
        new WorkflowResource.EditRequest(opened.id(), "after")).getEntity();
    assertTrue(edited.dirty());
    assertEquals("before", store.open(path).getDescription());

    var saved = (WorkflowResource.Snapshot) resource.save(
        new WorkflowResource.SaveRequest(opened.id())).getEntity();
    assertFalse(saved.dirty());
    assertEquals("after", store.open(path).getDescription());
    assertEquals("after", ((WorkflowResource.Snapshot) resource.open(
        new WorkflowResource.OpenRequest(path.toString())).getEntity()).description());
  }

  @Test
  void rejectsWrongFileExtensionWithoutOverwritingIt() throws Exception {
    var store = new WorkflowDocumentStore(
        mock(IHopMetadataProvider.class), Variables.getADefaultVariableSpace());
    Path path = tempDir.resolve("notes.txt");
    Files.writeString(path, "keep me");
    var response = new WorkflowResource(store).open(new WorkflowResource.OpenRequest(path.toString()));
    assertEquals(400, response.getStatus());
    assertEquals("keep me", Files.readString(path));
  }
  @Test
  void rejectsStaleSaveWithoutOverwritingExternalChanges() throws Exception {
    var variables = Variables.getADefaultVariableSpace();
    var store = new WorkflowDocumentStore(mock(IHopMetadataProvider.class), variables);
    Path path = tempDir.resolve("concurrent.hwf");
    WorkflowMeta workflow = new WorkflowMeta();
    workflow.setName("concurrent");
    workflow.setDescription("initial");
    store.save(path, workflow);
    var resource = new WorkflowResource(store);
    var opened = (WorkflowResource.Snapshot) resource.open(
        new WorkflowResource.OpenRequest(path.toString())).getEntity();
    resource.edit(new WorkflowResource.EditRequest(opened.id(), "my edit"));
    workflow.setDescription("external edit");
    store.save(path, workflow);
    assertEquals(409, resource.save(new WorkflowResource.SaveRequest(opened.id())).getStatus());
    assertEquals("external edit", store.open(path).getDescription());
  }

}