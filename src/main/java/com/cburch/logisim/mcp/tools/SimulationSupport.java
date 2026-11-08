/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp.tools;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.circuit.TickAware;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.mcp.Args;
import com.cburch.logisim.mcp.ComponentRefs;
import com.cburch.logisim.mcp.McpContext;
import com.cburch.logisim.mcp.McpToolException;
import com.cburch.logisim.mcp.McpToolRegistry;
import com.cburch.logisim.mcp.Schema;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Pin;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Helpers shared by the simulation and memory tool groups: value parsing/formatting, resolution
 * of simulation states (the subcircuit hierarchy) by path, and waiting for the simulator.
 */
final class SimulationSupport {
  private SimulationSupport() {}

  static final long DEFAULT_TIMEOUT_MS = 10_000;

  static final Schema PATH_ARG =
      Schema.string(
          "Simulation state path selecting a subcircuit instance inside the running simulation,"
              + " e.g. 'main/adder1/fa0': the root circuit name (optional) followed by one segment"
              + " per subcircuit instance. A segment is the instance's label, its component id"
              + " ('c12'), '@x,y' (its location) or 'CircuitName@x,y', or the subcircuit's circuit"
              + " name when that circuit is used only once at that level. See sim_list_substates."
              + " Defaults to the state currently viewed in the project.");

  static final Schema TIMEOUT_ARG =
      Schema.integer("Maximum time to wait for the simulator to settle, in milliseconds (default 10000).");

  static final Schema VALUE_ARG =
      Schema.any(
          "Value: a JSON integer (negative numbers are two's complement), a boolean (1-bit), or a"
              + " string: decimal '42' / '-3', hex '0x2A', binary '0b101010', binary with unknown"
              + " bits '0b10xx' (x/z/u/? = unknown/floating), a plain bit string containing x such as"
              + " '1x0', or 'x' for all bits unknown. Plain digit strings are decimal.");

  // ---------------------------------------------------------------------------------------------
  // Values
  // ---------------------------------------------------------------------------------------------

  /** Parses a value given by a client into a {@link Value} of the given width. */
  static Value parseValue(Object raw, int width, String argName) {
    if (width <= 0) throw new McpToolException("Cannot set a value of width " + width + ".");
    if (raw == null) throw new McpToolException("Missing value for '" + argName + "'.");
    if (raw instanceof Boolean b) return fromLong(b ? 1 : 0, width, argName, raw);
    if (raw instanceof Number n) {
      if (n.doubleValue() != Math.rint(n.doubleValue())) {
        throw new McpToolException("Value for '" + argName + "' must be an integer, got " + raw);
      }
      return fromBig(new BigInteger(Long.toString(n.longValue())), width, argName, raw);
    }
    var s = raw.toString().trim().toLowerCase().replace("_", "").replace(" ", "");
    if (s.isEmpty()) throw new McpToolException("Empty value for '" + argName + "'.");
    if (s.equals("x") || s.equals("z") || s.equals("u") || s.equals("?")) {
      return Value.createUnknown(com.cburch.logisim.data.BitWidth.create(width));
    }
    if (s.equals("true")) return fromLong(1, width, argName, raw);
    if (s.equals("false")) return fromLong(0, width, argName, raw);
    try {
      if (s.startsWith("0b")) return fromBits(s.substring(2), width, argName, raw);
      if (s.startsWith("0x")) return fromHex(s.substring(2), width, argName, raw);
      if (s.matches("-?[0-9]+")) return fromBig(new BigInteger(s), width, argName, raw);
      if (s.matches("[01xzu?]+")) return fromBits(s, width, argName, raw);
    } catch (NumberFormatException e) {
      // fall through
    }
    throw new McpToolException(
        "Cannot parse value '" + raw + "' for '" + argName
            + "'. Use e.g. 5, '0x1f', '0b0101', '0b1x0x' or 'x'.");
  }

  private static Value fromLong(long v, int width, String argName, Object raw) {
    return fromBig(BigInteger.valueOf(v), width, argName, raw);
  }

  private static Value fromBig(BigInteger v, int width, String argName, Object raw) {
    final var max = BigInteger.ONE.shiftLeft(width);
    final var minNeg = BigInteger.ONE.shiftLeft(width - 1).negate();
    if (v.compareTo(max) >= 0 || v.compareTo(minNeg) < 0) {
      throw new McpToolException(
          "Value " + raw + " for '" + argName + "' does not fit in " + width + " bit(s).");
    }
    if (v.signum() < 0) v = v.add(max);
    final var bits = new Value[width];
    for (var i = 0; i < width; i++) bits[i] = v.testBit(i) ? Value.TRUE : Value.FALSE;
    return width == 1 ? bits[0] : Value.create(bits);
  }

  private static Value fromBits(String s, int width, String argName, Object raw) {
    if (s.isEmpty()) throw new NumberFormatException();
    final var bits = new Value[width];
    final var n = s.length();
    for (var i = 0; i < width; i++) bits[i] = Value.FALSE;
    for (var i = 0; i < n; i++) {
      final var c = s.charAt(n - 1 - i);
      final Value b =
          switch (c) {
            case '0' -> Value.FALSE;
            case '1' -> Value.TRUE;
            case 'x', 'z', 'u', '?' -> Value.UNKNOWN;
            case 'e' -> Value.ERROR;
            default -> throw new NumberFormatException();
          };
      if (i >= width) {
        if (b != Value.FALSE) {
          throw new McpToolException(
              "Value " + raw + " for '" + argName + "' has more than " + width + " bit(s).");
        }
        continue;
      }
      bits[i] = b;
    }
    return width == 1 ? bits[0] : Value.create(bits);
  }

  private static Value fromHex(String s, int width, String argName, Object raw) {
    if (s.isEmpty()) throw new NumberFormatException();
    final var bin = new StringBuilder();
    for (final var c : s.toCharArray()) {
      if (c == 'x' || c == 'z' || c == 'u' || c == '?') {
        bin.append("xxxx");
      } else {
        final var d = Character.digit(c, 16);
        if (d < 0) throw new NumberFormatException();
        final var b = Integer.toBinaryString(d);
        bin.append("0".repeat(4 - b.length())).append(b);
      }
    }
    // Leading unknown digits beyond the width are allowed (e.g. 0xx for 3 bits).
    var str = bin.toString();
    while (str.length() > width && (str.charAt(0) == '0' || str.charAt(0) == 'x')) {
      str = str.substring(1);
    }
    return fromBits(str, width, argName, raw);
  }

  /** Binary representation, MSB first, using 0/1/x/E (independent of user preferences). */
  static String binary(Value v) {
    final var w = v.getWidth();
    if (w == 0) return "";
    final var sb = new StringBuilder(w);
    for (var i = w - 1; i >= 0; i--) sb.append(bitChar(v.get(i)));
    return sb.toString();
  }

  private static char bitChar(Value b) {
    if (b.isErrorValue()) return 'E';
    if (!b.isFullyDefined()) return 'x';
    return b.toLongValue() != 0 ? '1' : '0';
  }

  /** Hex representation with "0x" prefix; nibbles with unknown/error bits show x/E. */
  static String hex(Value v) {
    final var w = v.getWidth();
    if (w == 0) return "";
    final var digits = (w + 3) / 4;
    final var sb = new StringBuilder("0x");
    for (var d = digits - 1; d >= 0; d--) {
      var val = 0;
      var unknown = false;
      var error = false;
      for (var b = Math.min(w - 1, 4 * d + 3); b >= 4 * d; b--) {
        final var bit = v.get(b);
        if (bit.isErrorValue()) error = true;
        else if (!bit.isFullyDefined()) unknown = true;
        else if (bit.toLongValue() != 0) val |= 1 << (b - 4 * d);
      }
      sb.append(error ? 'E' : unknown ? 'x' : Character.forDigit(val, 16));
    }
    return sb.toString();
  }

  /** JSON description of a value: width, binary, hex and (if fully defined) decimal. */
  static Map<String, Object> valueJson(Value v) {
    final var out = new LinkedHashMap<String, Object>();
    out.put("width", v.getWidth());
    out.put("binary", binary(v));
    out.put("hex", hex(v));
    if (v.getWidth() > 0 && v.isFullyDefined()) {
      out.put("decimal", new BigInteger(v.toDecimalString(false)));
      if (v.getWidth() > 1 && v.get(v.getWidth() - 1) == Value.TRUE) {
        out.put("signed", new BigInteger(v.toDecimalString(true)));
      }
    } else {
      out.put("decimal", null);
      out.put("state", v.isErrorValue() ? "error" : v.isUnknown() ? "unknown" : "partially unknown");
    }
    return out;
  }

  /** Compact string of a value in the given format: binary, hex or decimal. */
  static String format(Value v, String fmt) {
    return switch (fmt) {
      case "hex" -> hex(v);
      case "decimal" -> v.isFullyDefined() ? v.toDecimalString(false) : binary(v);
      default -> binary(v);
    };
  }

  // ---------------------------------------------------------------------------------------------
  // Simulation states
  // ---------------------------------------------------------------------------------------------

  /** The simulation state chosen by a tool's arguments. */
  record Target(CircuitState state, String path, boolean rootSwitched) {}

  /** Root state of the running simulation. */
  static CircuitState rootState(Project proj) {
    var root = proj.getSimulator().getCircuitState();
    if (root == null && proj.getCircuitState() != null) {
      root = proj.getCircuitState();
      while (root.getParentState() != null) root = root.getParentState();
    }
    if (root == null) {
      throw new McpToolException(
          "No circuit is being simulated (an HDL entity may be open). Select a circuit first.");
    }
    return root;
  }

  /**
   * Resolves the state addressed by the "circuit" and "path" arguments:
   *
   * <ul>
   *   <li>"circuit" naming a circuit that is neither the viewed nor the root circuit makes that
   *       circuit the simulation root (like opening it in the GUI);
   *   <li>"path" is then resolved from the root;
   *   <li>without "path", the viewed state is used when its circuit matches, else the root.
   * </ul>
   */
  static Target target(McpContext ctx, Project proj, Args args) {
    var switched = false;
    final var viewed = proj.getCircuitState();
    if (args.has("circuit")) {
      final var circ = ctx.circuit(proj, args);
      final var root = rootState(proj);
      if (!args.has("path") && viewed != null && viewed.getCircuit() == circ) {
        return new Target(viewed, pathOf(viewed), false);
      }
      if (root.getCircuit() != circ) {
        proj.setCurrentCircuit(circ);
        switched = true;
      }
    }
    final var root = rootState(proj);
    CircuitState state;
    if (args.has("path")) {
      state = resolvePath(ctx, root, args.str("path"));
    } else if (!args.has("circuit") && viewed != null) {
      state = viewed;
    } else {
      state = root;
    }
    return new Target(state, pathOf(state), switched);
  }

  /** Path of a state ("main/adder1"). */
  static String pathOf(CircuitState state) {
    final var segs = new ArrayList<String>();
    var cur = state;
    while (cur.getParentState() != null) {
      final var parent = cur.getParentState();
      final var comp = componentOf(parent, cur);
      segs.add(0, comp == null ? "?" : segmentName(parent.getCircuit(), comp));
      cur = parent;
    }
    segs.add(0, cur.getCircuit().getName());
    return String.join("/", segs);
  }

  /** The subcircuit component of {@code parent} whose state is {@code child}. */
  static Component componentOf(CircuitState parent, CircuitState child) {
    for (final var comp : parent.getCircuit().getNonWires()) {
      if (comp.getFactory() instanceof SubcircuitFactory && parent.getData(comp) == child) return comp;
    }
    return null;
  }

  /** Path segment naming a subcircuit instance: its label when unique, else Name@x,y. */
  static String segmentName(Circuit circ, Component comp) {
    final var label = ComponentRefs.label(comp);
    if (label != null && !label.isEmpty() && !label.contains("/")) {
      var count = 0;
      for (final var c : circ.getNonWires()) if (label.equals(ComponentRefs.label(c))) count++;
      if (count == 1) return label;
    }
    final var loc = comp.getLocation();
    return ((SubcircuitFactory) comp.getFactory()).getSubcircuit().getName()
        + "@" + loc.getX() + "," + loc.getY();
  }

  static CircuitState resolvePath(McpContext ctx, CircuitState root, String path) {
    final var segs = new ArrayList<String>();
    for (final var s : path.split("/")) if (!s.isBlank()) segs.add(s.trim());
    if (!segs.isEmpty() && segs.get(0).equals(root.getCircuit().getName())) segs.remove(0);
    var cur = root;
    for (final var seg : segs) {
      final var comp = resolveSubcircuit(ctx, cur.getCircuit(), seg);
      cur = ((SubcircuitFactory) comp.getFactory()).getSubstate(cur, comp);
    }
    return cur;
  }

  private static Component resolveSubcircuit(McpContext ctx, Circuit circ, String seg) {
    String ref = seg;
    final var at = seg.lastIndexOf('@');
    if (at > 0) ref = seg.substring(at);
    Component found = null;
    try {
      found = ComponentRefs.resolve(ctx, circ, ref);
    } catch (McpToolException e) {
      // try by circuit name below
    }
    if (found == null || !(found.getFactory() instanceof SubcircuitFactory)) {
      final var matches = new ArrayList<Component>();
      for (final var c : circ.getNonWires()) {
        if (c.getFactory() instanceof SubcircuitFactory sf && sf.getSubcircuit().getName().equals(seg)) {
          matches.add(c);
        }
      }
      if (matches.size() == 1) return matches.get(0);
      final var names = new ArrayList<String>();
      for (final var c : circ.getNonWires()) {
        if (c.getFactory() instanceof SubcircuitFactory) names.add(segmentName(circ, c));
      }
      throw new McpToolException(
          "No subcircuit instance '" + seg + "' in circuit '" + circ.getName() + "'"
              + (matches.size() > 1 ? " (ambiguous circuit name)" : "")
              + ". Instances: " + names);
    }
    return found;
  }

  // ---------------------------------------------------------------------------------------------
  // Simulator control
  // ---------------------------------------------------------------------------------------------

  /** Asks the simulator to propagate pending changes (no effect without auto-propagation). */
  static boolean nudge(Project proj) {
    return proj.getSimulator().nudge();
  }

  /**
   * Waits for the simulator to become idle. Must be called off the model thread (tools using it
   * are registered with offModelThread()).
   */
  static Map<String, Object> settle(McpContext ctx, Project proj, long timeoutMs) {
    if (ctx.isModelThread()) {
      throw new IllegalStateException("settle() must not be called on the model thread");
    }
    final var sim = proj.getSimulator();
    final var out = new LinkedHashMap<String, Object>();
    boolean idle;
    try {
      idle = sim.waitUntilIdle(timeoutMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new McpToolException("Interrupted while waiting for the simulator.", e);
    }
    out.put("settled", idle && !sim.isOscillating());
    if (!idle) out.put("timedOut", true);
    out.put("oscillating", sim.isOscillating());
    if (idle) out.put("pendingEvents", sim.isPropagationPending());
    out.put("autoPropagate", sim.isAutoPropagating());
    if (sim.isAutoTicking()) out.put("autoTick", true);
    if (sim.isExceptionEncountered()) out.put("exception", sim.getExceptionMessage());
    if (sim.isOscillating()) {
      out.put(
          "hint",
          "The circuit oscillates; Logisim stopped and disabled auto-propagation. Fix the loop or"
              + " change inputs, then sim_reset and sim_set autoPropagate=true.");
    }
    return out;
  }

  /**
   * Wraps a read handler so that the target state is selected and the simulator has settled first
   * (headless projects are never nudged by a canvas, so a freshly opened circuit has not been
   * propagated yet). The wrapped handler runs on the model thread; register with offModelThread().
   */
  static McpToolRegistry.Handler settledFirst(McpToolRegistry.Handler handler) {
    return (ctx, args) -> {
      final var proj =
          ctx.onModelThread(
              () -> {
                final var p = ctx.project(args);
                if (args.has("circuit") || args.has("path")) target(ctx, p, args);
                nudge(p);
                return p;
              });
      settle(ctx, proj, args.lng("timeoutMs", DEFAULT_TIMEOUT_MS));
      return ctx.onModelThread(() -> handler.call(ctx, args));
    };
  }

  /** True if the circuit or any subcircuit contains a clock or tick-aware component. */
  static boolean hasClocks(Circuit circ, Set<Circuit> seen) {
    if (!seen.add(circ)) return false;
    if (!circ.getClocks().isEmpty()) return true;
    for (final var comp : circ.getNonWires()) {
      if (comp.getFactory() instanceof TickAware) return true;
      if (comp.getFactory() instanceof SubcircuitFactory sf && hasClocks(sf.getSubcircuit(), seen)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Makes sure the simulation root has a clock, without the GUI's clock-driver dialog.
   *
   * @throws McpToolException if there is none.
   */
  static void ensureClocks(Project proj) {
    final var root = rootState(proj);
    if (root.hasKnownClocks()) return;
    if (hasClocks(root.getCircuit(), new HashSet<>())) {
      root.markKnownClocks();
      return;
    }
    throw new McpToolException(
        "Circuit '" + root.getCircuit().getName() + "' contains no Clock component (also not in"
            + " subcircuits), so it cannot be ticked. Add a Clock, or drive inputs with sim_set_input.");
  }

  // ---------------------------------------------------------------------------------------------
  // Pins and ports
  // ---------------------------------------------------------------------------------------------

  static List<Component> pins(Circuit circ, Boolean input) {
    final var out = new ArrayList<Component>();
    for (final var comp : circ.getNonWires()) {
      if (comp.getFactory() instanceof Pin pin) {
        final var inst = ((com.cburch.logisim.instance.InstanceComponent) comp).getInstance();
        if (input == null || pin.isInputPin(inst) == input) out.add(comp);
      }
    }
    out.sort(
        (a, b) -> {
          final var la = a.getLocation();
          final var lb = b.getLocation();
          return la.getY() != lb.getY() ? Integer.compare(la.getY(), lb.getY()) : Integer.compare(la.getX(), lb.getX());
        });
    return out;
  }

  static Value pinValue(CircuitState state, Component pin) {
    return Pin.FACTORY.getValue(state.getInstanceState(pin));
  }

  static Map<String, Object> pinJson(McpContext ctx, CircuitState state, Component pin) {
    final var out = new LinkedHashMap<String, Object>();
    out.put("id", ctx.componentId(pin));
    final var label = ComponentRefs.label(pin);
    out.put("label", label == null ? "" : label);
    final var v = pinValue(state, pin);
    out.putAll(valueJson(v));
    return out;
  }

  static Value portValue(CircuitState state, Component comp, int index) {
    return state.getValue(comp.getEnd(index).getLocation());
  }

  /** Values of all ports of a component, using the port names from ComponentRefs.ports. */
  static List<Map<String, Object>> portValues(CircuitState state, Component comp) {
    final var out = new ArrayList<Map<String, Object>>();
    final var ports = ComponentRefs.ports(comp);
    for (var i = 0; i < ports.size(); i++) {
      final var p = new LinkedHashMap<String, Object>();
      p.put("index", i);
      if (ports.get(i).get("name") != null) p.put("name", ports.get(i).get("name"));
      p.put("direction", ports.get(i).get("direction"));
      p.put("location", ports.get(i).get("location"));
      final var v = portValue(state, comp, i);
      p.put("value", binary(v));
      if (v.getWidth() > 4) p.put("hex", hex(v));
      out.add(p);
    }
    return out;
  }

  /** Recursively collects (state, component) pairs whose factory matches. */
  static void collect(
      CircuitState state, java.util.function.Predicate<Component> filter, boolean recursive,
      int depth, List<Map.Entry<CircuitState, Component>> out) {
    for (final var comp : state.getCircuit().getNonWires()) {
      if (filter.test(comp)) out.add(Map.entry(state, comp));
    }
    if (!recursive || depth > 32) return;
    for (final var comp : state.getCircuit().getNonWires()) {
      if (comp.getFactory() instanceof SubcircuitFactory sf) {
        collect(sf.getSubstate(state, comp), filter, true, depth + 1, out);
      }
    }
  }
}
