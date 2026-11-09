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

import com.cburch.draw.tools.ToolbarToolItem;
import com.cburch.logisim.analyze.gui.AnalyzerManager;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.gui.generic.CanvasPane;
import com.cburch.logisim.gui.generic.LFrame;
import com.cburch.logisim.gui.generic.ZoomControl;
import com.cburch.logisim.gui.generic.ZoomModel;
import com.cburch.logisim.gui.main.Frame;
import com.cburch.logisim.gui.prefs.PreferencesFrame;
import com.cburch.logisim.mcp.Args;
import com.cburch.logisim.mcp.ComponentRefs;
import com.cburch.logisim.mcp.McpContext;
import com.cburch.logisim.mcp.McpToolException;
import com.cburch.logisim.mcp.McpToolRegistry;
import com.cburch.logisim.mcp.Schema;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.tools.AddTool;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.tools.Tool;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.JComponent;

/** Window, canvas and tool control plus synthetic mouse/keyboard input (GUI mode). */
final class ViewGuiTools {
  private ViewGuiTools() {}

  private static final Schema WINDOW_ARG =
      Schema.string(
          "Window id from window_list (e.g. 'w3') or a part of its title. Default: the main window"
              + " of the project.");

  // ---------------------------------------------------------------------------------------------
  // Canvas helpers
  // ---------------------------------------------------------------------------------------------

  static boolean isAppearance(Frame f) {
    return Frame.EDIT_APPEARANCE.equals(f.getEditorView()) && f.getAppearanceView() != null;
  }

  static CanvasPane activeCanvasPane(Frame f) {
    if (Frame.EDIT_HDL.equals(f.getEditorView())) {
      throw new McpToolException(
          "The window shows the HDL editor; switch with view_set_layout_mode mode='layout' first.");
    }
    if (isAppearance(f)) return f.getAppearanceView().getCanvasPane();
    return f.getCanvas().getCanvasPane();
  }

  static JComponent activeCanvas(Frame f) {
    activeCanvasPane(f);
    if (isAppearance(f)) return f.getAppearanceView().getCanvas();
    return f.getCanvas();
  }

  static ZoomModel activeZoomModel(Frame f) {
    if (isAppearance(f)) return f.getAppearanceView().getZoomModel();
    return f.getZoomModel();
  }

  private static Map<String, Object> viewState(Frame f) {
    final var out = new LinkedHashMap<String, Object>();
    out.put("editorView", f.getEditorView());
    final var proj = f.getProject();
    out.put("circuit", proj.getCurrentCircuit() == null ? null : proj.getCurrentCircuit().getName());
    if (Frame.EDIT_HDL.equals(f.getEditorView())) {
      out.put("hdl", proj.getCurrentHdl() == null ? null : proj.getCurrentHdl().getName());
      return out;
    }
    final var zm = activeZoomModel(f);
    out.put("zoom", zm.getZoomFactor());
    out.put("showGrid", zm.getShowGrid());
    final var pane = activeCanvasPane(f);
    final var zoom = pane.getZoomFactor();
    final var vr = pane.getViewport().getViewRect();
    out.put(
        "visibleCanvasRect",
        Map.of(
            "x", (int) Math.floor(vr.x / zoom),
            "y", (int) Math.floor(vr.y / zoom),
            "width", (int) Math.round(vr.width / zoom),
            "height", (int) Math.round(vr.height / zoom)));
    out.put("viewportPixels", Map.of("width", vr.width, "height", vr.height));
    return out;
  }

  // ---------------------------------------------------------------------------------------------
  // Tools
  // ---------------------------------------------------------------------------------------------

  private static final Map<String, String> TOOL_ALIASES =
      Map.of(
          "poke", "Poke Tool",
          "edit", "Edit Tool",
          "select", "Select Tool",
          "wiring", "Wiring Tool",
          "wire", "Wiring Tool",
          "text", "Text Tool",
          "menu", "Menu Tool");

  private static void collectTools(Library lib, String path, List<Map.Entry<String, Tool>> out) {
    for (final var t : lib.getTools()) out.add(Map.entry(path, t));
    for (final var sub : lib.getLibraries()) {
      collectTools(sub, path.isEmpty() ? sub.getName() : path + "/" + sub.getName(), out);
    }
  }

  /** All tools of the toolbox: the project's own circuits first, then libraries (path, tool). */
  static List<Map.Entry<String, Tool>> allTools(Project proj) {
    final var out = new ArrayList<Map.Entry<String, Tool>>();
    final var file = proj.getLogisimFile();
    for (final var t : file.getTools()) out.add(Map.entry("(project)", t));
    for (final var lib : file.getLibraries()) collectTools(lib, lib.getName(), out);
    return out;
  }

  static Tool findTool(Project proj, String name, String library) {
    final var alias = TOOL_ALIASES.get(name.toLowerCase(Locale.ROOT));
    var key = alias != null ? alias : name;
    var lib = library;
    if (lib == null && key.contains("/")) {
      lib = key.substring(0, key.lastIndexOf('/'));
      key = key.substring(key.lastIndexOf('/') + 1);
    }
    final var all = allTools(proj);
    for (var pass = 0; pass < 2; pass++) {
      for (final var e : all) {
        if (lib != null && !e.getKey().equalsIgnoreCase(lib) && !e.getKey().endsWith("/" + lib)) {
          continue;
        }
        final var t = e.getValue();
        final var candidate = pass == 0 ? t.getName() : t.getDisplayName();
        if (candidate != null && candidate.equalsIgnoreCase(key)) return t;
      }
    }
    throw new McpToolException(
        "Unknown tool '" + name + "'" + (lib != null ? " in library '" + lib + "'" : "")
            + ". Use tool_list to see tool names.");
  }

  static Map<String, Object> describeTool(Tool t) {
    final var m = new LinkedHashMap<String, Object>();
    if (t == null) return null;
    m.put("name", t.getName());
    m.put("displayName", t.getDisplayName());
    if (t instanceof AddTool) m.put("addsComponent", true);
    return m;
  }

  private static String stripHtml(String s) {
    return s == null ? null : s.replaceAll("<[^>]*>", "").trim();
  }

  @SuppressWarnings("unchecked")
  private static <V> void setAttr(AttributeSet attrs, Attribute<V> attr, String value) {
    final V v;
    try {
      v = attr.parse(value);
    } catch (RuntimeException e) {
      throw new McpToolException(
          "Bad value '" + value + "' for attribute '" + attr.getName() + "': " + e.getMessage());
    }
    attrs.setValue(attr, v);
  }

  // ---------------------------------------------------------------------------------------------
  // Synthetic input
  // ---------------------------------------------------------------------------------------------

  static int modifierMask(List<Object> mods) {
    var mask = 0;
    for (final var m : mods) {
      switch (String.valueOf(m).toLowerCase(Locale.ROOT)) {
        case "shift" -> mask |= InputEvent.SHIFT_DOWN_MASK;
        case "ctrl", "control" -> mask |= InputEvent.CTRL_DOWN_MASK;
        case "alt" -> mask |= InputEvent.ALT_DOWN_MASK;
        case "meta", "cmd", "command" -> mask |= InputEvent.META_DOWN_MASK;
        default -> throw new McpToolException("Unknown modifier '" + m + "'.");
      }
    }
    return mask;
  }

  static int button(String name) {
    return switch (name.toLowerCase(Locale.ROOT)) {
      case "left" -> MouseEvent.BUTTON1;
      case "middle" -> MouseEvent.BUTTON2;
      case "right" -> MouseEvent.BUTTON3;
      default -> throw new McpToolException("Unknown button '" + name + "'.");
    };
  }

  static int buttonDownMask(int button) {
    return switch (button) {
      case MouseEvent.BUTTON2 -> InputEvent.BUTTON2_DOWN_MASK;
      case MouseEvent.BUTTON3 -> InputEvent.BUTTON3_DOWN_MASK;
      default -> InputEvent.BUTTON1_DOWN_MASK;
    };
  }

  /** Converts canvas coordinates into the canvas component's pixel coordinates. */
  private static java.awt.Point toPixels(Frame f, Location loc) {
    final var zoom = activeCanvasPane(f).getZoomFactor();
    return new java.awt.Point(
        (int) Math.round(loc.getX() * zoom), (int) Math.round(loc.getY() * zoom));
  }

  private static void mouse(JComponent c, int id, java.awt.Point p, int mods, int clicks, int btn) {
    final var e =
        new MouseEvent(c, id, System.currentTimeMillis(), mods, p.x, p.y, clicks,
            btn == MouseEvent.BUTTON3 && id == MouseEvent.MOUSE_PRESSED, btn);
    c.dispatchEvent(e);
  }

  /** Parses "ENTER", "ctrl+Z", "shift+TAB", "DELETE" into (keyCode, modifiers, char). */
  static int[] parseKey(String spec) {
    final var parts = spec.split("\\+");
    var mods = new ArrayList<Object>();
    for (var i = 0; i < parts.length - 1; i++) mods.add(parts[i].trim());
    var name = parts[parts.length - 1].trim();
    if (spec.endsWith("+") && spec.length() > 1) name = "+";
    final var mask = modifierMask(mods);
    if (name.length() == 1) {
      final var ch = name.charAt(0);
      return new int[] {KeyEvent.getExtendedKeyCodeForChar(ch), mask, ch};
    }
    var upper = name.toUpperCase(Locale.ROOT);
    if (upper.equals("ESC")) upper = "ESCAPE";
    if (upper.equals("BACKSPACE")) upper = "BACK_SPACE";
    if (upper.equals("DEL")) upper = "DELETE";
    if (upper.equals("RETURN")) upper = "ENTER";
    try {
      final var code = KeyEvent.class.getField("VK_" + upper).getInt(null);
      char ch = KeyEvent.CHAR_UNDEFINED;
      if (code == KeyEvent.VK_ENTER) ch = '\n';
      else if (code == KeyEvent.VK_TAB) ch = '\t';
      else if (code == KeyEvent.VK_BACK_SPACE) ch = '\b';
      else if (code == KeyEvent.VK_ESCAPE) ch = 27;
      else if (code == KeyEvent.VK_DELETE) ch = 127;
      else if (code == KeyEvent.VK_SPACE) ch = ' ';
      return new int[] {code, mask, ch};
    } catch (ReflectiveOperationException e) {
      throw new McpToolException(
          "Unknown key '" + name + "'. Use names of java.awt.event.KeyEvent VK_ constants without"
              + " the prefix, e.g. ENTER, DELETE, BACK_SPACE, ESCAPE, LEFT, F2, or one character.");
    }
  }

  private static void key(JComponent c, int code, int mods, char ch) {
    final var now = System.currentTimeMillis();
    final var pressed = new KeyEvent(c, KeyEvent.KEY_PRESSED, now, mods, code, ch);
    for (final var l : c.getKeyListeners()) l.keyPressed(pressed);
    final var printable = ch != KeyEvent.CHAR_UNDEFINED
        && (mods & (InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK | InputEvent.ALT_DOWN_MASK)) == 0;
    if (printable) {
      final var typed =
          new KeyEvent(c, KeyEvent.KEY_TYPED, now, mods, KeyEvent.VK_UNDEFINED, ch);
      for (final var l : c.getKeyListeners()) l.keyTyped(typed);
    }
    final var released = new KeyEvent(c, KeyEvent.KEY_RELEASED, now, mods, code, ch);
    for (final var l : c.getKeyListeners()) l.keyReleased(released);
  }

  private static Map<String, Object> afterInput(McpContext ctx, Frame f) {
    final var proj = f.getProject();
    final var out = new LinkedHashMap<String, Object>();
    out.put("tool", describeTool(proj.getTool()));
    out.put("editorView", f.getEditorView());
    final var sel = proj.getSelection();
    if (sel != null && !isAppearance(f)) {
      final var ids = new ArrayList<String>();
      for (final var c : sel.getComponents()) ids.add(ctx.componentId(c));
      out.put("selection", ids);
    }
    out.put("dirty", proj.isFileDirty());
    final var dialogs = new ArrayList<Object>();
    for (final var w : ViewSupport.windows(false)) {
      if (w instanceof java.awt.Dialog d && d.isModal()) dialogs.add(ViewSupport.windowId(w));
    }
    if (!dialogs.isEmpty()) {
      out.put("openModalDialogs", dialogs);
      out.put("hint", "A modal dialog is open; use view_dialog_list / view_dialog_close.");
    }
    return out;
  }

  private static Schema pointSchema(String desc) {
    return Schema.object(desc)
        .prop("x", Schema.integer("Canvas x."), true)
        .prop("y", Schema.integer("Canvas y."), true);
  }

  private static Schema inputSchema() {
    return Schema.object()
        .prop("project", PROJECT_ARG)
        .prop(
            "tool",
            Schema.string(
                "Optionally select this tool first (same names as tool_select, e.g. 'poke',"
                    + " 'edit', 'wiring', 'AND Gate')."))
        .prop(
            "button",
            Schema.enumeration("Mouse button.", "left", "middle", "right").with("default", "left"))
        .prop(
            "modifiers",
            Schema.array("Held modifier keys.", Schema.enumeration("Modifier.", "shift", "ctrl", "alt", "meta")))
        .prop(
            "settleMs",
            Schema.integer("Milliseconds to wait afterwards so the simulation can propagate.")
                .with("default", 100));
  }

  private static void maybeSelectTool(Frame f, Args args) {
    if (args.has("tool")) {
      if (isAppearance(f)) {
        throw new McpToolException("'tool' is only supported in the layout editor (use tool_select).");
      }
      f.getProject().setTool(findTool(f.getProject(), args.str("tool"), null));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Registration
  // ---------------------------------------------------------------------------------------------

  static void register(McpToolRegistry registry) {
    registry
        .tool("window_list")
        .description(
            "GUI only. Lists Logisim's visible windows: project windows (with current circuit,"
                + " editor view, zoom, selected tool), auxiliary windows (log, test, options,"
                + " preferences, analyzer, hex editor) and dialogs (modal ones block the GUI; see"
                + " view_dialog_list). Each has an id ('w1'...) usable as 'window' argument,"
                + " title, kind, bounds (screen pixels) and state.")
        .input(
            Schema.object()
                .prop("includeHidden", Schema.bool("Also list hidden (not yet disposed) windows.").with("default", false)))
        .readOnly()
        .handler(
            (ctx, args) -> {
              ViewSupport.requireGui(ctx);
              final var out = new ArrayList<Object>();
              for (final var w : ViewSupport.windows(args.bool("includeHidden", false))) {
                // Skip heavyweight popup menu / tooltip windows.
                if (w instanceof javax.swing.JWindow) continue;
                out.add(ViewSupport.describeWindow(ctx, w));
              }
              return Map.of("windows", out);
            });

    registry
        .tool("window_focus")
        .description(
            "GUI only. Brings a window to the front (de-iconifying it). For a project window the"
                + " project also becomes the current MCP project.")
        .input(Schema.object().prop("project", PROJECT_ARG).prop("window", WINDOW_ARG))
        .handler(
            (ctx, args) -> {
              final var w = ViewSupport.targetWindow(ctx, args);
              if (w instanceof java.awt.Frame fr && (fr.getExtendedState() & java.awt.Frame.ICONIFIED) != 0) {
                fr.setExtendedState(fr.getExtendedState() & ~java.awt.Frame.ICONIFIED);
              }
              if (!w.isVisible()) w.setVisible(true);
              w.toFront();
              w.requestFocus();
              if (w instanceof LFrame lf && w instanceof Frame && lf.getProject() != null) {
                ctx.setCurrentProject(lf.getProject());
              }
              return ViewSupport.describeWindow(ctx, w);
            });

    registry
        .tool("window_set_bounds")
        .description(
            "GUI only. Moves/resizes a window (screen pixels; omitted values are kept) or changes"
                + " its state (normal, maximized, iconified).")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("window", WINDOW_ARG)
                .prop("x", Schema.integer("Left edge on screen."))
                .prop("y", Schema.integer("Top edge on screen."))
                .prop("width", Schema.integer("Width (>= 100)."))
                .prop("height", Schema.integer("Height (>= 80)."))
                .prop("state", Schema.enumeration("Window state.", "normal", "maximized", "iconified")))
        .handler(
            (ctx, args) -> {
              final var w = ViewSupport.targetWindow(ctx, args);
              if (args.has("state") && w instanceof java.awt.Frame fr) {
                fr.setExtendedState(
                    switch (args.str("state")) {
                      case "maximized" -> java.awt.Frame.MAXIMIZED_BOTH;
                      case "iconified" -> java.awt.Frame.ICONIFIED;
                      default -> java.awt.Frame.NORMAL;
                    });
              }
              final var b = w.getBounds();
              if (args.has("x") || args.has("y") || args.has("width") || args.has("height")) {
                w.setBounds(
                    args.integer("x", b.x),
                    args.integer("y", b.y),
                    Math.max(100, args.integer("width", b.width)),
                    Math.max(80, args.integer("height", b.height)));
                w.validate();
              }
              return ViewSupport.describeWindow(ctx, w);
            });

    registry
        .tool("window_open")
        .description(
            "GUI only. Opens (or brings to front) a non-modal auxiliary window of a project:"
                + " 'log' (Simulate > Logging, incl. chronogram), 'test' (Simulate > Test Vector),"
                + " 'options' (Project > Options), 'preferences' (File > Preferences), 'analyzer'"
                + " (Combinational Analysis window, empty or showing the last analysis). Returns"
                + " the window description; capture it with view_screenshot_window.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop(
                    "which",
                    Schema.enumeration("Window to open.", "log", "test", "options", "preferences", "analyzer"),
                    true))
        .handler(
            (ctx, args) -> {
              final var frame = ViewSupport.frame(ctx, args);
              final var proj = frame.getProject();
              final Window w =
                  switch (args.str("which")) {
                    case "log" -> proj.getLogFrame();
                    case "test" -> proj.getTestFrame();
                    case "options" -> proj.getOptionsFrame();
                    case "preferences" -> {
                      PreferencesFrame.showPreferences();
                      Window found = null;
                      for (final var x : ViewSupport.windows(false)) {
                        if (x instanceof PreferencesFrame) found = x;
                      }
                      yield found;
                    }
                    case "analyzer" -> AnalyzerManager.getAnalyzer(frame);
                    default -> throw new McpToolException("Unknown window '" + args.str("which") + "'.");
                  };
              if (w == null) throw new McpToolException("The window could not be opened.");
              w.setVisible(true);
              w.toFront();
              return ViewSupport.describeWindow(ctx, w);
            });

    registry
        .tool("window_close")
        .description(
            "GUI only. Closes (hides) an auxiliary window or a non-modal dialog, like clicking its"
                + " close button. Project windows are refused (use project_close); for modal"
                + " dialogs prefer view_dialog_close which can press a specific button.")
        .input(Schema.object().prop("window", Schema.string("Window id or part of its title."), true))
        .handler(
            (ctx, args) -> {
              ViewSupport.requireGui(ctx);
              final var w = ViewSupport.findWindow(args.str("window"));
              if (w instanceof Frame) {
                throw new McpToolException("This is a project window; use project_close instead.");
              }
              final var id = ViewSupport.windowId(w);
              if (w instanceof java.awt.Dialog d && d.isModal()) {
                javax.swing.SwingUtilities.invokeLater(
                    () -> w.dispatchEvent(new java.awt.event.WindowEvent(w, java.awt.event.WindowEvent.WINDOW_CLOSING)));
              } else {
                w.dispatchEvent(new java.awt.event.WindowEvent(w, java.awt.event.WindowEvent.WINDOW_CLOSING));
                if (w.isShowing()) w.setVisible(false);
              }
              return Map.of("closed", id);
            });

    registry
        .tool("view_get_zoom")
        .description(
            "GUI only. Returns the zoom factor of the project window's active canvas (layout or"
                + " appearance editor), the available zoom steps, grid visibility and the canvas"
                + " rectangle currently visible.")
        .input(Schema.object().prop("project", PROJECT_ARG))
        .readOnly()
        .handler(
            (ctx, args) -> {
              final var f = ViewSupport.frame(ctx, args);
              final var out = viewState(f);
              if (!Frame.EDIT_HDL.equals(f.getEditorView())) {
                out.put("zoomOptions", activeZoomModel(f).getZoomOptions());
              }
              return out;
            });

    registry
        .tool("view_set_zoom")
        .description(
            "GUI only. Sets the zoom of the active canvas: 'zoom' is a factor (1.0 = 100%, range"
                + " 0.05..10), or fit=true zooms to fit the whole circuit like the 'Auto' button."
                + " 'showGrid' toggles the grid. Returns the new view state.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("zoom", Schema.number("Zoom factor, e.g. 0.5, 1, 2."))
                .prop("fit", Schema.bool("Zoom to fit the circuit and center it."))
                .prop("showGrid", Schema.bool("Show or hide the grid.")))
        .handler(
            (ctx, args) -> {
              final var f = ViewSupport.frame(ctx, args);
              activeCanvasPane(f);
              final var zm = activeZoomModel(f);
              if (args.has("showGrid")) zm.setShowGrid(args.bool("showGrid"));
              if (args.bool("fit", false)) {
                final var circ = f.getProject().getCurrentCircuit();
                if (circ != null) {
                  final var bds =
                      isAppearance(f)
                          ? circ.getAppearance().getAbsoluteBounds()
                          : ViewTools.circuitBounds(circ);
                  final var pane = activeCanvasPane(f);
                  final var z =
                      ZoomControl.computeFitZoomFactor(
                          bds, pane.getViewport().getSize(), zm.getZoomOptions());
                  zm.setZoomFactor(z);
                  if (!isAppearance(f)) f.getCanvas().center();
                }
              } else if (args.has("zoom")) {
                final var z = args.dbl("zoom");
                if (!(z >= 0.05 && z <= 10)) throw new McpToolException("'zoom' must be in 0.05..10.");
                zm.setZoomFactor(z);
              }
              activeCanvasPane(f).getViewport().doLayout();
              return viewState(f);
            });

    registry
        .tool("view_scroll_to")
        .description(
            "GUI only. Scrolls the layout canvas so that a canvas location or a component is in"
                + " the center of the visible area; center=true instead centers the whole"
                + " circuit (like the GUI's recenter). Returns the new visible rectangle.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("location", pointSchema("Canvas point to center."))
                .prop("component", Schema.string("Component reference (id, label or '@x,y') to center."))
                .prop("center", Schema.bool("Center the whole circuit.")))
        .handler(
            (ctx, args) -> {
              final var f = ViewSupport.frame(ctx, args);
              final var pane = activeCanvasPane(f);
              if (args.bool("center", false) && !isAppearance(f)) {
                f.getCanvas().center();
                return viewState(f);
              }
              int cx;
              int cy;
              if (args.has("component")) {
                final var circ = f.getProject().getCurrentCircuit();
                if (circ == null) throw new McpToolException("No circuit is shown.");
                final var b = ComponentRefs.resolve(ctx, circ, args.str("component")).getBounds();
                cx = b.getCenterX();
                cy = b.getCenterY();
              } else if (args.has("location")) {
                final var l = ComponentRefs.parseLocation(args.get("location"), "location");
                cx = l.getX();
                cy = l.getY();
              } else {
                throw new McpToolException("Give 'location', 'component' or center=true.");
              }
              final var zoom = pane.getZoomFactor();
              final var vs = pane.getViewport().getExtentSize();
              final var sx = Math.max(0, (int) Math.round(cx * zoom - vs.width / 2.0));
              final var sy = Math.max(0, (int) Math.round(cy * zoom - vs.height / 2.0));
              pane.getHorizontalScrollBar().setValue(sx);
              pane.getVerticalScrollBar().setValue(sy);
              return viewState(f);
            });

    registry
        .tool("view_set_layout_mode")
        .description(
            "GUI only. Switches the project window's editor: 'layout' (schematic editing),"
                + " 'appearance' (editing the subcircuit symbol of the circuit) or 'hdl' (the VHDL"
                + " editor for entity 'hdl'). 'circuit' optionally switches the shown circuit"
                + " first.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("mode", Schema.enumeration("Editor view.", "layout", "appearance", "hdl"), true)
                .prop("circuit", CIRCUIT_ARG)
                .prop("hdl", Schema.string("Name of the VHDL entity to show (mode='hdl').")))
        .handler(
            (ctx, args) -> {
              final var f = ViewSupport.frame(ctx, args);
              final var proj = f.getProject();
              final var mode = args.str("mode");
              if ("hdl".equals(mode)) {
                final var name = args.str("hdl");
                final var vhdl = proj.getLogisimFile().getVhdlContent(name);
                if (vhdl == null) throw new McpToolException("No VHDL entity '" + name + "' in the project.");
                proj.setCurrentHdlModel(vhdl);
                return viewState(f);
              }
              if (args.has("circuit") || proj.getCurrentCircuit() == null) {
                final var circ = ctx.circuit(proj, args);
                if (circ != proj.getCurrentCircuit()) proj.setCurrentCircuit(circ);
              }
              f.setEditorView("appearance".equals(mode) ? Frame.EDIT_APPEARANCE : Frame.EDIT_LAYOUT);
              return viewState(f);
            });

    registry
        .tool("view_toggle_panels")
        .description(
            "GUI only. Shows or hides the left side panel (explorer with toolbox/simulation tree"
                + " and attribute table) of a project window, or resets the window layout to the"
                + " defaults. Returns the panel state.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("explorer", Schema.bool("Show (true) or hide (false) the explorer side panel."))
                .prop("resetLayout", Schema.bool("Restore default split positions.")))
        .handler(
            (ctx, args) -> {
              final var f = ViewSupport.frame(ctx, args);
              if (args.bool("resetLayout", false)) f.resetLayout();
              if (args.has("explorer")) f.setExplorerVisible(args.bool("explorer"));
              f.validate();
              return Map.of("explorerVisible", f.isExplorerVisible(), "editorView", f.getEditorView());
            });

    registry
        .tool("tool_list")
        .description(
            "Lists tools. Always: the toolbox, i.e. tools per library (the project's own"
                + " circuits under '(project)', then libraries such as 'Base', 'Gates', 'Wiring';"
                + " nested libraries as 'Parent/Child'); optional 'filter' (substring of names)."
                + " In GUI mode also: the selected tool and the items of the window's toolbar"
                + " (index, tooltip, selected), which differs in the appearance editor. Use names"
                + " with tool_select.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("filter", Schema.string("Case-insensitive substring of tool names to keep."))
                .prop("library", Schema.string("Only this library (path as shown).")))
        .readOnly()
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var out = new LinkedHashMap<String, Object>();
              final var frame = proj.getFrame();
              if (frame != null) {
                out.put("selectedTool", describeTool(proj.getTool()));
                final var model = frame.getToolbar().getToolbarModel();
                final var items = new ArrayList<Object>();
                if (model != null) {
                  var i = 0;
                  for (final var item : model.getItems()) {
                    final var m = new LinkedHashMap<String, Object>();
                    m.put("index", i++);
                    final var tip = stripHtml(item.getToolTip());
                    m.put("tooltip", tip);
                    if (item instanceof ToolbarToolItem tti) {
                      // Drawing tools of the appearance editor have no tooltip; name them by class.
                      m.put("drawingTool", tti.getTool().getClass().getSimpleName().replace("Tool", ""));
                    }
                    if (!item.isSelectable()) m.put("selectable", false);
                    if (model.isSelected(item)) m.put("selected", true);
                    if (tip == null && !item.isSelectable()) m.put("separator", true);
                    items.add(m);
                  }
                }
                out.put("toolbar", items);
                out.put("editorView", frame.getEditorView());
              }
              final var filter = args.str("filter", "").toLowerCase(Locale.ROOT);
              final var lib = args.str("library", null);
              final var libs = new LinkedHashMap<String, List<String>>();
              for (final var e : allTools(proj)) {
                if (lib != null && !e.getKey().equalsIgnoreCase(lib)) continue;
                final var t = e.getValue();
                if (!filter.isEmpty()
                    && !t.getName().toLowerCase(Locale.ROOT).contains(filter)
                    && !String.valueOf(t.getDisplayName()).toLowerCase(Locale.ROOT).contains(filter)) {
                  continue;
                }
                libs.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(t.getName());
              }
              out.put("toolbox", libs);
              return out;
            });

    registry
        .tool("tool_select")
        .description(
            "GUI only. Selects the active tool of a project window, exactly like clicking it in"
                + " the toolbar/toolbox: 'poke' (change input values, Poke Tool), 'edit'"
                + " (select/move, Edit Tool), 'select', 'wiring', 'text', 'menu', any toolbox tool"
                + " name such as 'AND Gate', 'Pin', 'Wiring/Clock' or a circuit name (adds a"
                + " subcircuit). Alternatively 'toolbarIndex' clicks a toolbar item by index from"
                + " tool_list (this also works for the appearance editor's drawing tools)."
                + " 'attributes' (name -> value strings) are set on the tool's default attributes"
                + " first, e.g. {\"facing\":\"west\",\"inputs\":\"3\"}. Then use view_canvas_click"
                + " / view_canvas_drag to act with the tool.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("tool", Schema.string("Tool name or alias."))
                .prop("library", Schema.string("Library path to disambiguate the tool name."))
                .prop("toolbarIndex", Schema.integer("Index of a toolbar item (see tool_list)."))
                .prop("attributes", Schema.object("Tool attribute values (strings)."))
                .additionalProperties(false))
        .handler(
            (ctx, args) -> {
              final var f = ViewSupport.frame(ctx, args);
              final var proj = f.getProject();
              if (args.has("toolbarIndex")) {
                final var model = f.getToolbar().getToolbarModel();
                final var items = model.getItems();
                final var i = args.integer("toolbarIndex");
                if (i < 0 || i >= items.size()) throw new McpToolException("toolbarIndex out of range.");
                final var item = items.get(i);
                model.itemSelected(item);
                final var out = new LinkedHashMap<String, Object>();
                out.put("clicked", i);
                out.put("tooltip", stripHtml(item.getToolTip()));
                if (item instanceof ToolbarToolItem tti) {
                  out.put("drawingTool", tti.getTool().getClass().getSimpleName().replace("Tool", ""));
                }
                out.put("selected", model.isSelected(item));
                out.put("editorView", f.getEditorView());
                return out;
              }
              final var tool = findTool(proj, args.str("tool"), args.str("library", null));
              if (args.has("attributes")) {
                final var attrs = tool.getAttributeSet();
                if (attrs == null) throw new McpToolException("This tool has no attributes.");
                for (final var e : args.map("attributes").entrySet()) {
                  Attribute<?> attr = null;
                  for (final var a : attrs.getAttributes()) if (a.getName().equals(e.getKey())) attr = a;
                  if (attr == null) {
                    final var names = new ArrayList<String>();
                    for (final var a : attrs.getAttributes()) names.add(a.getName());
                    throw new McpToolException("Unknown attribute '" + e.getKey() + "'. Available: " + names);
                  }
                  setAttr(attrs, attr, String.valueOf(e.getValue()));
                }
              }
              if (isAppearance(f)) f.setEditorView(Frame.EDIT_LAYOUT);
              proj.setTool(tool);
              final var out = new LinkedHashMap<String, Object>();
              out.put("selectedTool", describeTool(proj.getTool()));
              if (tool.getAttributeSet() != null) {
                out.put("toolAttributes", ComponentRefs.attributes(tool.getAttributeSet()));
              }
              return out;
            });

    registry
        .tool("view_canvas_click")
        .description(
            "GUI only. Clicks on the active canvas (layout or appearance editor) of a project"
                + " window at CANVAS coordinates (the same coordinates as component locations;"
                + " zoom and scrolling are handled), by dispatching real mouse events, so the"
                + " currently selected tool reacts exactly as to a user's click: Poke toggles an"
                + " input pin, Edit selects, an add tool places a component, right button opens"
                + " the context menu (list it with view_menu_list popup=true). The point does not"
                + " need to be scrolled into view. Returns the selected tool, selection and any"
                + " modal dialog that opened.")
        .input(
            inputSchema()
                .prop("x", Schema.integer("Canvas x."), true)
                .prop("y", Schema.integer("Canvas y."), true)
                .prop("clickCount", Schema.integer("1 = click, 2 = double click.").with("default", 1)))
        .offModelThread()
        .handler(
            (ctx, args) -> {
              final var result =
                  ctx.onModelThread(
                      () -> {
                        final var f = ViewSupport.frame(ctx, args);
                        maybeSelectTool(f, args);
                        final var c = activeCanvas(f);
                        final var p = toPixels(f, Location.create(args.integer("x"), args.integer("y"), false));
                        final var mods = modifierMask(args.list("modifiers", List.of()));
                        final var btn = button(args.str("button", "left"));
                        final var down = mods | buttonDownMask(btn);
                        mouse(c, MouseEvent.MOUSE_MOVED, p, mods, 0, MouseEvent.NOBUTTON);
                        final var clicks = Math.max(1, Math.min(3, args.integer("clickCount", 1)));
                        for (var n = 1; n <= clicks; n++) {
                          mouse(c, MouseEvent.MOUSE_PRESSED, p, down, n, btn);
                          mouse(c, MouseEvent.MOUSE_RELEASED, p, mods, n, btn);
                          mouse(c, MouseEvent.MOUSE_CLICKED, p, mods, n, btn);
                        }
                        return f;
                      });
              ViewSupport.sleep(args.integer("settleMs", 100));
              return ctx.onModelThread(() -> afterInput(ctx, result));
            });

    registry
        .tool("view_canvas_drag")
        .description(
            "GUI only. Drags with the mouse on the active canvas from one CANVAS point to another"
                + " (press, intermediate drag events, release), e.g. to draw a wire with the"
                + " wiring tool, move a selection with the edit tool, rubber-band select, or draw"
                + " a shape in the appearance editor. Returns like view_canvas_click.")
        .input(
            inputSchema()
                .prop("from", pointSchema("Start point (canvas coordinates)."), true)
                .prop("to", pointSchema("End point (canvas coordinates)."), true)
                .prop("steps", Schema.integer("Number of intermediate drag events.").with("default", 8)))
        .offModelThread()
        .handler(
            (ctx, args) -> {
              final var result =
                  ctx.onModelThread(
                      () -> {
                        final var f = ViewSupport.frame(ctx, args);
                        maybeSelectTool(f, args);
                        final var c = activeCanvas(f);
                        final var from = ComponentRefs.parseLocation(args.get("from"), "from");
                        final var to = ComponentRefs.parseLocation(args.get("to"), "to");
                        final var mods = modifierMask(args.list("modifiers", List.of()));
                        final var btn = button(args.str("button", "left"));
                        final var down = mods | buttonDownMask(btn);
                        final var steps = Math.max(1, Math.min(100, args.integer("steps", 8)));
                        final var p0 = toPixels(f, from);
                        mouse(c, MouseEvent.MOUSE_MOVED, p0, mods, 0, MouseEvent.NOBUTTON);
                        mouse(c, MouseEvent.MOUSE_PRESSED, p0, down, 1, btn);
                        for (var i = 1; i <= steps; i++) {
                          final var x = from.getX() + (to.getX() - from.getX()) * i / steps;
                          final var y = from.getY() + (to.getY() - from.getY()) * i / steps;
                          mouse(c, MouseEvent.MOUSE_DRAGGED, toPixels(f, Location.create(x, y, false)), down, 0, btn);
                        }
                        mouse(c, MouseEvent.MOUSE_RELEASED, toPixels(f, to), mods, 1, btn);
                        return f;
                      });
              ViewSupport.sleep(args.integer("settleMs", 100));
              return ctx.onModelThread(() -> afterInput(ctx, result));
            });

    registry
        .tool("view_canvas_type")
        .description(
            "GUI only. Sends keyboard input to the active canvas, which forwards it to the"
                + " selected tool (e.g. typing a label with the Text tool, Delete/BACK_SPACE to"
                + " delete the selection with the Edit tool, arrow keys to change the facing of a"
                + " component being added, digits to set a value with the Poke tool). 'text' is"
                + " typed character by character; 'keys' are key strokes like 'ENTER', 'ESCAPE',"
                + " 'DELETE', 'LEFT', 'ctrl+D', 'shift+TAB' (names of KeyEvent VK_ constants)."
                + " 'text' is sent before 'keys'. Menu shortcuts are not triggered this way; use"
                + " view_menu_invoke for menu commands.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("text", Schema.string("Characters to type."))
                .prop("keys", Schema.array("Key strokes.", Schema.string("Key, e.g. 'ENTER' or 'ctrl+A'.")))
                .prop("settleMs", Schema.integer("Wait afterwards (ms).").with("default", 100)))
        .offModelThread()
        .handler(
            (ctx, args) -> {
              final var result =
                  ctx.onModelThread(
                      () -> {
                        final var f = ViewSupport.frame(ctx, args);
                        final var c = activeCanvas(f);
                        for (final var ch : args.str("text", "").toCharArray()) {
                          final var code = KeyEvent.getExtendedKeyCodeForChar(ch);
                          final var mods = Character.isUpperCase(ch) ? InputEvent.SHIFT_DOWN_MASK : 0;
                          key(c, code, mods, ch);
                        }
                        for (final var k : args.list("keys", List.of())) {
                          final var spec = parseKey(String.valueOf(k));
                          key(c, spec[0], spec[1], (char) spec[2]);
                        }
                        return f;
                      });
              ViewSupport.sleep(args.integer("settleMs", 100));
              return ctx.onModelThread(() -> afterInput(ctx, result));
            });
  }
}
