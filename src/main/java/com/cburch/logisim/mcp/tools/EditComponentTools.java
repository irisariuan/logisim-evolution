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
import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.EndData;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Direction;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.mcp.Args;
import com.cburch.logisim.mcp.ComponentRefs;
import com.cburch.logisim.mcp.McpContext;
import com.cburch.logisim.mcp.McpToolException;
import com.cburch.logisim.mcp.McpToolRegistry;
import com.cburch.logisim.mcp.Schema;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.tools.SetAttributeAction;
import com.cburch.logisim.tools.move.MoveGesture;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** component_* and wire_* tools: placing, deleting, moving, configuring and wiring components. */
final class EditComponentTools {
  private EditComponentTools() {}

  static final String LOCATION_FORMAT = "{\"x\":100,\"y\":50}, [100,50] or \"100,50\"";

  static final Schema COMPONENT_SPEC =
      Schema.object("One component to place.")
          .prop("type", Schema.string("Component type (see component_types), e.g. 'AND Gate', 'Wiring/Pin', 'Text', or a circuit name for a subcircuit."), true)
          .prop("location", Schema.any("Anchor location, " + LOCATION_FORMAT + ". Snapped to the 10-unit grid for snapping components. For gates the anchor is the output; for pins the port."), true)
          .prop("facing", Schema.string("Optional facing: east (default for most), west, north, south."))
          .prop("label", Schema.string("Optional label (must be a valid unique identifier)."))
          .prop("attributes", Schema.object("Optional attribute name -> value in standard string format, e.g. {\"inputs\":\"3\",\"width\":\"8\",\"type\":\"output\"}. Unknown names are reported with the valid ones."));

  static final Schema REFS =
      Schema.array("Component references: ids ('c12'), labels or '@x,y'.", Schema.string("Component reference."));

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  /** Summary plus ports: what a client needs to attach wires. */
  static Map<String, Object> brief(McpContext ctx, Component comp) {
    final var out = ComponentRefs.summary(ctx, comp);
    if (!(comp instanceof Wire)) out.put("ports", ComponentRefs.ports(comp));
    return out;
  }

  /** Resolves "components" (array) and/or "component" (single) references. */
  static List<Component> resolveRefs(McpContext ctx, Circuit circ, Args args) {
    final var out = new LinkedHashSet<Component>();
    if (args.has("component")) out.add(ComponentRefs.resolve(ctx, circ, args.str("component")));
    if (args.has("components")) {
      for (final var r : args.list("components")) out.add(ComponentRefs.resolve(ctx, circ, String.valueOf(r)));
    }
    if (out.isEmpty()) throw new McpToolException("Give 'component' or 'components'.");
    return new ArrayList<>(out);
  }

  /** The tool's default attributes, cloned, with facing / attributes / label applied. */
  static AttributeSet prepareAttributes(EditSupport.TypeMatch match, Args spec) {
    final var factory = match.factory();
    final var attrs = (AttributeSet) match.tool().getAttributeSet().clone();
    if (spec.has("facing")) {
      final var fa = EditSupport.facingAttribute(factory, attrs);
      if (fa == null) throw new McpToolException("Type '" + factory.getName() + "' has no facing attribute.");
      attrs.setValue(fa, EditSupport.parseDirection(spec.str("facing")));
    }
    if (spec.has("attributes")) {
      for (final var e : spec.map("attributes").entrySet()) {
        final var attr = EditSupport.requireAttribute(attrs, e.getKey(), "type '" + factory.getName() + "'");
        setRaw(attrs, attr, EditSupport.parseValue(attr, e.getValue()));
      }
    }
    if (spec.has("label")) {
      if (!attrs.containsAttribute(StdAttr.LABEL)) {
        throw new McpToolException("Type '" + factory.getName() + "' has no label attribute.");
      }
      attrs.setValue(StdAttr.LABEL, spec.str("label"));
    }
    return attrs;
  }

  @SuppressWarnings("unchecked")
  private static void setRaw(AttributeSet attrs, Attribute<?> attr, Object value) {
    attrs.setValue((Attribute<Object>) attr, value);
  }

  /** Creates (but does not add) a component from a spec, validating it against the circuit. */
  static Component build(Project proj, Circuit circ, Args spec, List<String> warnings) {
    final var match = EditSupport.resolveType(proj, spec.str("type"));
    final var factory = match.factory();
    if (factory instanceof SubcircuitFactory sf && !proj.getDependencies().canAdd(circ, sf.getSubcircuit())) {
      throw new McpToolException(
          "Cannot place circuit '" + sf.getSubcircuit().getName() + "' inside '" + circ.getName()
              + "': that would create a circular dependency.");
    }
    final var attrs = prepareAttributes(match, spec);
    if (!spec.has("location")) throw new McpToolException("Missing 'location' for component '" + spec.str("type") + "'.");
    var loc = ComponentRefs.parseLocation(spec.get("location"), "location");
    final var snapFeature = factory.getFeature(com.cburch.logisim.comp.ComponentFactory.SHOULD_SNAP, attrs);
    if (snapFeature == null || (Boolean) snapFeature) {
      final var snapped = EditSupport.snap(loc);
      if (!snapped.equals(loc)) warnings.add("Location " + loc + " snapped to grid: " + snapped);
      loc = snapped;
    }
    if (attrs.containsAttribute(StdAttr.LABEL)) {
      final var problem = EditSupport.labelProblem(circ, null, factory, attrs, attrs.getValue(StdAttr.LABEL));
      if (problem != null) throw new McpToolException(problem);
    }
    final var comp = factory.createComponent(loc, attrs);
    final var bds = comp.getBounds();
    if (bds.getX() < 0 || bds.getY() < 0) {
      throw new McpToolException(
          "Component " + factory.getName() + " at " + loc + " would extend to negative coordinates (bounds "
              + ComponentRefs.bounds(bds) + "); choose a larger location.");
    }
    if (circ.hasConflict(comp)) {
      throw new McpToolException(
          "Component " + factory.getName() + " at " + loc + " conflicts with an existing component (same"
              + " type at the same location, or overlapping output ports).");
    }
    return comp;
  }

  /** Parses a list of points. */
  static List<Location> points(List<Object> raw, String name) {
    final var out = new ArrayList<Location>();
    for (final var p : raw) out.add(ComponentRefs.parseLocation(p, name));
    return out;
  }

  /** Orthogonal segments described by a wire spec: points / from+to / segments. */
  static List<Location[]> wireSegments(Args spec) {
    final var horizontalFirst = !"vertical".equalsIgnoreCase(spec.str("bend", "horizontal"));
    if (spec.has("points")) {
      final var pts = points(spec.list("points"), "points");
      if (pts.size() < 2) throw new McpToolException("'points' needs at least two points.");
      return EditSupport.segments(pts, horizontalFirst);
    }
    if (spec.has("segments")) {
      final var out = new ArrayList<Location[]>();
      for (final var s : spec.list("segments")) {
        final List<Location> pts;
        if (s instanceof List<?> l) {
          @SuppressWarnings("unchecked")
          final var ll = (List<Object>) l;
          pts = points(ll, "segments");
        } else {
          final var a = Args.of(s);
          pts = List.of(ComponentRefs.parseLocation(a.get("from"), "from"), ComponentRefs.parseLocation(a.get("to"), "to"));
        }
        out.addAll(EditSupport.segments(pts, horizontalFirst));
      }
      return out;
    }
    if (spec.has("from") && spec.has("to")) {
      final var a = ComponentRefs.parseLocation(spec.get("from"), "from");
      final var b = ComponentRefs.parseLocation(spec.get("to"), "to");
      return EditSupport.segments(List.of(a, b), horizontalFirst);
    }
    throw new McpToolException("Give the wire as 'points' (polyline), 'from'+'to', or 'segments'.");
  }

  /** What (other than wires) is connected at a point, for feedback after wiring. */
  static List<Object> connectionsAt(McpContext ctx, Circuit circ, Location loc) {
    final var out = new ArrayList<Object>();
    for (final var c : circ.getComponents(loc)) {
      if (c instanceof Wire) continue;
      final var m = new LinkedHashMap<String, Object>();
      m.put("id", ctx.componentId(c));
      m.put("type", c.getFactory().getName());
      final var label = ComponentRefs.label(c);
      if (label != null && !label.isEmpty()) m.put("label", label);
      final var ends = c.getEnds();
      for (var i = 0; i < ends.size(); i++) if (ends.get(i).getLocation().equals(loc)) m.put("port", i);
      out.add(m);
    }
    return out;
  }

  /** A wire end point: {component, port} or a location. Returns the location and port width. */
  record EndPoint(Location loc, Integer width, String description) {}

  static EndPoint endPoint(McpContext ctx, Circuit circ, Object raw, String name) {
    if (raw instanceof Map<?, ?> m && m.get("component") != null) {
      final var a = Args.of(raw);
      final var comp = ComponentRefs.resolve(ctx, circ, a.str("component"));
      final var index = portIndex(comp, a.get("port"));
      final var end = comp.getEnd(index);
      return new EndPoint(end.getLocation(), end.getWidth().getWidth(),
          ctx.componentId(comp) + " port " + index);
    }
    if (raw instanceof Map<?, ?> m && m.get("location") != null) {
      final var loc = ComponentRefs.parseLocation(m.get("location"), name);
      return new EndPoint(loc, null, loc.toString());
    }
    final var loc = ComponentRefs.parseLocation(raw, name);
    return new EndPoint(loc, null, loc.toString());
  }

  /** Port by index or (case-insensitive) name; "in"/"input"/"out"/"output" pick the unique one. */
  static int portIndex(Component comp, Object port) {
    final var ends = comp.getEnds();
    final var ports = ComponentRefs.ports(comp);
    if (port == null) {
      if (ends.size() == 1) return 0;
      throw new McpToolException(
          "Component " + comp.getFactory().getName() + " has " + ends.size() + " ports; give 'port' (index or name). Ports: " + ports);
    }
    if (port instanceof Number n) {
      final var i = n.intValue();
      if (i < 0 || i >= ends.size()) throw new McpToolException("Port index " + i + " out of range 0.." + (ends.size() - 1) + ". Ports: " + ports);
      return i;
    }
    final var s = port.toString().trim();
    if (s.matches("\\d+")) return portIndex(comp, Integer.parseInt(s));
    for (final var p : ports) {
      if (s.equalsIgnoreCase(String.valueOf(p.get("name")))) return (Integer) p.get("index");
    }
    final var dir = switch (s.toLowerCase()) {
      case "in", "input" -> EndData.INPUT_ONLY;
      case "out", "output" -> EndData.OUTPUT_ONLY;
      default -> -1;
    };
    if (dir >= 0) {
      var found = -1;
      for (var i = 0; i < ends.size(); i++) {
        if (ends.get(i).getType() == dir) {
          if (found >= 0) throw new McpToolException("Several " + s + " ports; use an index. Ports: " + ports);
          found = i;
        }
      }
      if (found >= 0) return found;
    }
    var found = -1;
    for (final var p : ports) {
      final var name = String.valueOf(p.get("name")).toLowerCase();
      if (p.get("name") != null && name.contains(s.toLowerCase())) {
        if (found >= 0) throw new McpToolException("Port name '" + s + "' is ambiguous; use an index. Ports: " + ports);
        found = (Integer) p.get("index");
      }
    }
    if (found >= 0) return found;
    throw new McpToolException("No port '" + s + "' on " + comp.getFactory().getName() + ". Ports: " + ports);
  }

  /** Applies attribute values to components as one undoable action (like the attribute table). */
  static List<Object> setAttributes(
      McpContext ctx, Project proj, Circuit circ, List<Component> comps, Map<String, Object> values, String actionName) {
    final var act = new SetAttributeAction(circ, EditSupport.text(actionName));
    final var expected = new ArrayList<Object[]>();
    for (final var comp : comps) {
      final var attrs = comp.getAttributeSet();
      final var owner = comp instanceof Wire ? "wires" : comp.getFactory().getName() + " " + ctx.componentId(comp);
      for (final var e : values.entrySet()) {
        final var attr = EditSupport.requireAttribute(attrs, e.getKey(), owner);
        if (comp instanceof Wire && !Wire.BUS_WIDTH_POS_ATTR.equals(attr)) {
          throw new McpToolException("Only '" + Wire.BUS_WIDTH_POS_ATTR.getName() + "' can be set on wires; move or re-add wires to change them.");
        }
        if (attrs.isReadOnly(attr)) throw new McpToolException("Attribute '" + attr.getName() + "' of " + owner + " is read-only.");
        if (comp.getFactory() instanceof SubcircuitFactory && attr == CircuitAttributes.NAME_ATTR) {
          throw new McpToolException("Use circuit_rename to rename the circuit of a subcircuit instance.");
        }
        final var value = EditSupport.parseValue(attr, e.getValue());
        if (attr == StdAttr.LABEL) {
          final var label = (String) value;
          if (!label.isEmpty() && comps.size() > 1) {
            throw new McpToolException("Labels must be unique; set a non-empty label on one component at a time.");
          }
          final var problem = EditSupport.labelProblem(circ, comp, comp.getFactory(), attrs, label);
          if (problem != null) throw new McpToolException(problem);
        }
        @SuppressWarnings("unchecked")
        final var a = (Attribute<Object>) attr;
        final var mayChange = attrs.attributesMayAlsoBeChanged(a, value);
        if (mayChange != null) for (final var other : mayChange) act.set(comp, other, attrs.getValue(other));
        act.set(comp, attr, value);
        expected.add(new Object[] {comp, attr, value});
      }
    }
    if (!act.isEmpty()) EditSupport.doAction(proj, act);
    final var warnings = new ArrayList<String>();
    for (final var x : expected) {
      final var comp = (Component) x[0];
      @SuppressWarnings("unchecked")
      final var attr = (Attribute<Object>) x[1];
      if (circ.contains(comp)) {
        final var now = comp.getAttributeSet().getValue(attr);
        if (now != null && !now.equals(x[2])) {
          warnings.add("Logisim kept " + attr.getName() + "=" + EditSupport.standardOf(attr, now) + " on " + ctx.componentId(comp));
        }
      }
    }
    final var out = new ArrayList<Object>();
    for (final var c : comps) {
      if (circ.contains(c)) out.add(ComponentRefs.details(ctx, c));
    }
    if (!warnings.isEmpty()) out.add(Map.of("warnings", warnings));
    return out;
  }

  // ---------------------------------------------------------------------------------------------
  // Tools
  // ---------------------------------------------------------------------------------------------

  static void register(McpToolRegistry registry) {
    registry
        .tool("component_add")
        .description(
            "Places one component in a circuit as one undoable action, like the GUI's add tool."
                + " Default attributes are those of the project's tool for that type (as shown in"
                + " the toolbox), overridden by 'attributes' (standard string format, e.g. Pin:"
                + " {\"type\":\"output\",\"width\":\"8\"}; gate: {\"inputs\":\"3\",\"size\":\"30\"};"
                + " Text: {\"text\":\"hello\"}), 'facing' and 'label'. Returns the new component"
                + " (id, bounds, attributes) with its ports (index, location, width, direction,"
                + " name) so that wires can be attached with wire_add / wire_connect. Tip: a"
                + " Pin's port is at its location; facing east (default) puts the body to the"
                + " left of the port, so use facing 'west' for output pins placed right of the"
                + " logic. Gates are anchored at their output.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("circuit", CIRCUIT_ARG)
                .prop("type", Schema.string("Component type, e.g. 'AND Gate', 'Gates/XOR Gate', 'Pin', 'Text', or a circuit name."), true)
                .prop("location", Schema.any("Anchor location, " + LOCATION_FORMAT + "."), true)
                .prop("facing", Schema.string("east, west, north or south."))
                .prop("label", Schema.string("Label (valid unique identifier)."))
                .prop("attributes", Schema.object("Attribute name -> value (standard string format).")))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var circ = ctx.circuit(proj, args);
              EditSupport.requireEditable(proj, circ);
              final var warnings = new ArrayList<String>();
              final var comp = build(proj, circ, args, warnings);
              final var xn = new CircuitMutation(circ);
              xn.add(comp);
              EditSupport.run(proj, xn, "Add " + comp.getFactory().getDisplayName());
              final var out = ComponentRefs.details(ctx, comp);
              if (!warnings.isEmpty()) out.put("warnings", warnings);
              return out;
            });

    registry
        .tool("component_add_many")
        .description(
            "Places several components and wires in ONE undoable action (efficient for building"
                + " a whole circuit). 'components' is a list of component specs (type, location,"
                + " optional facing/label/attributes as in component_add); 'wires' is a list of"
                + " wire specs as in wire_add ({\"points\":[[x,y],...]} or {\"from\":..,\"to\":..}"
                + " with optional \"bend\":\"horizontal\"|\"vertical\"). All specs are validated"
                + " before anything is changed. Returns the components in input order (summary +"
                + " ports), the resulting wires and bit-width conflicts.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("circuit", CIRCUIT_ARG)
                .prop("components", Schema.array("Component specs.", COMPONENT_SPEC))
                .prop("wires", Schema.array("Wire specs.", Schema.object("Wire: points / from+to / segments, optional bend."))))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var circ = ctx.circuit(proj, args);
              EditSupport.requireEditable(proj, circ);
              final var warnings = new ArrayList<String>();
              final var comps = new ArrayList<Component>();
              final var specs = args.list("components", List.of());
              for (var i = 0; i < specs.size(); i++) {
                try {
                  comps.add(build(proj, circ, Args.of(specs.get(i)), warnings));
                } catch (McpToolException e) {
                  throw new McpToolException("components[" + i + "]: " + e.getMessage(), e);
                }
              }
              final var segs = new ArrayList<Location[]>();
              final var wireSpecs = args.list("wires", List.of());
              for (var i = 0; i < wireSpecs.size(); i++) {
                try {
                  segs.addAll(wireSegments(Args.of(wireSpecs.get(i))));
                } catch (McpToolException e) {
                  throw new McpToolException("wires[" + i + "]: " + e.getMessage(), e);
                }
              }
              if (comps.isEmpty() && segs.isEmpty()) throw new McpToolException("Nothing to add.");
              final var xn = new CircuitMutation(circ);
              xn.addAll(comps);
              for (final var s : segs) xn.add(Wire.create(s[0], s[1]));
              EditSupport.run(proj, xn, "Add Components");
              final var out = new LinkedHashMap<String, Object>();
              final var list = new ArrayList<Object>();
              for (final var c : comps) list.add(brief(ctx, c));
              out.put("components", list);
              final var wires = new ArrayList<Object>();
              for (final var w : EditSupport.wiresOn(circ, segs)) wires.add(ComponentRefs.summary(ctx, w));
              out.put("wires", wires);
              out.put("widthErrors", EditSupport.widthErrors(circ));
              if (!warnings.isEmpty()) out.put("warnings", warnings);
              return out;
            });

    registry
        .tool("component_delete")
        .description(
            "Deletes components and/or wires (one undoable action). Wires attached to deleted"
                + " components stay in place, as in the GUI.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("circuit", CIRCUIT_ARG)
                .prop("component", Schema.string("One component reference."))
                .prop("components", REFS))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var circ = ctx.circuit(proj, args);
              EditSupport.requireEditable(proj, circ);
              final var comps = resolveRefs(ctx, circ, args);
              final var ids = new ArrayList<String>();
              for (final var c : comps) ids.add(ctx.componentId(c));
              final var xn = new CircuitMutation(circ);
              xn.removeAll(comps);
              EditSupport.run(proj, xn, comps.size() == 1 ? "Delete Component" : "Delete Components");
              return Map.of("deleted", ids);
            });

    registry
        .tool("component_move")
        .description(
            "Moves components and/or wires by (dx,dy) or so that the first referenced component's"
                + " anchor lands on 'to' (one undoable action). With keepConnected (default: the"
                + " user preference, normally true) wires to components that are not moved are"
                + " rerouted/extended like dragging in the GUI; otherwise connections may break."
                + " Moving replaces the component objects: the result maps every old id to its"
                + " new id ('moved') and lists wires added to keep connections.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("circuit", CIRCUIT_ARG)
                .prop("component", Schema.string("One component reference."))
                .prop("components", REFS)
                .prop("dx", Schema.integer("Horizontal offset (multiple of 10 recommended)."))
                .prop("dy", Schema.integer("Vertical offset."))
                .prop("to", Schema.any("Target anchor location of the first component, " + LOCATION_FORMAT + "."))
                .prop("keepConnected", Schema.bool("Reroute wires to keep connections (default: preference).")))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var circ = ctx.circuit(proj, args);
              EditSupport.requireEditable(proj, circ);
              final var comps = resolveRefs(ctx, circ, args);
              int dx;
              int dy;
              if (args.has("to")) {
                final var to = ComponentRefs.parseLocation(args.get("to"), "to");
                final var first = comps.get(0);
                final var anchor = first instanceof Wire w ? w.getEnd0() : first.getLocation();
                dx = to.getX() - anchor.getX();
                dy = to.getY() - anchor.getY();
              } else {
                dx = args.integer("dx", 0);
                dy = args.integer("dy", 0);
              }
              if (dx == 0 && dy == 0) throw new McpToolException("Give a non-zero dx/dy or a different 'to'.");
              return move(ctx, proj, circ, comps, dx, dy,
                  args.bool("keepConnected", AppPreferences.MOVE_KEEP_CONNECT.getBoolean()));
            });

    registry
        .tool("component_set_attributes")
        .description(
            "Sets attributes of one or more components in one undoable action, like editing the"
                + " attribute table with those components selected. 'attributes' maps attribute"
                + " names (see component_get with attributeInfo=true) to values in the standard"
                + " string format (e.g. {\"width\":\"8\"}, {\"facing\":\"north\"}, {\"label\":\"A\"},"
                + " {\"inputs\":\"4\"}). Invalid names/values are reported with the valid"
                + " attributes / allowed options; nothing is changed then. Note that changing"
                + " size, facing or input count moves the ports: re-check wiring. Returns the"
                + " updated components.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("circuit", CIRCUIT_ARG)
                .prop("component", Schema.string("One component reference."))
                .prop("components", REFS)
                .prop("attributes", Schema.object("Attribute name -> value."), true))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var circ = ctx.circuit(proj, args);
              EditSupport.requireEditable(proj, circ);
              final var comps = resolveRefs(ctx, circ, args);
              return Map.of("components",
                  setAttributes(ctx, proj, circ, comps, args.map("attributes"), "Change Attributes"));
            });

    registry
        .tool("component_set_label")
        .description(
            "Sets (or clears with \"\") the Label attribute of one component (undoable). Labels"
                + " must be valid identifiers and unique in the circuit.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("circuit", CIRCUIT_ARG)
                .prop("component", Schema.string("Component reference."), true)
                .prop("label", Schema.string("New label."), true))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var circ = ctx.circuit(proj, args);
              EditSupport.requireEditable(proj, circ);
              final var comp = ComponentRefs.resolve(ctx, circ, args.str("component"));
              if (!comp.getAttributeSet().containsAttribute(StdAttr.LABEL)) {
                throw new McpToolException(comp.getFactory().getName() + " has no label attribute.");
              }
              final var res = setAttributes(ctx, proj, circ, List.of(comp),
                  Map.of(StdAttr.LABEL.getName(), args.str("label")), "Set Label");
              return res.get(0);
            });

    registry
        .tool("component_rotate")
        .description(
            "Rotates components (undoable) by changing their facing attribute: rotation"
                + " 'clockwise' | 'counterclockwise' | '180', or an absolute 'facing' (east, west,"
                + " north, south). Components rotate around their anchor; attached wires are not"
                + " moved, so re-check the returned port locations.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("circuit", CIRCUIT_ARG)
                .prop("component", Schema.string("One component reference."))
                .prop("components", REFS)
                .prop("rotation", Schema.enumeration("Relative rotation.", "clockwise", "counterclockwise", "180"))
                .prop("facing", Schema.string("Absolute facing.")))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var circ = ctx.circuit(proj, args);
              EditSupport.requireEditable(proj, circ);
              final var comps = resolveRefs(ctx, circ, args);
              if (!args.has("rotation") && !args.has("facing")) throw new McpToolException("Give 'rotation' or 'facing'.");
              final var act = new SetAttributeAction(circ, EditSupport.text("Rotate"));
              for (final var c : comps) {
                if (c instanceof Wire) throw new McpToolException("Wires cannot be rotated.");
                final var fa = EditSupport.facingAttribute(c.getFactory(), c.getAttributeSet());
                if (fa == null) throw new McpToolException(c.getFactory().getName() + " " + ctx.componentId(c) + " cannot be rotated.");
                final Direction cur = c.getAttributeSet().getValue(fa);
                final Direction next;
                if (args.has("facing")) {
                  next = EditSupport.parseDirection(args.str("facing"));
                } else {
                  next = switch (args.str("rotation").toLowerCase()) {
                    case "clockwise", "cw", "right" -> cur.getRight();
                    case "counterclockwise", "ccw", "left" -> cur.getLeft();
                    case "180" -> cur.reverse();
                    default -> throw new McpToolException("rotation must be clockwise, counterclockwise or 180.");
                  };
                }
                act.set(c, fa, next);
              }
              EditSupport.doAction(proj, act);
              final var out = new ArrayList<Object>();
              for (final var c : comps) out.add(brief(ctx, c));
              return Map.of("components", out);
            });

    registry
        .tool("component_duplicate")
        .description(
            "Duplicates components/wires (like Edit > Duplicate, one undoable action). Copies are"
                + " offset by (dx,dy); without offsets the nearest free offset is chosen as in the"
                + " GUI. Labels are copied unchanged (set new ones if they must be unique). The"
                + " copies become the selection. Returns 'copies' as source id -> copy.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("circuit", CIRCUIT_ARG)
                .prop("component", Schema.string("One component reference."))
                .prop("components", REFS)
                .prop("dx", Schema.integer("Horizontal offset."))
                .prop("dy", Schema.integer("Vertical offset.")))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var circ = ctx.circuit(proj, args);
              EditSupport.requireEditable(proj, circ);
              final var comps = resolveRefs(ctx, circ, args);
              return duplicate(ctx, proj, circ, comps, args);
            });

    registerWireTools(registry);
  }

  /** Moves components (GUI semantics incl. optional wire rerouting). */
  static Map<String, Object> move(
      McpContext ctx, Project proj, Circuit circ, List<Component> comps, int dx, int dy, boolean keepConnected) {
    if (EditSupport.hasConflictTranslated(circ, comps, dx, dy, false)) {
      throw new McpToolException("Moving by (" + dx + "," + dy + ") would overlap another component.");
    }
    final var bds = EditSupport.boundsOf(comps);
    if (bds.getX() + dx < 0 || bds.getY() + dy < 0) {
      throw new McpToolException("Moving by (" + dx + "," + dy + ") would leave the canvas (negative coordinates).");
    }
    com.cburch.logisim.circuit.ReplacementMap repl = null;
    if (keepConnected) {
      final var gesture = new MoveGesture((g, x, y) -> { }, circ, comps);
      final var result = gesture.forceRequest(dx, dy);
      if (result != null) repl = result.getReplacementMap();
    }
    final var xn = new CircuitMutation(circ);
    final var newComps = new LinkedHashMap<Component, Component>();
    for (final var c : comps) {
      final var moved = EditSupport.translated(c, dx, dy, true);
      newComps.put(c, moved);
      xn.replace(c, moved);
    }
    if (repl != null) xn.replace(repl);
    EditSupport.run(proj, xn, "Move");
    final var moved = new ArrayList<Object>();
    for (final var e : newComps.entrySet()) {
      final var m = new LinkedHashMap<String, Object>();
      m.put("oldId", ctx.componentId(e.getKey()));
      final var now = e.getValue();
      if (circ.contains(now)) {
        m.putAll(brief(ctx, now));
      } else {
        m.put("note", "merged into other wires by wire repair");
      }
      moved.add(m);
    }
    final var out = new LinkedHashMap<String, Object>();
    out.put("moved", moved);
    if (repl != null) {
      final var added = new ArrayList<Object>();
      for (final var c : repl.getAdditions()) if (circ.contains(c)) added.add(ComponentRefs.summary(ctx, c));
      out.put("wiresAdded", added);
      out.put("wiresRemoved", repl.getRemovals().size());
    }
    return out;
  }

  /** GUI-like duplicate: copies with clone attributes at a free (or given) offset. */
  static Map<String, Object> duplicate(McpContext ctx, Project proj, Circuit circ, List<Component> comps, Args args) {
    int dx;
    int dy;
    if (args.has("dx") || args.has("dy")) {
      dx = args.integer("dx", 0);
      dy = args.integer("dy", 0);
    } else {
      final var off = freeOffset(circ, comps);
      dx = off[0];
      dy = off[1];
    }
    final var bds = EditSupport.boundsOf(comps);
    if (bds.getX() + dx < 0 || bds.getY() + dy < 0) {
      throw new McpToolException("The copies would extend to negative coordinates; use another offset.");
    }
    final var copies = new LinkedHashMap<Component, Component>();
    for (final var c : comps) copies.put(c, EditSupport.translated(c, dx, dy, false));
    final var xn = new CircuitMutation(circ);
    xn.addAll(copies.values());
    EditSupport.run(proj, xn, "Duplicate");
    final var placed = new ArrayList<Component>();
    final var out = new LinkedHashMap<String, Object>();
    final var list = new ArrayList<Object>();
    for (final var e : copies.entrySet()) {
      final var m = new LinkedHashMap<String, Object>();
      m.put("sourceId", ctx.componentId(e.getKey()));
      if (circ.contains(e.getValue())) {
        placed.add(e.getValue());
        m.putAll(brief(ctx, e.getValue()));
      }
      list.add(m);
    }
    EditSupport.setSelection(proj, circ, placed);
    out.put("offset", Map.of("dx", dx, "dy", dy));
    out.put("copies", list);
    return out;
  }

  /** The GUI's search for the nearest offset at which copies do not conflict. */
  static int[] freeOffset(Circuit circ, Collection<Component> comps) {
    final var bds = EditSupport.boundsOf(comps);
    for (var index = 1; index < 10000; index++) {
      var side = 1;
      while (side * side <= index) side += 2;
      var offs = index - (side - 2) * (side - 2);
      var dx = side / 2;
      var dy = side / 2;
      if (offs < side - 1) {
        dx -= offs;
      } else if (offs < 2 * (side - 1)) {
        offs -= side - 1;
        dx = -dx;
        dy -= offs;
      } else if (offs < 3 * (side - 1)) {
        offs -= 2 * (side - 1);
        dx = -dx + offs;
        dy = -dy;
      } else {
        offs -= 3 * (side - 1);
        dy = -dy + offs;
      }
      dx *= 10;
      dy *= 10;
      if (bds.getX() + dx >= 0 && bds.getY() + dy >= 0
          && !EditSupport.hasConflictTranslated(circ, comps, dx, dy, true)) {
        return new int[] {dx, dy};
      }
    }
    return new int[] {10, 10};
  }

  // ---------------------------------------------------------------------------------------------
  // Wires
  // ---------------------------------------------------------------------------------------------

  private static Map<String, Object> wireResult(McpContext ctx, Circuit circ, List<Location[]> segs) {
    final var out = new LinkedHashMap<String, Object>();
    final var wires = new ArrayList<Object>();
    for (final var w : EditSupport.wiresOn(circ, segs)) wires.add(ComponentRefs.summary(ctx, w));
    out.put("wires", wires);
    if (!segs.isEmpty()) {
      final var start = segs.get(0)[0];
      final var end = segs.get(segs.size() - 1)[1];
      out.put("startConnections", connectionsAt(ctx, circ, start));
      out.put("endConnections", connectionsAt(ctx, circ, end));
    }
    out.put("widthErrors", EditSupport.widthErrors(circ));
    return out;
  }

  private static void registerWireTools(McpToolRegistry registry) {
    registry
        .tool("wire_add")
        .description(
            "Adds wires (one undoable action). Logisim wires are straight horizontal or vertical"
                + " segments that connect only at their end points (crossing wires do not"
                + " connect; a wire end on another wire's middle does). Give a polyline 'points'"
                + " [[x,y],...] (consecutive points not on a line get an automatic bend,"
                + " horizontal-first unless bend='vertical'), or 'from'+'to' (straight or"
                + " L-shaped), or 'segments' [[[x0,y0],[x1,y1]],...]. Overlapping/collinear wires"
                + " are merged as in the GUI. Returns the resulting wires, what is connected at"
                + " the start and end points and any bit-width conflicts.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("circuit", CIRCUIT_ARG)
                .prop("points", Schema.array("Polyline points.", Schema.any("Point.")))
                .prop("from", Schema.any("Start point, " + LOCATION_FORMAT + "."))
                .prop("to", Schema.any("End point."))
                .prop("segments", Schema.array("Explicit segments as [from,to] pairs.", Schema.any("Segment.")))
                .prop("bend", Schema.enumeration("Bend style for diagonal steps.", "horizontal", "vertical")))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var circ = ctx.circuit(proj, args);
              EditSupport.requireEditable(proj, circ);
              final var segs = wireSegments(args);
              if (segs.isEmpty()) throw new McpToolException("The wire has zero length.");
              final var xn = new CircuitMutation(circ);
              for (final var s : segs) xn.add(Wire.create(s[0], s[1]));
              EditSupport.run(proj, xn, segs.size() == 1 ? "Add Wire" : "Add Wires");
              return wireResult(ctx, circ, segs);
            });

    registry
        .tool("wire_connect")
        .description(
            "Connects two points with an orthogonal wire route (one undoable action). Each end is"
                + " either {\"component\":ref,\"port\":index|name} (port names as listed in a"
                + " component's 'ports'; 'in'/'out' pick the unique input/output; port may be"
                + " omitted for single-port components like pins) or a location. Without 'via' a"
                + " route is chosen that avoids crossing component bodies, touching other"
                + " connection points or overlapping wires (Z/L shapes, then detours); 'via' forces"
                + " intermediate points. Returns the route, the wires, the connections at both"
                + " ends, bit-width conflicts and warnings (e.g. width mismatch, unavoidable"
                + " contact).")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("circuit", CIRCUIT_ARG)
                .prop("from", Schema.any("Start: {\"component\":\"c3\",\"port\":2} or a location."), true)
                .prop("to", Schema.any("End: {\"component\":\"c5\",\"port\":\"out\"} or a location."), true)
                .prop("via", Schema.array("Optional intermediate points.", Schema.any("Point."))))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var circ = ctx.circuit(proj, args);
              EditSupport.requireEditable(proj, circ);
              final var a = endPoint(ctx, circ, args.get("from"), "from");
              final var b = endPoint(ctx, circ, args.get("to"), "to");
              if (a.loc().equals(b.loc())) throw new McpToolException("Both ends are at the same point " + a.loc() + " (already connected).");
              final var warnings = new ArrayList<String>();
              if (a.width() != null && b.width() != null && !a.width().equals(b.width())) {
                warnings.add("Bit widths differ: " + a.description() + " is " + a.width() + " bits, " + b.description() + " is " + b.width() + " bits.");
              }
              List<Location> path;
              if (args.has("via")) {
                final var pts = new ArrayList<Location>();
                pts.add(a.loc());
                pts.addAll(points(args.list("via"), "via"));
                pts.add(b.loc());
                path = EditSupport.simplify(flatten(EditSupport.segments(pts, true)));
                final var problem = EditSupport.routeProblem(circ, flatten(EditSupport.segments(path, true)), a.loc(), b.loc());
                if (problem != null) warnings.add("Route " + problem + ".");
              } else {
                path = null;
                final var candidates = EditSupport.candidateRoutes(a.loc(), b.loc());
                for (final var r : candidates) {
                  if (EditSupport.routeProblem(circ, r, a.loc(), b.loc()) == null) {
                    path = r;
                    break;
                  }
                }
                if (path == null) {
                  path = candidates.get(0);
                  warnings.add("No clean route found; used a direct route that "
                      + EditSupport.routeProblem(circ, path, a.loc(), b.loc()) + ". Consider 'via' points.");
                }
              }
              final var segs = EditSupport.segments(path, true);
              final var xn = new CircuitMutation(circ);
              for (final var s : segs) xn.add(Wire.create(s[0], s[1]));
              EditSupport.run(proj, xn, "Connect");
              final var out = new LinkedHashMap<String, Object>();
              final var route = new ArrayList<Object>();
              for (final var p : flatten(segs)) route.add(ComponentRefs.loc(p));
              out.put("route", route);
              out.putAll(wireResult(ctx, circ, segs));
              if (!warnings.isEmpty()) out.put("warnings", warnings);
              return out;
            });

    registry
        .tool("wire_delete")
        .description(
            "Deletes wires (one undoable action): by reference ('wires': ids or '@x,y' points on"
                + " a wire) and/or every wire part lying on the straight segment 'from'-'to'"
                + " (wires extending beyond the segment are shortened, like erasing with the"
                + " wiring tool).")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("circuit", CIRCUIT_ARG)
                .prop("wires", REFS)
                .prop("from", Schema.any("Segment start (horizontal/vertical with 'to')."))
                .prop("to", Schema.any("Segment end.")))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var circ = ctx.circuit(proj, args);
              EditSupport.requireEditable(proj, circ);
              final var xn = new CircuitMutation(circ);
              final var removed = new ArrayList<String>();
              final var touched = new HashSet<Wire>();
              if (args.has("wires")) {
                for (final var r : args.list("wires")) {
                  final var ref = String.valueOf(r);
                  Wire wire = null;
                  final var comp = ctx.componentById(ref);
                  if (comp instanceof Wire w && circ.contains(w)) wire = w;
                  if (wire == null && ref.startsWith("@")) {
                    final var p = ComponentRefs.parseLocation(ref.substring(1), "wires");
                    for (final var w : circ.getWires()) if (w.contains(p)) wire = w;
                  }
                  if (wire == null) throw new McpToolException("'" + ref + "' is not a wire of circuit '" + circ.getName() + "'.");
                  if (touched.add(wire)) {
                    xn.remove(wire);
                    removed.add(ctx.componentId(wire));
                  }
                }
              }
              final var added = new ArrayList<Wire>();
              if (args.has("from") || args.has("to")) {
                final var p = ComponentRefs.parseLocation(args.get("from"), "from");
                final var q = ComponentRefs.parseLocation(args.get("to"), "to");
                if (p.getX() != q.getX() && p.getY() != q.getY()) throw new McpToolException("'from'-'to' must be horizontal or vertical.");
                for (final var w : circ.getWires()) {
                  if (touched.contains(w)) continue;
                  final var pieces = cut(w, p, q);
                  if (pieces == null) continue;
                  touched.add(w);
                  xn.remove(w);
                  removed.add(ctx.componentId(w));
                  for (final var piece : pieces) {
                    xn.add(piece);
                    added.add(piece);
                  }
                }
              }
              if (removed.isEmpty()) throw new McpToolException("No wires matched.");
              EditSupport.run(proj, xn, "Delete Wires");
              final var remaining = new ArrayList<Object>();
              for (final var w : added) if (circ.contains(w)) remaining.add(ComponentRefs.summary(ctx, w));
              return Map.of("deleted", removed, "shortenedPieces", remaining, "widthErrors", EditSupport.widthErrors(circ));
            });
  }

  private static List<Location> flatten(List<Location[]> segs) {
    final var out = new ArrayList<Location>();
    for (final var s : segs) {
      if (out.isEmpty()) out.add(s[0]);
      out.add(s[1]);
    }
    return out;
  }

  /**
   * Remaining parts of wire w after erasing segment p-q, or null if w does not overlap it with
   * positive length.
   */
  private static List<Wire> cut(Wire w, Location p, Location q) {
    final var e0 = w.getEnd0();
    final var e1 = w.getEnd1();
    final var horizontal = e0.getY() == e1.getY();
    if (horizontal) {
      if (p.getY() != q.getY() || p.getY() != e0.getY()) return null;
      final var w0 = Math.min(e0.getX(), e1.getX());
      final var w1 = Math.max(e0.getX(), e1.getX());
      final var s0 = Math.min(p.getX(), q.getX());
      final var s1 = Math.max(p.getX(), q.getX());
      if (Math.min(w1, s1) - Math.max(w0, s0) <= 0) return null;
      final var out = new ArrayList<Wire>();
      if (w0 < s0) out.add(Wire.create(Location.create(w0, e0.getY(), false), Location.create(s0, e0.getY(), false)));
      if (s1 < w1) out.add(Wire.create(Location.create(s1, e0.getY(), false), Location.create(w1, e0.getY(), false)));
      return out;
    }
    if (p.getX() != q.getX() || p.getX() != e0.getX()) return null;
    final var w0 = Math.min(e0.getY(), e1.getY());
    final var w1 = Math.max(e0.getY(), e1.getY());
    final var s0 = Math.min(p.getY(), q.getY());
    final var s1 = Math.max(p.getY(), q.getY());
    if (Math.min(w1, s1) - Math.max(w0, s0) <= 0) return null;
    final var out = new ArrayList<Wire>();
    if (w0 < s0) out.add(Wire.create(Location.create(e0.getX(), w0, false), Location.create(e0.getX(), s0, false)));
    if (s1 < w1) out.add(Wire.create(Location.create(e0.getX(), s1, false), Location.create(e0.getX(), w1, false)));
    return out;
  }
}
