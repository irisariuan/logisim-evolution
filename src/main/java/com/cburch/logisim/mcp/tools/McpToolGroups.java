/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp.tools;

import com.cburch.logisim.mcp.McpToolGroup;
import java.util.List;

/** The list of all MCP tool groups exposed by the server. */
public final class McpToolGroups {
  private McpToolGroups() {}

  public static List<McpToolGroup> all() {
    return List.of(
        new ProjectTools(),
        new EditTools(),
        new SimulationTools(),
        new MemoryTools(),
        new ViewTools(),
        new AnalysisTools(),
        new HdlTools(),
        new McpLibraryTools(),
        new PreferenceTools());
  }
}
