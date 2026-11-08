/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.EndData;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.instance.InstanceComponent;
import com.cburch.logisim.instance.StdAttr;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared JSON representation and lookup of components, used by all tool groups so that clients
 * see one consistent format.
 *
 * <p>A component reference (argument usually named "component") may be:
 *
 * <ul>
 *   <li>an id handed out earlier, e.g. {@code "c12"};
 *   <li>{@code "label:NAME"} or just the label text, matching the component's Label attribute;
 *   <li>{@code "@x,y"}: the non-wire component anchored at (x,y), else any component there.
 * </ul>
 */
public final class ComponentRefs {
  private ComponentRefs() {}

  /** Short description: id, type, location, label, bounds (wires: endpoints and width). */
  public static Map<String, Object> summary(McpContext ctx, Component comp) {
    final var out = new LinkedHashMap<String, Object>();
    out.put("id", ctx.componentId(comp));
    if (comp instanceof Wire w) {
      out.put("type", "Wire");
      out.put("from", loc(w.getEnd0()));
      out.put("to", loc(w.getEnd1()));
      return out;
    }
    out.put("type", comp.getFactory().getName());
    out.put("location", loc(comp.getLocation()));
    final var label = label(comp);
    if (label != null && !label.isEmpty()) out.put("label", label);
    final var attrs = comp.getAttributeSet();
    if (attrs.containsAttribute(StdAttr.FACING)) {
      out.put("facing", attrs.getValue(StdAttr.FACING).toString());
    }
    out.put("bounds", bounds(comp.getBounds()));
    return out;
  }

  /** Full description: summary plus all attributes and the ports (location, width, direction). */
  public static Map<String, Object> details(McpContext ctx, Component comp) {
    final var out = summary(ctx, comp);
    if (!(comp instanceof Wire)) {
      out.put("displayName", comp.getFactory().getDisplayName());
      out.put("attributes", attributes(comp.getAttributeSet()));
    }
    out.put("ports", ports(comp));
    return out;
  }

  /** All attributes as name to standard string (the format accepted when setting them). */
  public static Map<String, Object> attributes(AttributeSet attrs) {
    final var out = new LinkedHashMap<String, Object>();
    for (final Attribute<?> attr : attrs.getAttributes()) {
      if (attr.isHidden()) continue;
      out.put(attr.getName(), standardString(attrs, attr));
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  public static <V> String standardString(AttributeSet attrs, Attribute<V> attr) {
    final V value = attrs.getValue(attr);
    if (value == null) return null;
    try {
      return attr.toStandardString(value);
    } catch (RuntimeException e) {
      return String.valueOf(value);
    }
  }

  /** Ports in index order; names come from the component's port tooltips when available. */
  public static List<Map<String, Object>> ports(Component comp) {
    final var out = new ArrayList<Map<String, Object>>();
    final var ends = comp.getEnds();
    List<com.cburch.logisim.instance.Port> portDefs = null;
    if (comp instanceof InstanceComponent ic) portDefs = ic.getInstance().getPorts();
    for (var i = 0; i < ends.size(); i++) {
      final EndData end = ends.get(i);
      final var p = new LinkedHashMap<String, Object>();
      p.put("index", i);
      p.put("location", loc(end.getLocation()));
      p.put("width", end.getWidth().getWidth());
      p.put(
          "direction",
          switch (end.getType()) {
            case EndData.INPUT_ONLY -> "input";
            case EndData.OUTPUT_ONLY -> "output";
            default -> "inout";
          });
      if (comp.getFactory() instanceof com.cburch.logisim.std.wiring.Pin) {
        // A pin's port tooltip is generic; its label is what identifies it.
        final var lbl = label(comp);
        if (lbl != null && !lbl.isEmpty()) p.put("name", lbl);
      } else if (portDefs != null && i < portDefs.size()) {
        final var tip = portDefs.get(i).getToolTip();
        if (tip != null && !tip.isEmpty()) p.put("name", tip);
      }
      out.add(p);
    }
    return out;
  }

  /** The Label attribute (if the component has one), else null. */
  public static String label(Component comp) {
    final var attrs = comp.getAttributeSet();
    if (attrs != null && attrs.containsAttribute(StdAttr.LABEL)) {
      return attrs.getValue(StdAttr.LABEL);
    }
    return null;
  }

  public static Map<String, Object> loc(Location l) {
    final var m = new LinkedHashMap<String, Object>();
    m.put("x", l.getX());
    m.put("y", l.getY());
    return m;
  }

  public static Map<String, Object> bounds(Bounds b) {
    final var m = new LinkedHashMap<String, Object>();
    m.put("x", b.getX());
    m.put("y", b.getY());
    m.put("width", b.getWidth());
    m.put("height", b.getHeight());
    return m;
  }

  /** Parses {"x":..,"y":..}, [x,y] or "x,y" into a Location. */
  public static Location parseLocation(Object value, String argName) {
    if (value instanceof Map<?, ?> m && m.get("x") != null && m.get("y") != null) {
      return Location.create(toInt(m.get("x"), argName), toInt(m.get("y"), argName), false);
    }
    if (value instanceof List<?> l && l.size() == 2) {
      return Location.create(toInt(l.get(0), argName), toInt(l.get(1), argName), false);
    }
    if (value instanceof String s) {
      final var parts = s.replace("(", "").replace(")", "").replace("@", "").split(",");
      if (parts.length == 2) {
        return Location.create(toInt(parts[0].trim(), argName), toInt(parts[1].trim(), argName), false);
      }
    }
    throw new McpToolException(
        "Argument '" + argName + "' must be a location like {\"x\":100,\"y\":50}, got " + value);
  }

  private static int toInt(Object v, String argName) {
    if (v instanceof Number n) return n.intValue();
    try {
      return Integer.parseInt(v.toString().trim());
    } catch (NumberFormatException e) {
      throw new McpToolException("Bad coordinate in '" + argName + "': " + v);
    }
  }

  /** Resolves a component reference (see class comment) within {@code circ}. */
  public static Component resolve(McpContext ctx, Circuit circ, String ref) {
    if (ref == null || ref.isEmpty()) throw new McpToolException("Empty component reference.");
    final var byId = ctx.componentById(ref);
    if (byId != null && circ.contains(byId)) return byId;
    if (ref.startsWith("@")) {
      final var at = parseLocation(ref.substring(1), "component");
      for (final var c : circ.getNonWires()) if (c.getLocation().equals(at)) return c;
      for (final var c : circ.getAllContaining(at)) return c;
      throw new McpToolException("No component at " + ref.substring(1) + " in '" + circ.getName() + "'.");
    }
    final var label = ref.startsWith("label:") ? ref.substring(6) : ref;
    Component found = null;
    for (final var c : circ.getNonWires()) {
      if (label.equals(label(c))) {
        if (found != null) {
          throw new McpToolException("Label '" + label + "' is ambiguous in '" + circ.getName() + "'; use an id.");
        }
        found = c;
      }
    }
    if (found != null) return found;
    if (ref.matches("c\\d+")) {
      throw new McpToolException(
          "Unknown component id '" + ref + "' in circuit '" + circ.getName()
              + "'. Ids change when components are moved/replaced; list components again.");
    }
    throw new McpToolException("No component '" + ref + "' in circuit '" + circ.getName() + "'.");
  }
}
