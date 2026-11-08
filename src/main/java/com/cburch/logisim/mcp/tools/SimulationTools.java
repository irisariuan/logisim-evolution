/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp.tools;

import static com.cburch.logisim.mcp.McpToolRegistry.CIRCUIT_ARG;
import static com.cburch.logisim.mcp.McpToolRegistry.PROJECT_ARG;
import static com.cburch.logisim.mcp.tools.SimulationSupport.DEFAULT_TIMEOUT_MS;
import static com.cburch.logisim.mcp.tools.SimulationSupport.PATH_ARG;
import static com.cburch.logisim.mcp.tools.SimulationSupport.TIMEOUT_ARG;
import static com.cburch.logisim.mcp.tools.SimulationSupport.VALUE_ARG;

import com.cburch.logisim.circuit.Analyze;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.gui.log.LoggableContract;
import com.cburch.logisim.instance.InstanceComponent;
import com.cburch.logisim.instance.InstanceDataSingleton;
import com.cburch.logisim.instance.InstanceFactory;
import com.cburch.logisim.instance.InstancePoker;
import com.cburch.logisim.instance.InstanceState;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.mcp.Args;
import com.cburch.logisim.mcp.ComponentRefs;
import com.cburch.logisim.mcp.McpContext;
import com.cburch.logisim.mcp.McpToolException;
import com.cburch.logisim.mcp.McpToolGroup;
import com.cburch.logisim.mcp.McpToolRegistry;
import com.cburch.logisim.mcp.Schema;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.io.DipSwitch;
import com.cburch.logisim.std.io.Joystick;
import com.cburch.logisim.std.io.Keyboard;
import com.cburch.logisim.std.io.Tty;
import com.cburch.logisim.std.io.extra.Switch;
import com.cburch.logisim.std.io.extra.TwoWaySwitch;
import com.cburch.logisim.std.memory.Mem;
import com.cburch.logisim.std.wiring.Clock;
import com.cburch.logisim.std.wiring.Pin;
import com.cburch.logisim.std.wiring.Probe;
import com.cburch.logisim.std.wiring.Tunnel;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import javax.swing.JPanel;

/**
 * Simulation control: poking inputs, reading values, ticking clocks, stepping, reset, simulation
 * hierarchy, TTY and keyboard components.
 *
 * <p>Logisim simulates asynchronously on its own thread. Tools that change something and then
 * report results wait for that thread to become idle ("settle"), so they run off the model thread.
 */
public final class SimulationTools implements McpToolGroup {

  /** Pokers that keep state between press and release are kept per component. */
  private final Map<Component, InstancePoker> pokers = new WeakHashMap<>();

  private static java.awt.Component eventSource;

  private static final String TARGET_DOC =
      " The target simulation state is chosen with 'path' (a subcircuit instance, see"
          + " sim_list_substates) and/or 'circuit'; by default it is the state currently viewed"
          + " in the project. Naming a 'circuit' that is not the one being simulated makes it the"
          + " simulation root (like opening it in the GUI).";

  private static Schema targetSchema() {
    return Schema.object()
        .prop("project", PROJECT_ARG)
        .prop("circuit", CIRCUIT_ARG)
        .prop("path", PATH_ARG);
  }

  @Override
  public void register(McpToolRegistry registry) {
    registerControl(registry);
    registerValues(registry);
    registerInteraction(registry);
    registerHierarchy(registry);
    registerTruthTable(registry);
  }

  // ---------------------------------------------------------------------------------------------
  // Simulator control
  // ---------------------------------------------------------------------------------------------

  private static Map<String, Object> status(McpContext ctx, Project proj) {
    final var sim = proj.getSimulator();
    final var out = new LinkedHashMap<String, Object>();
    out.put("project", ctx.projectId(proj));
    out.put("autoPropagate", sim.isAutoPropagating());
    out.put("autoTick", sim.isAutoTicking());
    out.put("tickFrequencyHz", sim.getTickFrequency());
    out.put("oscillating", sim.isOscillating());
    out.put("exception", sim.isExceptionEncountered() ? sim.getExceptionMessage() : null);
    final var root = SimulationSupport.rootState(proj);
    out.put("rootCircuit", root.getCircuit().getName());
    final var viewed = proj.getCircuitState();
    out.put("viewedPath", viewed == null ? null : SimulationSupport.pathOf(viewed));
    out.put("halfTicks", root.getPropagator().getTickCount());
    out.put("hasClocks", SimulationSupport.hasClocks(root.getCircuit(), new java.util.HashSet<>()));
    final var msg = sim.getSingleStepMessage();
    if (msg != null && !msg.isEmpty()) out.put("singleStepMessage", msg);
    return out;
  }

  private void registerControl(McpToolRegistry registry) {
    registry
        .tool("sim_status")
        .description(
            "Reports the simulator state of a project (Simulate menu): whether auto-propagation"
                + " and auto-tick are enabled, the tick frequency in Hz, oscillation and exception"
                + " state, the root circuit being simulated, the viewed simulation state path"
                + " (see sim_list_substates), the number of half clock ticks since reset, whether"
                + " the circuit has clocks, and the single-step message when stepping.")
        .input(Schema.object().prop("project", PROJECT_ARG))
        .readOnly()
        .handler((ctx, args) -> status(ctx, ctx.project(args)));

    registry
        .tool("sim_set")
        .description(
            "Changes simulator settings (Simulate menu). 'autoPropagate': propagate signal changes"
                + " automatically (when false, changes stay pending until sim_step). 'autoTick':"
                + " let clocks tick continuously in real time at 'tickFrequency' (requires a Clock;"
                + " prefer sim_tick for deterministic ticking). 'tickFrequency': auto-tick frequency"
                + " in Hz (e.g. 1, 4, 1000; values like 0.5 are allowed). Returns sim_status.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("autoPropagate", Schema.bool("Enable/disable auto-propagation."))
                .prop("autoTick", Schema.bool("Enable/disable automatic clock ticking."))
                .prop("tickFrequency", Schema.number("Auto-tick frequency in Hz (> 0).")))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var sim = proj.getSimulator();
              if (args.has("tickFrequency")) {
                final var f = args.dbl("tickFrequency");
                if (!(f > 0) || f > 1e9) throw new McpToolException("tickFrequency must be > 0 Hz.");
                sim.setTickFrequency(f);
              }
              if (args.has("autoPropagate")) {
                sim.setAutoPropagation(args.bool("autoPropagate"));
              }
              if (args.has("autoTick")) {
                final var on = args.bool("autoTick");
                if (on) SimulationSupport.ensureClocks(proj);
                sim.setAutoTicking(on);
              }
              return status(ctx, proj);
            });

    registry
        .tool("sim_reset")
        .description(
            "Resets the simulation (Simulate > Reset Simulation): all signals, RAM contents"
                + " (volatile RAMs), registers, input pins and interactive components return to"
                + " their initial state, and the half-tick counter restarts. Waits until the reset"
                + " has propagated and returns the settle status and sim_status.")
        .input(Schema.object().prop("project", PROJECT_ARG).prop("timeoutMs", TIMEOUT_ARG))
        .offModelThread()
        .handler(
            (ctx, args) -> {
              final var proj = ctx.onModelThread(() -> ctx.project(args));
              ctx.onModelThread(() -> proj.getSimulator().reset());
              final var settle =
                  SimulationSupport.settle(ctx, proj, args.lng("timeoutMs", DEFAULT_TIMEOUT_MS));
              return ctx.onModelThread(
                  () -> {
                    final var out = new LinkedHashMap<String, Object>();
                    out.put("settle", settle);
                    out.put("status", status(ctx, proj));
                    return out;
                  });
            });

    registry
        .tool("sim_step")
        .description(
            "Performs single propagation steps (Simulate > Step Simulation): each step processes"
                + " the signal changes of one simulated time unit. This DISABLES auto-propagation"
                + " (as in the GUI); re-enable it with sim_set autoPropagate=true. If auto-tick is on"
                + " and nothing is pending, a step also ticks the clock. Returns whether events are"
                + " still pending and Logisim's single-step message.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("count", Schema.integer("Number of steps (default 1, max 10000)."))
                .prop("timeoutMs", TIMEOUT_ARG))
        .offModelThread()
        .handler(
            (ctx, args) -> {
              final var count = args.integer("count", 1);
              if (count < 1 || count > 10000) throw new McpToolException("count must be 1..10000.");
              final var proj = ctx.onModelThread(() -> ctx.project(args));
              ctx.onModelThread(
                  () -> {
                    for (var i = 0; i < count; i++) proj.getSimulator().step();
                  });
              final var settle =
                  SimulationSupport.settle(ctx, proj, args.lng("timeoutMs", DEFAULT_TIMEOUT_MS));
              return ctx.onModelThread(
                  () -> {
                    final var out = new LinkedHashMap<String, Object>();
                    out.put("steps", count);
                    out.put("pendingEvents", settle.get("pendingEvents"));
                    out.put("settle", settle);
                    out.put("status", status(ctx, proj));
                    return out;
                  });
            });

    registry
        .tool("sim_tick")
        .description(
            "Ticks the clocks of the simulated circuit (Simulate > Tick Half/Full Cycle) and waits"
                + " until the circuit has settled after the last tick. A full tick is two half ticks"
                + " (clock goes high then low, starting from the reset state). Requires a Clock"
                + " component in the simulated circuit or its subcircuits. Returns the settle"
                + " status, the half-tick counter and, optionally, the pin values of the root"
                + " circuit after ticking.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("count", Schema.integer("Number of ticks (default 1, max 1000000)."))
                .prop("unit", Schema.enumeration("'full' (default) or 'half' clock cycles.", "full", "half"))
                .prop("returnValues", Schema.bool("Also return the root circuit's pin values (default true)."))
                .prop("timeoutMs", Schema.integer("Max wait in ms (default 10000 + 1 ms per tick).")))
        .offModelThread()
        .handler(
            (ctx, args) -> {
              final var count = args.integer("count", 1);
              if (count < 1 || count > 1_000_000) throw new McpToolException("count must be 1..1000000.");
              final var half = "half".equals(args.str("unit", "full"));
              final var halfTicks = half ? count : 2 * count;
              final var proj =
                  ctx.onModelThread(
                      () -> {
                        final var p = ctx.project(args);
                        SimulationSupport.ensureClocks(p);
                        p.getSimulator().tick(halfTicks);
                        return p;
                      });
              final var settle =
                  SimulationSupport.settle(
                      ctx, proj, args.lng("timeoutMs", DEFAULT_TIMEOUT_MS + halfTicks));
              return ctx.onModelThread(
                  () -> {
                    final var out = new LinkedHashMap<String, Object>();
                    out.put("halfTicksRequested", halfTicks);
                    final var root = SimulationSupport.rootState(proj);
                    out.put("halfTicks", root.getPropagator().getTickCount());
                    out.put("settle", settle);
                    if (args.bool("returnValues", true)) out.putAll(pinValues(ctx, root, false));
                    return out;
                  });
            });

    registry
        .tool("sim_settle")
        .description(
            "Waits until the simulator has processed all pending work (propagation after input"
                + " changes, requested ticks/steps/reset) and reports whether it settled, timed out"
                + " or oscillates. With auto-tick enabled the circuit never stays still; this then"
                + " waits only for the current tick.")
        .input(Schema.object().prop("project", PROJECT_ARG).prop("timeoutMs", TIMEOUT_ARG))
        .readOnly()
        .offModelThread()
        .handler(
            (ctx, args) -> {
              final var proj = ctx.onModelThread(() -> ctx.project(args));
              ctx.onModelThread(() -> SimulationSupport.nudge(proj));
              return SimulationSupport.settle(ctx, proj, args.lng("timeoutMs", DEFAULT_TIMEOUT_MS));
            });

    registry
        .tool("sim_run_until")
        .description(
            "Ticks the clock repeatedly until a signal reaches a value (or stops having it), up"
                + " to 'maxTicks' ticks or 'timeoutMs'. The signal is a component port ('component'"
                + " + optional 'port', default port 0 — e.g. an output Pin or a Probe, given by"
                + " label or id) in the target state. The condition is checked before the first"
                + " tick and after each tick once the circuit has settled. Stops early on"
                + " oscillation. Returns whether the condition was met, the number of ticks"
                + " performed and the final value." + TARGET_DOC)
        .input(
            targetSchema()
                .prop("component", Schema.string("Component reference (label, id 'c12' or '@x,y')."), true)
                .prop("port", Schema.any("Port index or name (default 0)."))
                .prop("value", VALUE_ARG, true)
                .prop("condition", Schema.enumeration("'equals' (default) or 'notEquals'.", "equals", "notEquals"))
                .prop("unit", Schema.enumeration("'full' (default) or 'half' clock cycles per tick.", "full", "half"))
                .prop("maxTicks", Schema.integer("Maximum number of ticks (default 1000)."))
                .prop("timeoutMs", Schema.integer("Overall time limit in ms (default 30000).")))
        .offModelThread()
        .handler(this::runUntil);
  }

  private record Probed(Project proj, CircuitState state, Component comp, int port, Value target) {}

  private Object runUntil(McpContext ctx, Args args) {
    final var probe =
        ctx.onModelThread(
            () -> {
              final var proj = ctx.project(args);
              SimulationSupport.ensureClocks(proj);
              final var t = SimulationSupport.target(ctx, proj, args);
              final var comp = ComponentRefs.resolve(ctx, t.state().getCircuit(), args.str("component"));
              final var port = portIndex(comp, args.get("port"));
              final var width = comp.getEnd(port).getWidth().getWidth();
              final var target = SimulationSupport.parseValue(args.get("value"), width, "value");
              return new Probed(proj, t.state(), comp, port, target);
            });
    final var proj = probe.proj();
    final var notEquals = "notEquals".equals(args.str("condition", "equals"));
    final var halfPerTick = "half".equals(args.str("unit", "full")) ? 1 : 2;
    final var maxTicks = args.integer("maxTicks", 1000);
    if (maxTicks < 0 || maxTicks > 10_000_000) throw new McpToolException("maxTicks must be 0..10000000.");
    final var deadline = System.currentTimeMillis() + args.lng("timeoutMs", 30_000);
    ctx.onModelThread(() -> SimulationSupport.nudge(proj));
    var settle = SimulationSupport.settle(ctx, proj, DEFAULT_TIMEOUT_MS);
    var ticks = 0;
    var met = false;
    var reason = "maxTicks";
    Value current;
    while (true) {
      current = ctx.onModelThread(() -> SimulationSupport.portValue(probe.state(), probe.comp(), probe.port()));
      final var eq = probe.target().compatible(current);
      if (eq != notEquals) {
        met = true;
        reason = "condition";
        break;
      }
      if (proj.getSimulator().isOscillating()) {
        reason = "oscillation";
        break;
      }
      if (ticks >= maxTicks) break;
      final var remaining = deadline - System.currentTimeMillis();
      if (remaining <= 0) {
        reason = "timeout";
        break;
      }
      ctx.onModelThread(() -> proj.getSimulator().tick(halfPerTick));
      settle = SimulationSupport.settle(ctx, proj, Math.max(1, remaining));
      ticks++;
      if (Boolean.TRUE.equals(settle.get("timedOut"))) {
        reason = "timeout";
        break;
      }
    }
    final var finalValue = current;
    final var finalSettle = settle;
    final var metFinal = met;
    final var ticksFinal = ticks;
    final var reasonFinal = reason;
    return ctx.onModelThread(
        () -> {
          final var out = new LinkedHashMap<String, Object>();
          out.put("met", metFinal);
          out.put("stopReason", reasonFinal);
          out.put("ticks", ticksFinal);
          out.put("unit", halfPerTick == 1 ? "half" : "full");
          out.put("halfTicks", SimulationSupport.rootState(proj).getPropagator().getTickCount());
          out.put("value", SimulationSupport.valueJson(finalValue));
          out.put("settle", finalSettle);
          return out;
        });
  }

  // ---------------------------------------------------------------------------------------------
  // Values
  // ---------------------------------------------------------------------------------------------

  private static int portIndex(Component comp, Object port) {
    final var n = comp.getEnds().size();
    if (n == 0) throw new McpToolException("Component has no ports.");
    if (port == null) return 0;
    if (port instanceof Number num) {
      final var i = num.intValue();
      if (i < 0 || i >= n) throw new McpToolException("Port index " + i + " out of range 0.." + (n - 1) + ".");
      return i;
    }
    final var s = port.toString().trim();
    if (s.matches("\\d+")) return portIndex(comp, Integer.parseInt(s));
    final var ports = ComponentRefs.ports(comp);
    for (var i = 0; i < ports.size(); i++) {
      final var name = ports.get(i).get("name");
      if (name != null && name.toString().equalsIgnoreCase(s)) return i;
    }
    throw new McpToolException("Unknown port '" + s + "'. Ports: " + ports);
  }

  /** Inputs and outputs (pins) of the state's circuit. */
  static Map<String, Object> pinValues(McpContext ctx, CircuitState state, boolean withPath) {
    final var out = new LinkedHashMap<String, Object>();
    if (withPath) {
      out.put("path", SimulationSupport.pathOf(state));
      out.put("circuit", state.getCircuit().getName());
    }
    final var inputs = new ArrayList<Object>();
    for (final var pin : SimulationSupport.pins(state.getCircuit(), true)) {
      inputs.add(SimulationSupport.pinJson(ctx, state, pin));
    }
    final var outputs = new ArrayList<Object>();
    for (final var pin : SimulationSupport.pins(state.getCircuit(), false)) {
      outputs.add(SimulationSupport.pinJson(ctx, state, pin));
    }
    out.put("inputs", inputs);
    out.put("outputs", outputs);
    return out;
  }

  private static boolean isInteresting(Component comp) {
    final var f = comp.getFactory();
    if (f instanceof Pin || f instanceof SubcircuitFactory || f instanceof Mem) return false;
    if (f instanceof Probe || f instanceof Tunnel || f instanceof Clock) return true;
    final var pkg = f.getClass().getPackageName();
    if (pkg.startsWith("com.cburch.logisim.std.io")) return true;
    return comp.getFeature(LoggableContract.class) != null;
  }

  private static Map<String, Object> componentState(McpContext ctx, CircuitState state, Component comp) {
    final var out = new LinkedHashMap<String, Object>();
    out.put("id", ctx.componentId(comp));
    out.put("type", comp.getFactory().getName());
    final var label = ComponentRefs.label(comp);
    if (label != null && !label.isEmpty()) out.put("label", label);
    out.put("location", ComponentRefs.loc(comp.getLocation()));
    final var f = comp.getFactory();
    if (f instanceof Tty tty) {
      out.put("text", tty.getDisplayText(state.getInstanceState(comp)));
    } else if (f instanceof Keyboard) {
      out.put("buffer", Keyboard.getBufferContents(state.getInstanceState(comp)));
    } else if (f instanceof Switch || f instanceof TwoWaySwitch) {
      final var data = state.getData(comp);
      out.put("on", data instanceof InstanceDataSingleton s && Boolean.TRUE.equals(s.getValue()));
    }
    final var logger = (LoggableContract) comp.getFeature(LoggableContract.class);
    if (logger != null && !(f instanceof Tunnel) && !(f instanceof Probe)) {
      try {
        final var opts = logger.getLogOptions();
        if (opts == null || opts.length == 0) {
          final var v = logger.getLogValue(state, null);
          if (v != null) out.put("state", SimulationSupport.valueJson(v));
        } else if (opts.length <= 16) {
          final var st = new LinkedHashMap<String, Object>();
          for (final var o : opts) {
            final var v = logger.getLogValue(state, o);
            if (v != null) st.put(String.valueOf(logger.getLogName(o)), SimulationSupport.binary(v));
          }
          out.put("state", st);
        }
      } catch (RuntimeException e) {
        // the component has no readable state yet
      }
    }
    out.put("ports", SimulationSupport.portValues(state, comp));
    return out;
  }

  private void registerValues(McpToolRegistry registry) {
    registry
        .tool("sim_get_values")
        .description(
            "Returns the current values of all input and output pins of the target circuit state"
                + " (id, label, width, binary/hex/decimal value; 'signed' is added when the top bit"
                + " is 1; undefined bits show as x, conflicts as E). With includeComponents=true also"
                + " returns probes, tunnels, clocks, I/O components (LEDs, displays, buttons, TTY"
                + " text, keyboard buffer, switches...) and components with internal state"
                + " (registers, counters, flip-flops) with their port values. Does not wait; call"
                + " sim_settle first if inputs changed by other means." + TARGET_DOC)
        .input(targetSchema().prop("includeComponents", Schema.bool("Also report non-pin components (default false).")))
        .readOnly()
        .offModelThread()
        .handler(
            SimulationSupport.settledFirst((ctx, args) -> {
              final var proj = ctx.project(args);
              final var t = SimulationSupport.target(ctx, proj, args);
              final var out = pinValues(ctx, t.state(), true);
              if (t.rootSwitched()) out.put("simulationRootChanged", true);
              if (args.bool("includeComponents", false)) {
                final var comps = new ArrayList<Object>();
                for (final var comp : t.state().getCircuit().getNonWires()) {
                  if (isInteresting(comp)) comps.add(componentState(ctx, t.state(), comp));
                }
                out.put("components", comps);
              }
              return out;
            }));

    registry
        .tool("sim_get_value")
        .description(
            "Reads simulation values at one place in the target state: either all ports (or one"
                + " 'port') of a 'component' (label, id 'c12' — wires included — or '@x,y'),"
                + " or the value of the wire/net at 'location' {x,y}. For non-pin components the"
                + " result also includes internal state when available (register/counter value,"
                + " TTY text, keyboard buffer...)." + TARGET_DOC)
        .input(
            targetSchema()
                .prop("component", Schema.string("Component reference."))
                .prop("port", Schema.any("Port index or name (default: all ports)."))
                .prop("location", Schema.any("Grid location {\"x\":..,\"y\":..} of a wire point or port.")))
        .readOnly()
        .offModelThread()
        .handler(
            SimulationSupport.settledFirst((ctx, args) -> {
              final var proj = ctx.project(args);
              final var t = SimulationSupport.target(ctx, proj, args);
              final var state = t.state();
              final var out = new LinkedHashMap<String, Object>();
              out.put("path", t.path());
              if (args.has("location")) {
                final var loc = ComponentRefs.parseLocation(args.get("location"), "location");
                out.put("location", ComponentRefs.loc(loc));
                out.put("value", SimulationSupport.valueJson(state.getValue(loc)));
                return out;
              }
              if (!args.has("component")) throw new McpToolException("Pass 'component' or 'location'.");
              final var comp = ComponentRefs.resolve(ctx, state.getCircuit(), args.str("component"));
              if (comp instanceof Wire w) {
                out.put("component", ComponentRefs.summary(ctx, w));
                out.put("value", SimulationSupport.valueJson(state.getValue(w.getEnd0())));
                return out;
              }
              if (args.has("port")) {
                final var i = portIndex(comp, args.get("port"));
                out.put("component", ComponentRefs.summary(ctx, comp));
                out.put("port", i);
                out.put("value", SimulationSupport.valueJson(SimulationSupport.portValue(state, comp, i)));
                return out;
              }
              out.put("component", componentState(ctx, state, comp));
              if (comp.getFactory() instanceof Pin) {
                out.put("pin", SimulationSupport.pinJson(ctx, state, comp));
              }
              return out;
            }));

    registry
        .tool("sim_set_input")
        .description(
            "Sets input Pins of the target circuit state (like poking them in the GUI) and, by"
                + " default, waits until the circuit settles and returns all pin values. Give one"
                + " pin with 'pin' + 'value', or several at once with 'values' = {\"pinLabelOrId\":"
                + " value, ...}. Pins are referenced by label, id ('c12') or '@x,y'. Note: input"
                + " pins of a subcircuit instance are driven by the parent circuit, so setting them"
                + " there has no lasting effect; set the root circuit's inputs instead. With"
                + " auto-propagation off, the change stays pending until sim_step."
                + TARGET_DOC)
        .input(
            targetSchema()
                .prop("pin", Schema.string("Input pin reference (label, id or '@x,y')."))
                .prop("value", VALUE_ARG)
                .prop(
                    "values",
                    Schema.object("Map from pin reference to value (same formats as 'value').")
                        .additionalProperties(true))
                .prop("settle", Schema.bool("Wait for the circuit to settle and return the pin values (default true)."))
                .prop("timeoutMs", TIMEOUT_ARG))
        .offModelThread()
        .handler(this::setInput);
  }

  private Object setInput(McpContext ctx, Args args) {
    record Prep(Project proj, CircuitState state, List<Object> applied, boolean switched) {}
    final var prep =
        ctx.onModelThread(
            () -> {
              final var proj = ctx.project(args);
              final var t = SimulationSupport.target(ctx, proj, args);
              final var state = t.state();
              final var assignments = new LinkedHashMap<String, Object>();
              if (args.has("pin")) {
                if (!args.has("value")) throw new McpToolException("Missing 'value' for 'pin'.");
                assignments.put(args.str("pin"), args.get("value"));
              }
              if (args.has("values")) assignments.putAll(args.map("values"));
              if (assignments.isEmpty()) throw new McpToolException("Pass 'pin' and 'value', or 'values'.");
              // Resolve and parse everything before changing anything.
              final var comps = new ArrayList<Component>();
              final var vals = new ArrayList<Value>();
              for (final var e : assignments.entrySet()) {
                final var comp = ComponentRefs.resolve(ctx, state.getCircuit(), e.getKey());
                if (!(comp.getFactory() instanceof Pin pin)
                    || !pin.isInputPin(((InstanceComponent) comp).getInstance())) {
                  throw new McpToolException(
                      "'" + e.getKey() + "' is not an input pin (it is a "
                          + comp.getFactory().getName() + "). Use sim_poke for buttons/switches.");
                }
                final var width = pin.getWidth(((InstanceComponent) comp).getInstance()).getWidth();
                comps.add(comp);
                vals.add(SimulationSupport.parseValue(e.getValue(), width, e.getKey()));
              }
              final var applied = new ArrayList<Object>();
              for (var i = 0; i < comps.size(); i++) {
                final var is = state.getInstanceState(comps.get(i));
                Pin.FACTORY.driveInputPin(is, vals.get(i));
                is.fireInvalidated();
                final var a = new LinkedHashMap<String, Object>();
                a.put("id", ctx.componentId(comps.get(i)));
                final var label = ComponentRefs.label(comps.get(i));
                if (label != null && !label.isEmpty()) a.put("label", label);
                a.put("value", SimulationSupport.binary(vals.get(i)));
                applied.add(a);
              }
              SimulationSupport.nudge(proj);
              return new Prep(proj, state, applied, t.rootSwitched());
            });
    Map<String, Object> settle = null;
    if (args.bool("settle", true)) {
      settle = SimulationSupport.settle(ctx, prep.proj(), args.lng("timeoutMs", DEFAULT_TIMEOUT_MS));
    }
    final var settleFinal = settle;
    return ctx.onModelThread(
        () -> {
          final var out = new LinkedHashMap<String, Object>();
          out.put("set", prep.applied());
          if (prep.switched()) out.put("simulationRootChanged", true);
          if (prep.state().isSubstate()) {
            out.put("warning", "Pins were set inside a subcircuit instance; the parent circuit drives them.");
          }
          if (settleFinal != null) {
            out.put("settle", settleFinal);
            out.putAll(pinValues(ctx, prep.state(), true));
          } else if (!prep.proj().getSimulator().isAutoPropagating()) {
            out.put("note", "Auto-propagation is off; use sim_step to propagate.");
          }
          return out;
        });
  }

  // ---------------------------------------------------------------------------------------------
  // Interactive components
  // ---------------------------------------------------------------------------------------------

  private static java.awt.Component eventSource() {
    if (eventSource == null) eventSource = new JPanel();
    return eventSource;
  }

  private static MouseEvent mouse(int id, int x, int y) {
    return new MouseEvent(eventSource(), id, System.currentTimeMillis(), 0, x, y, 1, false);
  }

  private InstancePoker poker(Component comp, boolean fresh) {
    if (!(comp instanceof InstanceComponent)
        || !(comp.getFactory() instanceof InstanceFactory factory)
        || factory.getInstancePokerClass() == null) {
      throw new McpToolException(
          "Component " + comp.getFactory().getName() + " cannot be poked (it has no Poke behaviour).");
    }
    if (!fresh) {
      final var cached = pokers.get(comp);
      if (cached != null) return cached;
    }
    try {
      final var p = factory.getInstancePokerClass().getDeclaredConstructor().newInstance();
      pokers.put(comp, p);
      return p;
    } catch (ReflectiveOperationException e) {
      throw new McpToolException("Cannot create the poker of " + comp.getFactory().getName(), e);
    }
  }

  private void press(InstancePoker p, InstanceState is, int x, int y) {
    final var e = mouse(MouseEvent.MOUSE_PRESSED, x, y);
    if (!p.init(is, e)) throw new McpToolException("The component does not react to a click at " + x + "," + y + ".");
    p.mousePressed(is, e);
  }

  private void release(InstancePoker p, InstanceState is, int x, int y) {
    p.mouseReleased(is, mouse(MouseEvent.MOUSE_RELEASED, x, y));
  }

  private static void ensureData(CircuitState state, Component comp) {
    // Several pokers expect their instance data to exist (it is created by the first propagation).
    if (state.getData(comp) == null) {
      throw new McpToolException(
          "The component has no simulation state yet; call sim_settle (or sim_reset) first.");
    }
  }

  private Object poke(McpContext ctx, Args args) {
    record Prep(Project proj, CircuitState state, Component comp, String done) {}
    final var prep =
        ctx.onModelThread(
            () -> {
              final var proj = ctx.project(args);
              final var t = SimulationSupport.target(ctx, proj, args);
              final var state = t.state();
              final var comp = ComponentRefs.resolve(ctx, state.getCircuit(), args.str("component"));
              final var f = comp.getFactory();
              if (f instanceof Pin) {
                throw new McpToolException("Use sim_set_input to change input pins.");
              }
              final var action = args.str("action", "click");
              final var is = state.getInstanceState(comp);
              final var b = comp.getBounds();
              final var x = args.integer("x", b.getX() + b.getWidth() / 2);
              final var y = args.integer("y", b.getY() + b.getHeight() / 2);
              String done;
              try {
                done = doPoke(state, comp, is, action, x, y, args);
              } catch (NullPointerException | ClassCastException e) {
                throw new McpToolException(
                    "Poking failed (" + e + "); the component may need a settled simulation first"
                        + " (sim_settle).", e);
              }
              SimulationSupport.nudge(proj);
              return new Prep(proj, state, comp, done);
            });
    final var settle =
        SimulationSupport.settle(ctx, prep.proj(), args.lng("timeoutMs", DEFAULT_TIMEOUT_MS));
    return ctx.onModelThread(
        () -> {
          final var out = new LinkedHashMap<String, Object>();
          out.put("done", prep.done());
          out.put("settle", settle);
          out.put("component", componentState(ctx, prep.state(), prep.comp()));
          out.putAll(pinValues(ctx, prep.state(), true));
          return out;
        });
  }

  private String doPoke(
      CircuitState state, Component comp, InstanceState is, String action, int x, int y, Args args) {
    final var f = comp.getFactory();
    final var loc = comp.getLocation();
    switch (action) {
      case "type" -> {
        final var text = args.str("text");
        if (f instanceof Keyboard) {
          Keyboard.addToBuffer(is, text.toCharArray());
          is.fireInvalidated();
          return "typed " + text.length() + " character(s) into the keyboard buffer";
        }
        final var p = poker(comp, true);
        press(p, is, x, y);
        release(p, is, x, y);
        for (final var ch : text.toCharArray()) {
          p.keyTyped(is, new KeyEvent(eventSource(), KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, ch));
        }
        p.stopEditing(is);
        return "typed '" + text + "'";
      }
      case "toggle" -> {
        if (f instanceof DipSwitch) {
          ensureData(state, comp);
          final var bit = args.integer("bit");
          final var n = comp.getAttributeSet().getValue(DipSwitch.ATTR_SIZE).getWidth();
          if (bit < 0 || bit >= n) throw new McpToolException("bit must be 0.." + (n - 1) + ".");
          final var pt = dipPoint(comp, bit, n);
          poker(comp, true).mousePressed(is, mouse(MouseEvent.MOUSE_PRESSED, pt[0], pt[1]));
          return "toggled switch " + bit + " (sw_" + (bit + 1) + ")";
        }
        final var p = poker(comp, true);
        press(p, is, x, y);
        release(p, is, x, y);
        return "toggled";
      }
      case "set" -> {
        if (f instanceof DipSwitch) {
          ensureData(state, comp);
          final var n = comp.getAttributeSet().getValue(DipSwitch.ATTR_SIZE).getWidth();
          final var target = SimulationSupport.parseValue(args.get("value"), n, "value");
          if (!target.isFullyDefined()) throw new McpToolException("DIP switch value must be fully defined.");
          final var want = target.toLongValue();
          var toggled = 0;
          for (var i = 0; i < n; i++) {
            final var cur = SimulationSupport.portValue(state, comp, i) == Value.TRUE;
            final var w = ((want >> i) & 1) != 0;
            if (cur != w) {
              final var pt = dipPoint(comp, i, n);
              poker(comp, true).mousePressed(is, mouse(MouseEvent.MOUSE_PRESSED, pt[0], pt[1]));
              toggled++;
            }
          }
          return "set DIP switch to " + SimulationSupport.binary(target) + " (" + toggled + " switch(es) toggled)";
        }
        if (f instanceof Switch || f instanceof TwoWaySwitch) {
          final var want = args.bool("value");
          final var data = state.getData(comp);
          final var cur = data instanceof InstanceDataSingleton s && Boolean.TRUE.equals(s.getValue());
          if (cur != want) {
            final var p = poker(comp, true);
            press(p, is, x, y);
            release(p, is, x, y);
          }
          return "switch is " + (want ? "on" : "off");
        }
        if (f instanceof Joystick) {
          final var dx = args.integer("dx", 0);
          final var dy = args.integer("dy", 0);
          if (Math.abs(dx) > 14 || Math.abs(dy) > 14) throw new McpToolException("dx/dy must be within -14..14.");
          press(poker(comp, false), is, loc.getX() - 15 + dx, loc.getY() + 5 + dy);
          return "joystick held at dx=" + dx + ", dy=" + dy + " (use action=release to center it)";
        }
        throw new McpToolException(
            "action=set is supported for DIP switches, switches and joysticks; use click/press/release/drag/type.");
      }
      case "press" -> {
        press(poker(comp, true), is, x, y);
        return "pressed at " + x + "," + y + " (call action=release to release)";
      }
      case "release" -> {
        release(poker(comp, false), is, x, y);
        return "released at " + x + "," + y;
      }
      case "drag" -> {
        final var x2 = args.integer("x2");
        final var y2 = args.integer("y2");
        final var p = poker(comp, true);
        press(p, is, x, y);
        p.mouseDragged(is, mouse(MouseEvent.MOUSE_DRAGGED, x2, y2));
        release(p, is, x2, y2);
        return "dragged from " + x + "," + y + " to " + x2 + "," + y2;
      }
      case "click" -> {
        if (f instanceof DipSwitch && args.has("bit")) return doPoke(state, comp, is, "toggle", x, y, args);
        final var p = poker(comp, true);
        press(p, is, x, y);
        release(p, is, x, y);
        return "clicked at " + x + "," + y;
      }
      default -> throw new McpToolException(
          "Unknown action '" + action + "'. Use click, press, release, toggle, set, type or drag.");
    }
  }

  /** A point inside DIP switch number {@code i} (inverse of DipSwitch.Poker's mapping). */
  private static int[] dipPoint(Component comp, int i, int n) {
    final var loc = comp.getLocation();
    final var facing = comp.getAttributeSet().getValue(StdAttr.FACING);
    return switch (facing.toString()) {
      case "south" -> new int[] {loc.getX() + 5 + 10 * (i - n), loc.getY()};
      case "east" -> new int[] {loc.getX(), loc.getY() + 5 + 10 * i};
      case "west" -> new int[] {loc.getX(), loc.getY() - 5 - 10 * i};
      default -> new int[] {loc.getX() + 5 + 10 * i, loc.getY()};
    };
  }

  private void registerInteraction(McpToolRegistry registry) {
    registry
        .tool("sim_poke")
        .description(
            "Operates an interactive component like the Poke tool does in the GUI, then waits for"
                + " the circuit to settle and returns the component's state and the pin values."
                + " Actions: 'click' (default; press+release — Button pulse, Switch toggle, keypad"
                + " key, etc.), 'press' / 'release' (hold a Button down / let go; also releases a"
                + " Joystick), 'toggle' (DIP switch: requires 'bit', 0-based, sw_1 is bit 0; other"
                + " components: click), 'set' (DIP switch: 'value' as an integer whose bit i is"
                + " switch i; Switch/Two-way switch: 'value' true/false; Joystick: 'dx','dy' in"
                + " -14..14 held until release), 'type' ('text' appended to a Keyboard buffer; for"
                + " other components with a keyboard poker, e.g. Register/Counter, the characters"
                + " are typed after clicking, like entering a hex value), 'drag' (press at x,y,"
                + " drag to x2,y2 and release, e.g. a Slider knob). 'x','y' are absolute circuit"
                + " coordinates (default: the component's center). Use sim_set_input for Pins."
                + TARGET_DOC)
        .input(
            targetSchema()
                .prop("component", Schema.string("Component reference (label, id or '@x,y')."), true)
                .prop("action", Schema.enumeration("What to do (default click).", "click", "press", "release", "toggle", "set", "type", "drag"))
                .prop("bit", Schema.integer("DIP switch number for toggle (0-based)."))
                .prop("value", Schema.any("Value for action=set."))
                .prop("text", Schema.string("Text for action=type."))
                .prop("dx", Schema.integer("Joystick x offset for action=set (-14..14)."))
                .prop("dy", Schema.integer("Joystick y offset for action=set (-14..14)."))
                .prop("x", Schema.integer("Absolute x of the click (default: component center)."))
                .prop("y", Schema.integer("Absolute y of the click (default: component center)."))
                .prop("x2", Schema.integer("Drag end x (action=drag)."))
                .prop("y2", Schema.integer("Drag end y (action=drag)."))
                .prop("timeoutMs", TIMEOUT_ARG))
        .offModelThread()
        .handler(this::poke);

    registry
        .tool("sim_tty_read")
        .description(
            "Returns the text currently displayed by TTY components (one line per row, older lines"
                + " that scrolled off are lost). Without 'component', all TTYs in the target state"
                + " and its subcircuits are reported with their paths." + TARGET_DOC)
        .input(targetSchema().prop("component", Schema.string("TTY reference (label, id or '@x,y').")))
        .readOnly()
        .offModelThread()
        .handler(
            SimulationSupport.settledFirst((ctx, args) -> {
              final var proj = ctx.project(args);
              final var t = SimulationSupport.target(ctx, proj, args);
              final var found = findComponents(ctx, t.state(), args, Tty.class, "TTY");
              final var list = new ArrayList<Object>();
              for (final var e : found) {
                final var m = new LinkedHashMap<String, Object>();
                m.put("path", SimulationSupport.pathOf(e.getKey()));
                m.put("id", ctx.componentId(e.getValue()));
                final var label = ComponentRefs.label(e.getValue());
                if (label != null && !label.isEmpty()) m.put("label", label);
                m.put("text", ((Tty) e.getValue().getFactory()).getDisplayText(e.getKey().getInstanceState(e.getValue())));
                list.add(m);
              }
              return Map.of("ttys", list);
            }));

    registry
        .tool("sim_keyboard_type")
        .description(
            "Appends characters to the buffer of a Keyboard component (as if typed while poking it),"
                + " then waits for the circuit to settle. '\\n' is Enter, '\\b' backspace. Without"
                + " 'component' the only Keyboard in the target state (or its subcircuits) is used."
                + " Returns the buffer contents. The circuit reads characters from the buffer on its"
                + " clock edges." + TARGET_DOC)
        .input(
            targetSchema()
                .prop("component", Schema.string("Keyboard reference (label, id or '@x,y')."))
                .prop("text", Schema.string("Characters to type."), true)
                .prop("timeoutMs", TIMEOUT_ARG))
        .offModelThread()
        .handler(
            (ctx, args) -> {
              record Prep(Project proj, CircuitState state, Component comp) {}
              final var prep =
                  ctx.onModelThread(
                      () -> {
                        final var proj = ctx.project(args);
                        final var t = SimulationSupport.target(ctx, proj, args);
                        final var found = findComponents(ctx, t.state(), args, Keyboard.class, "Keyboard");
                        if (found.size() != 1) {
                          throw new McpToolException(
                              found.size() + " keyboards found; pass 'component' (and 'path').");
                        }
                        final var e = found.get(0);
                        final var is = e.getKey().getInstanceState(e.getValue());
                        Keyboard.addToBuffer(is, args.str("text").toCharArray());
                        is.fireInvalidated();
                        SimulationSupport.nudge(proj);
                        return new Prep(proj, e.getKey(), e.getValue());
                      });
              final var settle =
                  SimulationSupport.settle(ctx, prep.proj(), args.lng("timeoutMs", DEFAULT_TIMEOUT_MS));
              return ctx.onModelThread(
                  () -> {
                    final var out = new LinkedHashMap<String, Object>();
                    out.put("path", SimulationSupport.pathOf(prep.state()));
                    out.put("id", ctx.componentId(prep.comp()));
                    out.put("buffer", Keyboard.getBufferContents(prep.state().getInstanceState(prep.comp())));
                    out.put("settle", settle);
                    return out;
                  });
            });
  }

  private static List<Map.Entry<CircuitState, Component>> findComponents(
      McpContext ctx, CircuitState state, Args args, Class<?> factoryClass, String what) {
    final var out = new ArrayList<Map.Entry<CircuitState, Component>>();
    if (args.has("component")) {
      final var comp = ComponentRefs.resolve(ctx, state.getCircuit(), args.str("component"));
      if (!factoryClass.isInstance(comp.getFactory())) {
        throw new McpToolException("'" + args.str("component") + "' is not a " + what + ".");
      }
      out.add(Map.entry(state, comp));
      return out;
    }
    SimulationSupport.collect(state, c -> factoryClass.isInstance(c.getFactory()), true, 0, out);
    return out;
  }

  // ---------------------------------------------------------------------------------------------
  // Hierarchy
  // ---------------------------------------------------------------------------------------------

  private static Map<String, Object> substateTree(
      McpContext ctx, CircuitState state, CircuitState viewed, int depth, int maxDepth) {
    final var out = new LinkedHashMap<String, Object>();
    out.put("path", SimulationSupport.pathOf(state));
    out.put("circuit", state.getCircuit().getName());
    if (state == viewed) out.put("viewed", true);
    final var children = new ArrayList<Object>();
    for (final var comp : state.getCircuit().getNonWires()) {
      if (!(comp.getFactory() instanceof SubcircuitFactory sf)) continue;
      if (depth >= maxDepth) {
        out.put("truncated", true);
        break;
      }
      final var sub = sf.getSubstate(state, comp);
      final var child = new LinkedHashMap<String, Object>();
      child.put("component", ctx.componentId(comp));
      final var label = ComponentRefs.label(comp);
      if (label != null && !label.isEmpty()) child.put("label", label);
      child.put("location", ComponentRefs.loc(comp.getLocation()));
      child.putAll(substateTree(ctx, sub, viewed, depth + 1, maxDepth));
      children.add(child);
    }
    if (!children.isEmpty()) out.put("children", children);
    return out;
  }

  private void registerHierarchy(McpToolRegistry registry) {
    registry
        .tool("sim_list_substates")
        .description(
            "Returns the tree of subcircuit instances of the running simulation, starting at the"
                + " root circuit: for each instance its state 'path' (usable as 'path' argument of"
                + " other sim_/memory_ tools, e.g. 'main/adder1'), circuit name, component id,"
                + " label and location. The state currently viewed in the project is marked"
                + " viewed=true.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("maxDepth", Schema.integer("Maximum nesting depth (default 8).")))
        .readOnly()
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var root = SimulationSupport.rootState(proj);
              return substateTree(ctx, root, proj.getCircuitState(), 0, args.integer("maxDepth", 8));
            });

    registry
        .tool("sim_select_state")
        .description(
            "Views the simulation state of a subcircuit instance, like double-clicking it (or"
                + " 'View <circuit>') in the GUI: the project's canvas then shows that instance"
                + " with its live values, and it becomes the default target of sim_ tools."
                + " 'path' selects the instance (e.g. 'main/adder1'; just the root name returns to"
                + " the top level). 'circuit' alone makes that circuit the simulation root."
                + " Returns the viewed path.")
        .input(targetSchema())
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              if (!args.has("path") && !args.has("circuit")) {
                throw new McpToolException("Pass 'path' and/or 'circuit'.");
              }
              final var t = SimulationSupport.target(ctx, proj, args);
              proj.setCircuitState(t.state());
              final var out = new LinkedHashMap<String, Object>();
              out.put("viewedPath", SimulationSupport.pathOf(proj.getCircuitState()));
              out.put("circuit", proj.getCircuitState().getCircuit().getName());
              out.put("rootCircuit", SimulationSupport.rootState(proj).getCircuit().getName());
              return out;
            });
  }

  // ---------------------------------------------------------------------------------------------
  // Truth table
  // ---------------------------------------------------------------------------------------------

  private void registerTruthTable(McpToolRegistry registry) {
    registry
        .tool("sim_truth_table_quick")
        .description(
            "Enumerates all combinations of the input pins of a (small, combinational) circuit and"
                + " returns the resulting output pin values, like Logisim's command-line table mode."
                + " It runs on a separate, private simulation state, so the project's simulation is"
                + " not disturbed. Columns are named after pin labels (unlabeled pins get generated"
                + " names); rows are in counting order with the first input column as most"
                + " significant. Output values that oscillate are reported as 'E'.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("circuit", CIRCUIT_ARG)
                .prop("format", Schema.enumeration("Value format (default binary).", "binary", "hex", "decimal"))
                .prop("maxInputBits", Schema.integer("Refuse circuits with more input bits (default 12, max 16).")))
        .readOnly()
        .offModelThread()
        .handler(this::truthTable);
  }

  private Object truthTable(McpContext ctx, Args args) {
    record Prep(Project proj, com.cburch.logisim.circuit.Circuit circ, List<com.cburch.logisim.instance.Instance> ins,
        List<com.cburch.logisim.instance.Instance> outs, List<String> columns, int inputBits) {}
    final var maxBits = Math.min(16, args.integer("maxInputBits", 12));
    final var fmt = args.str("format", "binary");
    final var prep =
        ctx.onModelThread(
            () -> {
              final var proj = ctx.project(args);
              final var circ = ctx.circuit(proj, args);
              final var labels = Analyze.getPinLabels(circ);
              final var ins = new ArrayList<com.cburch.logisim.instance.Instance>();
              final var outs = new ArrayList<com.cburch.logisim.instance.Instance>();
              final var cols = new ArrayList<String>();
              var bits = 0;
              for (final var e : labels.entrySet()) {
                if (Pin.FACTORY.isInputPin(e.getKey())) {
                  ins.add(e.getKey());
                  cols.add(e.getValue());
                  bits += Pin.FACTORY.getWidth(e.getKey()).getWidth();
                }
              }
              for (final var e : labels.entrySet()) {
                if (!Pin.FACTORY.isInputPin(e.getKey())) {
                  outs.add(e.getKey());
                  cols.add(e.getValue());
                }
              }
              if (bits > maxBits) {
                throw new McpToolException(
                    "Circuit has " + bits + " input bits (> maxInputBits=" + maxBits + ").");
              }
              if (outs.isEmpty()) throw new McpToolException("Circuit has no output pins.");
              return new Prep(proj, circ, ins, outs, cols, bits);
            });
    final var rows = new ArrayList<Object>();
    var oscillations = 0;
    final var rowCount = 1 << prep.inputBits();
    for (var r = 0; r < rowCount; r++) {
      // A fresh private state per row (as the command-line table mode does).
      final var state = CircuitState.createRootState(prep.proj(), prep.circ(), Thread.currentThread());
      final var prop = state.getPropagator();
      final var row = new ArrayList<String>();
      var col = 0;
      for (final var pin : prep.ins()) {
        final var w = Pin.FACTORY.getWidth(pin).getWidth();
        final var v = new Value[w];
        for (var b = w - 1; b >= 0; b--) {
          final var set = (r & (1 << (prep.inputBits() - col - 1))) != 0;
          v[b] = set ? Value.TRUE : Value.FALSE;
          col++;
        }
        final var val = w == 1 ? v[0] : Value.create(v);
        final var ps = state.getInstanceState(pin);
        Pin.FACTORY.driveInputPin(ps, val);
        ps.fireInvalidated();
        row.add(SimulationSupport.format(val, fmt));
      }
      prop.propagate();
      final var osc = prop.isOscillating();
      if (osc) oscillations++;
      for (final var pin : prep.outs()) {
        final var val = osc
            ? Value.createError(Pin.FACTORY.getWidth(pin))
            : Pin.FACTORY.getValue(state.getInstanceState(pin));
        row.add(SimulationSupport.format(val, fmt));
      }
      rows.add(row);
    }
    final var out = new LinkedHashMap<String, Object>();
    out.put("circuit", prep.circ().getName());
    out.put("inputColumns", prep.columns().subList(0, prep.ins().size()));
    out.put("outputColumns", prep.columns().subList(prep.ins().size(), prep.columns().size()));
    out.put("columns", prep.columns());
    out.put("rows", rows);
    if (oscillations > 0) out.put("oscillatingRows", oscillations);
    return out;
  }
}
