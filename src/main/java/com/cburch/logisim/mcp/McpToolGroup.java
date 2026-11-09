/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

/** A cohesive set of MCP tools (e.g. editing, simulation) that registers itself with the server. */
public interface McpToolGroup {
  void register(McpToolRegistry registry);
}
