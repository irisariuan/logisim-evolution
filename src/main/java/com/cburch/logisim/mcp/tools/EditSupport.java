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
import com.cburch.logisim.circuit.CircuitEvent;
import com.cburch.logisim.circuit.CircuitException;
import com.cburch.logisim.circuit.CircuitListener;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeOptionInterface;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.Direction;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.gui.main.Canvas;
import com.cburch.logisim.gui.main.SelectionActions;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.mcp.ComponentRefs;
import com.cburch.logisim.mcp.McpContext;
import com.cburch.logisim.mcp.McpToolException;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.proj.Action;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.base.BaseLibrary;
import com.cburch.logisim.std.base.Text;
import com.cburch.logisim.tools.AddTool;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.util.StringGetter;
import com.cburch.logisim.util.SyntaxChecker;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/** Helpers shared by the editing tools (component types, attributes, selection, routing). */
final class EditSupport {
  private EditSupport() {}

  // ---------------------------------------------------------------------------------------------
  // Actions
  // ---------------------------------------------------------------------------------------------

  /** A fixed, non-localized action name for the undo history. */
  static StringGetter text(String value) {
    return new StringGetter() {
      @Override
      public String toString() {
        return value;
      }
    };
  }

  /** Executes {@code xn} as one undoable action named {@code name}. */
  static void run(Project proj, CircuitMutation xn, String name) {
    if (xn.isEmpty()) return;
    doAction(proj, xn.toAction(text(name)));
  }

  static void doAction(Project proj, Action action) {
    try {
      proj.doAction(action);
    } catch (CircuitException e) {
      throw new McpToolException("Logisim refused the change: " + e.getMessage(), e);
    }
  }

  /** Throws if the circuit belongs to a library rather than the project itself. */
  static void requireEditable(Project proj, Circuit circ) {
    if (!proj.getLogisimFile().contains(circ)) {
      throw new McpToolException(
          "Circuit '" + circ.getName() + "' belongs to a library and cannot be modified.");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Component types
  // ---------------------------------------------------------------------------------------------

  /** A tool (component type) and the library it was found in. */
  record TypeMatch(String library, AddTool tool) {
    ComponentFactory factory() {
      return tool.getFactory();
    }

    String qualifiedName() {
      return library + "/" + tool.getName();
    }
  }

  /** All component types available in the project: its own circuits first, then the libraries. */
  static List<TypeMatch> allTypes(Project proj) {
    final var out = new ArrayList<TypeMatch>();
    final var file = proj.getLogisimFile();
    for (final var tool : file.getTools()) out.add(new TypeMatch(file.getName(), tool));
    for (final var lib : file.getLibraries()) collectTypes(lib, out, new LinkedHashSet<>());
    return out;
  }

  private static void collectTypes(Library lib, List<TypeMatch> out, LinkedHashSet<Library> seen) {
    if (!seen.add(lib)) return;
    for (final var tool : lib.getTools()) {
      if (tool instanceof AddTool add) out.add(new TypeMatch(lib.getName(), add));
    }
    // The Base library places text through a special tool; its add tool is only reachable by name.
    if (lib instanceof BaseLibrary && lib.getTool(Text._ID) instanceof AddTool text) {
      out.add(new TypeMatch(lib.getName(), text));
    }
    for (final var sub : lib.getLibraries()) collectTypes(sub, out, seen);
  }

  /**
   * Resolves a component type name: factory name with optional "Library/" prefix, matched
   * case-insensitively; then display names; then "NAME Gate" (so "AND" finds "AND Gate").
   */
  static TypeMatch resolveType(Project proj, String type) {
    if (type == null || type.isBlank()) throw new McpToolException("Missing component type.");
    final var all = allTypes(proj);
    String libPart = null;
    var namePart = type.trim();
    final var slash = namePart.indexOf('/');
    if (slash > 0) {
      libPart = namePart.substring(0, slash).trim();
      namePart = namePart.substring(slash + 1).trim();
    }
    final var candidates = new ArrayList<TypeMatch>();
    for (final var m : all) {
      if (libPart == null || libraryMatches(proj, m.library(), libPart)) candidates.add(m);
    }
    if (libPart != null && candidates.isEmpty()) {
      throw new McpToolException(
          "Unknown library '" + libPart + "'. Use component_types to list libraries and types.");
    }
    for (final var m : candidates) if (m.tool().getName().equalsIgnoreCase(namePart)) return m;
    for (final var m : candidates) {
      final var disp = safeDisplayName(m.tool());
      if (disp != null && disp.equalsIgnoreCase(namePart)) return m;
    }
    for (final var m : candidates) {
      if (m.tool().getName().equalsIgnoreCase(namePart + " Gate")) return m;
    }
    final var norm = normalize(namePart);
    for (final var m : candidates) if (normalize(m.tool().getName()).equals(norm)) return m;
    final var suggestions = new ArrayList<String>();
    for (final var m : candidates) {
      if (normalize(m.tool().getName()).contains(norm)
          || (safeDisplayName(m.tool()) != null
              && normalize(safeDisplayName(m.tool())).contains(norm))) {
        suggestions.add(m.qualifiedName());
      }
      if (suggestions.size() >= 15) break;
    }
    throw new McpToolException(
        "Unknown component type '" + type + "'."
            + (suggestions.isEmpty() ? "" : " Did you mean: " + String.join(", ", suggestions) + "?")
            + " Use component_types to list all types.");
  }

  private static boolean libraryMatches(Project proj, String libName, String query) {
    if (libName.equalsIgnoreCase(query)) return true;
    final var lib = findLibrary(proj.getLogisimFile(), libName);
    if (lib != null) {
      final var disp = lib.getDisplayName();
      return disp != null && disp.equalsIgnoreCase(query);
    }
    return false;
  }

  private static Library findLibrary(LogisimFile file, String name) {
    if (file.getName().equals(name)) return file;
    for (final var lib : file.getLibraries()) {
      if (lib.getName().equals(name)) return lib;
      final var sub = lib.getLibrary(name);
      if (sub != null) return sub;
    }
    return null;
  }

  static String safeDisplayName(AddTool tool) {
    try {
      return tool.getDisplayName();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String normalize(String s) {
    return s.toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-]", "");
  }

  // ---------------------------------------------------------------------------------------------
  // Attributes
  // ---------------------------------------------------------------------------------------------

  /** Finds an attribute by internal name, else by display name (both case-insensitive). */
  static Attribute<?> findAttribute(AttributeSet attrs, String name) {
    final var exact = attrs.getAttribute(name);
    if (exact != null) return exact;
    for (final var a : attrs.getAttributes()) if (a.getName().equalsIgnoreCase(name)) return a;
    for (final var a : attrs.getAttributes()) {
      if (!a.isHidden() && a.getDisplayName() != null && a.getDisplayName().equalsIgnoreCase(name)) {
        return a;
      }
    }
    return null;
  }

  /** Like {@link #findAttribute} but throws a helpful error listing the valid attributes. */
  static Attribute<?> requireAttribute(AttributeSet attrs, String name, String owner) {
    final var a = findAttribute(attrs, name);
    if (a != null) return a;
    final var valid = new ArrayList<String>();
    for (final var x : attrs.getAttributes()) if (!x.isHidden()) valid.add(x.getName());
    throw new McpToolException(
        "Unknown attribute '" + name + "' for " + owner + ". Valid attributes: "
            + String.join(", ", valid) + ".");
  }

  /** Converts a JSON value to the attribute's value using its standard string format. */
  static Object parseValue(Attribute<?> attr, Object raw) {
    if (raw == null) throw new McpToolException("Missing value for attribute '" + attr.getName() + "'.");
    String s;
    if (raw instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
      s = Long.toString(n.longValue());
    } else {
      s = raw.toString();
    }
    try {
      return attr.parse(s);
    } catch (RuntimeException first) {
      final var info = valueConstraints(attr);
      @SuppressWarnings("unchecked")
      final var options = (List<Object>) info.get("optionValues");
      if (options != null) {
        for (final var o : options) {
          if (String.valueOf(o).equalsIgnoreCase(s.trim()) || displayOf(attr, o).equalsIgnoreCase(s.trim())) {
            return o;
          }
        }
      }
      info.remove("optionValues");
      throw new McpToolException(
          "Invalid value '" + s + "' for attribute '" + attr.getName() + "'"
              + (first.getMessage() != null ? " (" + first.getMessage() + ")" : "")
              + (info.isEmpty() ? "." : ". Allowed: " + info + "."));
    }
  }

  @SuppressWarnings("unchecked")
  static String displayOf(Attribute<?> attr, Object value) {
    try {
      return ((Attribute<Object>) attr).toDisplayString(value);
    } catch (RuntimeException e) {
      return String.valueOf(value);
    }
  }

  @SuppressWarnings("unchecked")
  static String standardOf(Attribute<?> attr, Object value) {
    try {
      return ((Attribute<Object>) attr).toStandardString(value);
    } catch (RuntimeException e) {
      return String.valueOf(value);
    }
  }

  /**
   * Describes the allowed values of an attribute where they can be determined: "options" (list of
   * standard strings, with display names) for choice attributes, "range" for bounded integers and
   * bit widths. The internal key "optionValues" holds the raw option objects.
   */
  static Map<String, Object> valueConstraints(Attribute<?> attr) {
    final var out = new LinkedHashMap<String, Object>();
    Integer min = null;
    Integer max = null;
    Object[] array = null;
    try {
      for (Class<?> c = attr.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
        for (final var f : c.getDeclaredFields()) {
          if (Modifier.isStatic(f.getModifiers())) continue;
          final var t = f.getType();
          final var n = f.getName();
          if (t == int.class && (n.equals("min") || n.equals("start"))) {
            f.setAccessible(true);
            min = f.getInt(attr);
          } else if (t == int.class && (n.equals("max") || n.equals("end"))) {
            f.setAccessible(true);
            max = f.getInt(attr);
          } else if (t.isArray() && !t.getComponentType().isPrimitive() && array == null) {
            f.setAccessible(true);
            array = (Object[]) f.get(attr);
          }
        }
      }
    } catch (ReflectiveOperationException | RuntimeException e) {
      // Constraints are best effort only.
    }
    if (min != null && max != null) {
      out.put("range", List.of(min, max));
    } else if (array != null && array.length > 0 && !(array[0] instanceof BitWidth)
        && !(array[0] instanceof Integer)) {
      final var opts = new ArrayList<Object>();
      final var raw = new ArrayList<Object>();
      for (final var o : array) {
        if (o == null) continue;
        raw.add(o);
        final var std = standardOf(attr, o);
        final var disp = displayOf(attr, o);
        if (o instanceof AttributeOptionInterface && !disp.equals(std)) {
          opts.add(std + " (" + disp + ")");
        } else {
          opts.add(std);
        }
      }
      out.put("options", opts);
      out.put("optionValues", raw);
    }
    return out;
  }

  /** Detailed attribute list: name, display name, value (standard string), constraints. */
  static List<Map<String, Object>> attributeInfo(AttributeSet attrs) {
    final var out = new ArrayList<Map<String, Object>>();
    for (final Attribute<?> attr : attrs.getAttributes()) {
      if (attr.isHidden()) continue;
      final var m = new LinkedHashMap<String, Object>();
      m.put("name", attr.getName());
      m.put("displayName", attr.getDisplayName());
      m.put("value", ComponentRefs.standardString(attrs, attr));
      if (attrs.isReadOnly(attr)) m.put("readOnly", true);
      final var c = valueConstraints(attr);
      c.remove("optionValues");
      m.putAll(c);
      out.add(m);
    }
    return out;
  }

  /** The attribute holding the facing of components of this factory (usually StdAttr.FACING). */
  @SuppressWarnings("unchecked")
  static Attribute<Direction> facingAttribute(ComponentFactory factory, AttributeSet attrs) {
    final var feature = factory.getFeature(ComponentFactory.FACING_ATTRIBUTE_KEY, attrs);
    if (feature instanceof Attribute<?> a) return (Attribute<Direction>) a;
    if (attrs.containsAttribute(StdAttr.FACING)) return StdAttr.FACING;
    return null;
  }

  static Direction parseDirection(String s) {
    try {
      return Direction.parse(s.trim().toLowerCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw new McpToolException("Invalid direction '" + s + "'. Use east, west, north or south.");
    }
  }

  /**
   * Checks a label like the GUI attribute table does (syntax and uniqueness in the circuit).
   * Returns an error message, or null when the label is acceptable.
   */
  static String labelProblem(Circuit circ, Component self, ComponentFactory factory, AttributeSet attrs, String label) {
    if (label == null || label.isEmpty()) return null;
    final var hdl = AppPreferences.HdlType.get();
    if (!SyntaxChecker.isVariableNameAcceptable(label, hdl, false)) {
      final var msg = SyntaxChecker.getErrorMessage(label, hdl);
      return "Label '" + label + "' is not a valid name" + (msg == null ? "" : ": " + msg.replace('\n', ' '));
    }
    final var others = new LinkedHashSet<>(circ.getNonWires());
    if (self != null) others.remove(self);
    if (!Circuit.isCorrectLabelForCurrentHdl(circ.getName(), label, others, attrs, factory, false)) {
      return "Label '" + label + "' is already used in circuit '" + circ.getName()
          + "' (or equals a component type / the circuit name).";
    }
    return null;
  }

  // ---------------------------------------------------------------------------------------------
  // Locations, grid
  // ---------------------------------------------------------------------------------------------

  static boolean shouldSnap(Component comp) {
    final var snap = comp.getFactory().getFeature(ComponentFactory.SHOULD_SNAP, comp.getAttributeSet());
    return snap == null || (Boolean) snap;
  }

  static Location snap(Location loc) {
    return Location.create(Canvas.snapXToGrid(loc.getX()), Canvas.snapYToGrid(loc.getY()), false);
  }

  static Bounds boundsOf(Collection<? extends Component> comps) {
    Bounds ret = null;
    for (final var c : comps) ret = ret == null ? c.getBounds() : ret.add(c.getBounds());
    return ret == null ? Bounds.EMPTY_BOUNDS : ret;
  }

  /**
   * Copy of the GUI's move conflict test: would {@code comps} translated by (dx,dy) overlap an
   * exclusive port or a component with identical bounds?
   */
  static boolean hasConflictTranslated(
      Circuit circuit, Collection<Component> comps, int dx, int dy, boolean selfConflicts) {
    for (final var comp : comps) {
      if (comp instanceof Wire) continue;
      for (final var end : comp.getEnds()) {
        if (end != null && end.isExclusive()) {
          final var conflict = circuit.getExclusive(end.getLocation().translate(dx, dy));
          if (conflict != null && (selfConflicts || !comps.contains(conflict))) return true;
        }
      }
      final var newLoc = comp.getLocation().translate(dx, dy);
      final var newBounds = comp.getBounds().translate(dx, dy);
      for (final var other : circuit.getAllContaining(newLoc)) {
        if (other.getBounds().equals(newBounds) && (selfConflicts || !comps.contains(other))) {
          return true;
        }
      }
    }
    return false;
  }

  /** A copy of {@code comp} translated by (dx,dy); reuses the attribute set when {@code move}. */
  static Component translated(Component comp, int dx, int dy, boolean move) {
    final var attrs = move ? comp.getAttributeSet() : (AttributeSet) comp.getAttributeSet().clone();
    var loc = comp.getLocation().translate(dx, dy);
    if (shouldSnap(comp)) loc = snap(loc);
    if (comp instanceof Wire w) {
      final var e0 = w.getEnd0().translate(dx, dy);
      final var e1 = w.getEnd1().translate(dx, dy);
      return Wire.create(e0, e1);
    }
    return comp.getFactory().createComponent(loc, attrs);
  }

  /** True when the subcircuit components in {@code comps} may be placed into {@code circ}. */
  static void checkNoRecursion(Project proj, Circuit circ, Collection<? extends Component> comps) {
    for (final var c : comps) {
      if (c.getFactory() instanceof SubcircuitFactory sf) {
        if (!proj.getDependencies().canAdd(circ, sf.getSubcircuit())) {
          throw new McpToolException(
              "Cannot place circuit '" + sf.getSubcircuit().getName() + "' inside '" + circ.getName()
                  + "': that would create a circular dependency.");
        }
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Wires
  // ---------------------------------------------------------------------------------------------

  /** Splits a polyline into orthogonal segments; diagonal steps get a bend (horizontal first). */
  static List<Location[]> segments(List<Location> points, boolean horizontalFirst) {
    final var out = new ArrayList<Location[]>();
    for (var i = 0; i + 1 < points.size(); i++) {
      final var a = points.get(i);
      final var b = points.get(i + 1);
      if (a.equals(b)) continue;
      if (a.getX() == b.getX() || a.getY() == b.getY()) {
        out.add(new Location[] {a, b});
      } else {
        final var m = horizontalFirst
            ? Location.create(b.getX(), a.getY(), false)
            : Location.create(a.getX(), b.getY(), false);
        out.add(new Location[] {a, m});
        out.add(new Location[] {m, b});
      }
    }
    return out;
  }

  /** True if p lies on the (orthogonal) segment a-b, endpoints included. */
  static boolean onSegment(Location p, Location a, Location b) {
    if (a.getX() == b.getX()) {
      return p.getX() == a.getX()
          && p.getY() >= Math.min(a.getY(), b.getY()) && p.getY() <= Math.max(a.getY(), b.getY());
    }
    if (a.getY() == b.getY()) {
      return p.getY() == a.getY()
          && p.getX() >= Math.min(a.getX(), b.getX()) && p.getX() <= Math.max(a.getX(), b.getX());
    }
    return false;
  }

  /** Wires of the circuit lying completely on one of the given segments. */
  static List<Wire> wiresOn(Circuit circ, List<Location[]> segs) {
    final var out = new ArrayList<Wire>();
    for (final var w : circ.getWires()) {
      for (final var s : segs) {
        if (onSegment(w.getEnd0(), s[0], s[1]) && onSegment(w.getEnd1(), s[0], s[1])) {
          out.add(w);
          break;
        }
      }
    }
    return out;
  }

  /** Bit-width conflicts of the circuit (points where connected ends disagree on the width). */
  static List<Map<String, Object>> widthErrors(Circuit circ) {
    final var out = new ArrayList<Map<String, Object>>();
    final var all = circ.getWidthIncompatibilityData();
    if (all == null) return out;
    for (final var data : all) {
      final var points = new ArrayList<Object>();
      for (var i = 0; i < data.size(); i++) {
        final var m = new LinkedHashMap<String, Object>(ComponentRefs.loc(data.getPoint(i)));
        m.put("width", data.getBitWidth(i).getWidth());
        points.add(m);
      }
      out.add(Map.of("points", points));
    }
    return out;
  }

  /** Locations electrically joined to {@code start} through wires (including start). */
  static java.util.Set<Location> netLocations(Circuit circ, Location start) {
    final var seen = new java.util.HashSet<Location>();
    final var todo = new java.util.ArrayDeque<Location>();
    seen.add(start);
    todo.add(start);
    while (!todo.isEmpty()) {
      final var loc = todo.poll();
      for (final var w : circ.getWires(loc)) {
        final var other = w.getOtherEnd(loc);
        if (seen.add(other)) todo.add(other);
      }
    }
    return seen;
  }

  /**
   * Describes why an orthogonal route would touch something it should not (connect to a foreign
   * net, overlap a foreign wire, run through or along a component body); null when the route is
   * clean. Touching the nets of the intended end points {@code a} and {@code b} is allowed.
   */
  static String routeProblem(Circuit circ, List<Location> path, Location a, Location b) {
    final var exempt = netLocations(circ, a);
    exempt.addAll(netLocations(circ, b));
    final var ends = circ.getAllLocations();
    for (var i = 0; i + 1 < path.size(); i++) {
      final var p = path.get(i);
      final var q = path.get(i + 1);
      for (final var loc : ends) {
        if (!exempt.contains(loc) && onSegment(loc, p, q)) {
          return "passes through connection point " + loc;
        }
      }
      for (final var w : circ.getWires()) {
        if (exempt.contains(w.getEnd0()) && exempt.contains(w.getEnd1())) continue;
        if (collinearOverlap(w.getEnd0(), w.getEnd1(), p, q)) return "overlaps wire " + w.getEnd0() + "-" + w.getEnd1();
        if (i > 0 && w.contains(p) && !w.endsAt(p)) return "bend " + p + " lies on an existing wire";
      }
      for (final var comp : circ.getNonWires()) {
        if (crossesBody(comp.getBounds(), p, q)) {
          return "runs through/along " + comp.getFactory().getName() + " at " + comp.getLocation();
        }
      }
    }
    return null;
  }

  private static boolean collinearOverlap(Location a0, Location a1, Location b0, Location b1) {
    if (a0.getY() == a1.getY() && b0.getY() == b1.getY() && a0.getY() == b0.getY()) {
      final var lo = Math.max(Math.min(a0.getX(), a1.getX()), Math.min(b0.getX(), b1.getX()));
      final var hi = Math.min(Math.max(a0.getX(), a1.getX()), Math.max(b0.getX(), b1.getX()));
      return hi > lo;
    }
    if (a0.getX() == a1.getX() && b0.getX() == b1.getX() && a0.getX() == b0.getX()) {
      final var lo = Math.max(Math.min(a0.getY(), a1.getY()), Math.min(b0.getY(), b1.getY()));
      final var hi = Math.min(Math.max(a0.getY(), a1.getY()), Math.max(b0.getY(), b1.getY()));
      return hi > lo;
    }
    return false;
  }

  /** True if the segment overlaps the closed bounds in more than a single point. */
  private static boolean crossesBody(Bounds bds, Location p, Location q) {
    final var x0 = bds.getX();
    final var y0 = bds.getY();
    final var x1 = x0 + bds.getWidth();
    final var y1 = y0 + bds.getHeight();
    final var minX = Math.min(p.getX(), q.getX());
    final var maxX = Math.max(p.getX(), q.getX());
    final var minY = Math.min(p.getY(), q.getY());
    final var maxY = Math.max(p.getY(), q.getY());
    if (minY == maxY) {
      if (minY < y0 || minY > y1) return false;
      return Math.min(maxX, x1) > Math.max(minX, x0);
    }
    if (minX < x0 || minX > x1) return false;
    return Math.min(maxY, y1) > Math.max(minY, y0);
  }

  /** Candidate orthogonal routes from a to b, most natural first. */
  static List<List<Location>> candidateRoutes(Location a, Location b) {
    final var out = new ArrayList<List<Location>>();
    final int ax = a.getX();
    final int ay = a.getY();
    final int bx = b.getX();
    final int by = b.getY();
    if (ax == bx || ay == by) {
      out.add(List.of(a, b));
    } else {
      final var midX = Canvas.snapXToGrid((ax + bx) / 2);
      final var midY = Canvas.snapYToGrid((ay + by) / 2);
      out.add(zh(a, b, midX));
      out.add(List.of(a, Location.create(bx, ay, false), b));
      out.add(List.of(a, Location.create(ax, by, false), b));
      out.add(zv(a, b, midY));
      for (var d = 10; d <= 200; d += 10) {
        out.add(zh(a, b, midX + d));
        out.add(zh(a, b, midX - d));
        out.add(zv(a, b, midY + d));
        out.add(zv(a, b, midY - d));
      }
    }
    // Detours for straight or blocked connections: leave sideways and come back.
    for (var d = 10; d <= 100; d += 10) {
      for (final var sign : new int[] {-1, 1}) {
        final var y = Math.min(ay, by) + sign * d;
        if (sign > 0) {
          final var yy = Math.max(ay, by) + d;
          out.add(List.of(a, Location.create(ax, yy, false), Location.create(bx, yy, false), b));
        } else {
          out.add(List.of(a, Location.create(ax, y, false), Location.create(bx, y, false), b));
        }
      }
    }
    final var cleaned = new ArrayList<List<Location>>();
    for (final var r : out) cleaned.add(simplify(r));
    return cleaned;
  }

  private static List<Location> zh(Location a, Location b, int x) {
    return List.of(a, Location.create(x, a.getY(), false), Location.create(x, b.getY(), false), b);
  }

  private static List<Location> zv(Location a, Location b, int y) {
    return List.of(a, Location.create(a.getX(), y, false), Location.create(b.getX(), y, false), b);
  }

  /** Removes duplicate and collinear intermediate points. */
  static List<Location> simplify(List<Location> pts) {
    final var out = new ArrayList<Location>();
    for (final var p : pts) {
      if (!out.isEmpty() && out.get(out.size() - 1).equals(p)) continue;
      if (out.size() >= 2) {
        final var a = out.get(out.size() - 2);
        final var m = out.get(out.size() - 1);
        if ((a.getX() == m.getX() && m.getX() == p.getX()) || (a.getY() == m.getY() && m.getY() == p.getY())) {
          out.set(out.size() - 1, p);
          continue;
        }
      }
      out.add(p);
    }
    return out;
  }

  // ---------------------------------------------------------------------------------------------
  // Selection
  // ---------------------------------------------------------------------------------------------

  /** Headless selection of one project: a circuit and components, tracked across replacements. */
  private static final class HeadlessSelection implements CircuitListener {
    final Circuit circuit;
    final LinkedHashSet<Component> components = new LinkedHashSet<>();

    HeadlessSelection(Circuit circuit) {
      this.circuit = circuit;
      circuit.addCircuitListener(this);
    }

    @Override
    public void circuitChanged(CircuitEvent event) {
      if (event.getAction() != CircuitEvent.TRANSACTION_DONE || event.getResult() == null) return;
      final var repl = event.getResult().getReplacementMap(circuit);
      if (repl == null) return;
      for (final var comp : new ArrayList<>(components)) {
        final var by = repl.getReplacementsFor(comp);
        if (by != null) {
          components.remove(comp);
          for (final var c : by) if (circuit.contains(c)) components.add(c);
        }
      }
    }
  }

  private static final Map<Project, HeadlessSelection> headlessSelections = new WeakHashMap<>();

  /** The selection: the circuit it belongs to and its (placed) components. */
  record Sel(Circuit circuit, List<Component> components) {}

  /**
   * Current selection of a project. In GUI mode this is the canvas selection (floating pasted
   * components are anchored first, as the GUI does before acting on them).
   */
  static Sel getSelection(McpContext ctx, Project proj) {
    if (proj.getFrame() != null) {
      final var sel = proj.getSelection();
      final var circ = proj.getCurrentCircuit();
      if (sel == null) return new Sel(circ, List.of());
      if (!sel.getFloatingComponents().isEmpty()) {
        final var act = SelectionActions.anchorAll(sel);
        if (act != null) doAction(proj, act);
      }
      final var list = new ArrayList<Component>();
      for (final var c : sel.getComponents()) if (circ.contains(c)) list.add(c);
      return new Sel(circ, list);
    }
    final var hs = headlessSelections.get(proj);
    if (hs == null) return new Sel(proj.getCurrentCircuit(), List.of());
    final var list = new ArrayList<Component>();
    for (final var c : hs.components) if (hs.circuit.contains(c)) list.add(c);
    return new Sel(hs.circuit, list);
  }

  /** Replaces the selection; in GUI mode the canvas switches to {@code circ} if needed. */
  static void setSelection(Project proj, Circuit circ, Collection<? extends Component> comps) {
    if (proj.getFrame() != null) {
      if (proj.getCurrentCircuit() != circ) proj.setCurrentCircuit(circ);
      final var sel = proj.getSelection();
      if (sel == null) return;
      final var act = SelectionActions.dropAll(sel);
      if (act != null) doAction(proj, act);
      if (!comps.isEmpty()) sel.addAll(comps);
      proj.getFrame().getCanvas().repaint();
      return;
    }
    final var old = headlessSelections.remove(proj);
    if (old != null) old.circuit.removeCircuitListener(old);
    final var hs = new HeadlessSelection(circ);
    hs.components.addAll(comps);
    headlessSelections.put(proj, hs);
  }
}
