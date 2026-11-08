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

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitAttributes;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.file.LogisimFileActions;
import com.cburch.logisim.fpga.designrulecheck.CorrectLabel;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.mcp.ComponentRefs;
import com.cburch.logisim.mcp.McpContext;
import com.cburch.logisim.mcp.McpToolException;
import com.cburch.logisim.mcp.McpToolGroup;
import com.cburch.logisim.mcp.McpToolRegistry;
import com.cburch.logisim.mcp.Schema;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Pin;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.util.SyntaxChecker;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Circuit editing: circuit management, inspection, components, wires, attributes, selection,
 * clipboard and undo/redo. Component, wire and selection tools live in {@link
 * EditComponentTools} and {@link EditSelectionTools}.
 */
public final class EditTools implements McpToolGroup {

  @Override
  public void register(McpToolRegistry registry) {
    registerCircuitTools(registry);
    registerInspectTools(registry);
    EditComponentTools.register(registry);
    EditSelectionTools.register(registry);
    registerUndoTools(registry);
  }

  // ---------------------------------------------------------------------------------------------
  // Circuit management
  // ---------------------------------------------------------------------------------------------

  static Map<String, Object> circuitInfo(Project proj, Circuit circ) {
    final var file = proj.getLogisimFile();
    final var out = new LinkedHashMap<String, Object>();
    out.put("name", circ.getName());
    out.put("index", file.getCircuits().indexOf(circ));
    out.put("main", file.getMainCircuit() == circ);
    out.put("current", proj.getCurrentCircuit() == circ);
    out.put("components", circ.getNonWires().size());
    out.put("wires", circ.getWires().size());
    final var users = new ArrayList<String>();
    for (final var c : circ.getCircuitsUsingThis()) {
      if (!users.contains(c.getName())) users.add(c.getName());
    }
    out.put("usedBy", users);
    out.put("appearance",
        ComponentRefs.standardString(circ.getStaticAttributes(), CircuitAttributes.APPEARANCE_ATTR));
    return out;
  }

  /** Error message if {@code name} cannot be used for a (new or renamed) circuit, else null. */
  static String circuitNameProblem(Project proj, String name, Circuit self) {
    final var hdl = AppPreferences.HdlType.get();
    if (name == null || name.isEmpty()) return "Circuit name must not be empty.";
    if (CorrectLabel.isKeyword(name, hdl, false)) return "'" + name + "' is a reserved " + hdl + " keyword.";
    final var msg = SyntaxChecker.getErrorMessage(name, hdl);
    if (msg != null) return "'" + name + "' is not a valid circuit name: " + msg.replace('\n', ' ');
    final var file = proj.getLogisimFile();
    for (final var tool : file.getTools()) {
      final var isSelf = self != null && file.getAddTool(self) == tool;
      if (!isSelf && SyntaxChecker.namesEqualForCurrentHdl(name, tool.getName())) {
        return "A circuit or VHDL entity named '" + tool.getName() + "' already exists.";
      }
    }
    for (final var lib : file.getLibraries()) {
      final var clash = nameInLibrary(lib, name);
      if (clash != null) return "'" + name + "' clashes with component '" + clash + "' of library " + lib.getName() + ".";
    }
    if (self != null) {
      for (final var comp : self.getNonWires()) {
        if (comp.getFactory() instanceof Pin) {
          final var label = comp.getAttributeSet().getValue(StdAttr.LABEL);
          if (!label.isEmpty() && SyntaxChecker.namesEqualForCurrentHdl(label, name)) {
            return "'" + name + "' equals the label of a pin of this circuit.";
          }
        }
      }
    }
    return null;
  }

  private static String nameInLibrary(Library lib, String name) {
    for (final var sub : lib.getLibraries()) {
      final var r = nameInLibrary(sub, name);
      if (r != null) return r;
    }
    for (final var tool : lib.getTools()) {
      if (SyntaxChecker.namesEqualForCurrentHdl(name, tool.getName())) return tool.getName();
    }
    return null;
  }

  private static void rename(Project proj, Circuit circ, String name) {
    if (name.equals(circ.getName())) return;
    final var problem = circuitNameProblem(proj, name, circ);
    if (problem != null) throw new McpToolException(problem);
    final var xn = new CircuitMutation(circ);
    xn.setForCircuit(CircuitAttributes.NAME_ATTR, name);
    EditSupport.run(proj, xn, "Rename Circuit");
    if (!name.equals(circ.getName())) {
      throw new McpToolException("Logisim rejected the circuit name '" + name + "'.");
    }
  }

  private void registerCircuitTools(McpToolRegistry registry) {
    registry
        .tool("circuit_list")
        .description(
            "Lists the circuits of a project in their order in the explorer, with index, whether"
                + " each is the main / currently shown circuit, number of components (excluding"
                + " wires) and wires, the circuits using it as a subcircuit and its appearance.")
        .input(Schema.object().prop("project", PROJECT_ARG))
        .readOnly()
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var list = new ArrayList<Object>();
              for (final var c : proj.getLogisimFile().getCircuits()) list.add(circuitInfo(proj, c));
              return Map.of("project", ctx.projectId(proj), "circuits", list);
            });

    registry
        .tool("circuit_add")
        .description(
            "Adds a new empty circuit to the project (undoable, like Project > Add Circuit). The"
                + " name must be a valid HDL identifier and unique among circuits and library"
                + " components. By default the new circuit becomes the current (shown) circuit.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("name", Schema.string("Name of the new circuit."), true)
                .prop("select", Schema.bool("Make it the current circuit (default true).")))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var name = args.str("name").trim();
              final var problem = circuitNameProblem(proj, name, null);
              if (problem != null) throw new McpToolException(problem);
              final var circ = new Circuit(name, proj.getLogisimFile(), proj);
              EditSupport.doAction(proj, LogisimFileActions.addCircuit(circ));
              if (args.bool("select", true)) proj.setCurrentCircuit(circ);
              return circuitInfo(proj, circ);
            });

    registry
        .tool("circuit_rename")
        .description("Renames a circuit (undoable). Instances of it in other circuits follow.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("circuit", Schema.string("Current circuit name. Defaults to the current circuit."))
                .prop("newName", Schema.string("New name (valid, unique HDL identifier)."), true))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var circ = ctx.circuit(proj, args);
              EditSupport.requireEditable(proj, circ);
              rename(proj, circ, args.str("newName").trim());
              return circuitInfo(proj, circ);
            });

    registry
        .tool("circuit_delete")
        .description(
            "Removes a circuit from the project (undoable). Refused for the last circuit and for"
                + " circuits used as subcircuits elsewhere (delete those instances first).")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("circuit", Schema.string("Name of the circuit to delete."), true))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var circ = ctx.circuit(proj, args);
              final var file = proj.getLogisimFile();
              if (file.getCircuits().size() <= 1) {
                throw new McpToolException("Cannot delete the only circuit of the project.");
              }
              if (!proj.getDependencies().canRemove(circ)) {
                throw new McpToolException(
                    "Circuit '" + circ.getName() + "' is used as a subcircuit (in "
                        + circuitInfo(proj, circ).get("usedBy") + "); remove those instances first.");
              }
              EditSupport.doAction(proj, LogisimFileActions.removeCircuit(circ));
              return Map.of(
                  "deleted", circ.getName(),
                  "circuits", McpContext.circuitNames(proj),
                  "mainCircuit", file.getMainCircuit().getName(),
                  "currentCircuit", proj.getCurrentCircuit() == null ? "" : proj.getCurrentCircuit().getName());
            });

    registry
        .tool("circuit_set_main")
        .description("Makes a circuit the project's main (top-level) circuit (undoable).")
        .input(Schema.object().prop("project", PROJECT_ARG).prop("circuit", Schema.string("Circuit name."), true))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var circ = ctx.circuit(proj, args);
              EditSupport.doAction(proj, LogisimFileActions.setMainCircuit(circ));
              return circuitInfo(proj, circ);
            });

    registry
        .tool("circuit_move")
        .description(
            "Reorders a circuit in the project's circuit list (undoable). Give either 'delta'"
                + " (-1 = up one place, +1 = down one place) or 'index' (0-based target position"
                + " among the project's circuits and VHDL entities).")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("circuit", Schema.string("Circuit name."), true)
                .prop("delta", Schema.integer("Relative move: negative = up, positive = down."))
                .prop("index", Schema.integer("Absolute 0-based target position.")))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var circ = ctx.circuit(proj, args);
              final var file = proj.getLogisimFile();
              final var tool = file.getAddTool(circ);
              final var from = file.getTools().indexOf(tool);
              final int to;
              if (args.has("index")) to = args.integer("index");
              else if (args.has("delta")) to = from + args.integer("delta");
              else throw new McpToolException("Give 'delta' or 'index'.");
              final var count = file.getTools().size();
              if (to < 0 || to >= count) {
                throw new McpToolException("Target position " + to + " is out of range 0.." + (count - 1) + ".");
              }
              if (to != from) EditSupport.doAction(proj, LogisimFileActions.moveCircuit(tool, to));
              final var list = new ArrayList<Object>();
              for (final var c : file.getCircuits()) list.add(c.getName());
              return Map.of("circuits", list);
            });

    registry
        .tool("circuit_set_current")
        .description(
            "Switches the circuit shown/edited in the project (like double-clicking it in the"
                + " explorer); it becomes the default 'circuit' for other tools and the root of"
                + " the simulation. With a GUI the canvas switches to it.")
        .input(Schema.object().prop("project", PROJECT_ARG).prop("circuit", Schema.string("Circuit name."), true))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var circ = ctx.circuit(proj, args);
              proj.setCurrentCircuit(circ);
              if (proj.getFrame() != null) proj.getFrame().getCanvas().repaint();
              return circuitInfo(proj, circ);
            });

    registry
        .tool("circuit_get_attributes")
        .description(
            "Returns the circuit-level attributes (name, shared label, label facing/font,"
                + " appearance, simulation frequency, ...) with their current values in the"
                + " standard string format accepted by circuit_set_attributes, plus allowed"
                + " options where they are a fixed choice.")
        .input(Schema.object().prop("project", PROJECT_ARG).prop("circuit", CIRCUIT_ARG))
        .readOnly()
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var circ = ctx.circuit(proj, args);
              final var out = new LinkedHashMap<String, Object>();
              out.put("circuit", circ.getName());
              out.put("attributes", EditSupport.attributeInfo(circ.getStaticAttributes()));
              return out;
            });

    registry
        .tool("circuit_set_attributes")
        .description(
            "Sets circuit-level attributes in one undoable step. 'attributes' maps attribute"
                + " names (see circuit_get_attributes, e.g. 'circuit' = name, 'clabel' = shared"
                + " label, 'clabelup', 'clabelfont', 'appearance') to values in the standard"
                + " string format (e.g. appearance 'classic'|'evolution'|'logisim_evolution'|...)."
                + " Setting 'circuit' renames the circuit.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("circuit", CIRCUIT_ARG)
                .prop("attributes", Schema.object("Attribute name to value."), true))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var circ = ctx.circuit(proj, args);
              EditSupport.requireEditable(proj, circ);
              final var attrs = circ.getStaticAttributes();
              final var xn = new CircuitMutation(circ);
              String newName = null;
              for (final var e : args.map("attributes").entrySet()) {
                final var attr = EditSupport.requireAttribute(attrs, e.getKey(), "circuits");
                if (attr == CircuitAttributes.NAME_ATTR) {
                  newName = String.valueOf(e.getValue()).trim();
                  continue;
                }
                if (attrs.isReadOnly(attr)) {
                  throw new McpToolException("Attribute '" + attr.getName() + "' is read-only.");
                }
                xn.setForCircuit(attr, EditSupport.parseValue(attr, e.getValue()));
              }
              EditSupport.run(proj, xn, "Change Circuit Attributes");
              if (newName != null) rename(proj, circ, newName);
              final var out = new LinkedHashMap<String, Object>();
              out.put("circuit", circ.getName());
              out.put("attributes", ComponentRefs.attributes(attrs));
              return out;
            });
  }

  // ---------------------------------------------------------------------------------------------
  // Inspection
  // ---------------------------------------------------------------------------------------------

  private void registerInspectTools(McpToolRegistry registry) {
    registry
        .tool("circuit_describe")
        .description(
            "Describes the contents of a circuit: every component (id, type, location, label,"
                + " facing, bounds; with details=true also all attributes and ports with their"
                + " locations/widths/directions), every wire (id, from, to), the overall bounds"
                + " and bit-width conflicts ('widthErrors'). Ids can be passed to the other"
                + " component_/wire_ tools.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("circuit", CIRCUIT_ARG)
                .prop("details", Schema.bool("Include attributes and ports of components (default false)."))
                .prop("wires", Schema.bool("Include the wire list (default true).")))
        .readOnly()
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var circ = ctx.circuit(proj, args);
              final var details = args.bool("details", false);
              final var out = new LinkedHashMap<String, Object>(circuitInfo(proj, circ));
              final var comps = new ArrayList<Object>();
              for (final var c : sorted(circ.getNonWires())) {
                comps.add(details ? ComponentRefs.details(ctx, c) : ComponentRefs.summary(ctx, c));
              }
              out.put("componentList", comps);
              if (args.bool("wires", true)) {
                final var wires = new ArrayList<Object>();
                for (final var w : sorted(circ.getWires())) wires.add(ComponentRefs.summary(ctx, w));
                out.put("wireList", wires);
              }
              out.put("bounds", ComponentRefs.bounds(circ.getBounds()));
              out.put("widthErrors", EditSupport.widthErrors(circ));
              return out;
            });

    registry
        .tool("component_get")
        .description(
            "Full description of one component: id, type, location, label, facing, bounds, all"
                + " attributes (standard string values) and ports (index, location, width,"
                + " direction, name). With attributeInfo=true also each attribute's display name"
                + " and allowed options/range. Reference: id ('c12'), label, or '@x,y'.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("circuit", CIRCUIT_ARG)
                .prop("component", Schema.string("Component reference."), true)
                .prop("attributeInfo", Schema.bool("Include attribute metadata (default false).")))
        .readOnly()
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var circ = ctx.circuit(proj, args);
              final var comp = ComponentRefs.resolve(ctx, circ, args.str("component"));
              final var out = ComponentRefs.details(ctx, comp);
              if (args.bool("attributeInfo", false) && !(comp instanceof Wire)) {
                out.put("attributeInfo", EditSupport.attributeInfo(comp.getAttributeSet()));
              }
              return out;
            });

    registry
        .tool("component_at")
        .description(
            "Lists what is at a canvas location: components whose body/wire contains the point"
                + " ('containing') and components having a port or wire end exactly there"
                + " ('connectedHere'), plus the bit width of the net at that point if known.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("circuit", CIRCUIT_ARG)
                .prop("location", Schema.any("Point as {\"x\":..,\"y\":..}, [x,y] or \"x,y\"."), true))
        .readOnly()
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var circ = ctx.circuit(proj, args);
              final var loc = ComponentRefs.parseLocation(args.get("location"), "location");
              final var containing = new ArrayList<Object>();
              for (final var c : circ.getAllContaining(loc)) containing.add(ComponentRefs.summary(ctx, c));
              final var here = new ArrayList<Object>();
              for (final var c : circ.getComponents(loc)) here.add(ComponentRefs.summary(ctx, c));
              final var out = new LinkedHashMap<String, Object>();
              out.put("location", ComponentRefs.loc(loc));
              out.put("containing", containing);
              out.put("connectedHere", here);
              final var w = circ.getWidth(loc);
              if (w != null && w.getWidth() > 0) out.put("netWidth", w.getWidth());
              return out;
            });

    registry
        .tool("component_types")
        .description(
            "Lists the component types that can be placed with component_add: the project's own"
                + " circuits (as subcircuits), VHDL entities and every tool of the loaded libraries"
                + " (Wiring, Gates, Plexers, Arithmetic, Memory, I/O, Base, ...). Without 'query' or"
                + " 'library' a compact map library -> type names is returned; with a filter each"
                + " match is listed with library, name and display name. A type is referenced in"
                + " other tools by its name (case-insensitive), optionally qualified as"
                + " 'Library/Name' (e.g. 'Gates/AND Gate', 'Wiring/Pin').")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("library", Schema.string("Only this library (name, case-insensitive)."))
                .prop("query", Schema.string("Case-insensitive substring of name or display name.")))
        .readOnly()
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var lib = args.str("library", null);
              final var query = args.str("query", null);
              final var all = EditSupport.allTypes(proj);
              if (lib == null && query == null) {
                final var grouped = new LinkedHashMap<String, List<String>>();
                for (final var m : all) {
                  grouped.computeIfAbsent(m.library(), k -> new ArrayList<>()).add(m.tool().getName());
                }
                return Map.of("projectLibrary", proj.getLogisimFile().getName(), "types", grouped);
              }
              final var q = query == null ? null : query.toLowerCase();
              final var out = new ArrayList<Object>();
              for (final var m : all) {
                if (lib != null && !m.library().equalsIgnoreCase(lib)) continue;
                final var disp = EditSupport.safeDisplayName(m.tool());
                if (q != null
                    && !m.tool().getName().toLowerCase().contains(q)
                    && (disp == null || !disp.toLowerCase().contains(q))) {
                  continue;
                }
                final var e = new LinkedHashMap<String, Object>();
                e.put("library", m.library());
                e.put("name", m.tool().getName());
                if (disp != null) e.put("displayName", disp);
                out.add(e);
              }
              return Map.of("types", out);
            });

    registry
        .tool("component_type_info")
        .description(
            "Describes a component type before placing it: library, name, display name, the"
                + " default attributes (as component_add would use them, with options/ranges), and"
                + " the bounds and ports of an instance anchored at (0,0) with the given optional"
                + " attributes/facing, so port offsets relative to the anchor location are known.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("type", Schema.string("Component type, e.g. 'AND Gate' or 'Wiring/Pin'."), true)
                .prop("attributes", Schema.object("Optional attribute overrides (standard strings)."))
                .prop("facing", Schema.string("Optional facing: east, west, north, south.")))
        .readOnly()
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var match = EditSupport.resolveType(proj, args.str("type"));
              final var factory = match.factory();
              final var attrs = EditComponentTools.prepareAttributes(match, args);
              final var comp = factory.createComponent(
                  com.cburch.logisim.data.Location.create(0, 0, false), attrs);
              final var out = new LinkedHashMap<String, Object>();
              out.put("library", match.library());
              out.put("name", match.tool().getName());
              out.put("displayName", factory.getDisplayName());
              out.put("attributes", EditSupport.attributeInfo(attrs));
              out.put("bounds", ComponentRefs.bounds(comp.getBounds()));
              out.put("ports", ComponentRefs.ports(comp));
              return out;
            });
  }

  static <T extends Component> List<T> sorted(java.util.Collection<T> comps) {
    final var list = new ArrayList<>(comps);
    list.sort(
        (a, b) -> {
          final var c = a.getLocation().compareTo(b.getLocation());
          return c != 0 ? c : Integer.compare(System.identityHashCode(a), System.identityHashCode(b));
        });
    return list;
  }

  // ---------------------------------------------------------------------------------------------
  // Undo / redo
  // ---------------------------------------------------------------------------------------------

  private static Map<String, Object> history(Project proj) {
    final var undo = new ArrayList<String>();
    for (final var a : proj.getUndoActions()) undo.add(a.getName());
    final var redo = new ArrayList<String>();
    for (final var a : proj.getRedoActions()) redo.add(a.getName());
    final var out = new LinkedHashMap<String, Object>();
    out.put("undo", undo);
    out.put("redo", redo);
    out.put("dirty", proj.isFileDirty());
    out.put("currentCircuit", proj.getCurrentCircuit() == null ? null : proj.getCurrentCircuit().getName());
    return out;
  }

  private void registerUndoTools(McpToolRegistry registry) {
    final Schema stepsSchema =
        Schema.object()
            .prop("project", PROJECT_ARG)
            .prop("steps", Schema.integer("Number of actions (default 1)."));
    registry
        .tool("edit_undo")
        .description(
            "Undoes the last edit action(s) of the project, exactly like Edit > Undo (the"
                + " current circuit switches to where the action happened). Returns the undone"
                + " action names and the remaining history. Component ids of re-created"
                + " components may differ afterwards; list them again.")
        .input(stepsSchema)
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var done = new ArrayList<String>();
              for (var i = 0; i < args.integer("steps", 1); i++) {
                final var list = proj.getUndoActions();
                if (list.isEmpty()) break;
                done.add(list.get(0).getName());
                proj.undoAction();
              }
              if (done.isEmpty()) throw new McpToolException("Nothing to undo.");
              final var out = new LinkedHashMap<String, Object>();
              out.put("undone", done);
              out.putAll(history(proj));
              return out;
            });

    registry
        .tool("edit_redo")
        .description("Redoes previously undone action(s), like Edit > Redo.")
        .input(stepsSchema)
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var done = new ArrayList<String>();
              for (var i = 0; i < args.integer("steps", 1); i++) {
                final var list = proj.getRedoActions();
                if (list.isEmpty()) break;
                done.add(list.get(0).getName());
                proj.redoAction();
              }
              if (done.isEmpty()) throw new McpToolException("Nothing to redo.");
              final var out = new LinkedHashMap<String, Object>();
              out.put("redone", done);
              out.putAll(history(proj));
              return out;
            });

    registry
        .tool("edit_history")
        .description(
            "Lists the names of the undoable actions ('undo', most recent first) and redoable"
                + " actions ('redo', next first) of a project, and whether it has unsaved changes.")
        .input(Schema.object().prop("project", PROJECT_ARG))
        .readOnly()
        .handler((ctx, args) -> history(ctx.project(args)));
  }
}
