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

import com.cburch.draw.actions.ModelAddAction;
import com.cburch.draw.actions.ModelRemoveAction;
import com.cburch.draw.actions.ModelTranslateAction;
import com.cburch.draw.model.AbstractCanvasObject;
import com.cburch.draw.model.CanvasObject;
import com.cburch.draw.shapes.SvgReader;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitAttributes;
import com.cburch.logisim.circuit.CircuitMutator;
import com.cburch.logisim.circuit.CircuitTransaction;
import com.cburch.logisim.circuit.appear.AppearanceAnchor;
import com.cburch.logisim.circuit.appear.AppearanceElement;
import com.cburch.logisim.circuit.appear.AppearancePort;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.gui.appear.CanvasActionAdapter;
import com.cburch.logisim.mcp.ComponentRefs;
import com.cburch.logisim.mcp.McpContext;
import com.cburch.logisim.mcp.McpToolException;
import com.cburch.logisim.mcp.McpToolRegistry;
import com.cburch.logisim.mcp.Schema;
import com.cburch.logisim.proj.Action;
import com.cburch.logisim.proj.Project;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

/**
 * Model-level editing of a circuit's custom appearance (the symbol drawn when the circuit is used
 * as a subcircuit), going through the same undoable actions as the appearance editor.
 */
final class ViewAppearanceTools {
  private ViewAppearanceTools() {}

  // ---------------------------------------------------------------------------------------------
  // Rendering
  // ---------------------------------------------------------------------------------------------

  static ViewTools.Rendered renderAppearance(Circuit circ, ViewTools.RenderOptions o) {
    final var appear = circ.getAppearance();
    final var objects = appear.getObjectsFromBottom();
    var region = o.region;
    if (region == null) {
      final var bds = appear.getAbsoluteBounds();
      region =
          (bds == null || bds.getWidth() <= 0 || bds.getHeight() <= 0)
              ? Bounds.create(0, 0, 100, 100)
              : bds.expand(Math.max(0, o.margin));
    }
    var scale = o.scale;
    if (scale <= 0) throw new McpToolException("'scale' must be > 0.");
    final var longest = Math.max(region.getWidth(), region.getHeight());
    if (longest * scale > o.maxSize) scale = (double) o.maxSize / longest;
    final var w = Math.max(1, (int) Math.round(region.getWidth() * scale));
    final var h = Math.max(1, (int) Math.round(region.getHeight() * scale));
    final var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    final var g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, w, h);
    g.scale(scale, scale);
    g.translate(-region.getX(), -region.getY());
    if (o.showGrid) {
      g.setColor(new Color(0xbbbbbb));
      final var x0 = Math.floorDiv(region.getX(), 10) * 10;
      final var y0 = Math.floorDiv(region.getY(), 10) * 10;
      for (var x = x0; x <= region.getX() + region.getWidth(); x += 10) {
        for (var y = y0; y <= region.getY() + region.getHeight(); y += 10) {
          g.fill(new java.awt.geom.Rectangle2D.Double(x - 0.5, y - 0.5, 1, 1));
        }
      }
    }
    for (final var obj : objects) {
      final var g2 = (Graphics2D) g.create();
      obj.paint(g2, null);
      g2.dispose();
    }
    if (o.showIds) {
      final var custom = appear.isDefaultAppearance() ? List.<CanvasObject>of() : appear.getCustomObjectsFromBottom();
      g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 6));
      for (var i = 0; i < custom.size(); i++) {
        final var b = custom.get(i).getBounds();
        g.setColor(new Color(160, 0, 160));
        g.drawString("s" + i, b.getX(), b.getY() - 1);
      }
    }
    g.dispose();
    return new ViewTools.Rendered(img, null, region, scale, w, h);
  }

  // ---------------------------------------------------------------------------------------------
  // Description
  // ---------------------------------------------------------------------------------------------

  static String toSvg(CanvasObject obj) {
    if (!(obj instanceof AbstractCanvasObject aco)) return null;
    try {
      final var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
      final var elt = aco.toSvgElement(doc);
      if (elt == null) return null;
      doc.appendChild(elt);
      final var tf = TransformerFactory.newInstance().newTransformer();
      tf.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
      final var sw = new StringWriter();
      tf.transform(new DOMSource(elt), new StreamResult(sw));
      return sw.toString().trim();
    } catch (Exception e) {
      return null;
    }
  }

  private static Map<String, Object> describeShape(McpContext ctx, CanvasObject obj, int index) {
    final var m = new LinkedHashMap<String, Object>();
    m.put("id", "s" + index);
    if (obj instanceof AppearancePort port) {
      m.put("kind", "port");
      m.put("location", ComponentRefs.loc(port.getLocation()));
      final var pin = port.getPin();
      if (pin != null) {
        m.put("pin", ctx.componentId(pin.getComponent()));
        final var label = ComponentRefs.label(pin.getComponent());
        if (label != null && !label.isEmpty()) m.put("pinLabel", label);
        m.put("pinLocation", ComponentRefs.loc(pin.getLocation()));
      }
    } else if (obj instanceof AppearanceAnchor anchor) {
      m.put("kind", "anchor");
      m.put("location", ComponentRefs.loc(anchor.getLocation()));
      m.put("facing", anchor.getFacingDirection().toString());
    } else {
      m.put("kind", "shape");
      m.put("type", obj.getDisplayName());
      m.put("bounds", ComponentRefs.bounds(obj.getBounds()));
      m.put("svg", toSvg(obj));
    }
    return m;
  }

  static Map<String, Object> describe(McpContext ctx, Circuit circ) {
    final var appear = circ.getAppearance();
    final var out = new LinkedHashMap<String, Object>();
    out.put("circuit", circ.getName());
    out.put(
        "appearanceAttribute",
        circ.getStaticAttributes().getValue(CircuitAttributes.APPEARANCE_ATTR).getValue());
    out.put("usesCustomDrawing", !appear.isDefaultAppearance());
    out.put("customDrawingModified", appear.hasCustomAppearance());
    out.put("bounds", ComponentRefs.bounds(appear.getAbsoluteBounds()));
    final var shapes = new ArrayList<Object>();
    final var objs = appear.getCustomObjectsFromBottom();
    for (var i = 0; i < objs.size(); i++) shapes.add(describeShape(ctx, objs.get(i), i));
    out.put("customDrawing", shapes);
    if (appear.isDefaultAppearance()) {
      out.put(
          "note",
          "The circuit currently uses a built-in appearance; 'customDrawing' is what the"
              + " appearance editor edits and becomes active (appearance attribute 'custom') as"
              + " soon as it is changed with appearance_* tools.");
    }
    return out;
  }

  // ---------------------------------------------------------------------------------------------
  // Editing helpers
  // ---------------------------------------------------------------------------------------------

  private static List<CanvasObject> resolveShapes(Circuit circ, List<Object> ids) {
    final var objs = circ.getAppearance().getCustomObjectsFromBottom();
    final var out = new LinkedHashSet<CanvasObject>();
    for (final var idObj : ids) {
      final var id = String.valueOf(idObj);
      if (id.equals("anchor")) {
        for (final var o : objs) if (o instanceof AppearanceAnchor) out.add(o);
        continue;
      }
      if (!id.matches("s\\d+")) throw new McpToolException("Bad shape id '" + id + "' (expected e.g. 's3').");
      final var i = Integer.parseInt(id.substring(1));
      if (i >= objs.size()) {
        throw new McpToolException("No shape '" + id + "'; there are " + objs.size() + ". Use appearance_get.");
      }
      out.add(objs.get(i));
    }
    if (out.isEmpty()) throw new McpToolException("No shapes given.");
    return new ArrayList<>(out);
  }

  private static int topShapeIndex(Circuit circ) {
    final var objs = circ.getAppearance().getCustomObjectsFromBottom();
    for (var i = objs.size() - 1; i >= 0; i--) {
      if (!(objs.get(i) instanceof AppearanceElement)) return i + 1;
    }
    return 0;
  }

  private static List<CanvasObject> parseSvg(String svg) {
    final Element root;
    try {
      final var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(false);
      factory.setExpandEntityReferences(false);
      final var doc =
          factory.newDocumentBuilder().parse(new InputSource(new StringReader("<g>" + svg + "</g>")));
      root = doc.getDocumentElement();
    } catch (Exception e) {
      throw new McpToolException("Could not parse the SVG: " + e.getMessage());
    }
    final var out = new ArrayList<CanvasObject>();
    final var children = root.getChildNodes();
    for (var i = 0; i < children.getLength(); i++) {
      if (!(children.item(i) instanceof Element elt)) continue;
      final var tag = elt.getTagName();
      if (tag.equals("circ-port") || tag.equals("circ-anchor")) {
        throw new McpToolException("Ports and the anchor cannot be added; move them with appearance_move.");
      }
      if (!elt.hasAttribute("stroke") && !elt.hasAttribute("fill")) {
        if (tag.equals("text")) {
          elt.setAttribute("fill", "#000000");
        } else {
          elt.setAttribute("stroke", "#000000");
          elt.setAttribute("fill", "none");
        }
      }
      if (tag.equals("text")) {
        if (!elt.hasAttribute("font-size")) elt.setAttribute("font-size", "12");
        if (!elt.hasAttribute("font-family")) elt.setAttribute("font-family", "SansSerif");
      }
      final AbstractCanvasObject shape;
      try {
        shape = SvgReader.createShape(elt);
      } catch (RuntimeException e) {
        throw new McpToolException("Bad <" + tag + "> element (" + e + "). Coordinates must be integers.");
      }
      if (shape == null) {
        throw new McpToolException(
            "Unsupported SVG element <" + tag + ">. Use rect, ellipse, line, polyline, polygon, path"
                + " (quadratic curve 'M x y Q cx cy x y') or text.");
      }
      out.add(shape);
    }
    if (out.isEmpty()) throw new McpToolException("The SVG contains no element.");
    return out;
  }

  /** Undoable reset of the custom drawing (mirrors the appearance editor's reset buttons). */
  private static final class ResetAppearanceAction extends Action {
    private final Circuit circuit;
    private final boolean logisimStyle;
    private List<CanvasObject> old;

    ResetAppearanceAction(Circuit circuit, boolean logisimStyle) {
      this.circuit = circuit;
      this.logisimStyle = logisimStyle;
    }

    @Override
    public String getName() {
      return "Reset Appearance";
    }

    private void run(boolean forward) {
      new CircuitTransaction() {
        @Override
        protected Map<Circuit, Integer> getAccessedCircuits() {
          final var map = new HashMap<Circuit, Integer>();
          for (final var sup : circuit.getCircuitsUsingThis()) map.put(sup, READ_WRITE);
          return map;
        }

        @Override
        protected void run(CircuitMutator mutator) {
          final var appear = circuit.getAppearance();
          if (forward) {
            old = new ArrayList<>();
            for (final var o : appear.getCustomObjectsFromBottom()) old.add(o.clone());
            if (logisimStyle) appear.loadDefaultLogisimAppearance();
            else appear.resetDefaultCustomAppearance();
          } else {
            appear.setObjectsForce(old);
          }
        }
      }.execute();
    }

    @Override
    public void doIt(Project proj) {
      run(true);
    }

    @Override
    public void undo(Project proj) {
      run(false);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Registration
  // ---------------------------------------------------------------------------------------------

  private static Schema base() {
    return Schema.object().prop("project", PROJECT_ARG).prop("circuit", CIRCUIT_ARG);
  }

  static void register(McpToolRegistry registry) {
    registry
        .tool("appearance_get")
        .description(
            "Describes a circuit's appearance (the symbol drawn where the circuit is used as a"
                + " subcircuit): the 'appearance' attribute (classic / logisim_evolution /"
                + " custom ...), its bounds, and the custom drawing edited by the appearance"
                + " editor as a list of objects with ids 's0','s1',... (bottom to top): shapes"
                + " (type, bounds and SVG markup), ports (each tied to an input/output pin of the"
                + " circuit: 'pin' id, label) and the anchor (the point that becomes the"
                + " component's location, plus facing). Shape ids are indices and change after"
                + " adds/removes; re-read after editing. Render with view_screenshot"
                + " view='appearance'.")
        .input(base())
        .readOnly()
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              return describe(ctx, ctx.circuit(proj, args));
            });

    registry
        .tool("appearance_add_shape")
        .description(
            "Adds one or more shapes to a circuit's custom appearance (undoable, like drawing in"
                + " the appearance editor; switches the circuit to its custom appearance). Shapes"
                + " are given as SVG elements in appearance coordinates (same coordinate system"
                + " as the port/anchor locations from appearance_get), integers only. Supported:"
                + " <rect x y width height [rx ry]/>, <ellipse cx cy rx ry/>, <line x1 y1 x2 y2/>,"
                + " <polyline points=\"x,y x,y ...\"/>, <polygon points=\"...\"/>, <path d=\"M x y Q"
                + " cx cy x y\"/>, <text x y [font-family font-size font-weight=bold"
                + " text-anchor=start|middle|end]>label</text>. Style attributes: stroke"
                + " (#rrggbb or none), fill (#rrggbb or none), stroke-width. Default without"
                + " stroke/fill: black outline, no fill (text: black). Example: <rect x=\"50\""
                + " y=\"50\" width=\"60\" height=\"40\" stroke=\"#000000\" fill=\"#ffffcc\""
                + " stroke-width=\"2\"/>. New shapes go on top of other shapes (below ports).")
        .input(
            base()
                .prop("svg", Schema.string("One or more SVG shape elements."), true)
                .prop("index", Schema.integer("Optional z-order index to insert at (0 = bottom).")))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var circ = ctx.circuit(proj, args);
              final var shapes = parseSvg(args.str("svg"));
              final var appear = circ.getAppearance();
              final var top = topShapeIndex(circ);
              final var index = args.has("index") ? Math.max(0, Math.min(top, args.integer("index"))) : top;
              final var model = appear.getCustomAppearanceDrawing();
              proj.doAction(new CanvasActionAdapter(circ, new ModelAddAction(model, shapes, index)));
              final var ids = new ArrayList<String>();
              final var objs = appear.getCustomObjectsFromBottom();
              for (final var s : shapes) ids.add("s" + objs.indexOf(s));
              final var out = describe(ctx, circ);
              out.put("added", ids);
              return out;
            });

    registry
        .tool("appearance_remove_shape")
        .description(
            "Removes shapes (ids from appearance_get, e.g. ['s2','s5']) from a circuit's custom"
                + " appearance. Undoable. Ports and the anchor cannot be removed (ports belong to"
                + " pins of the circuit).")
        .input(base().prop("shapes", Schema.array("Shape ids.", Schema.string("Shape id.")), true))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var circ = ctx.circuit(proj, args);
              final var shapes = resolveShapes(circ, args.list("shapes"));
              for (final var s : shapes) {
                if (!s.canRemove()) {
                  throw new McpToolException("Ports and the anchor cannot be removed.");
                }
              }
              final var model = circ.getAppearance().getCustomAppearanceDrawing();
              proj.doAction(new CanvasActionAdapter(circ, new ModelRemoveAction(model, shapes)));
              return describe(ctx, circ);
            });

    registry
        .tool("appearance_move")
        .description(
            "Moves objects of a circuit's custom appearance by (dx, dy): shapes, ports (to change"
                + " where a pin attaches on the symbol) and/or the anchor (id from appearance_get,"
                + " or 'anchor'). Keep ports on the 10-unit grid so wires can connect. Undoable.")
        .input(
            base()
                .prop("shapes", Schema.array("Object ids (shapes, ports, 'anchor').", Schema.string("Id.")), true)
                .prop("dx", Schema.integer("Horizontal offset."), true)
                .prop("dy", Schema.integer("Vertical offset."), true))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var circ = ctx.circuit(proj, args);
              final var shapes = resolveShapes(circ, args.list("shapes"));
              final var dx = args.integer("dx");
              final var dy = args.integer("dy");
              if (dx == 0 && dy == 0) return describe(ctx, circ);
              final var model = circ.getAppearance().getCustomAppearanceDrawing();
              proj.doAction(new CanvasActionAdapter(circ, new ModelTranslateAction(model, shapes, dx, dy)));
              return describe(ctx, circ);
            });

    registry
        .tool("appearance_set_default")
        .description(
            "Resets a circuit's custom appearance drawing (undoable), like the reset buttons of"
                + " the appearance editor: style='box' rebuilds the default custom drawing (a"
                + " rectangle with all pins as ports), style='logisim' replaces it with an"
                + " editable copy of the logisim-evolution standard shape. To stop using a custom"
                + " drawing altogether set the circuit's 'appearance' attribute to"
                + " 'logisim_evolution' or 'classic' with the circuit attribute tools.")
        .input(
            base()
                .prop(
                    "style",
                    Schema.enumeration("Kind of default drawing.", "box", "logisim").with("default", "box")))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var circ = ctx.circuit(proj, args);
              proj.doAction(new ResetAppearanceAction(circ, "logisim".equals(args.str("style", "box"))));
              return describe(ctx, circ);
            });
  }
}
