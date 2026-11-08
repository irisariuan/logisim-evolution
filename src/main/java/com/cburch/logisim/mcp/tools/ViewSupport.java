/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp.tools;

import com.cburch.logisim.analyze.gui.Analyzer;
import com.cburch.logisim.gui.generic.LFrame;
import com.cburch.logisim.gui.hex.HexFrame;
import com.cburch.logisim.gui.log.LogFrame;
import com.cburch.logisim.gui.main.Frame;
import com.cburch.logisim.gui.opts.OptionsFrame;
import com.cburch.logisim.gui.prefs.PreferencesFrame;
import com.cburch.logisim.gui.test.TestFrame;
import com.cburch.logisim.mcp.Args;
import com.cburch.logisim.mcp.McpContext;
import com.cburch.logisim.mcp.McpToolException;
import com.cburch.logisim.proj.Project;
import java.awt.Dialog;
import java.awt.Window;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JOptionPane;

/** Helpers shared by the view/GUI tool classes: GUI checks, window ids and window lookup. */
final class ViewSupport {
  private ViewSupport() {}

  private static final Map<Window, String> windowIds = new WeakHashMap<>();
  private static final Map<String, WeakReference<Window>> windowsById = new HashMap<>();
  private static int nextWindowId = 1;

  /** Throws a clear error when Logisim runs without windows. */
  static void requireGui(McpContext ctx) {
    if (!ctx.isGui()) {
      throw new McpToolException(
          "This tool needs the Logisim GUI, but this server runs headless (no windows). Start"
              + " Logisim with --mcp-gui or use the HTTP server of a running Logisim window."
              + " Model-level tools (and view_screenshot / export_image) work headless.");
    }
  }

  /** The main window of the project selected by the "project" argument (GUI mode only). */
  static Frame frame(McpContext ctx, Args args) {
    requireGui(ctx);
    final var proj = ctx.project(args);
    final var frame = proj.getFrame();
    if (frame == null) throw new McpToolException("The project has no window.");
    return frame;
  }

  static synchronized String windowId(Window w) {
    var id = windowIds.get(w);
    if (id == null) {
      id = "w" + nextWindowId++;
      windowIds.put(w, id);
      windowsById.put(id, new WeakReference<>(w));
    }
    return id;
  }

  private static synchronized Window windowById(String id) {
    final var ref = windowsById.get(id);
    return ref == null ? null : ref.get();
  }

  /** All windows that are currently visible on screen (or all displayable ones). */
  static List<Window> windows(boolean includeHidden) {
    final var out = new ArrayList<Window>();
    for (final var w : Window.getWindows()) {
      if (!w.isDisplayable()) continue;
      if (!includeHidden && !w.isShowing()) continue;
      out.add(w);
    }
    return out;
  }

  /**
   * Resolves a window reference: a window id ("w3"), or a case-insensitive substring of its title.
   * Prefers showing windows.
   */
  static Window findWindow(String ref) {
    final var byId = windowById(ref);
    if (byId != null && byId.isDisplayable()) return byId;
    final var needle = ref.toLowerCase(Locale.ROOT);
    Window found = null;
    for (final var w : windows(true)) {
      final var title = title(w);
      if (title != null && title.toLowerCase(Locale.ROOT).contains(needle)) {
        if (w.isShowing()) return w;
        if (found == null) found = w;
      }
    }
    if (found != null) return found;
    throw new McpToolException(
        "No window '" + ref + "'. Use window_list to see window ids and titles.");
  }

  /** The window given by the "window" argument, else the main window of the project. */
  static Window targetWindow(McpContext ctx, Args args) {
    requireGui(ctx);
    if (args.has("window")) return findWindow(args.str("window"));
    return frame(ctx, args);
  }

  static String title(Window w) {
    if (w instanceof java.awt.Frame f) return f.getTitle();
    if (w instanceof Dialog d) return d.getTitle();
    return null;
  }

  /** A short machine-friendly kind of a window. */
  static String kind(Window w) {
    if (w instanceof Frame) return "project";
    if (w instanceof LogFrame) return "log";
    if (w instanceof TestFrame) return "test";
    if (w instanceof OptionsFrame) return "options";
    if (w instanceof PreferencesFrame) return "preferences";
    if (w instanceof Analyzer) return "analyzer";
    if (w instanceof HexFrame) return "hex_editor";
    if (w instanceof JDialog d) {
      if (find(d, JFileChooser.class) != null) return "file_chooser";
      if (find(d, JOptionPane.class) != null) return "message_dialog";
      return "dialog";
    }
    if (w instanceof Dialog) return "dialog";
    if (w instanceof JFrame) return "frame";
    return "window";
  }

  static Map<String, Object> describeWindow(McpContext ctx, Window w) {
    final var out = new LinkedHashMap<String, Object>();
    out.put("id", windowId(w));
    out.put("kind", kind(w));
    out.put("title", title(w));
    out.put("class", w.getClass().getSimpleName());
    out.put("visible", w.isShowing());
    out.put("active", w.isActive());
    out.put("focused", w.isFocused());
    if (w instanceof Dialog d) out.put("modal", d.isModal());
    if (w instanceof java.awt.Frame f) {
      final var st = f.getExtendedState();
      out.put(
          "state",
          (st & java.awt.Frame.ICONIFIED) != 0
              ? "iconified"
              : (st & java.awt.Frame.MAXIMIZED_BOTH) == java.awt.Frame.MAXIMIZED_BOTH
                  ? "maximized"
                  : "normal");
    }
    final var b = w.getBounds();
    out.put("bounds", Map.of("x", b.x, "y", b.y, "width", b.width, "height", b.height));
    if (w instanceof LFrame lf && lf.getProject() != null) {
      final Project proj = lf.getProject();
      out.put("project", ctx.projectId(proj));
    }
    if (w instanceof Frame f) {
      final var proj = f.getProject();
      out.put(
          "circuit", proj.getCurrentCircuit() == null ? null : proj.getCurrentCircuit().getName());
      if (proj.getCurrentHdl() != null) out.put("hdl", proj.getCurrentHdl().getName());
      out.put("editorView", f.getEditorView());
      out.put("zoom", f.getZoomModel().getZoomFactor());
      out.put("explorerVisible", f.isExplorerVisible());
      out.put("tool", proj.getTool() == null ? null : proj.getTool().getName());
    }
    return out;
  }

  /** Depth-first search for the first component of a type. */
  static <T> T find(java.awt.Component root, Class<T> type) {
    if (type.isInstance(root)) return type.cast(root);
    if (root instanceof java.awt.Container c) {
      for (final var child : c.getComponents()) {
        final var r = find(child, type);
        if (r != null) return r;
      }
    }
    return null;
  }

  /** All components of a type, in depth-first order. */
  static <T> List<T> findAll(java.awt.Component root, Class<T> type) {
    final var out = new ArrayList<T>();
    collect(root, type, out);
    return out;
  }

  private static <T> void collect(java.awt.Component root, Class<T> type, List<T> out) {
    if (type.isInstance(root)) out.add(type.cast(root));
    if (root instanceof java.awt.Container c) {
      for (final var child : c.getComponents()) collect(child, type, out);
    }
  }

  /** Normalizes button/menu labels for matching: lower case, no mnemonic markers or ellipsis. */
  static String norm(String s) {
    if (s == null) return "";
    var t = s.replace("&", "").replace("…", "").trim();
    while (t.endsWith(".")) t = t.substring(0, t.length() - 1);
    return t.trim().toLowerCase(Locale.ROOT);
  }

  static void sleep(int ms) {
    if (ms <= 0) return;
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
