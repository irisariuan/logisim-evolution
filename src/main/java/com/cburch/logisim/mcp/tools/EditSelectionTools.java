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
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.gui.main.Clipboard;
import com.cburch.logisim.mcp.Args;
import com.cburch.logisim.mcp.ComponentRefs;
import com.cburch.logisim.mcp.McpContext;
import com.cburch.logisim.mcp.McpToolException;
import com.cburch.logisim.mcp.McpToolRegistry;
import com.cburch.logisim.mcp.Schema;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.base.Text;
import com.cburch.logisim.tools.AddTool;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.vhdl.base.VhdlEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * selection_* and edit_* clipboard tools. With a GUI the selection is the canvas selection of the
 * project window (and the circuit shown switches when selecting in another circuit); headless a
 * per-project selection is kept. The clipboard is Logisim's own, so it is shared with the GUI.
 */
final class EditSelectionTools {
  private EditSelectionTools() {}

  private static Map<String, Object> describe(McpContext ctx, EditSupport.Sel sel) {
    final var out = new LinkedHashMap<String, Object>();
    out.put("circuit", sel.circuit() == null ? null : sel.circuit().getName());
    out.put("count", sel.components().size());
    final var list = new ArrayList<Object>();
    for (final var c : EditTools.sorted(sel.components())) list.add(ComponentRefs.summary(ctx, c));
    out.put("components", list);
    if (!sel.components().isEmpty()) out.put("bounds", ComponentRefs.bounds(EditSupport.boundsOf(sel.components())));
    return out;
  }

  /**
   * The components an edit_* tool acts on: explicit 'components' (in the given or current
   * circuit) or else the current selection.
   */
  private static EditSupport.Sel targets(McpContext ctx, Project proj, Args args) {
    if (args.has("components") || args.has("component")) {
      final var circ = ctx.circuit(proj, args);
      return new EditSupport.Sel(circ, EditComponentTools.resolveRefs(ctx, circ, args));
    }
    final var sel = EditSupport.getSelection(ctx, proj);
    if (args.has("circuit") && sel.circuit() != ctx.circuit(proj, args)) {
      throw new McpToolException("The selection is in circuit '" + sel.circuit().getName() + "'; pass 'components' explicitly.");
    }
    if (sel.components().isEmpty()) {
      throw new McpToolException("Nothing is selected. Use selection_set or pass 'components'.");
    }
    return sel;
  }

  private static Schema targetSchema() {
    return Schema.object()
        .prop("project", PROJECT_ARG)
        .prop("circuit", CIRCUIT_ARG)
        .prop("components", Schema.array(
            "Components to act on instead of the current selection (ids, labels or '@x,y').",
            Schema.string("Component reference.")));
  }

  static void register(McpToolRegistry registry) {
    registry
        .tool("selection_get")
        .description(
            "Returns the current selection of a project: its circuit and the selected components"
                + " (summaries) and their bounds. With a GUI this is what is highlighted on the"
                + " canvas, including selections made by the user.")
        .input(Schema.object().prop("project", PROJECT_ARG))
        .readOnly()
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              return describe(ctx, EditSupport.getSelection(ctx, proj));
            });

    registry
        .tool("selection_set")
        .description(
            "Selects components like clicking/dragging with the select tool: 'components' (ids,"
                + " labels, '@x,y') and/or every component completely inside 'area'"
                + " {x,y,width,height}. mode 'replace' (default), 'add' or 'remove'. With a GUI the"
                + " canvas shows the selection (switching to the circuit if needed). The selection"
                + " is used by edit_cut/edit_copy/edit_duplicate/edit_delete_selection.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("circuit", CIRCUIT_ARG)
                .prop("components", EditComponentTools.REFS)
                .prop("area", Schema.object("Rectangle {x,y,width,height}; selects components fully inside."))
                .prop("mode", Schema.enumeration("How to combine with the current selection.", "replace", "add", "remove")))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var circ = ctx.circuit(proj, args);
              final var picked = new LinkedHashSet<Component>();
              if (args.has("components")) {
                for (final var r : args.list("components")) picked.add(ComponentRefs.resolve(ctx, circ, String.valueOf(r)));
              }
              if (args.has("area")) {
                final var a = Args.of(args.get("area"));
                final var bds = Bounds.create(a.integer("x"), a.integer("y"), a.integer("width"), a.integer("height"));
                picked.addAll(circ.getAllWithin(bds));
              }
              final var mode = args.str("mode", "replace");
              final var result = new LinkedHashSet<Component>();
              if (!mode.equals("replace")) {
                final var cur = EditSupport.getSelection(ctx, proj);
                if (cur.circuit() == circ) result.addAll(cur.components());
              }
              switch (mode) {
                case "replace", "add" -> result.addAll(picked);
                case "remove" -> result.removeAll(picked);
                default -> throw new McpToolException("mode must be replace, add or remove.");
              }
              EditSupport.setSelection(proj, circ, result);
              return describe(ctx, EditSupport.getSelection(ctx, proj));
            });

    registry
        .tool("selection_clear")
        .description("Deselects everything (Edit > Select None). Nothing is deleted.")
        .input(Schema.object().prop("project", PROJECT_ARG))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var cur = EditSupport.getSelection(ctx, proj);
              final var circ = cur.circuit() != null ? cur.circuit() : ctx.circuit(proj, args);
              EditSupport.setSelection(proj, circ, List.of());
              return describe(ctx, EditSupport.getSelection(ctx, proj));
            });

    registry
        .tool("edit_select_all")
        .description("Selects all components and wires of the circuit (Edit > Select All).")
        .input(Schema.object().prop("project", PROJECT_ARG).prop("circuit", CIRCUIT_ARG))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var circ = ctx.circuit(proj, args);
              EditSupport.setSelection(proj, circ, circ.getComponents());
              return describe(ctx, EditSupport.getSelection(ctx, proj));
            });

    registry
        .tool("edit_delete_selection")
        .description(
            "Deletes the selected components (or the given 'components') in one undoable action,"
                + " like Edit > Delete.")
        .input(targetSchema())
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var t = targets(ctx, proj, args);
              EditSupport.requireEditable(proj, t.circuit());
              final var ids = new ArrayList<String>();
              for (final var c : t.components()) ids.add(ctx.componentId(c));
              final var xn = new CircuitMutation(t.circuit());
              xn.removeAll(t.components());
              EditSupport.run(proj, xn, "Delete Selection");
              EditSupport.setSelection(proj, t.circuit(), List.of());
              return Map.of("deleted", ids);
            });

    registry
        .tool("edit_copy")
        .description(
            "Copies the selected components (or the given 'components') to Logisim's clipboard"
                + " (Edit > Copy). The clipboard is shared with the GUI and across projects of"
                + " this Logisim instance.")
        .input(targetSchema())
        .readOnly()
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var t = targets(ctx, proj, args);
              Clipboard.setComponents(t.components());
              return clipboardInfo();
            });

    registry
        .tool("edit_cut")
        .description(
            "Copies the selected components (or the given 'components') to the clipboard and"
                + " deletes them (Edit > Cut, one undoable action for the deletion).")
        .input(targetSchema())
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var t = targets(ctx, proj, args);
              EditSupport.requireEditable(proj, t.circuit());
              Clipboard.setComponents(t.components());
              final var xn = new CircuitMutation(t.circuit());
              xn.removeAll(t.components());
              EditSupport.run(proj, xn, "Cut");
              EditSupport.setSelection(proj, t.circuit(), List.of());
              return clipboardInfo();
            });

    registry
        .tool("edit_paste")
        .description(
            "Pastes the clipboard into a circuit (Edit > Paste, one undoable action). Position:"
                + " offset 'dx'/'dy' relative to where the components were copied from, or"
                + " 'location' = new top-left corner of the pasted group; without either, the"
                + " nearest position that does not collide with existing components (often the"
                + " original position when pasting into another circuit). Components whose type"
                + " does not exist in the target project are replaced by a same-named type or"
                + " dropped (reported in 'warnings'). The pasted components become the"
                + " selection and are returned.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("circuit", CIRCUIT_ARG)
                .prop("dx", Schema.integer("Horizontal offset."))
                .prop("dy", Schema.integer("Vertical offset."))
                .prop("location", Schema.any("Top-left corner of the pasted group, e.g. {\"x\":200,\"y\":100}.")))
        .handler((ctx, args) -> paste(ctx, args));

    registry
        .tool("edit_duplicate")
        .description(
            "Duplicates the selected components (or the given 'components'), like Edit >"
                + " Duplicate: copies are placed at the nearest free offset (or 'dx'/'dy') and"
                + " become the selection.")
        .input(targetSchema().prop("dx", Schema.integer("Horizontal offset.")).prop("dy", Schema.integer("Vertical offset.")))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var t = targets(ctx, proj, args);
              EditSupport.requireEditable(proj, t.circuit());
              return EditComponentTools.duplicate(ctx, proj, t.circuit(), t.components(), args);
            });

    registry
        .tool("edit_move_selection")
        .description(
            "Moves the selected components (or the given 'components') by dx/dy, like dragging"
                + " the selection (keepConnected reroutes attached wires, default: preference)."
                + " The moved components stay selected.")
        .input(
            targetSchema()
                .prop("dx", Schema.integer("Horizontal offset."))
                .prop("dy", Schema.integer("Vertical offset."))
                .prop("keepConnected", Schema.bool("Reroute attached wires.")))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var t = targets(ctx, proj, args);
              EditSupport.requireEditable(proj, t.circuit());
              final var dx = args.integer("dx", 0);
              final var dy = args.integer("dy", 0);
              if (dx == 0 && dy == 0) throw new McpToolException("Give a non-zero dx or dy.");
              final var res = EditComponentTools.move(ctx, proj, t.circuit(), t.components(), dx, dy,
                  args.bool("keepConnected",
                      com.cburch.logisim.prefs.AppPreferences.MOVE_KEEP_CONNECT.getBoolean()));
              final var moved = new ArrayList<Component>();
              @SuppressWarnings("unchecked")
              final var list = (List<Map<String, Object>>) res.get("moved");
              for (final var m : list) {
                final var id = m.get("id");
                if (id != null) {
                  final var c = ctx.componentById(id.toString());
                  if (c != null) moved.add(c);
                }
              }
              EditSupport.setSelection(proj, t.circuit(), moved);
              return res;
            });
  }

  private static Map<String, Object> clipboardInfo() {
    final var clip = Clipboard.get();
    final var out = new LinkedHashMap<String, Object>();
    final var comps = clip == null ? List.<Component>of() : new ArrayList<>(clip.getComponents());
    var wires = 0;
    final var types = new LinkedHashMap<String, Integer>();
    for (final var c : comps) {
      if (c instanceof Wire) wires++;
      else types.merge(c.getFactory().getName(), 1, Integer::sum);
    }
    out.put("clipboardComponents", comps.size() - wires);
    out.put("clipboardWires", wires);
    out.put("types", types);
    if (!comps.isEmpty()) out.put("bounds", ComponentRefs.bounds(EditSupport.boundsOf(comps)));
    return out;
  }

  // ---------------------------------------------------------------------------------------------
  // Paste
  // ---------------------------------------------------------------------------------------------

  private static ComponentFactory findFactory(ComponentFactory factory, List<Library> libs, boolean acceptNameMatch) {
    final var name = factory.getName();
    for (final var lib : libs) {
      for (final var tool : lib.getTools()) {
        if (tool instanceof AddTool addTool && name.equals(addTool.getName())) {
          final var fact = addTool.getFactory(true);
          if (acceptNameMatch || fact == factory) return fact;
          if (fact != null && fact.getClass() == factory.getClass()
              && !(fact instanceof SubcircuitFactory) && !(fact instanceof VhdlEntity)) {
            return fact;
          }
        }
      }
    }
    return null;
  }

  private static Object paste(McpContext ctx, Args args) {
    final var proj = ctx.project(args);
    final var circ = ctx.circuit(proj, args);
    EditSupport.requireEditable(proj, circ);
    final var clip = Clipboard.get();
    if (clip == null || clip.getComponents().isEmpty()) throw new McpToolException("The clipboard is empty.");
    final var file = proj.getLogisimFile();
    final var libs = new ArrayList<Library>();
    libs.add(file);
    libs.addAll(file.getLibraries());
    final var warnings = new ArrayList<String>();
    final var sources = new ArrayList<Component>();
    final var factories = new ArrayList<ComponentFactory>();
    for (final var comp : clip.getComponents()) {
      if (comp instanceof Wire || comp.getFactory() == Text.FACTORY) {
        sources.add(comp);
        factories.add(comp.getFactory());
        continue;
      }
      var fact = findFactory(comp.getFactory(), libs, false);
      if (fact == null) {
        fact = findFactory(comp.getFactory(), libs, true);
        if (fact != null) {
          warnings.add("Type '" + comp.getFactory().getName() + "' replaced by the project's own '" + fact.getName() + "'.");
        } else {
          warnings.add("Dropped '" + comp.getFactory().getDisplayName() + "': type not available in this project.");
          continue;
        }
      }
      sources.add(comp);
      factories.add(fact);
    }
    if (sources.isEmpty()) throw new McpToolException("Nothing on the clipboard can be pasted here. " + warnings);
    // Build copies at offset 0 first (for conflict search), then translate.
    final var base = new ArrayList<Component>();
    for (var i = 0; i < sources.size(); i++) {
      final var src = sources.get(i);
      if (src instanceof Wire w) {
        base.add(Wire.create(w.getEnd0(), w.getEnd1()));
      } else {
        base.add(factories.get(i).createComponent(src.getLocation(), (AttributeSet) src.getAttributeSet().clone()));
      }
    }
    EditSupport.checkNoRecursion(proj, circ, base);
    final var bds = EditSupport.boundsOf(base);
    int dx;
    int dy;
    if (args.has("location")) {
      final var loc = ComponentRefs.parseLocation(args.get("location"), "location");
      dx = loc.getX() - bds.getX();
      dy = loc.getY() - bds.getY();
    } else if (args.has("dx") || args.has("dy")) {
      dx = args.integer("dx", 0);
      dy = args.integer("dy", 0);
    } else if (!EditSupport.hasConflictTranslated(circ, base, 0, 0, true) && bds.getX() >= 0 && bds.getY() >= 0) {
      dx = 0;
      dy = 0;
    } else {
      final var off = EditComponentTools.freeOffset(circ, base);
      dx = off[0];
      dy = off[1];
    }
    if (bds.getX() + dx < 0 || bds.getY() + dy < 0) {
      throw new McpToolException("The pasted components would extend to negative coordinates; use another position.");
    }
    final var copies = new ArrayList<Component>();
    for (final var c : base) copies.add(dx == 0 && dy == 0 ? c : EditSupport.translated(c, dx, dy, true));
    final var xn = new CircuitMutation(circ);
    xn.addAll(copies);
    EditSupport.run(proj, xn, "Paste");
    final var placed = new ArrayList<Component>();
    for (final var c : copies) if (circ.contains(c)) placed.add(c);
    // Wires may have been merged by wire repair: also report the wires now on the pasted segments.
    final var segs = new ArrayList<Location[]>();
    for (final var c : copies) if (c instanceof Wire w) segs.add(new Location[] {w.getEnd0(), w.getEnd1()});
    for (final var w : EditSupport.wiresOn(circ, segs)) if (!placed.contains(w)) placed.add(w);
    EditSupport.setSelection(proj, circ, placed);
    final var out = new LinkedHashMap<String, Object>();
    out.put("circuit", circ.getName());
    out.put("offset", Map.of("dx", dx, "dy", dy));
    final var list = new ArrayList<Object>();
    for (final var c : placed) list.add(EditComponentTools.brief(ctx, c));
    out.put("pasted", list);
    if (!warnings.isEmpty()) out.put("warnings", warnings);
    return out;
  }
}
