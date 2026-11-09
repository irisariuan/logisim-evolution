/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp.tools;

import static com.cburch.logisim.mcp.McpToolRegistry.PROJECT_ARG;

import com.cburch.logisim.mcp.Args;
import com.cburch.logisim.mcp.McpContext;
import com.cburch.logisim.mcp.McpToolException;
import com.cburch.logisim.mcp.McpToolRegistry;
import com.cburch.logisim.mcp.Schema;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButton;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.MenuElement;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

/** Menu bar / popup menu access and modal dialog inspection and handling (GUI mode). */
final class ViewDialogTools {
  private ViewDialogTools() {}

  // ---------------------------------------------------------------------------------------------
  // Menus
  // ---------------------------------------------------------------------------------------------

  private static String itemText(JMenuItem item) {
    final var t = item.getText();
    return t == null || t.isEmpty() ? item.getActionCommand() : t;
  }

  private static void walkMenu(
      MenuElement elem, String path, List<Map<String, Object>> out, String prefix) {
    for (final var child : elem.getSubElements()) {
      if (child instanceof JPopupMenu popup) {
        walkMenu(popup, path, out, prefix);
        continue;
      }
      if (!(child instanceof JMenuItem item)) continue;
      if (!item.isVisible()) continue;
      final var p = path.isEmpty() ? itemText(item) : path + "/" + itemText(item);
      final var include = prefix == null || ViewSupport.norm(p).startsWith(ViewSupport.norm(prefix));
      if (include) {
        final var m = new LinkedHashMap<String, Object>();
        m.put("path", p);
        m.put(
            "type",
            item instanceof JMenu
                ? "menu"
                : item instanceof JCheckBoxMenuItem
                    ? "checkbox"
                    : item instanceof JRadioButtonMenuItem ? "radio" : "item");
        if (!item.isEnabled()) m.put("enabled", false);
        if ((item instanceof JCheckBoxMenuItem || item instanceof JRadioButtonMenuItem)) {
          m.put("selected", item.isSelected());
        }
        if (!(item instanceof JMenu) && item.getAccelerator() != null) {
          m.put("shortcut", item.getAccelerator().toString().replace("pressed ", ""));
        }
        out.add(m);
      }
      if (item instanceof JMenu menu) {
        // JMenus populated lazily (e.g. recent files) are only complete once shown.
        walkMenu(menu, p, out, prefix);
      }
    }
  }

  /** Showing popup menus (context menus), topmost last. */
  private static List<JPopupMenu> showingPopups() {
    final var out = new ArrayList<JPopupMenu>();
    for (final var w : Window.getWindows()) {
      if (!w.isShowing()) continue;
      for (final var p : ViewSupport.findAll(w, JPopupMenu.class)) {
        if (p.isShowing() && !(p.getInvoker() instanceof JMenu)) out.add(p);
      }
    }
    return out;
  }

  private static MenuElement menuRoot(McpContext ctx, Args args) {
    if (args.bool("popup", false)) {
      final var popups = showingPopups();
      if (popups.isEmpty()) {
        throw new McpToolException(
            "No popup (context) menu is showing. Open one with view_canvas_click button='right'.");
      }
      return popups.get(popups.size() - 1);
    }
    final var w = ViewSupport.targetWindow(ctx, args);
    if (!(w instanceof javax.swing.JFrame f) || f.getJMenuBar() == null) {
      throw new McpToolException("This window has no menu bar.");
    }
    return f.getJMenuBar();
  }

  private static JMenuItem findItem(MenuElement root, String path) {
    final var all = new ArrayList<Map<String, Object>>();
    final var items = new ArrayList<JMenuItem>();
    collectItems(root, "", items, all);
    final var want = ViewSupport.norm(path);
    for (var i = 0; i < items.size(); i++) {
      if (ViewSupport.norm((String) all.get(i).get("path")).equals(want)) return items.get(i);
    }
    // Allow a unique match on the last path segment(s).
    JMenuItem found = null;
    for (var i = 0; i < items.size(); i++) {
      final var p = ViewSupport.norm((String) all.get(i).get("path"));
      if (p.endsWith("/" + want)) {
        if (found != null) {
          throw new McpToolException("Menu path '" + path + "' is ambiguous; give the full path.");
        }
        found = items.get(i);
      }
    }
    if (found != null) return found;
    // Allow a unique prefix match for items with dynamic text, e.g. 'Edit/Undo' -> 'Edit/Undo Delete'.
    for (var i = 0; i < items.size(); i++) {
      final var p = ViewSupport.norm((String) all.get(i).get("path"));
      if (!(items.get(i) instanceof JMenu) && (p.startsWith(want) || p.contains("/" + want))) {
        if (found != null) {
          throw new McpToolException("Menu path '" + path + "' is ambiguous; give the full path.");
        }
        found = items.get(i);
      }
    }
    if (found != null) return found;
    throw new McpToolException("No menu item '" + path + "'. Use view_menu_list to see the paths.");
  }

  private static void collectItems(
      MenuElement elem, String path, List<JMenuItem> items, List<Map<String, Object>> paths) {
    for (final var child : elem.getSubElements()) {
      if (child instanceof JPopupMenu popup) {
        collectItems(popup, path, items, paths);
        continue;
      }
      if (!(child instanceof JMenuItem item) || !item.isVisible()) continue;
      final var p = path.isEmpty() ? itemText(item) : path + "/" + itemText(item);
      items.add(item);
      paths.add(Map.of("path", p));
      if (item instanceof JMenu menu) collectItems(menu, p, items, paths);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Dialogs
  // ---------------------------------------------------------------------------------------------

  /** Showing dialogs, most recently opened (topmost) last. */
  static List<Window> dialogs() {
    final var out = new ArrayList<Window>();
    for (final var w : ViewSupport.windows(false)) {
      if (w instanceof Dialog) out.add(w);
    }
    return out;
  }

  private static Window findDialog(Args args) {
    if (args.has("dialog")) {
      final var w = ViewSupport.findWindow(args.str("dialog"));
      if (!w.isShowing()) throw new McpToolException("That window is not showing.");
      return w;
    }
    final var all = dialogs();
    if (all.isEmpty()) throw new McpToolException("No dialog is open.");
    // Prefer the modal dialog that is active / owned last.
    for (var i = all.size() - 1; i >= 0; i--) {
      if (all.get(i).isActive()) return all.get(i);
    }
    for (var i = all.size() - 1; i >= 0; i--) {
      if (all.get(i) instanceof Dialog d && d.isModal()) return all.get(i);
    }
    return all.get(all.size() - 1);
  }

  private static String labelFor(java.awt.Component c) {
    final var root = SwingUtilities.getWindowAncestor(c);
    if (root == null) return null;
    for (final var l : ViewSupport.findAll(root, JLabel.class)) {
      if (l.getLabelFor() == c) return l.getText();
    }
    // Fall back to the label placed right before the component in its parent.
    final Container parent = c.getParent();
    if (parent != null) {
      JLabel last = null;
      for (final var sib : parent.getComponents()) {
        if (sib == c) break;
        if (sib instanceof JLabel l) last = l;
        else if (!(sib instanceof javax.swing.Box.Filler)) last = null;
      }
      if (last != null) return last.getText();
    }
    return null;
  }

  static Map<String, Object> describeDialog(McpContext ctx, Window w) {
    final var out = ViewSupport.describeWindow(ctx, w);
    final var pane = ViewSupport.find(w, JOptionPane.class);
    if (pane != null) {
      final var msg = pane.getMessage();
      if (msg instanceof String s) out.put("message", s);
      else if (msg instanceof Object[] arr) {
        final var parts = new ArrayList<String>();
        for (final var o : arr) if (o instanceof String s) parts.add(s);
        if (!parts.isEmpty()) out.put("message", String.join("\n", parts));
      }
    }
    final var chooser = ViewSupport.find(w, JFileChooser.class);
    if (chooser != null) {
      final var fc = new LinkedHashMap<String, Object>();
      final var type = chooser.getDialogType();
      fc.put(
          "type",
          type == JFileChooser.SAVE_DIALOG ? "save" : type == JFileChooser.OPEN_DIALOG ? "open" : "custom");
      final var dir = chooser.getCurrentDirectory();
      fc.put("directory", dir == null ? null : dir.getAbsolutePath());
      fc.put("selectedFile", chooser.getSelectedFile() == null ? null : chooser.getSelectedFile().getAbsolutePath());
      fc.put("directoriesOnly", chooser.getFileSelectionMode() == JFileChooser.DIRECTORIES_ONLY);
      if (chooser.getFileFilter() != null) fc.put("filter", chooser.getFileFilter().getDescription());
      out.put("fileChooser", fc);
      out.put("hint", "Use view_dialog_close with 'file' to choose a file and approve, or button='Cancel'.");
    }
    final var texts = new ArrayList<String>();
    for (final var l : ViewSupport.findAll(w, JLabel.class)) {
      if (l.isShowing() && l.getText() != null && !l.getText().isBlank()) {
        texts.add(l.getText().replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim());
      }
    }
    for (final var ta : ViewSupport.findAll(w, JTextArea.class)) {
      if (ta.isShowing() && !ta.isEditable() && !ta.getText().isBlank()) texts.add(ta.getText());
    }
    if (!texts.isEmpty() && chooser == null) out.put("texts", texts);
    final var fields = new ArrayList<Object>();
    var i = 0;
    for (final var t : ViewSupport.findAll(w, JTextComponent.class)) {
      if (!t.isShowing() || !t.isEditable()) continue;
      final var m = new LinkedHashMap<String, Object>();
      m.put("index", i++);
      final var label = labelFor(t);
      if (label != null) m.put("label", label);
      m.put("text", t.getText());
      fields.add(m);
    }
    if (!fields.isEmpty()) out.put("textFields", fields);
    final var toggles = new ArrayList<Object>();
    final var buttons = new ArrayList<Object>();
    for (final var b : ViewSupport.findAll(w, AbstractButton.class)) {
      if (!b.isShowing()) continue;
      final var text = b.getText() != null && !b.getText().isBlank() ? b.getText() : b.getToolTipText();
      if (b instanceof JToggleButton) {
        if (text == null) continue;
        final var m = new LinkedHashMap<String, Object>();
        m.put("label", text);
        m.put("type", b instanceof JCheckBox ? "checkbox" : b instanceof JRadioButton ? "radio" : "toggle");
        m.put("selected", b.isSelected());
        if (!b.isEnabled()) m.put("enabled", false);
        toggles.add(m);
      } else if (text != null && !(b instanceof JMenuItem)) {
        final var m = new LinkedHashMap<String, Object>();
        m.put("label", text.replaceAll("<[^>]*>", "").trim());
        if (!b.isEnabled()) m.put("enabled", false);
        buttons.add(m);
      }
    }
    if (!toggles.isEmpty()) out.put("toggles", toggles);
    final var combos = new ArrayList<Object>();
    var ci = 0;
    for (final var cb : ViewSupport.findAll(w, JComboBox.class)) {
      if (!cb.isShowing()) continue;
      final var m = new LinkedHashMap<String, Object>();
      m.put("index", ci++);
      final var label = labelFor(cb);
      if (label != null) m.put("label", label);
      m.put("selected", comboText(cb, cb.getSelectedItem()));
      final var items = new ArrayList<String>();
      for (var k = 0; k < Math.min(50, cb.getItemCount()); k++) items.add(comboText(cb, cb.getItemAt(k)));
      m.put("items", items);
      combos.add(m);
    }
    if (!combos.isEmpty()) out.put("comboBoxes", combos);
    out.put("buttons", buttons);
    return out;
  }

  /** The text a combo box shows for an item (via its renderer), falling back to toString. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  static String comboText(JComboBox cb, Object item) {
    if (item instanceof javax.swing.filechooser.FileFilter ff) return ff.getDescription();
    try {
      final var r = cb.getRenderer().getListCellRendererComponent(new javax.swing.JList(), item, -1, false, false);
      if (r instanceof JLabel l && l.getText() != null && !l.getText().isEmpty()) return l.getText();
    } catch (RuntimeException ignored) {
      // fall through
    }
    return String.valueOf(item);
  }

  private static AbstractButton findButton(Window w, String label) {
    final var want = ViewSupport.norm(label);
    AbstractButton partial = null;
    for (final var b : ViewSupport.findAll(w, AbstractButton.class)) {
      if (!b.isShowing() || b instanceof JMenuItem) continue;
      final var text = b.getText() != null && !b.getText().isBlank() ? b.getText() : b.getToolTipText();
      if (text == null) continue;
      final var n = ViewSupport.norm(text.replaceAll("<[^>]*>", ""));
      if (n.equals(want)) return b;
      if (partial == null && n.contains(want)) partial = b;
    }
    if (partial != null) return partial;
    throw new McpToolException("No button '" + label + "' in the dialog. Use view_dialog_list.");
  }

  private static Object findField(List<? extends java.awt.Component> comps, String key) {
    if (key.matches("\\d+")) {
      final var i = Integer.parseInt(key);
      if (i < comps.size()) return comps.get(i);
    }
    for (final var c : comps) {
      final var l = labelFor(c);
      if (l != null && ViewSupport.norm(l).contains(ViewSupport.norm(key))) return c;
    }
    throw new McpToolException("No field '" + key + "' in the dialog. Use view_dialog_list.");
  }

  /** Windows showing now, to report which ones appeared after an asynchronous action. */
  private static Set<Window> snapshot() {
    return new HashSet<>(ViewSupport.windows(false));
  }

  private static Map<String, Object> report(McpContext ctx, Set<Window> before) {
    final var out = new LinkedHashMap<String, Object>();
    final var opened = new ArrayList<Object>();
    for (final var w : ViewSupport.windows(false)) {
      if (!before.contains(w) && !(w instanceof javax.swing.JWindow)) {
        opened.add(w instanceof Dialog ? describeDialog(ctx, w) : ViewSupport.describeWindow(ctx, w));
      }
    }
    if (!opened.isEmpty()) out.put("openedWindows", opened);
    final var open = new ArrayList<Object>();
    for (final var d : dialogs()) open.add(ViewSupport.windowId(d) + ": " + ViewSupport.title(d));
    out.put("openDialogs", open);
    if (!open.isEmpty()) {
      out.put("hint", "Dialogs are open (modal ones block the GUI); handle them with view_dialog_list / view_dialog_close.");
    }
    return out;
  }

  // ---------------------------------------------------------------------------------------------
  // Registration
  // ---------------------------------------------------------------------------------------------

  static void register(McpToolRegistry registry) {
    registry
        .tool("view_menu_list")
        .description(
            "GUI only. Lists the menu items of a window's menu bar (default: the project's main"
                + " window) as paths like 'Simulate/Reset Simulation', with type (menu, item,"
                + " checkbox, radio), enabled/selected state and keyboard shortcut. 'path' limits"
                + " the list to one menu, e.g. 'File'. popup=true lists the currently open"
                + " context menu instead (opened by a right click).")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("window", Schema.string("Window id or part of its title."))
                .prop("path", Schema.string("Menu path prefix to list, e.g. 'Edit'."))
                .prop("popup", Schema.bool("List the showing popup/context menu.").with("default", false)))
        .readOnly()
        .handler(
            (ctx, args) -> {
              ViewSupport.requireGui(ctx);
              final var root = menuRoot(ctx, args);
              final var out = new ArrayList<Map<String, Object>>();
              walkMenu(root, "", out, args.str("path", null));
              return Map.of("items", out);
            });

    registry
        .tool("view_menu_invoke")
        .description(
            "GUI only. Clicks a menu item by its path as listed by view_menu_list (e.g."
                + " 'Simulate/Reset Simulation', 'Edit/Undo', 'Window/Combinational Analysis';"
                + " matching ignores case and a trailing '...'; a unique last segment such as"
                + " 'Reset Simulation' is enough). popup=true clicks an item of the open context"
                + " menu. The click is performed asynchronously, exactly like a user's click: items"
                + " that open dialogs or file choosers (Open..., Save As..., Export Image...,"
                + " Print..., Load Library, etc.) leave a MODAL dialog open which blocks the GUI"
                + " until handled: the result lists open dialogs; use view_dialog_list and"
                + " view_dialog_close to fill in / answer / cancel them. Prefer dedicated model"
                + " tools (project_save, export_image, ...) where they exist.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("window", Schema.string("Window id or part of its title."))
                .prop("path", Schema.string("Menu item path."), true)
                .prop("popup", Schema.bool("Invoke an item of the open popup menu.").with("default", false))
                .prop("waitMs", Schema.integer("Time to wait before reporting (ms).").with("default", 400)))
        .offModelThread()
        .handler(
            (ctx, args) -> {
              final var before =
                  ctx.onModelThread(
                      () -> {
                        ViewSupport.requireGui(ctx);
                        final var root = menuRoot(ctx, args);
                        final var item = findItem(root, args.str("path"));
                        if (item instanceof JMenu) {
                          throw new McpToolException("'" + args.str("path") + "' is a menu, not an item.");
                        }
                        if (!item.isEnabled()) {
                          throw new McpToolException("Menu item '" + args.str("path") + "' is disabled.");
                        }
                        final var snap = snapshot();
                        SwingUtilities.invokeLater(
                            () -> {
                              javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();
                              item.doClick(0);
                            });
                        return snap;
                      });
              ViewSupport.sleep(Math.max(0, Math.min(10000, args.integer("waitMs", 400))));
              final var out = ctx.onModelThread(() -> report(ctx, before));
              out.put("invoked", args.str("path"));
              return out;
            });

    registry
        .tool("view_window_click")
        .description(
            "GUI only. Clicks at a pixel position inside any Logisim window, e.g. a tab, button,"
                + " list entry, tree node or attribute table cell that has no dedicated tool. 'x'/'y'"
                + " are pixel coordinates in the image returned by view_screenshot_window for the"
                + " same window (area='window', scale 1). The mouse events go to the deepest"
                + " component at that point. Asynchronous like a real click: the result reports"
                + " the clicked component and any dialogs that opened. For the drawing canvas"
                + " prefer view_canvas_click (canvas coordinates).")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("window", Schema.string("Window id or part of its title. Default: the project's main window."))
                .prop("x", Schema.integer("Pixel x in the window screenshot."), true)
                .prop("y", Schema.integer("Pixel y in the window screenshot."), true)
                .prop("button", Schema.enumeration("Mouse button.", "left", "middle", "right").with("default", "left"))
                .prop("clickCount", Schema.integer("1 = click, 2 = double click.").with("default", 1))
                .prop("waitMs", Schema.integer("Time to wait before reporting (ms).").with("default", 300)))
        .offModelThread()
        .handler(
            (ctx, args) -> {
              final var clicked = new LinkedHashMap<String, Object>();
              final var before =
                  ctx.onModelThread(
                      () -> {
                        final var w = ViewSupport.targetWindow(ctx, args);
                        if (!(w instanceof RootPaneContainer rpc)) {
                          throw new McpToolException("Cannot click into this window type.");
                        }
                        final var root = rpc.getRootPane();
                        final var x = args.integer("x");
                        final var y = args.integer("y");
                        final var target = SwingUtilities.getDeepestComponentAt(root, x, y);
                        if (target == null) {
                          throw new McpToolException("No component at " + x + "," + y + ".");
                        }
                        final var p = SwingUtilities.convertPoint(root, x, y, target);
                        clicked.put("component", target.getClass().getSimpleName());
                        if (target instanceof AbstractButton b && b.getText() != null) {
                          clicked.put("text", b.getText());
                        }
                        if (target instanceof JLabel l && l.getText() != null) clicked.put("text", l.getText());
                        final var btn = ViewGuiTools.button(args.str("button", "left"));
                        final var down = ViewGuiTools.buttonDownMask(btn);
                        final var clicks = Math.max(1, Math.min(3, args.integer("clickCount", 1)));
                        final var snap = snapshot();
                        SwingUtilities.invokeLater(
                            () -> {
                              final var now = System.currentTimeMillis();
                              target.dispatchEvent(
                                  new MouseEvent(target, MouseEvent.MOUSE_MOVED, now, 0, p.x, p.y, 0, false, 0));
                              for (var n = 1; n <= clicks; n++) {
                                target.dispatchEvent(
                                    new MouseEvent(target, MouseEvent.MOUSE_PRESSED, now, down, p.x, p.y, n,
                                        btn == MouseEvent.BUTTON3, btn));
                                target.dispatchEvent(
                                    new MouseEvent(target, MouseEvent.MOUSE_RELEASED, now, 0, p.x, p.y, n, false, btn));
                                target.dispatchEvent(
                                    new MouseEvent(target, MouseEvent.MOUSE_CLICKED, now, 0, p.x, p.y, n, false, btn));
                              }
                            });
                        return snap;
                      });
              ViewSupport.sleep(Math.max(0, Math.min(10000, args.integer("waitMs", 300))));
              final var out = ctx.onModelThread(() -> report(ctx, before));
              out.put("clicked", clicked);
              return out;
            });

    registry
        .tool("view_window_type")
        .description(
            "GUI only. Types into a component of any Logisim window: the component at pixel"
                + " (x, y) of the window screenshot if given (it is focused first), otherwise the"
                + " window's focused component. 'text' is inserted into a text field/area at the"
                + " caret (replacing the selection); 'keys' are then sent as key strokes such as"
                + " 'ENTER', 'TAB', 'ESCAPE', 'DOWN', 'ctrl+A' (KeyEvent VK_ names). Use it e.g."
                + " for attribute table editors, search fields or the HDL editor. For dialogs"
                + " view_dialog_close can fill fields directly.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("window", Schema.string("Window id or part of its title. Default: the project's main window."))
                .prop("x", Schema.integer("Pixel x in the window screenshot."))
                .prop("y", Schema.integer("Pixel y in the window screenshot."))
                .prop("text", Schema.string("Text to insert."))
                .prop("keys", Schema.array("Key strokes.", Schema.string("Key, e.g. 'ENTER'.")))
                .prop("waitMs", Schema.integer("Time to wait before reporting (ms).").with("default", 200)))
        .offModelThread()
        .handler(
            (ctx, args) -> {
              final var info = new LinkedHashMap<String, Object>();
              final var before =
                  ctx.onModelThread(
                      () -> {
                        final var w = ViewSupport.targetWindow(ctx, args);
                        java.awt.Component target;
                        if (args.has("x") && args.has("y")) {
                          if (!(w instanceof RootPaneContainer rpc)) {
                            throw new McpToolException("Cannot address components of this window type.");
                          }
                          target =
                              SwingUtilities.getDeepestComponentAt(
                                  rpc.getRootPane(), args.integer("x"), args.integer("y"));
                          if (target == null) throw new McpToolException("No component at that point.");
                          target.requestFocusInWindow();
                        } else {
                          target = w.getFocusOwner();
                          if (target == null) target = w.getMostRecentFocusOwner();
                          if (target == null) {
                            throw new McpToolException("The window has no focused component; give x/y.");
                          }
                        }
                        info.put("component", target.getClass().getSimpleName());
                        final var text = args.str("text", null);
                        if (text != null) {
                          if (!(target instanceof JTextComponent tc)) {
                            throw new McpToolException(
                                "'text' needs a text component, found " + target.getClass().getSimpleName() + ".");
                          }
                          if (!tc.isEditable()) throw new McpToolException("The text component is not editable.");
                          tc.replaceSelection(text);
                        }
                        final var keys = new ArrayList<int[]>();
                        for (final var k : args.list("keys", List.of())) {
                          keys.add(ViewGuiTools.parseKey(String.valueOf(k)));
                        }
                        final var snap = snapshot();
                        final var c = target;
                        if (!keys.isEmpty()) {
                          SwingUtilities.invokeLater(
                              () -> {
                                for (final var k : keys) {
                                  final var now = System.currentTimeMillis();
                                  final var ch = (char) k[2];
                                  c.dispatchEvent(new KeyEvent(c, KeyEvent.KEY_PRESSED, now, k[1], k[0], ch));
                                  final var ctrl =
                                      InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK | InputEvent.ALT_DOWN_MASK;
                                  if (ch != KeyEvent.CHAR_UNDEFINED && (k[1] & ctrl) == 0) {
                                    c.dispatchEvent(
                                        new KeyEvent(c, KeyEvent.KEY_TYPED, now, k[1], KeyEvent.VK_UNDEFINED, ch));
                                  }
                                  c.dispatchEvent(new KeyEvent(c, KeyEvent.KEY_RELEASED, now, k[1], k[0], ch));
                                }
                              });
                        }
                        return snap;
                      });
              ViewSupport.sleep(Math.max(0, Math.min(10000, args.integer("waitMs", 200))));
              final var out = ctx.onModelThread(() -> report(ctx, before));
              out.put("target", info);
              return out;
            });

    registry
        .tool("view_dialog_list")
        .description(
            "Lists the dialogs currently showing (modal message/confirm dialogs, file choosers,"
                + " custom dialogs) with their id, title, message texts, text fields (index,"
                + " label, text), toggles, combo boxes and buttons, so you can answer them with"
                + " view_dialog_close. Works while a modal dialog blocks the GUI. Returns an"
                + " empty list headless (dialogs are then intercepted and reported in tool"
                + " results instead).")
        .readOnly()
        .offModelThread()
        .handler(
            (ctx, args) -> {
              if (!ctx.isGui()) return Map.of("dialogs", List.of());
              return ctx.onModelThread(
                  () -> {
                    final var out = new ArrayList<Object>();
                    for (final var d : dialogs()) out.add(describeDialog(ctx, d));
                    return Map.of("dialogs", out);
                  });
            });

    registry
        .tool("view_dialog_close")
        .description(
            "GUI only. Answers or closes a showing dialog (default: the active/topmost one). In"
                + " order: fills 'fields' (text field index or label -> text), sets 'toggles'"
                + " (checkbox/radio label -> true/false), selects 'combos' (index or label -> item"
                + " text), for a file chooser selects 'file' (path) and approves it, then presses"
                + " 'button' (label such as 'OK', 'Cancel', 'Yes', 'No', 'Save'; matching ignores"
                + " case). Without 'button' and 'file' the dialog is closed like with its window"
                + " close button. The press is asynchronous; the result reports dialogs still open"
                + " (a press may open a follow-up dialog, e.g. confirm overwrite).")
        .input(
            Schema.object()
                .prop("dialog", Schema.string("Dialog id (from view_dialog_list) or part of its title."))
                .prop("fields", Schema.object("Text field index or label -> new text."))
                .prop("toggles", Schema.object("Checkbox/radio label -> selected state."))
                .prop("combos", Schema.object("Combo box index or label -> item text to select."))
                .prop("file", Schema.string("File path to choose in a file chooser (then approved)."))
                .prop("button", Schema.string("Label of the button to press."))
                .prop("waitMs", Schema.integer("Time to wait before reporting (ms).").with("default", 300)))
        .offModelThread()
        .handler(
            (ctx, args) -> {
              ViewSupport.requireGui(ctx);
              final var before =
                  ctx.onModelThread(
                      () -> {
                        final var w = findDialog(args);
                        final var snap = snapshot();
                        final var fieldsArg = args.map("fields", Map.of());
                        if (!fieldsArg.isEmpty()) {
                          final var fields = new ArrayList<JTextComponent>();
                          for (final var t : ViewSupport.findAll(w, JTextComponent.class)) {
                            if (t.isShowing() && t.isEditable()) fields.add(t);
                          }
                          for (final var e : fieldsArg.entrySet()) {
                            ((JTextComponent) findField(fields, e.getKey())).setText(String.valueOf(e.getValue()));
                          }
                        }
                        for (final var e : args.map("toggles", Map.of()).entrySet()) {
                          final var b = findButton(w, e.getKey());
                          final var want = new Args(Map.of("v", e.getValue())).bool("v");
                          if (b.isSelected() != want) b.doClick(0);
                        }
                        final var combosArg = args.map("combos", Map.of());
                        if (!combosArg.isEmpty()) {
                          final var combos = new ArrayList<JComboBox<?>>();
                          for (final var cb : ViewSupport.findAll(w, JComboBox.class)) {
                            if (cb.isShowing()) combos.add(cb);
                          }
                          for (final var e : combosArg.entrySet()) {
                            final var cb = (JComboBox<?>) findField(combos, e.getKey());
                            var ok = false;
                            final var want = String.valueOf(e.getValue());
                            for (var k = 0; k < cb.getItemCount(); k++) {
                              final var item = cb.getItemAt(k);
                              if (comboText(cb, item).equalsIgnoreCase(want)
                                  || String.valueOf(item).equalsIgnoreCase(want)) {
                                cb.setSelectedIndex(k);
                                ok = true;
                                break;
                              }
                            }
                            if (!ok) throw new McpToolException("Combo box has no item '" + e.getValue() + "'.");
                          }
                        }
                        if (args.has("file")) {
                          final var chooser = ViewSupport.find(w, JFileChooser.class);
                          if (chooser == null) throw new McpToolException("This dialog is not a file chooser.");
                          final var f = new File(args.str("file")).getAbsoluteFile();
                          if (f.getParentFile() != null) chooser.setCurrentDirectory(f.getParentFile());
                          chooser.setSelectedFile(f);
                          SwingUtilities.invokeLater(chooser::approveSelection);
                        } else if (args.has("button")) {
                          final var b = findButton(w, args.str("button"));
                          if (!b.isEnabled()) throw new McpToolException("Button '" + args.str("button") + "' is disabled.");
                          SwingUtilities.invokeLater(() -> b.doClick(0));
                        } else {
                          SwingUtilities.invokeLater(
                              () -> w.dispatchEvent(new WindowEvent(w, WindowEvent.WINDOW_CLOSING)));
                        }
                        return snap;
                      });
              ViewSupport.sleep(Math.max(0, Math.min(10000, args.integer("waitMs", 300))));
              return ctx.onModelThread(() -> report(ctx, before));
            });
  }
}
