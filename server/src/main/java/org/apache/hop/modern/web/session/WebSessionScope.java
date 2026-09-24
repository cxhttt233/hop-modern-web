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

import jakarta.servlet.http.HttpSession;
import org.apache.hop.core.scope.IHopScope;

/** Keeps Hop ambient state isolated by HTTP session and inherited by execution threads. */
public class WebSessionScope<T> implements IHopScope<T> {
  private static final ThreadLocal<HttpSession> REQUEST_SESSION = new ThreadLocal<>();

  private final String attributeName;
  private final InheritableThreadLocal<T> outsideRequest = new InheritableThreadLocal<>();

  public WebSessionScope(String attributeName) {
    this.attributeName = attributeName;
  }

  /** Bind the HTTP session being served by the current request thread. */
  public static void bind(HttpSession session) {
    REQUEST_SESSION.set(session);
  }

  /** Remove request state before the container reuses the request thread. */
  public static void unbind() {
    REQUEST_SESSION.remove();
  }

  @Override
  @SuppressWarnings("unchecked")
  public T get() {
    HttpSession session = REQUEST_SESSION.get();
    return session != null ? (T) session.getAttribute(attributeName) : outsideRequest.get();
  }

  @Override
  public void set(T value) {
    HttpSession session = REQUEST_SESSION.get();
    if (session != null) {
      session.setAttribute(attributeName, value);
    }
    outsideRequest.set(value);
  }

  @Override
  public void remove() {
    HttpSession session = REQUEST_SESSION.get();
    if (session != null) {
      session.removeAttribute(attributeName);
    }
    outsideRequest.remove();
  }
}
