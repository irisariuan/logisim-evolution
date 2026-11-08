/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

/**
 * Thrown by an MCP tool handler when the request cannot be fulfilled (bad arguments, unknown
 * circuit, ...). The message is returned to the client as an error result, so it should tell the
 * caller how to fix the request.
 */
public class McpToolException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public McpToolException(String message) {
    super(message);
  }

  public McpToolException(String message, Throwable cause) {
    super(message, cause);
  }
}
