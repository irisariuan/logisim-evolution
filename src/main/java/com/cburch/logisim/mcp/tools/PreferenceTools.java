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

import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.Options;
import com.cburch.logisim.gui.opts.OptionsActions;
import com.cburch.logisim.gui.opts.ToolbarActions;
import com.cburch.logisim.mcp.Args;
import com.cburch.logisim.mcp.ComponentRefs;
import com.cburch.logisim.mcp.McpContext;
import com.cburch.logisim.mcp.McpToolException;
import com.cburch.logisim.mcp.McpToolGroup;
import com.cburch.logisim.mcp.McpToolRegistry;
import com.cburch.logisim.mcp.Schema;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.prefs.PrefMonitor;
import com.cburch.logisim.prefs.PrefMonitorKeyStroke;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.tools.Tool;
import com.cburch.logisim.util.InputEventUtil;
import com.cburch.logisim.util.LocaleManager;
import java.awt.event.InputEvent;
import java.io.File;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import javax.swing.KeyStroke;
import javax.swing.UIManager;

/**
 * Project options (Project ▸ Options: simulation, toolbar, mouse) and application preferences
 * (File ▸ Preferences).
 *
 * <p>Project options are stored in the .circ file and changed through undoable actions, like the
 * Options window does. Preferences are global to the user (java.util.prefs) and not undoable.
 */
public final class PreferenceTools implements McpToolGroup {

  // ---------------------------------------------------------------------------------------------
  // Project options
  // ---------------------------------------------------------------------------------------------

  /** Friendly aliases for the option attribute names stored in the .circ file. */
  private static final Map<String, String> OPTION_ALIASES =
      Map.of(
          "simulationlimit", "simlimit",
          "iterationsuntiloscillation", "simlimit",
          "oscillationlimit", "simlimit",
          "randomnoise", "simrand",
          "randomness", "simrand",
          "gateundefinedbehavior", "gateUndefined",
          "gateundefined", "gateUndefined");

  private static final Map<String, String> OPTION_HINTS =
      Map.of(
          "simlimit",
          "Iterations until oscillation: number of propagation steps after which the simulator"
              + " reports an oscillation. The Options window offers 200, 500, 1000 (default), 2000,"
              + " 5000, 10000, 20000 and 50000.",
          "simrand",
          "Random noise added to component delays: 0 = off (default); the Options window's 'Add"
              + " noise to component delays' checkbox uses 32. true/false are accepted.",
          "gateUndefined",
          "Gate output when input is undefined: 'ignore' (default, ignore undefined/floating"
              + " inputs) or 'error'.");

  private static Map<String, Object> describeOptions(Project proj) {
    final var attrs = proj.getLogisimFile().getOptions().getAttributeSet();
    final var out = new LinkedHashMap<String, Object>();
    final var list = new ArrayList<Object>();
    for (final var attr : attrs.getAttributes()) {
      final var o = new LinkedHashMap<String, Object>();
      o.put("name", attr.getName());
      o.put("displayName", attr.getDisplayName());
      final Object value = attrs.getValue(attr);
      o.put("value", value instanceof Integer i ? i : ComponentRefs.standardString(attrs, attr));
      final var choices = optionChoices(attr);
      if (choices != null) o.put("choices", choices);
      if (OPTION_HINTS.containsKey(attr.getName())) o.put("description", OPTION_HINTS.get(attr.getName()));
      list.add(o);
    }
    out.put("options", list);
    return out;
  }

  private static List<String> optionChoices(Attribute<?> attr) {
    if (attr == Options.ATTR_GATE_UNDEFINED) {
      return List.of(Options.GATE_UNDEFINED_IGNORE.getValue().toString(),
          Options.GATE_UNDEFINED_ERROR.getValue().toString());
    }
    return null;
  }

  private static Attribute<?> findOption(AttributeSet attrs, String key) {
    final var name = OPTION_ALIASES.getOrDefault(key.toLowerCase(Locale.ROOT).replace("_", ""), key);
    for (final var attr : attrs.getAttributes()) {
      if (attr.getName().equalsIgnoreCase(name) || attr.getDisplayName().equalsIgnoreCase(key)) {
        return attr;
      }
    }
    final var names = new ArrayList<String>();
    for (final var attr : attrs.getAttributes()) names.add(attr.getName());
    throw new McpToolException("Unknown project option '" + key + "'. Known: " + names);
  }

  private static Object parseOption(Attribute<?> attr, Object value) {
    if (attr == Options.ATTR_SIM_RAND && value instanceof Boolean b) {
      return b ? Options.SIM_RAND_DFLT : 0;
    }
    if (attr == Options.ATTR_SIM_RAND && ("true".equals(value) || "false".equals(value))) {
      return "true".equals(value) ? Options.SIM_RAND_DFLT : 0;
    }
    final var text = value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
        ? Long.toString(n.longValue())
        : String.valueOf(value);
    Object parsed;
    try {
      parsed = attr.parse(text);
    } catch (RuntimeException e) {
      parsed = null;
    }
    if (parsed == null) {
      throw new McpToolException("Invalid value '" + value + "' for option '" + attr.getName() + "'.");
    }
    if (attr == Options.ATTR_SIM_LIMIT && (Integer) parsed <= 0) {
      throw new McpToolException("simlimit must be a positive number of iterations.");
    }
    if (attr == Options.ATTR_SIM_RAND && (Integer) parsed < 0) {
      throw new McpToolException("simrand must be 0 or positive.");
    }
    return parsed;
  }

  // ---------------------------------------------------------------------------------------------
  // Tools of the project (toolbar, mouse mappings)
  // ---------------------------------------------------------------------------------------------

  /** The library (possibly nested) that provides a tool; the file itself for circuits. */
  private static Library libraryOf(Library lib, Tool tool) {
    for (final var t : lib.getTools()) {
      if (t.sharesSource(tool)) return lib;
    }
    for (final var sub : lib.getLibraries()) {
      final var found = libraryOf(sub, tool);
      if (found != null) return found;
    }
    return null;
  }

  private static Map<String, Object> describeTool(LogisimFile file, Tool tool) {
    final var out = new LinkedHashMap<String, Object>();
    out.put("tool", tool.getName());
    out.put("displayName", tool.getDisplayName());
    final var lib = libraryOf(file, tool);
    out.put("library", lib == null ? null : lib == file ? "(project circuits)" : lib.getName());
    final var attrs = tool.getAttributeSet();
    if (attrs != null && !attrs.getAttributes().isEmpty()) out.put("attributes", ComponentRefs.attributes(attrs));
    return out;
  }

  private static void collectTools(Library lib, List<Object[]> out) {
    for (final var tool : lib.getTools()) out.add(new Object[] {lib, tool});
    for (final var sub : lib.getLibraries()) collectTools(sub, out);
  }

  /**
   * Resolves a tool reference "Name" or "Library/Name" (tool name or display name,
   * case-insensitive) among the project's circuits and libraries.
   */
  static Tool findTool(LogisimFile file, String ref) {
    String libName = null;
    var name = ref.trim();
    final var slash = name.lastIndexOf('/');
    if (slash > 0) {
      libName = name.substring(0, slash).trim();
      name = name.substring(slash + 1).trim();
    }
    final var all = new ArrayList<Object[]>();
    collectTools(file, all);
    for (var pass = 0; pass < 2; pass++) {
      for (final var pair : all) {
        final var lib = (Library) pair[0];
        final var tool = (Tool) pair[1];
        if (libName != null && !lib.getName().equalsIgnoreCase(libName)
            && !lib.getDisplayName().equalsIgnoreCase(libName)) {
          continue;
        }
        final var match = pass == 0 ? tool.getName().equalsIgnoreCase(name)
            : tool.getDisplayName().equalsIgnoreCase(name);
        if (match) return tool;
      }
    }
    throw new McpToolException(
        "Unknown tool '" + ref + "'. Use a tool name from library_list(includeTools=true), e.g."
            + " 'Edit Tool', 'Poke Tool', 'Wiring Tool', 'Text Tool', 'AND Gate' or 'Gates/AND Gate',"
            + " or the name of a circuit of the project.");
  }

  /** Creates a configured copy of a tool, as the Options window does. */
  private static Tool cloneTool(Tool tool, Map<String, Object> attributes) {
    final var copy = tool.cloneTool();
    if (attributes != null && !attributes.isEmpty()) {
      final var attrs = copy.getAttributeSet();
      if (attrs == null) throw new McpToolException("Tool '" + tool.getName() + "' has no attributes.");
      for (final var entry : attributes.entrySet()) {
        Attribute<?> found = null;
        for (final var attr : attrs.getAttributes()) {
          if (attr.getName().equals(entry.getKey()) || attr.getDisplayName().equalsIgnoreCase(entry.getKey())) {
            found = attr;
          }
        }
        if (found == null) {
          throw new McpToolException("Tool '" + tool.getName() + "' has no attribute '" + entry.getKey() + "'.");
        }
        setParsed(attrs, found, String.valueOf(entry.getValue()));
      }
    }
    return copy;
  }

  private static <V> void setParsed(AttributeSet attrs, Attribute<V> attr, String text) {
    final V value;
    try {
      value = attr.parse(text);
    } catch (RuntimeException e) {
      throw new McpToolException("Invalid value '" + text + "' for attribute '" + attr.getName() + "'.");
    }
    attrs.setValue(attr, value);
  }

  private static Map<String, Object> describeToolbar(McpContext ctx, Project proj) {
    final var file = proj.getLogisimFile();
    final var items = new ArrayList<Object>();
    final var contents = file.getOptions().getToolbarData().getContents();
    for (var i = 0; i < contents.size(); i++) {
      final var tool = contents.get(i);
      final Map<String, Object> item;
      if (tool == null) {
        item = new LinkedHashMap<>();
        item.put("separator", true);
      } else {
        item = describeTool(file, tool);
      }
      final var withIndex = new LinkedHashMap<String, Object>();
      withIndex.put("index", i);
      withIndex.putAll(item);
      items.add(withIndex);
    }
    return Map.of("project", ctx.projectId(proj), "toolbar", items);
  }

  private static final String[][] MOD_TOKENS = {
    {"ctrl", InputEventUtil.CTRL}, {"control", InputEventUtil.CTRL}, {"shift", InputEventUtil.SHIFT},
    {"alt", InputEventUtil.ALT}, {"button1", InputEventUtil.BUTTON1}, {"left", InputEventUtil.BUTTON1},
    {"button2", InputEventUtil.BUTTON2}, {"middle", InputEventUtil.BUTTON2},
    {"button3", InputEventUtil.BUTTON3}, {"right", InputEventUtil.BUTTON3},
  };

  static int parseModifiers(String text) {
    final var normalized = new StringBuilder();
    for (final var tok : text.trim().split("[\\s+,-]+")) {
      if (tok.isEmpty()) continue;
      String found = null;
      for (final var pair : MOD_TOKENS) {
        if (pair[0].equalsIgnoreCase(tok)) found = pair[1];
      }
      if (found == null) {
        throw new McpToolException("Unknown modifier '" + tok + "' in '" + text
            + "'. Use Ctrl, Shift, Alt and one of Button1 (left), Button2 (middle), Button3 (right).");
      }
      normalized.append(found).append(' ');
    }
    final var mods = InputEventUtil.fromString(normalized.toString());
    final var buttons = InputEvent.BUTTON1_DOWN_MASK | InputEvent.BUTTON2_DOWN_MASK | InputEvent.BUTTON3_DOWN_MASK;
    if ((mods & buttons) == 0) {
      throw new McpToolException("A mouse mapping needs a mouse button (Button1, Button2 or Button3): " + text);
    }
    return mods;
  }

  private static Map<String, Object> describeMouse(McpContext ctx, Project proj) {
    final var file = proj.getLogisimFile();
    final var sorted = new TreeMap<Integer, Tool>(file.getOptions().getMouseMappings().getMappings());
    final var list = new ArrayList<Object>();
    for (final var entry : sorted.entrySet()) {
      final var m = new LinkedHashMap<String, Object>();
      m.put("modifiers", InputEventUtil.toString(entry.getKey()));
      m.putAll(describeTool(file, entry.getValue()));
      list.add(m);
    }
    return Map.of("project", ctx.projectId(proj), "mappings", list);
  }

  // ---------------------------------------------------------------------------------------------
  // Application preferences
  // ---------------------------------------------------------------------------------------------

  /** One preference: the AppPreferences field name and its monitor. */
  record Pref(String field, PrefMonitor<?> monitor) {
    String key() {
      return monitor.getIdentifier();
    }
  }

  private static List<Pref> prefs;

  /** All PrefMonitor constants of AppPreferences, in declaration order. */
  static synchronized List<Pref> allPrefs() {
    if (prefs == null) {
      final var list = new ArrayList<Pref>();
      for (final var field : AppPreferences.class.getFields()) {
        if (!Modifier.isStatic(field.getModifiers())) continue;
        if (!PrefMonitor.class.isAssignableFrom(field.getType())) continue;
        try {
          final var monitor = (PrefMonitor<?>) field.get(null);
          if (monitor != null) list.add(new Pref(field.getName(), monitor));
        } catch (IllegalAccessException e) {
          // not accessible: skip
        }
      }
      prefs = list;
    }
    return prefs;
  }

  /** Localized labels of the File ▸ Preferences window (key of gui strings) per preference key. */
  private static final Map<String, String> LABELS = Map.ofEntries(
      Map.entry("afterAdd", "layoutAddAfter"),
      Map.entry("showGhosts", "layoutAddShowGhosts"),
      Map.entry("attributeHalo", "layoutAttributeHalo"),
      Map.entry("AntiAliassing", "layoutAntiAliasing"),
      Map.entry("uiAntiAliasing", "layoutAntiAliasing"),
      Map.entry("canvasPlacement", "windowCanvasLocation"),
      Map.entry("componentTips", "layoutShowTips"),
      Map.entry("defaultAppearance", "layoutDefaultAppearance"),
      Map.entry("FollowSystemTheme", "windowFollowSystemTheme"),
      Map.entry("gateShape", "intlGateShape"),
      Map.entry("graphicsAcceleration", "accelLabel"),
      Map.entry("keepConnected", "layoutMoveKeepConnect"),
      Map.entry("namedBoxesFixed", "layoutNamedCircuitBoxesFixedSize"),
      Map.entry("oldIO", "layoutUseNewInputOutputSymbols"),
      Map.entry("pinAppearance", "layoutPinAppearance"),
      Map.entry("recenterOnDoubleMiddleMouse", "windowRecenterOnDoubleMiddleMouse"),
      Map.entry("searchDoubleShift", "windowSearchDoubleShift"),
      Map.entry("showTickRate", "windowTickRate"),
      Map.entry("simQueue", "simQueueLabel"),
      Map.entry("toolbarPlacement", "windowToolbarLocation"),
      Map.entry("useHeaderBar", "windowUseHeaderBar"),
      Map.entry("locale", "intlLocale"));

  /** Extra explanations (English) for preferences whose meaning is not obvious from the key. */
  private static final Map<String, String> HINTS = Map.ofEntries(
      Map.entry("gateShape", "Gate symbol style: 'shaped' = ANSI/US shapes, 'rectangular' = IEC boxes."),
      Map.entry("locale", "User interface language as a language code, e.g. 'en', 'de', 'zh';"
          + " empty = system default."),
      Map.entry("autosaveEnabled", "Periodically save open projects to autosave files."),
      Map.entry("autosaveInterval", "Autosave interval in seconds."),
      Map.entry("LookAndFeel", "Swing look & feel class name of the user interface."),
      Map.entry("Scale", "User interface scale factor (1.0 = 100%)."),
      Map.entry("oldIO", "Use the new input/output pin symbols (true) or the classic ones (false)."),
      Map.entry("afterAdd", "Tool selected after placing a component: 'edit' (Edit tool) or"
          + " 'unchanged' (keep the component tool)."),
      Map.entry("tickFrequency", "Clock tick frequency in Hz used by Simulate ▸ Ticks Enabled."),
      Map.entry("hdlType", "HDL used for generated code: VHDL or Verilog."),
      Map.entry("removeUnusedLibs", "Remove unused libraries when saving a project."),
      Map.entry("memoryStartupUnknown", "Memories start with unknown (undefined) contents."),
      Map.entry("FPGAWorkspace", "Directory where FPGA/HDL generation writes its files."));

  /** Preferences that only take full effect after restarting Logisim. */
  private static final List<String> RESTART =
      List.of("LookAndFeel", "FollowSystemTheme", "LightLookAndFeel", "DarkLookAndFeel", "Scale",
          "graphicsAcceleration", "AppFont", "uiAntiAliasing", "useHeaderBar");

  private static String type(PrefMonitor<?> monitor) {
    if (monitor instanceof PrefMonitorKeyStroke) return "keystroke";
    if (monitor.getChoices() != null || monitor == AppPreferences.LOCALE) return "choice";
    Object v = monitor.get();
    if (v == null) v = monitor.getDefault();
    if (v instanceof Boolean) return "boolean";
    if (v instanceof Integer) return "integer";
    if (v instanceof Double) return "number";
    return "string";
  }

  private static boolean isColor(Pref pref) {
    return pref.field().endsWith("_COLOR") && "integer".equals(type(pref.monitor()));
  }

  private static Object jsonValue(PrefMonitor<?> monitor, Object value) {
    if (value instanceof KeyStroke ks) return ks.toString().replace("pressed ", "");
    return value;
  }

  private static String humanize(String field) {
    final var words = field.replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ').toLowerCase(Locale.ROOT);
    return Character.toUpperCase(words.charAt(0)) + words.substring(1);
  }

  private static Locale[] localeOptions() {
    return com.cburch.logisim.gui.Strings.S.getLocaleOptions();
  }

  private static Map<String, Object> describePref(Pref pref) {
    final var monitor = pref.monitor();
    final var out = new LinkedHashMap<String, Object>();
    final var key = pref.key();
    out.put("key", key);
    out.put("type", type(monitor));
    out.put("value", jsonValue(monitor, monitor.get()));
    out.put("default", jsonValue(monitor, monitor.getDefault()));
    if (monitor.getChoices() != null) out.put("choices", monitor.getChoices());
    if ("locale".equals(key)) {
      final var langs = new ArrayList<String>();
      for (final var loc : localeOptions()) langs.add(loc.toLanguageTag());
      out.put("choices", langs);
    }
    if (isColor(pref)) {
      out.put("hex", String.format("#%08X", (Integer) monitor.get()));
    }
    if (monitor instanceof PrefMonitorKeyStroke ks) {
      out.put("display", ks.getDisplayString());
      if (!ks.canModify()) out.put("readOnly", true);
    }
    var description = HINTS.get(key);
    final var label = LABELS.get(key);
    String localized = null;
    if (label != null) {
      localized = com.cburch.logisim.gui.Strings.S.get(label).replaceAll(":\\s*$", "").trim();
    }
    if (description == null) description = localized != null ? localized : humanize(pref.field());
    out.put("description", description);
    if (localized != null && !localized.equals(description)) out.put("label", localized);
    if (RESTART.contains(key)) out.put("note", "Takes full effect after restarting Logisim.");
    return out;
  }

  private static final String TEMPLATE_KEY = "template";

  private static Map<String, Object> describeTemplate() {
    final var out = new LinkedHashMap<String, Object>();
    out.put("key", TEMPLATE_KEY);
    out.put("type", "choice");
    final var type = AppPreferences.getTemplateType();
    out.put("value", switch (type) {
      case AppPreferences.TEMPLATE_EMPTY -> "empty";
      case AppPreferences.TEMPLATE_CUSTOM -> "custom";
      case AppPreferences.TEMPLATE_PLAIN -> "plain";
      default -> "unknown";
    });
    out.put("default", "plain");
    out.put("choices", List.of("plain", "empty", "custom"));
    final var f = AppPreferences.getTemplateFile();
    out.put("file", f == null ? null : f.getAbsolutePath());
    out.put("description", "Template for new projects: 'plain' (built-in default), 'empty', or"
        + " 'custom' (a .circ file given as 'file').");
    return out;
  }

  private static Pref findPref(String key) {
    for (final var p : allPrefs()) if (p.key().equals(key)) return p;
    for (final var p : allPrefs()) {
      if (p.key().equalsIgnoreCase(key) || p.field().equalsIgnoreCase(key)) return p;
    }
    throw new McpToolException("Unknown preference '" + key + "'. Use prefs_list to see all keys.");
  }

  private static Object describeKey(String key) {
    if (TEMPLATE_KEY.equalsIgnoreCase(key)) return describeTemplate();
    return describePref(findPref(key));
  }

  /** Marker: the preference is updated synchronously, no need to wait. */
  private static final Object NO_WAIT = new Object();

  /** Result of a preference change: a note for the client and the value to wait for. */
  record Outcome(String note, Object expected) {}

  /**
   * Some monitors (booleans, doubles, the template) only write the backing store and pick up the
   * new value from java.util.prefs' asynchronous change event. Waits (off the model thread) until
   * {@code done} holds, at most two seconds.
   */
  private static void awaitPrefs(McpContext ctx, BooleanSupplier done) {
    final var deadline = System.currentTimeMillis() + 2000;
    while (!ctx.onModelThread(done::getAsBoolean) && System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private static Map<String, Object> withNote(Map<String, Object> desc, String note) {
    final var out = new LinkedHashMap<String, Object>();
    out.put("preference", desc);
    if (note != null) out.put("note", note);
    return out;
  }

  private static String resetPref(Pref pref) {
    if (pref.monitor() == AppPreferences.LOCALE) {
      AppPreferences.getPrefs().remove(pref.key());
      return "The language preference was removed; the system language is used after a restart.";
    }
    if (pref.monitor().getDefault() == null && !(pref.monitor() instanceof PrefMonitorKeyStroke)) {
      // No default value to store: remove the entry so that the built-in fallback applies.
      AppPreferences.getPrefs().remove(pref.key());
    } else {
      pref.monitor().restoreDefault();
    }
    if (pref.monitor() instanceof PrefMonitorKeyStroke) AppPreferences.hotkeySync();
    return RESTART.contains(pref.key()) ? "Takes full effect after restarting Logisim." : null;
  }

  @SuppressWarnings("unchecked")
  private static Outcome setPref(Pref pref, Object value) {
    final var monitor = (PrefMonitor<Object>) pref.monitor();
    final var key = pref.key();
    final var type = type(monitor);
    final var a = new Args(value == null ? Map.of() : Map.of("value", value));
    if (pref.monitor() == AppPreferences.LOCALE) {
      final var tag = a.str("value");
      Locale found = null;
      for (final var loc : localeOptions()) {
        if (loc.toLanguageTag().equalsIgnoreCase(tag) || loc.getLanguage().equalsIgnoreCase(tag)) found = loc;
      }
      if (found == null) throw new McpToolException("Unsupported locale '" + tag + "'. See prefs_get(['locale']).");
      LocaleManager.setLocale(found);
      monitor.set(found.getLanguage());
      return new Outcome("The user interface language was changed; open windows update their"
          + " texts, some only after a restart.", NO_WAIT);
    }
    Object expected = NO_WAIT;
    switch (type) {
      case "boolean" -> {
        expected = a.bool("value");
        monitor.set(expected);
      }
      case "integer" -> {
        if (value instanceof String s && s.startsWith("#")) {
          final int color;
          try {
            if (s.length() != 7 && s.length() != 9) throw new NumberFormatException();
            color = (int) Long.parseLong(s.substring(1), 16) | (s.length() == 7 ? 0xFF000000 : 0);
          } catch (NumberFormatException e) {
            throw new McpToolException("Invalid color '" + s + "'. Use #RRGGBB or #AARRGGBB.");
          }
          monitor.set(color);
        } else {
          monitor.set((int) a.lng("value"));
        }
      }
      case "number" -> {
        expected = a.dbl("value");
        monitor.set(expected);
      }
      case "keystroke" -> {
        final var ks = (PrefMonitorKeyStroke) pref.monitor();
        if (!ks.canModify()) throw new McpToolException("Hotkey '" + key + "' cannot be changed.");
        if (value == null || value.toString().isBlank()) {
          ks.set((KeyStroke) null);
        } else {
          final var stroke = KeyStroke.getKeyStroke(value.toString().trim());
          if (stroke == null) {
            throw new McpToolException("Invalid keystroke '" + value + "'. Use the javax.swing.KeyStroke"
                + " format, e.g. 'ctrl E', 'shift F9', 'ctrl shift Z' (letters upper case).");
          }
          if (!ks.metaCheckPass(stroke.getModifiers())) {
            throw new McpToolException("Hotkey '" + key + "' must use the menu shortcut modifier.");
          }
          ks.set(stroke);
        }
        AppPreferences.hotkeySync();
      }
      case "choice" -> {
        final var s = a.str("value");
        if (!monitor.getChoices().contains(s)) {
          throw new McpToolException("Invalid value '" + s + "' for '" + key + "'. Choices: " + monitor.getChoices());
        }
        monitor.set(s);
      }
      default -> {
        if ("LookAndFeel".equals(key) || key.endsWith("LookAndFeel")) {
          final var cls = a.str("value");
          final var known = new ArrayList<String>();
          for (final var info : UIManager.getInstalledLookAndFeels()) known.add(info.getClassName());
          if (!known.contains(cls)) {
            try {
              Class.forName(cls);
            } catch (ClassNotFoundException e) {
              throw new McpToolException("Unknown look & feel class '" + cls + "'. Installed: " + known);
            }
          }
        }
        monitor.set(value == null ? null : value.toString());
      }
    }
    // Preferences that need a restart carry a 'note' in their description already.
    return new Outcome(null, expected);
  }

  private static String setTemplate(Object value, String file) {
    final var v = String.valueOf(value).toLowerCase(Locale.ROOT);
    switch (v) {
      case "plain" -> AppPreferences.setTemplateType(AppPreferences.TEMPLATE_PLAIN);
      case "empty" -> AppPreferences.setTemplateType(AppPreferences.TEMPLATE_EMPTY);
      case "custom" -> {
        if (file == null && AppPreferences.getTemplateFile() == null) {
          throw new McpToolException("template=custom needs 'file' (a readable .circ file).");
        }
        if (file != null) {
          final var f = new File(file).getAbsoluteFile();
          if (!f.canRead()) throw new McpToolException("Cannot read template file " + f);
          AppPreferences.setTemplateFile(f);
        }
        AppPreferences.setTemplateType(AppPreferences.TEMPLATE_CUSTOM);
      }
      default -> throw new McpToolException("template must be plain, empty or custom.");
    }
    return "Applies to projects created from now on.";
  }

  // ---------------------------------------------------------------------------------------------
  // Registration
  // ---------------------------------------------------------------------------------------------

  @Override
  public void register(McpToolRegistry registry) {
    registerOptions(registry);
    registerToolbarAndMouse(registry);
    registerPrefs(registry);
  }

  private void registerOptions(McpToolRegistry registry) {
    registry
        .tool("options_get")
        .description(
            "Returns the project's simulation options (Project ▸ Options ▸ Simulation), stored in"
                + " the .circ file: 'simlimit' (iterations until oscillation), 'simrand' (random"
                + " noise added to component delays, 0 = off) and 'gateUndefined' (gate output"
                + " when input is undefined: ignore|error). Each entry has name, displayName,"
                + " value, choices (if restricted) and a description.")
        .input(Schema.object().prop("project", PROJECT_ARG))
        .readOnly()
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var out = new LinkedHashMap<String, Object>();
              out.put("project", ctx.projectId(proj));
              out.putAll(describeOptions(proj));
              return out;
            });

    registry
        .tool("options_set")
        .description(
            "Changes project simulation options (Project ▸ Options ▸ Simulation). 'values' maps"
                + " option names to new values, e.g. {\"simlimit\": 5000, \"simrand\": true,"
                + " \"gateUndefined\": \"error\"}. Aliases: simulationLimit, randomNoise,"
                + " gateUndefinedBehavior. Each change is an undoable action and marks the project"
                + " dirty; save the project to store them in the .circ file. Returns all options.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("values", Schema.object("Option name → new value.").additionalProperties(true), true))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var attrs = proj.getLogisimFile().getOptions().getAttributeSet();
              final var values = args.map("values");
              if (values.isEmpty()) throw new McpToolException("'values' is empty.");
              final var parsed = new LinkedHashMap<Attribute<?>, Object>();
              for (final var e : values.entrySet()) {
                final var attr = findOption(attrs, e.getKey());
                parsed.put(attr, parseOption(attr, e.getValue()));
              }
              final var changed = new ArrayList<String>();
              for (final var e : parsed.entrySet()) {
                final var action = OptionsActions.setAttribute(attrs, e.getKey(), e.getValue());
                if (action != null) {
                  proj.doAction(action);
                  changed.add(e.getKey().getName());
                }
              }
              final var out = new LinkedHashMap<String, Object>();
              out.put("project", ctx.projectId(proj));
              out.put("changed", changed);
              out.putAll(describeOptions(proj));
              return out;
            });
  }

  private void registerToolbarAndMouse(McpToolRegistry registry) {
    registry
        .tool("toolbar_get")
        .description(
            "Returns the project's toolbar (Project ▸ Options ▸ Toolbar), stored in the .circ file:"
                + " a list of items with 'index' and either separator=true or tool, displayName,"
                + " library and the tool's preset attributes.")
        .input(Schema.object().prop("project", PROJECT_ARG))
        .readOnly()
        .handler((ctx, args) -> describeToolbar(ctx, ctx.project(args)));

    final var op =
        Schema.object("One toolbar edit.")
            .prop("op", Schema.enumeration("Operation.", "add", "separator", "remove", "move"), true)
            .prop("tool", Schema.string("For 'add': tool name, optionally 'Library/Tool' (e.g. 'Poke Tool',"
                + " 'Gates/AND Gate', or a circuit name)."))
            .prop("attributes", Schema.object("For 'add': attribute presets of the toolbar tool, e.g."
                + " {\"inputs\": \"3\"} (attribute names as in component attribute listings).")
                .additionalProperties(true))
            .prop("index", Schema.integer("For 'add'/'separator': insert position (default: end). For"
                + " 'remove': index of the item to remove."))
            .prop("from", Schema.integer("For 'move': current index."))
            .prop("to", Schema.integer("For 'move': new index."));
    registry
        .tool("toolbar_set")
        .description(
            "Edits the project's toolbar like Project ▸ Options ▸ Toolbar. 'operations' are applied"
                + " in order; indexes refer to the toolbar as it is after the previous operations:"
                + " add (append or insert a tool), separator (insert a separator), remove (tool or"
                + " separator at index), move (from → to). Every operation is undoable and is saved"
                + " in the .circ file. Returns the new toolbar.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("operations", Schema.array("Edits to apply in order.", op), true))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var file = proj.getLogisimFile();
              final var data = file.getOptions().getToolbarData();
              for (final var o : args.list("operations")) {
                final var a = Args.of(o);
                final var size = data.size();
                switch (a.str("op")) {
                  case "add" -> {
                    final var tool = cloneTool(findTool(file, a.str("tool")), a.map("attributes", null));
                    final var index = a.integer("index", size);
                    if (index < 0 || index > size) throw new McpToolException("index out of range 0.." + size);
                    proj.doAction(ToolbarActions.addTool(data, tool));
                    if (index != size) proj.doAction(ToolbarActions.moveTool(data, size, index));
                  }
                  case "separator" -> {
                    final var index = a.integer("index", size);
                    if (index < 0 || index > size) throw new McpToolException("index out of range 0.." + size);
                    proj.doAction(ToolbarActions.addSeparator(data, index));
                  }
                  case "remove" -> {
                    final var index = a.integer("index");
                    if (index < 0 || index >= size) throw new McpToolException("index out of range 0.." + (size - 1));
                    proj.doAction(ToolbarActions.removeTool(data, index));
                  }
                  case "move" -> {
                    final var from = a.integer("from");
                    final var to = a.integer("to");
                    if (from < 0 || from >= size || to < 0 || to >= size) {
                      throw new McpToolException("from/to out of range 0.." + (size - 1));
                    }
                    if (from != to) proj.doAction(ToolbarActions.moveTool(data, from, to));
                  }
                  default -> throw new McpToolException("Unknown op '" + a.str("op") + "'.");
                }
              }
              return describeToolbar(ctx, proj);
            });

    registry
        .tool("mouse_get")
        .description(
            "Returns the project's mouse mappings (Project ▸ Options ▸ Mouse), stored in the .circ"
                + " file: which tool a mouse button with modifier keys activates on the canvas."
                + " Each mapping has 'modifiers' (e.g. 'Button1', 'Ctrl Button1', 'Shift Button3'),"
                + " tool, displayName, library and attributes.")
        .input(Schema.object().prop("project", PROJECT_ARG))
        .readOnly()
        .handler((ctx, args) -> describeMouse(ctx, ctx.project(args)));

    registry
        .tool("mouse_set")
        .description(
            "Sets or removes a mouse mapping (Project ▸ Options ▸ Mouse). 'modifiers' is a"
                + " combination of Ctrl, Shift, Alt and exactly one mouse button Button1 (left),"
                + " Button2 (middle), Button3 (right), e.g. 'Ctrl Button1'. Give 'tool' (name or"
                + " 'Library/Tool', with optional 'attributes' presets) to map it, or tool=null /"
                + " remove=true to delete the mapping. Undoable; saved in the .circ file.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("modifiers", Schema.string("Button and modifier keys, e.g. 'Shift Button1'."), true)
                .prop("tool", Schema.string("Tool to map, e.g. 'Poke Tool', 'Wiring Tool', 'Gates/OR Gate'."))
                .prop("attributes", Schema.object("Attribute presets for the mapped tool.").additionalProperties(true))
                .prop("remove", Schema.bool("Remove the mapping for 'modifiers'.")))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var file = proj.getLogisimFile();
              final var mm = file.getOptions().getMouseMappings();
              final Integer mods = parseModifiers(args.str("modifiers"));
              if (args.bool("remove", false) || !args.has("tool")) {
                if (mm.getToolFor(mods) == null) {
                  throw new McpToolException("There is no mapping for '" + InputEventUtil.toString(mods) + "'.");
                }
                proj.doAction(OptionsActions.removeMapping(mm, mods));
              } else {
                final var tool = cloneTool(findTool(file, args.str("tool")), args.map("attributes", null));
                proj.doAction(OptionsActions.setMapping(mm, mods, tool));
              }
              return describeMouse(ctx, proj);
            });
  }

  private void registerPrefs(McpToolRegistry registry) {
    registry
        .tool("prefs_list")
        .description(
            "Lists the application preferences (File ▸ Preferences; global to the user, not stored"
                + " in projects). Each entry: key, type (boolean|integer|number|string|choice|keystroke),"
                + " value, default, choices (for choice and locale), description, and for colors a"
                + " 'hex' (#AARRGGBB) form, for hotkeys a 'display' form. Also contains the special"
                + " key 'template' (template for new projects). Important keys: gateShape"
                + " (shaped=ANSI, rectangular=IEC), locale, template, afterAdd, autosaveEnabled,"
                + " tickFrequency, hdlType, LookAndFeel.")
        .input(
            Schema.object()
                .prop("filter", Schema.string("Only keys/descriptions containing this text (case-insensitive)."))
                .prop("type", Schema.string("Only preferences of this type, e.g. 'boolean' or 'keystroke'.")))
        .readOnly()
        .handler(
            (ctx, args) -> {
              final var filter = args.str("filter", "").toLowerCase(Locale.ROOT);
              final var type = args.str("type", null);
              final var list = new ArrayList<Object>();
              final var all = new ArrayList<Map<String, Object>>();
              all.add(describeTemplate());
              for (final var p : allPrefs()) all.add(describePref(p));
              for (final var d : all) {
                final var text = (d.get("key") + " " + d.get("description") + " " + d.getOrDefault("label", ""))
                    .toLowerCase(Locale.ROOT);
                if (!filter.isEmpty() && !text.contains(filter)) continue;
                if (type != null && !type.equalsIgnoreCase(String.valueOf(d.get("type")))) continue;
                list.add(d);
              }
              return Map.of("count", list.size(), "preferences", list);
            });

    registry
        .tool("prefs_get")
        .description(
            "Returns one or more application preferences by key (see prefs_list), with value,"
                + " default, type and choices.")
        .input(
            Schema.object()
                .prop("keys", Schema.array("Preference keys, e.g. ['gateShape', 'locale', 'template'].",
                    Schema.string("key")), true))
        .readOnly()
        .handler(
            (ctx, args) -> {
              final var list = new ArrayList<Object>();
              for (final var k : args.list("keys")) list.add(describeKey(String.valueOf(k)));
              return Map.of("preferences", list);
            });

    registry
        .tool("prefs_set")
        .description(
            "Changes an application preference (global, persistent, not undoable). The value is"
                + " validated against the preference's type and choices: booleans true/false,"
                + " integers (colors also as '#RRGGBB' or '#AARRGGBB'), numbers, strings, choices"
                + " from prefs_list, keystrokes in KeyStroke format ('ctrl E', 'shift F9'; empty to"
                + " unassign). Special keys: 'locale' (language code, applied immediately),"
                + " 'template' (plain|empty|custom, with 'file' for custom), 'gateShape'"
                + " (shaped|rectangular). Look & feel, scale, font and graphics acceleration only"
                + " take full effect after a restart; the result says so in 'note'.")
        .input(
            Schema.object()
                .prop("key", Schema.string("Preference key (see prefs_list)."), true)
                .prop("value", Schema.any("New value."), true)
                .prop("file", Schema.string("For key 'template' with value 'custom': template .circ file.")))
        .offModelThread()
        .handler(
            (ctx, args) -> {
              final var key = args.str("key");
              final var value = args.get("value");
              if (TEMPLATE_KEY.equalsIgnoreCase(key)) {
                final var note = ctx.onModelThread(() -> setTemplate(value, args.str("file", null)));
                final var type = switch (String.valueOf(value).toLowerCase(Locale.ROOT)) {
                  case "empty" -> AppPreferences.TEMPLATE_EMPTY;
                  case "custom" -> AppPreferences.TEMPLATE_CUSTOM;
                  default -> AppPreferences.TEMPLATE_PLAIN;
                };
                awaitPrefs(ctx, () -> AppPreferences.getTemplateType() == type);
                return withNote(ctx.onModelThread(PreferenceTools::describeTemplate), note);
              }
              final var pref = ctx.onModelThread(() -> findPref(key));
              final var outcome = ctx.onModelThread(() -> setPref(pref, value));
              if (outcome.expected() != NO_WAIT) {
                awaitPrefs(ctx, () -> Objects.equals(pref.monitor().get(), outcome.expected()));
              }
              return withNote(ctx.onModelThread(() -> describePref(pref)), outcome.note());
            });

    registry
        .tool("prefs_reset")
        .description(
            "Resets application preferences to their defaults. With 'key', only that preference."
                + " With all=true (requires confirm=true) every preference listed by prefs_list is"
                + " reset; additionally wipe=true erases the whole preference store (also recent"
                + " files, window positions and FPGA board settings), like --clear-prefs, which"
                + " should be followed by a restart. Not undoable.")
        .input(
            Schema.object()
                .prop("key", Schema.string("Preference key to reset."))
                .prop("all", Schema.bool("Reset all preferences."))
                .prop("wipe", Schema.bool("With all=true: also clear the entire preference store."))
                .prop("confirm", Schema.bool("Must be true when all=true.")))
        .destructive()
        .offModelThread()
        .handler(
            (ctx, args) -> {
              if (args.has("key")) {
                final var key = args.str("key");
                if (TEMPLATE_KEY.equalsIgnoreCase(key)) {
                  ctx.onModelThread(() -> AppPreferences.setTemplateType(AppPreferences.TEMPLATE_PLAIN));
                  awaitPrefs(ctx, () -> AppPreferences.getTemplateType() == AppPreferences.TEMPLATE_PLAIN);
                  return withNote(ctx.onModelThread(PreferenceTools::describeTemplate), null);
                }
                final var pref = ctx.onModelThread(() -> findPref(key));
                final var note = ctx.onModelThread(() -> resetPref(pref));
                if (pref.monitor() != AppPreferences.LOCALE) {
                  awaitPrefs(ctx, () -> Objects.equals(pref.monitor().get(), pref.monitor().getDefault()));
                }
                return withNote(ctx.onModelThread(() -> describePref(pref)), note);
              }
              if (!args.bool("all", false)) throw new McpToolException("Pass 'key' or all=true.");
              if (!args.bool("confirm", false)) {
                throw new McpToolException("Resetting all preferences needs confirm=true.");
              }
              final var changed = new ArrayList<String>();
              final var failed = new LinkedHashMap<String, String>();
              ctx.onModelThread(
                  () -> {
                    for (final var p : allPrefs()) {
                      try {
                        if (!Objects.equals(p.monitor().get(), p.monitor().getDefault())) {
                          changed.add(p.key());
                        }
                        resetPref(p);
                      } catch (RuntimeException e) {
                        failed.put(p.key(), String.valueOf(e.getMessage()));
                      }
                    }
                    if (AppPreferences.getTemplateType() != AppPreferences.TEMPLATE_PLAIN) {
                      changed.add(TEMPLATE_KEY);
                    }
                    AppPreferences.setTemplateType(AppPreferences.TEMPLATE_PLAIN);
                  });
              awaitPrefs(
                  ctx,
                  () -> {
                    for (final var p : allPrefs()) {
                      if (p.monitor() != AppPreferences.LOCALE
                          && !Objects.equals(p.monitor().get(), p.monitor().getDefault())) {
                        return false;
                      }
                    }
                    return true;
                  });
              final var out = new LinkedHashMap<String, Object>();
              out.put("changed", changed);
              if (!failed.isEmpty()) out.put("failed", failed);
              if (args.bool("wipe", false)) {
                ctx.onModelThread(AppPreferences::clear);
                out.put("wiped", true);
                out.put("note", "The preference store was cleared; restart Logisim.");
              }
              return out;
            });
  }
}
