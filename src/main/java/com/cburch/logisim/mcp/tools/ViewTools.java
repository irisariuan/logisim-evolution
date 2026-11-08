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
import com.cburch.logisim.circuit.Simulator;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentDrawContext;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.gui.generic.TikZWriter;
import com.cburch.logisim.gui.main.Frame;
import com.cburch.logisim.mcp.Args;
import com.cburch.logisim.mcp.ComponentRefs;
import com.cburch.logisim.mcp.ImageResult;
import com.cburch.logisim.mcp.McpContext;
import com.cburch.logisim.mcp.McpToolException;
import com.cburch.logisim.mcp.McpToolGroup;
import com.cburch.logisim.mcp.McpToolRegistry;
import com.cburch.logisim.mcp.Schema;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.proj.Project;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.swing.JPanel;

/**
 * Rendering/screenshots, window and canvas control, tool selection, mouse/keyboard emulation,
 * menus, dialogs and appearance editing. This class holds the rendering tools; the others live in
 * {@link ViewGuiTools}, {@link ViewDialogTools} and {@link ViewAppearanceTools}.
 */
public final class ViewTools implements McpToolGroup {

  /** Used as drawing destination when there is no canvas (headless). */
  private static JPanel dummyDestination;

  static final Schema RECT_ARG =
      Schema.object(
              "Rectangle in canvas coordinates: {\"x\":..,\"y\":..,\"width\":..,\"height\":..}.")
          .prop("x", Schema.integer("Left edge."), true)
          .prop("y", Schema.integer("Top edge."), true)
          .prop("width", Schema.integer("Width (> 0)."), true)
          .prop("height", Schema.integer("Height (> 0)."), true);

  @Override
  public void register(McpToolRegistry registry) {
    registerRendering(registry);
    ViewGuiTools.register(registry);
    ViewDialogTools.register(registry);
    ViewAppearanceTools.register(registry);
  }

  // ---------------------------------------------------------------------------------------------
  // Rendering
  // ---------------------------------------------------------------------------------------------

  /** Options for {@link #render}. */
  static final class RenderOptions {
    double scale = 1.0;
    int margin = 10;
    Bounds region;
    boolean showGrid;
    boolean printerView;
    boolean showIds;
    List<Component> highlight = List.of();
    int maxSize = 3000;
    boolean vector; // render into a TikZ/SVG writer instead of a bitmap
  }

  /** Result of a render: the image (bitmap or TikZ writer) and the mapping to canvas coords. */
  record Rendered(BufferedImage image, TikZWriter vector, Bounds region, double scale, int width,
      int height) {}

  private static Graphics measuringGraphics() {
    return new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
  }

  static java.awt.Component destination(Project proj) {
    final var frame = proj.getFrame();
    if (frame != null) return frame.getCanvas();
    if (dummyDestination == null) dummyDestination = new JPanel();
    return dummyDestination;
  }

  /** Bounds of everything drawn in a circuit (texts included), or an empty bounds. */
  static Bounds circuitBounds(Circuit circ) {
    final var g = measuringGraphics();
    try {
      return circ.getBounds(g);
    } finally {
      g.dispose();
    }
  }

  /** Renders a circuit with the live values of its current simulation state. */
  static Rendered render(Project proj, Circuit circ, RenderOptions o) {
    var region = o.region;
    if (region == null) {
      final var bds = circuitBounds(circ);
      region =
          (bds == null || bds.getWidth() <= 0 || bds.getHeight() <= 0)
              ? Bounds.create(0, 0, 200, 150)
              : bds.expand(Math.max(0, o.margin));
    }
    var scale = o.scale;
    if (scale <= 0 || Double.isNaN(scale)) throw new McpToolException("'scale' must be > 0.");
    final var longest = Math.max(region.getWidth(), region.getHeight());
    if (!o.vector && longest * scale > o.maxSize) scale = (double) o.maxSize / longest;
    final var width = Math.max(1, (int) Math.round(region.getWidth() * scale));
    final var height = Math.max(1, (int) Math.round(region.getHeight() * scale));

    BufferedImage img = null;
    TikZWriter tikz = null;
    final Graphics base;
    if (o.vector) {
      tikz = new TikZWriter();
      base = tikz;
    } else {
      img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
      final var g2 = img.createGraphics();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
      base = g2;
    }
    final var g = (Graphics2D) base.create();
    if (!(o.vector && o.printerView)) {
      g.setColor(
          o.printerView ? Color.WHITE : new Color(AppPreferences.CANVAS_BG_COLOR.get(), true));
      g.fillRect(0, 0, width, height);
    }
    g.setColor(Color.BLACK);
    g.scale(scale, scale);
    g.translate(-region.getX(), -region.getY());
    if (o.showGrid && !o.vector) paintGrid(g, region, scale);

    final var state = proj.getCircuitState(circ);
    final var context =
        new ComponentDrawContext(destination(proj), circ, state, base, g, o.printerView);
    circ.draw(context, null);

    if (!o.highlight.isEmpty()) {
      final var hg = (Graphics2D) g.create();
      hg.setColor(new Color(255, 0, 255));
      hg.setStroke(new BasicStroke(2f));
      for (final var comp : o.highlight) {
        final var b = comp.getBounds(hg).expand(4);
        hg.drawRect(b.getX(), b.getY(), b.getWidth(), b.getHeight());
      }
      hg.dispose();
    }
    if (o.showIds) paintIds(g, proj, circ);
    g.dispose();
    if (!o.vector) base.dispose();
    return new Rendered(img, tikz, region, scale, width, height);
  }

  private static void paintGrid(Graphics2D g, Bounds region, double scale) {
    final var step = scale < 0.6 ? 50 : 10;
    final var dot = new Color(AppPreferences.GRID_DOT_COLOR.get(), true);
    final var g2 = (Graphics2D) g.create();
    g2.setColor(dot);
    final var size = Math.max(1.0 / scale, scale >= 2 ? 1.5 : 1.0);
    final var x0 = Math.floorDiv(region.getX(), step) * step;
    final var y0 = Math.floorDiv(region.getY(), step) * step;
    for (var x = x0; x <= region.getX() + region.getWidth(); x += step) {
      for (var y = y0; y <= region.getY() + region.getHeight(); y += step) {
        final var bold = (x % 50 == 0 && y % 50 == 0) ? 1.6 : 1.0;
        final var s = size * bold;
        g2.fill(new java.awt.geom.Rectangle2D.Double(x - s / 2, y - s / 2, s, s));
      }
    }
    g2.dispose();
  }

  private static void paintIds(Graphics2D g, Project proj, Circuit circ) {
    final var ctx = McpContext.get();
    final var g2 = (Graphics2D) g.create();
    g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 8));
    final var fm = g2.getFontMetrics();
    for (final var comp : circ.getNonWires()) {
      final var id = ctx.componentId(comp);
      final var b = comp.getBounds(g2);
      final var w = fm.stringWidth(id) + 2;
      final var h = fm.getAscent();
      final var x = b.getX();
      final var y = b.getY() - h;
      g2.setColor(new Color(255, 255, 210));
      g2.fillRect(x, y, w, h);
      g2.setColor(new Color(160, 0, 160));
      g2.drawString(id, x + 1, y + h - 1);
    }
    g2.dispose();
  }

  /**
   * Gives the simulator of a project a chance to finish propagating pending changes so that a
   * render shows settled values: nudges it (when auto-propagation is on) and waits up to {@code
   * timeoutMs} for the propagation to complete. Must not be called on the model thread.
   */
  static void settle(McpContext ctx, Project proj, int timeoutMs) {
    if (timeoutMs <= 0 || ctx.isModelThread()) return;
    final var sim = proj.getSimulator();
    if (sim == null || !sim.isAutoPropagating()) return;
    final var latch = new CountDownLatch(1);
    final Simulator.Listener listener =
        new Simulator.Listener() {
          @Override
          public void propagationCompleted(Simulator.Event e) {
            latch.countDown();
          }

          @Override
          public void simulatorReset(Simulator.Event e) {
            latch.countDown();
          }

          @Override
          public void simulatorStateChanged(Simulator.Event e) {
            // not needed
          }
        };
    sim.addSimulatorListener(listener);
    try {
      if (sim.nudge()) latch.await(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      sim.removeSimulatorListener(listener);
    }
  }

  static Map<String, Object> mappingDetails(Rendered r) {
    final var out = new LinkedHashMap<String, Object>();
    out.put("width", r.width());
    out.put("height", r.height());
    out.put("scale", r.scale());
    out.put("region", ComponentRefs.bounds(r.region()));
    out.put(
        "pixelToCanvas",
        "canvasX = " + r.region().getX() + " + pixelX / " + r.scale() + "; canvasY = "
            + r.region().getY() + " + pixelY / " + r.scale());
    return out;
  }

  static Bounds parseRect(Map<String, Object> m) {
    final var a = new Args(m);
    final var w = a.integer("width");
    final var h = a.integer("height");
    if (w <= 0 || h <= 0) throw new McpToolException("Region width and height must be > 0.");
    return Bounds.create(a.integer("x"), a.integer("y"), w, h);
  }

  private void registerRendering(McpToolRegistry registry) {
    registry
        .tool("view_screenshot")
        .description(
            "Renders a circuit to a PNG image so you can see it, with the live simulation values"
                + " of the project's current simulation state (wire colors: bright green = 1, dark"
                + " green = 0, blue = floating/unknown, red = error/conflict, black = multi-bit"
                + " bus). Works headless and with GUI; it draws the circuit off-screen (not the"
                + " window; see view_screenshot_window for that). By default the image is cropped"
                + " to the circuit's bounds plus 'margin'. The JSON details give the mapping from"
                + " image pixels to canvas coordinates: canvasX = region.x + pixelX/scale,"
                + " canvasY = region.y + pixelY/scale. With view='appearance' the circuit's"
                + " subcircuit symbol (appearance) is drawn instead, as edited by appearance_*"
                + " tools.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("circuit", CIRCUIT_ARG)
                .prop(
                    "view",
                    Schema.enumeration(
                            "'layout' (the circuit schematic, default) or 'appearance' (the"
                                + " symbol used when the circuit is placed as a subcircuit).",
                            "layout", "appearance")
                        .with("default", "layout"))
                .prop(
                    "scale",
                    Schema.number(
                            "Pixels per canvas unit (grid spacing is 10 units). Default 1.5."
                                + " Automatically reduced so no side exceeds maxSize.")
                        .with("default", 1.5))
                .prop("margin", Schema.integer("Border around the bounds, in canvas units.").with("default", 10))
                .prop("region", RECT_ARG)
                .prop("showGrid", Schema.bool("Draw grid dots every 10 units.").with("default", false))
                .prop(
                    "printerView",
                    Schema.bool("Black-and-white printer style instead of live colors.").with("default", false))
                .prop(
                    "showIds",
                    Schema.bool(
                            "Annotate every non-wire component with its MCP id (e.g. 'c12') at"
                                + " its top-left corner.")
                        .with("default", false))
                .prop(
                    "highlight",
                    Schema.array(
                        "Components to outline in magenta: ids ('c12'), labels or '@x,y'.",
                        Schema.string("Component reference.")))
                .prop("maxSize", Schema.integer("Maximum width/height in pixels.").with("default", 3000))
                .prop(
                    "settleMs",
                    Schema.integer(
                            "Max. time to wait for the simulator to finish propagating pending"
                                + " changes before drawing (0 = draw immediately).")
                        .with("default", 500)))
        .readOnly()
        .offModelThread()
        .handler(
            (ctx, args) -> {
              settle(ctx, ctx.onModelThread(() -> ctx.project(args)), args.integer("settleMs", 500));
              return ctx.onModelThread(() -> screenshot(ctx, args));
            });

    registerExport(registry);
  }

  private static Object screenshot(McpContext ctx, Args args) {
    final var proj = ctx.project(args);
    final var circ = ctx.circuit(proj, args);
    final var view = args.str("view", "layout");
    final var o = new RenderOptions();
    o.scale = args.dbl("scale", 1.5);
    o.margin = args.integer("margin", 10);
    o.showGrid = args.bool("showGrid", false);
    o.printerView = args.bool("printerView", false);
    o.showIds = args.bool("showIds", false);
    o.maxSize = Math.max(16, args.integer("maxSize", 3000));
    if (args.has("region")) o.region = parseRect(args.map("region"));
    if ("appearance".equals(view)) {
      final var r = ViewAppearanceTools.renderAppearance(circ, o);
      final var d = mappingDetails(r);
      d.put("circuit", circ.getName());
      d.put("view", "appearance");
      return ImageResult.png(r.image(), d);
    }
    if (!"layout".equals(view)) throw new McpToolException("Unknown view '" + view + "'.");
    final var hl = new ArrayList<Component>();
    for (final var ref : args.list("highlight", List.of())) {
      hl.add(ComponentRefs.resolve(ctx, circ, String.valueOf(ref)));
    }
    o.highlight = hl;
    final var r = render(proj, circ, o);
    final var d = mappingDetails(r);
    d.put("project", ctx.projectId(proj));
    d.put("circuit", circ.getName());
    final var state = proj.getCircuitState(circ);
    d.put("simulationState", state == proj.getCircuitState() ? "current" : "root");
    d.put("components", circ.getNonWires().size());
    d.put("wires", circ.getWires().size());
    return ImageResult.png(r.image(), d);
  }

  private void registerExport(McpToolRegistry registry) {

    registry
        .tool("export_image")
        .description(
            "Writes images of one or more circuits to files without any dialog (like File >"
                + " Export Image). Formats: png, jpg, gif (bitmaps), svg and tikz (vector, .tex)."
                + " With a single circuit 'path' is the output file (extension added if missing)"
                + " or an existing directory; with several circuits (or all=true) 'path' must be a directory"
                + " (created if needed) and files are named after the circuits. Returns the"
                + " written files.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop(
                    "circuits",
                    Schema.array(
                        "Circuit names. Default: the current circuit.", Schema.string("Circuit name.")))
                .prop("all", Schema.bool("Export all circuits of the project.").with("default", false))
                .prop("path", Schema.string("Output file or directory."), true)
                .prop(
                    "format",
                    Schema.enumeration(
                            "Image format. Default: from the file extension, else png.",
                            "png", "jpg", "gif", "svg", "tikz"))
                .prop("scale", Schema.number("Pixels per canvas unit.").with("default", 1.0))
                .prop(
                    "printerView",
                    Schema.bool("Printer-friendly black/white rendering (the GUI's default).")
                        .with("default", true))
                .prop("overwrite", Schema.bool("Replace existing files.").with("default", false)))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var circuits = new ArrayList<Circuit>();
              if (args.bool("all", false)) {
                circuits.addAll(proj.getLogisimFile().getCircuits());
              } else if (args.has("circuits")) {
                for (final var name : args.list("circuits")) {
                  circuits.add(ctx.circuit(proj, new Args(Map.of("circuit", String.valueOf(name)))));
                }
              } else {
                circuits.add(ctx.circuit(proj, args));
              }
              if (circuits.isEmpty()) throw new McpToolException("No circuits to export.");
              final var path = new File(args.str("path")).getAbsoluteFile();
              var format = args.str("format", null);
              if (format == null) {
                final var n = path.getName().toLowerCase(Locale.ROOT);
                if (n.endsWith(".jpg") || n.endsWith(".jpeg")) format = "jpg";
                else if (n.endsWith(".gif")) format = "gif";
                else if (n.endsWith(".svg")) format = "svg";
                else if (n.endsWith(".tex")) format = "tikz";
                else format = "png";
              }
              final var ext =
                  switch (format) {
                    case "png" -> ".png";
                    case "jpg", "jpeg" -> ".jpg";
                    case "gif" -> ".gif";
                    case "svg" -> ".svg";
                    case "tikz" -> ".tex";
                    default -> throw new McpToolException("Unsupported format '" + format + "'.");
                  };
              final var multi = circuits.size() > 1 || args.bool("all", false);
              if (multi && path.exists() && !path.isDirectory()) {
                throw new McpToolException("With several circuits 'path' must be a directory.");
              }
              if (multi && !path.exists() && !path.mkdirs()) {
                throw new McpToolException("Could not create directory " + path);
              }
              final var overwrite = args.bool("overwrite", false);
              final var written = new ArrayList<Object>();
              for (final var circ : circuits) {
                File where;
                if (path.isDirectory()) {
                  where = new File(path, circ.getName() + ext);
                } else if (path.getName().toLowerCase(Locale.ROOT).endsWith(ext)
                    || (ext.equals(".jpg") && path.getName().toLowerCase(Locale.ROOT).endsWith(".jpeg"))) {
                  where = path;
                } else {
                  where = new File(path.getParentFile(), path.getName() + ext);
                }
                if (where.exists() && !overwrite) {
                  throw new McpToolException(where + " exists. Pass overwrite=true to replace it.");
                }
                if (where.getParentFile() != null && !where.getParentFile().exists()) {
                  where.getParentFile().mkdirs();
                }
                final var o = new RenderOptions();
                o.scale = args.dbl("scale", 1.0);
                o.margin = 5;
                o.printerView = args.bool("printerView", true);
                o.maxSize = 20000;
                o.vector = format.equals("svg") || format.equals("tikz");
                final var r = render(proj, circ, o);
                try {
                  switch (format) {
                    case "png" -> ImageIO.write(r.image(), "PNG", where);
                    case "jpg", "jpeg" -> ImageIO.write(r.image(), "JPEG", where);
                    case "gif" -> ImageIO.write(r.image(), "GIF", where);
                    case "svg" -> r.vector().writeSvg(r.width(), r.height(), where);
                    default -> r.vector().writeFile(where);
                  }
                } catch (IOException | RuntimeException e) {
                  throw new McpToolException("Could not write " + where + ": " + e.getMessage(), e);
                }
                final var entry = new LinkedHashMap<String, Object>();
                entry.put("circuit", circ.getName());
                entry.put("file", where.getAbsolutePath());
                entry.put("bytes", where.length());
                entry.put("width", r.width());
                entry.put("height", r.height());
                written.add(entry);
              }
              return Map.of("format", format, "files", written);
            });

    registry
        .tool("view_screenshot_window")
        .description(
            "GUI only. Captures what a user sees in a Logisim window (menus bar, toolbars,"
                + " explorer, attribute table, canvas, or a dialog) as a PNG by painting the window"
                + " off-screen. Default window: the project's main window. area='canvas' captures"
                + " only the visible part of the drawing canvas; the details then include the"
                + " visible canvas rectangle and zoom so pixels map to canvas coordinates:"
                + " canvasX = visible.x + pixelX/(zoom*scale).")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("window", Schema.string("Window id from window_list (e.g. 'w2') or part of its title."))
                .prop(
                    "area",
                    Schema.enumeration(
                            "'window' (whole window, default) or 'canvas' (visible canvas only;"
                                + " project windows).",
                            "window", "canvas")
                        .with("default", "window"))
                .prop("scale", Schema.number("Image scale factor (0.25..4).").with("default", 1.0)))
        .readOnly()
        .handler(
            (ctx, args) -> {
              final var w = ViewSupport.targetWindow(ctx, args);
              final var area = args.str("area", "window");
              final var scale = Math.max(0.25, Math.min(4.0, args.dbl("scale", 1.0)));
              final javax.swing.JComponent comp;
              final var details = new LinkedHashMap<String, Object>();
              details.put("window", ViewSupport.describeWindow(ctx, w));
              if ("canvas".equals(area)) {
                if (!(w instanceof Frame f)) {
                  throw new McpToolException("area='canvas' needs a project window.");
                }
                final var pane = ViewGuiTools.activeCanvasPane(f);
                comp = (javax.swing.JComponent) pane.getViewport();
                final var zoom = pane.getZoomFactor();
                final var vr = pane.getViewport().getViewRect();
                details.put("zoom", zoom);
                details.put(
                    "visible",
                    Map.of(
                        "x", (int) Math.floor(vr.x / zoom),
                        "y", (int) Math.floor(vr.y / zoom),
                        "width", (int) Math.round(vr.width / zoom),
                        "height", (int) Math.round(vr.height / zoom)));
              } else if (w instanceof javax.swing.RootPaneContainer rpc) {
                comp = rpc.getRootPane();
              } else {
                throw new McpToolException("Cannot capture this window type.");
              }
              final var cw = Math.max(1, comp.getWidth());
              final var ch = Math.max(1, comp.getHeight());
              final var img =
                  new BufferedImage(
                      Math.max(1, (int) Math.round(cw * scale)),
                      Math.max(1, (int) Math.round(ch * scale)),
                      BufferedImage.TYPE_INT_RGB);
              final var g = img.createGraphics();
              g.setColor(comp.getBackground() == null ? Color.LIGHT_GRAY : comp.getBackground());
              g.fillRect(0, 0, img.getWidth(), img.getHeight());
              g.scale(scale, scale);
              g.setClip(0, 0, cw, ch);
              comp.paint(g);
              g.dispose();
              details.put("width", img.getWidth());
              details.put("height", img.getHeight());
              details.put("scale", scale);
              return ImageResult.png(img, details);
            });
  }
}
