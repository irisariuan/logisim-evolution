/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.gui.generic.OptionPane;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpCoreTest {

  @Test
  void argsConvertAndValidate() {
    final var args = new Args(Map.of("i", 3, "h", "0x1f", "b", "true", "d", 2.5, "s", "x"));
    assertEquals(3, args.integer("i"));
    assertEquals(31, args.integer("h"));
    assertTrue(args.bool("b"));
    assertEquals(2.5, args.dbl("d"));
    assertEquals("x", args.str("s"));
    assertEquals(7, args.integer("missing", 7));
    assertThrows(McpToolException.class, () -> args.integer("missing"));
    assertThrows(McpToolException.class, () -> args.integer("d"));
    assertThrows(McpToolException.class, () -> args.bool("s"));
  }

  @Test
  void schemaBuildsObjectWithRequiredProps() {
    final var map =
        Schema.object()
            .prop("a", Schema.string("A"), true)
            .prop("b", Schema.enumeration("B", "x", "y"))
            .toMap();
    assertEquals("object", map.get("type"));
    assertEquals(List.of("a"), map.get("required"));
    @SuppressWarnings("unchecked")
    final var props = (Map<String, Object>) map.get("properties");
    assertEquals(List.of("x", "y"), ((Map<?, ?>) props.get("b")).get("enum"));
  }

  @Test
  void dialogInterceptorAnswersInsteadOfShowing() {
    try {
      OptionPane.setInterceptor((kind, title, message, options, initial) -> "confirm".equals(kind) ? 0 : "typed");
      assertEquals(0, OptionPane.showConfirmDialog(null, "Sure?", "T", OptionPane.YES_NO_OPTION));
      assertEquals("typed", OptionPane.showInputDialog(null, "Name?"));
    } finally {
      OptionPane.setInterceptor(null);
    }
    assertNull(OptionPane.getInterceptor());
  }
}
