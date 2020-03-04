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

import com.datastax.oss.driver.api.core.session.Session;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.jcip.annotations.GuardedBy;

/**
 * A node state listener that provides easier semantics if you're not interested in events emitted
 * during the initialization of the session.
 *
 * <p>By default, the driver emits node state events before the session is ready (see {@link
 * SessionAwareNodeStateListener} for a detailed explanation). This class is a wrapper that allows
 * you to delay the construction of the actual listener until the session is ready:
 *
 * <pre>
 * public class SimpleListener implements NodeStateListener {
 *
 *   private final Session session;
 *
 *   public SimpleListener(Session session) {
 *     this.session = session;
 *   }
 *
 *   ... // implement other methods
 * }
 *
 * SafeInitNodeStateListenerWrapper wrapper =
 *     new SafeInitNodeStateListenerWrapper(SimpleListener::new, true);
 *
 * CqlSession session = CqlSession.builder().withNodeStateListener(wrapper).build();
 * </pre>
 *
 * The second constructor argument indicates what to do with the initialization-time events:
 *
 * <ul>
 *   <li>if {@code true}, they will be recorded, and replayed to the child listener after it gets
 *       created. These calls are guaranteed to happen in the original order, and before any
 *       post-initialization events.
 *   <li>if {@code false}, they are discarded.
 * </ul>
 */
public class SafeInitNodeStateListenerWrapper implements SessionAwareNodeStateListener {

  private final Function<Session, NodeStateListener> childListenerFactory;
  private final boolean replayInitEvents;

  // Write lock: recording init events or setting the child listener.
  // Read lock: using the child listener
  private final ReadWriteLock lock = new ReentrantReadWriteLock();

  @GuardedBy("lock")
  private NodeStateListener childListener;

  @GuardedBy("lock")
  private List<InitEvent> initEvents;

  /**
   * Creates a new instance.
   *
   * @param childListenerFactory the callback that will be invoked to create the child listener once
   *     the session is ready.
   * @param replayInitEvents whether to record events during initialization, and replay them to the
   *     child listener once it's created.
   */
  public SafeInitNodeStateListenerWrapper(
      @NonNull Function<Session, NodeStateListener> childListenerFactory,
      boolean replayInitEvents) {
    this.childListenerFactory = Objects.requireNonNull(childListenerFactory);
    this.replayInitEvents = replayInitEvents;
  }

  @Override
  public void onSessionReady(@NonNull Session session) {
    lock.writeLock().lock();
    try {
      childListener = childListenerFactory.apply(session);
      if (replayInitEvents && initEvents != null) {
        for (InitEvent event : initEvents) {
          event.invoke(childListener);
        }
        initEvents = null;
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public void onAdd(@NonNull Node node) {
    onEvent(node, NodeStateListener::onAdd, InitEvent.Type.ADD);
  }

  @Override
  public void onUp(@NonNull Node node) {
    onEvent(node, NodeStateListener::onUp, InitEvent.Type.UP);
  }

  @Override
  public void onDown(@NonNull Node node) {
    onEvent(node, NodeStateListener::onDown, InitEvent.Type.DOWN);
  }

  @Override
  public void onRemove(@NonNull Node node) {
    onEvent(node, NodeStateListener::onRemove, InitEvent.Type.REMOVE);
  }

  private void onEvent(
      Node node, BiConsumer<NodeStateListener, Node> listenerMethod, InitEvent.Type initEventType) {

    // Cheap case: the child listener is already set, just delegate
    lock.readLock().lock();
    try {
      if (childListener != null) {
        listenerMethod.accept(childListener, node);
        return;
      }
    } finally {
      lock.readLock().unlock();
    }

    // Otherwise, we must acquire the write lock to record the event
    if (replayInitEvents) {
      lock.writeLock().lock();
      try {
        // Must re-check because we completely released the lock for a short duration
        if (childListener != null) {
          listenerMethod.accept(childListener, node);
        } else {
          if (initEvents == null) {
            initEvents = new ArrayList<>();
          }
          initEvents.add(new InitEvent(node, initEventType));
        }
      } finally {
        lock.writeLock().unlock();
      }
    }
  }

  @Override
  public void close() throws Exception {
    lock.readLock().lock();
    try {
      if (childListener != null) {
        childListener.close();
      }
    } finally {
      lock.readLock().unlock();
    }
  }

  private static class InitEvent {
    enum Type {
      ADD,
      UP,
      DOWN,
      REMOVE,
    }

    final Node node;
    final Type type;

    InitEvent(@NonNull Node node, @NonNull Type type) {
      this.node = Objects.requireNonNull(node);
      this.type = Objects.requireNonNull(type);
    }

    void invoke(@NonNull NodeStateListener target) {
      Objects.requireNonNull(target);
      switch (type) {
        case ADD:
          target.onAdd(node);
          break;
        case UP:
          target.onUp(node);
          break;
        case DOWN:
          target.onDown(node);
          break;
        case REMOVE:
          target.onRemove(node);
          break;
        default:
          throw new AssertionError("Unhandled type " + type);
      }
    }
  }
}
