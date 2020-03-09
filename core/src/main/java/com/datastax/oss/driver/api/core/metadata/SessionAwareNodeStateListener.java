/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.driver.api.core.metadata;

import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.loadbalancing.NodeDistance;
import com.datastax.oss.driver.api.core.session.Session;
import com.datastax.oss.driver.api.core.session.SessionBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A node state listener that gets notified when its owning session is ready to execute requests.
 *
 * <p>Note that {@link #onSessionReady(Session)} will not be the first method invoked on this
 * object; the driver emits node events before that, during the initialization of the session:
 *
 * <ul>
 *   <li>First the driver shuffles the contact points, and tries each one sequentially. For any
 *       contact point that can't be reached, {@link #onDown(Node)} is invoked; for the one that
 *       eventually succeeds, {@link #onUp(Node)} is invoked and that node becomes the control node
 *       (if none succeeds, the session initialization fails and the process stops here).
 *   <li>The control node's {@code system.peers} table is inspected to discover the remaining nodes
 *       in the cluster. For any node that wasn't already a contact point, {@link #onAdd(Node)} is
 *       invoked; for any contact point that doesn't have a corresponding entry in the table, {@link
 *       #onRemove(Node)} is invoked;
 *   <li>The load balancing policy computes the nodes' {@linkplain NodeDistance distances}, and, for
 *       each node that is not ignored, the driver creates a connection pool. If at least one pooled
 *       connection can be established, {@link #onUp(Node)} is invoked; otherwise, {@link
 *       #onDown(Node)} is invoked (no additional event is emitted for the control node, it is
 *       considered up since we already have a connection to it).
 *   <li>Once all the pools are created, the session is fully initialized and the {@link
 *       CqlSessionBuilder#build()} call returns.
 * </ul>
 *
 * If you're not interested in those init events, and would rather have a reference to the
 * initialized session at construction time, take a look at {@link
 * SafeInitNodeStateListenerWrapper}.
 */
public interface SessionAwareNodeStateListener extends NodeStateListener {

  /**
   * Invoked when the session is ready to process user requests.
   *
   * <p>This corresponds to the moment when the {@link SessionBuilder#build()} returns, or the
   * future returned by {@link SessionBuilder#buildAsync()} completes. If the session initialization
   * fails, this method will not get called.
   *
   * <p>This method is invoked on a driver thread, it should complete relatively quickly and not
   * block.
   */
  void onSessionReady(@NonNull Session session);
}
