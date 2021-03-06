package org.rabix.engine.processor.handler;

import org.rabix.engine.event.Event;

/**
 * Describes an event handler interface
 */
public interface EventHandler<T extends Event> {

  /**
   * Handles the event
   */
  void handle(T event) throws EventHandlerException;

}
