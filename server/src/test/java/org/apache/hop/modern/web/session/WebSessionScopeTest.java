/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hop.modern.web.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpSession;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WebSessionScopeTest {
  private final WebSessionScope<String> scope = new WebSessionScope<>("test.value");

  @AfterEach
  void cleanUp() {
    scope.remove();
    WebSessionScope.unbind();
  }

  @Test
  void isolatesBoundSessionsEvenOnSameThread() {
    HttpSession first = mock(HttpSession.class);
    HttpSession second = mock(HttpSession.class);
    when(first.getAttribute("test.value")).thenReturn("first");
    when(second.getAttribute("test.value")).thenReturn(null);

    WebSessionScope.bind(first);
    assertEquals("first", scope.get());
    WebSessionScope.bind(second);
    assertNull(scope.get(), "a bound empty session must not inherit a previous session value");
  }

  @Test
  void executionThreadInheritsValueAfterRequestIsUnbound() throws Exception {
    HttpSession session = mock(HttpSession.class);
    WebSessionScope.bind(session);
    scope.set("project-a");

    AtomicReference<String> inherited = new AtomicReference<>();
    Thread execution = new Thread(() -> {
      WebSessionScope.unbind();
      inherited.set(scope.get());
    });
    execution.start();
    execution.join();

    assertEquals("project-a", inherited.get());
  }

  @Test
  void removeClearsFallbackOutsideRequest() {
    scope.set("value");
    scope.remove();
    assertNull(scope.get());
  }
}
