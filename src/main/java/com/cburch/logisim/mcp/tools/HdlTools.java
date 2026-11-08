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
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.LogisimFileActions;
import com.cburch.logisim.fpga.data.BoardInformation;
import com.cburch.logisim.fpga.data.ComponentMapParser;
import com.cburch.logisim.fpga.data.DriveStrength;
import com.cburch.logisim.fpga.data.FpgaIoInformationContainer;
import com.cburch.logisim.fpga.data.IoStandards;
import com.cburch.logisim.fpga.data.MapComponent;
import com.cburch.logisim.fpga.data.MappableResourcesContainer;
import com.cburch.logisim.fpga.data.PinActivity;
import com.cburch.logisim.fpga.data.PullBehaviors;
import com.cburch.logisim.fpga.designrulecheck.CorrectLabel;
import com.cburch.logisim.fpga.designrulecheck.SimpleDrcContainer;
import com.cburch.logisim.fpga.download.Download;
import com.cburch.logisim.fpga.download.DownloadBase;
import com.cburch.logisim.fpga.file.BoardReaderClass;
import com.cburch.logisim.fpga.gui.Reporter;
import com.cburch.logisim.fpga.hdlgenerator.HdlGeneratorFactory;
import com.cburch.logisim.fpga.settings.VendorSoftware;
import com.cburch.logisim.gui.generic.OptionPane;
import com.cburch.logisim.mcp.Args;
import com.cburch.logisim.mcp.ComponentRefs;
import com.cburch.logisim.mcp.ImageResult;
import com.cburch.logisim.mcp.McpContext;
import com.cburch.logisim.mcp.McpToolException;
import com.cburch.logisim.mcp.McpToolGroup;
import com.cburch.logisim.mcp.McpToolRegistry;
import com.cburch.logisim.mcp.Schema;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.proj.Action;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.util.FileUtil;
import com.cburch.logisim.util.Softwares;
import com.cburch.logisim.verilog.VerilogImporter;
import com.cburch.logisim.verilog.YosysRunner;
import com.cburch.logisim.vhdl.base.VhdlContent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.swing.JOptionPane;

/**
 * HDL generation, FPGA boards/mapping/download (the FPGA menu and FPGA Commander) and the HDL
 * components of a project (VHDL entities, Verilog import, VHDL simulation).
 */
public final class HdlTools implements McpToolGroup {

  private static final int MAX_LINES = 400;

  // ---------------------------------------------------------------------------------------------
  // Registration
  // ---------------------------------------------------------------------------------------------

  @Override
  public void register(McpToolRegistry registry) {
    registerGeneration(registry);
    registerBoards(registry);
    registerVhdl(registry);
  }

  private void registerGeneration(McpToolRegistry registry) {
    registry
        .tool("hdl_generate")
        .description(
            "Generates VHDL or Verilog for a circuit and all its subcircuits, like the FPGA"
                + " Commander's 'Generate HDL only' (no dialogs): first auto-labels components"
                + " that need a label (undoable), runs the design rule check (DRC), then writes"
                + " the HDL files plus the FPGA top-level shell (and the vendor scripts) into"
                + " <directory>/<projectName>/<circuit>/ (sources in vhdl/ or verilog/). A board is"
                + " needed for the"
                + " top-level shell (clock, pin mapping); unmapped circuit I/O is accepted. Returns"
                + " success, the output directory, the generated files (relative paths, sizes),"
                + " and the DRC/generation errors and warnings as structured entries (message,"
                + " severity, circuit, offending components). The project name must not contain"
                + " spaces (save it under a name without spaces first).")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("circuit", CIRCUIT_ARG)
                .prop(
                    "language",
                    Schema.enumeration(
                        "HDL to generate. Default: the 'HDL type' preference (VHDL if it is"
                            + " 'None'). The preference itself is not changed.",
                        "VHDL",
                        "Verilog"))
                .prop(
                    "directory",
                    Schema.string(
                        "Workspace directory to write into (created if needed). Default: the FPGA"
                            + " workspace preference (see fpga_get_settings)."))
                .prop(
                    "board",
                    Schema.string(
                        "FPGA board name (see fpga_list_boards). Default: the circuit's last"
                            + " download board, else the selected board, else the first board."))
                .prop(
                    "mapFile",
                    Schema.string("Optional XML map file to apply before generation."))
                .prop(
                    "tickFrequency",
                    Schema.number(
                        "Tick frequency in Hz for clocked designs. Default: the circuit's"
                            + " download frequency, else its simulation tick frequency.")))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var circ = ctx.circuit(proj, args);
              return generate(ctx, proj, circ, args);
            });

    registry
        .tool("fpga_download")
        .description(
            "Runs the complete FPGA flow like the FPGA Commander's 'Download' (or --test-fpga):"
                + " annotate, DRC, map check, HDL generation, vendor synthesis/place&route scripts"
                + " and programming of the board. Needs the vendor toolchain (Quartus, ISE, Vivado"
                + " or the openFPGA tools) configured with fpga_set_settings and a connected board;"
                + " otherwise it fails with a clear message before doing anything. Can take"
                + " minutes. Returns success, captured toolchain output (last"
                + " " + MAX_LINES + " lines) and errors. The circuit's I/O should be mapped to the"
                + " board (fpga_map) unless allowIncompleteMap=true.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("circuit", CIRCUIT_ARG)
                .prop("board", Schema.string("FPGA board name (default as for hdl_generate)."))
                .prop("language", Schema.enumeration("HDL to use (default: preference).", "VHDL", "Verilog"))
                .prop("directory", Schema.string("Workspace directory (default: preference)."))
                .prop("mapFile", Schema.string("Optional XML map file to apply first."))
                .prop("tickFrequency", Schema.number("Tick frequency in Hz (default as for hdl_generate)."))
                .prop("writeToFlash", Schema.bool("Program the board's flash instead of the FPGA (default false)."))
                .prop("cable", Schema.string("Programming cable name (Altera/Quartus only)."))
                .prop(
                    "allowIncompleteMap",
                    Schema.bool(
                        "Continue when some circuit I/O is not mapped to the board (default"
                            + " false).")))
        .offModelThread()
        .handler(this::download);
  }

  private void registerBoards(McpToolRegistry registry) {
    registry
        .tool("fpga_list_boards")
        .description(
            "Lists the FPGA boards known to Logisim (built-in and added external board files) and"
                + " the currently selected board. With details=true also vendor, part and clock"
                + " frequency of each board (slower).")
        .input(Schema.object().prop("details", Schema.bool("Include FPGA details (default false).")))
        .readOnly()
        .handler(
            (ctx, args) -> {
              final var out = new LinkedHashMap<String, Object>();
              final var names = AppPreferences.Boards.getBoardNames();
              out.put("selected", AppPreferences.SelectedBoard.get());
              if (args.bool("details", false)) {
                final var list = new ArrayList<Object>();
                for (final var n : names) {
                  final var d = new LinkedHashMap<String, Object>();
                  d.put("name", n);
                  try {
                    d.putAll(fpgaInfo(loadBoard(n)));
                  } catch (RuntimeException e) {
                    d.put("error", String.valueOf(e.getMessage()));
                  }
                  list.add(d);
                }
                out.put("boards", list);
              } else {
                out.put("boards", new ArrayList<>(names));
              }
              return out;
            });

    registry
        .tool("fpga_board_info")
        .description(
            "Describes an FPGA board: vendor, technology, part, package, clock, and every I/O"
                + " component (LEDs, buttons, switches, 7-segment displays, ...) with its id (used"
                + " by fpga_map), type, label, number of pins, pin names and FPGA pin locations,"
                + " active level and electrical settings. With image=true the board picture is"
                + " returned too (I/O rectangles are in its pixel coordinates).")
        .input(
            Schema.object()
                .prop("board", Schema.string("Board name (default: the selected board)."))
                .prop(
                    "type",
                    Schema.string(
                        "Only list I/O components of this type (e.g. Led, Button, DIPSwitch, Pin,"
                            + " SevenSegment)."))
                .prop("image", Schema.bool("Also return the board image (default false).")))
        .readOnly()
        .handler(
            (ctx, args) -> {
              final var name = resolveBoardName(args, null);
              final var board = loadBoard(name);
              final var out = new LinkedHashMap<String, Object>();
              out.put("name", board.getBoardName());
              out.putAll(fpgaInfo(board));
              final var comps = board.getAllComponents();
              final var ids = ioIds(comps);
              final var list = new ArrayList<Object>();
              final var type = args.str("type", null);
              for (var i = 0; i < comps.size(); i++) {
                if (type != null && !comps.get(i).getType().toString().equalsIgnoreCase(type)) continue;
                list.add(ioInfo(comps.get(i), ids.get(i), true));
              }
              out.put("ioComponents", list);
              if (args.bool("image", false) && board.getImage() != null) {
                return ImageResult.png(board.getImage(), out);
              }
              return out;
            });

    registry
        .tool("fpga_map")
        .description(
            "Shows and edits how the I/O of a circuit (pins, LEDs, buttons, DIP switches, ..."
                + " in the circuit and its subcircuits) is mapped to the I/O components of an FPGA"
                + " board, like the FPGA Commander's mapping dialog. Runs the DRC first (and"
                + " auto-labels unlabeled components, undoable). Optionally loads an XML map file,"
                + " clears all mappings, and applies 'mappings' in order. Returns every mappable"
                + " circuit component (key such as '/a' or '/sub/led1', type, and per pin: label,"
                + " direction, and what it is mapped to), how many pins are still unmapped, and"
                + " the board I/O components with their ids. Mappings are stored in the circuit"
                + " and saved with the project (project_save); they are not undoable.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("circuit", CIRCUIT_ARG)
                .prop("board", Schema.string("Board name (default as for hdl_generate)."))
                .prop("mapFile", Schema.string("XML map file to load first."))
                .prop("unmapAll", Schema.bool("Remove all mappings first (default false)."))
                .prop(
                    "mappings",
                    Schema.array(
                        "Mapping operations. Each object has 'component' (key like '/a', or just"
                            + " the label 'a') and optionally 'pin' (pin label or index; omit to"
                            + " map the whole component) and exactly one of: 'to' (board I/O id"
                            + " or label from fpga_board_info, with optional 'ioPin' index for"
                            + " single-pin mapping, default 0), 'open': true (leave an output"
                            + " unconnected), 'constant': number (drive an input with a constant;"
                            + " bit i goes to pin i), or 'unmap': true.",
                        Schema.object().additionalProperties(true)))
                .prop(
                    "includeBoardIo",
                    Schema.bool("Include the list of board I/O components (default true).")))
        .handler(this::map);

    registry
        .tool("fpga_get_settings")
        .description(
            "Returns the FPGA/HDL settings (Preferences > FPGA): HDL type, FPGA workspace"
                + " directory, selected board, VHDL options, the tool path of each vendor"
                + " toolchain with whether all required programs were found there, and the"
                + " QuestaSim path used for VHDL validation/simulation.")
        .readOnly()
        .handler((ctx, args) -> settings());

    registry
        .tool("fpga_set_settings")
        .description(
            "Changes FPGA/HDL settings (persisted preferences). Only the given keys change."
                + " Tool paths are directories containing the vendor programs; a path where the"
                + " programs are missing is rejected unless force=true. Returns the new settings.")
        .input(
            Schema.object()
                .prop("hdlType", Schema.enumeration("HDL type preference.", "VHDL", "Verilog", "None"))
                .prop("workspace", Schema.string("FPGA workspace directory for generated files."))
                .prop("selectedBoard", Schema.string("Board selected by default."))
                .prop("quartusPath", Schema.string("Altera/Intel Quartus bin directory."))
                .prop("isePath", Schema.string("Xilinx ISE bin directory."))
                .prop("vivadoPath", Schema.string("Xilinx Vivado bin directory."))
                .prop("openFpgaPath", Schema.string("Directory with ghdl, yosys, nextpnr-ecp5, ecppack, openFPGALoader."))
                .prop("questaPath", Schema.string("QuestaSim bin directory (VHDL validation/simulation)."))
                .prop("questaValidation", Schema.bool("Validate VHDL entities with QuestaSim."))
                .prop("vhdlKeywordsUpperCase", Schema.bool("Write VHDL keywords in upper case."))
                .prop("vhdlStandard", Schema.enumeration("VHDL standard.", "1993", "2002", "2008"))
                .prop("suppressGatedClockWarnings", Schema.bool("Hide gated-clock DRC warnings."))
                .prop("suppressOpenPinWarnings", Schema.bool("Hide open-pin DRC warnings."))
                .prop("force", Schema.bool("Store tool paths even if the programs are not found.")))
        .handler((ctx, args) -> setSettings(args));
  }

  private void registerVhdl(McpToolRegistry registry) {
    registry
        .tool("hdl_vhdl_list")
        .description(
            "Lists the VHDL entities defined in a project (Project > Add VHDL entity) with"
                + " validity, ports and generics.")
        .input(Schema.object().prop("project", PROJECT_ARG))
        .readOnly()
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var list = new ArrayList<Object>();
              for (final var v : proj.getLogisimFile().getVhdlContents()) list.add(vhdlInfo(v, false));
              return Map.of("entities", list);
            });

    registry
        .tool("hdl_vhdl_entity_get")
        .description("Returns a VHDL entity of the project: its full source text, ports and generics.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("name", Schema.string("Entity name."), true))
        .readOnly()
        .handler((ctx, args) -> vhdlInfo(vhdl(ctx.project(args), args.str("name")), true));

    registry
        .tool("hdl_vhdl_entity_create")
        .description(
            "Adds a VHDL entity to the project (like Project > Add VHDL entity / Import VHDL"
                + " entity, without dialogs) as one undoable action. Without 'content' or 'path'"
                + " Logisim's template is used. The source is parsed (entity, ports, generics);"
                + " the entity name in the source becomes the component name. Invalid source or"
                + " a name that is not a valid VHDL identifier or already used is rejected with"
                + " the parser's message. The entity can then be placed like any component (its"
                + " name is the component type).")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop(
                    "name",
                    Schema.string(
                        "Entity name (required with the template; otherwise taken from the"
                            + " source)."))
                .prop("content", Schema.string("Full VHDL source (library/use clauses, entity, architecture)."))
                .prop("path", Schema.string("Read the VHDL source from this .vhd file instead.")))
        .handler(this::createVhdl);

    registry
        .tool("hdl_vhdl_entity_set_content")
        .description(
            "Replaces the source text of a VHDL entity of the project (like editing it in the"
                + " HDL editor and validating). Undoable. If the new source does not parse, nothing"
                + " changes and the parser's message is returned as an error. Changing the entity"
                + " name in the source renames the component.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("name", Schema.string("Current entity name."), true)
                .prop("content", Schema.string("New full VHDL source."), true))
        .handler(this::setVhdlContent);

    registry
        .tool("hdl_vhdl_entity_remove")
        .description(
            "Removes a VHDL entity from the project (undoable). Refuses when it is still used in a"
                + " circuit.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("name", Schema.string("Entity name."), true))
        .destructive()
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var v = vhdl(proj, args.str("name"));
              if (!proj.getDependencies().canRemove(v)) {
                throw new McpToolException(
                    "VHDL entity '" + v.getName() + "' is used in a circuit; delete those"
                        + " components first.");
              }
              proj.doAction(LogisimFileActions.removeVhdl(v));
              return Map.of("removed", v.getName());
            });

    registry
        .tool("hdl_vhdl_simulation")
        .description(
            "Shows or changes the state of the VHDL simulation (Simulate > Enable VHDL"
                + " simulation). Simulating VHDL entities requires QuestaSim (Mentor/Siemens);"
                + " 'enable' fails clearly when its path (fpga_set_settings questaPath) is not"
                + " valid. action: 'status' (default), 'enable', 'disable' or 'restart'"
                + " (regenerate the simulation files).")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("action", Schema.enumeration("What to do.", "status", "enable", "disable", "restart")))
        .handler(this::vhdlSimulation);

    registry
        .tool("hdl_verilog_import")
        .description(
            "Imports Verilog as Logisim circuits (Project > Import Verilog): either synthesizes"
                + " Verilog/SystemVerilog sources with Yosys ('sources', needs the yosys"
                + " executable) or reads a netlist already produced by Yosys 'write_json'"
                + " ('jsonPath'; no Yosys needed). One circuit is created per module (names made"
                + " unique), as one undoable action. Returns the created circuits, the top"
                + " circuit, the component count and Yosys' messages.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("sources", Schema.array("Verilog source files.", Schema.string("Path")))
                .prop("jsonPath", Schema.string("Yosys JSON netlist (alternative to 'sources')."))
                .prop("top", Schema.string("Top module name (default: auto-detected)."))
                .prop("yosys", Schema.string("Yosys executable (default: last used, else 'yosys')."))
                .prop("includes", Schema.array("Include directories.", Schema.string("Path")))
                .prop("defines", Schema.array("Macro definitions NAME or NAME=VALUE.", Schema.string("Define")))
                .prop("workingDirectory", Schema.string("Directory relative paths are resolved against (default: the first source's directory)."))
                .prop("systemVerilog", Schema.bool("Parse sources as SystemVerilog (default false)."))
                .prop(
                    "undefined",
                    Schema.enumeration("How to treat undefined (x) bits: fail, or replace by 0 or 1 (default 'error').", "error", "0", "1"))
                .prop(
                    "memories",
                    Schema.enumeration("How to map memories (default AUTO).", "AUTO", "NATIVE", "LOGIC")))
        .offModelThread()
        .handler(this::verilogImport);
  }

  // ---------------------------------------------------------------------------------------------
  // Boards
  // ---------------------------------------------------------------------------------------------

  static String resolveBoardName(Args args, Circuit circ) {
    final var names = AppPreferences.Boards.getBoardNames();
    if (names.isEmpty()) throw new McpToolException("No FPGA boards are available.");
    if (args.has("board")) {
      final var want = args.str("board");
      for (final var n : names) if (n.equals(want)) return n;
      for (final var n : names) if (n.equalsIgnoreCase(want)) return n;
      throw new McpToolException(
          "Unknown board '" + want + "'. Use fpga_list_boards; e.g. " + names.subList(0, Math.min(8, names.size())));
    }
    if (circ != null) {
      final var b = circ.getDownloadBoard();
      if (b != null && names.contains(b)) return b;
    }
    final var sel = AppPreferences.SelectedBoard.get();
    if (sel != null && names.contains(sel)) return sel;
    return names.get(0);
  }

  static BoardInformation loadBoard(String name) {
    final var path = AppPreferences.Boards.getBoardFilePath(name);
    if (path == null) throw new McpToolException("No board file for board '" + name + "'.");
    final var info = new BoardReaderClass(path).getBoardInformation();
    if (info == null) throw new McpToolException("Could not read the board file of '" + name + "' (" + path + ").");
    return info;
  }

  private static Map<String, Object> fpgaInfo(BoardInformation board) {
    final var f = board.fpga;
    final var out = new LinkedHashMap<String, Object>();
    out.put("vendor", VendorSoftware.getVendorString(f.getVendor()));
    out.put("technology", f.getTechnology());
    out.put("part", f.getPart());
    out.put("package", f.getPackage());
    out.put("speedGrade", f.getSpeedGrade());
    out.put("clockFrequencyHz", f.getClockFrequency());
    out.put("clockPin", f.getClockPinLocation());
    out.put("flash", f.isFlashDefined() ? f.getFlashName() : null);
    out.put("ioComponentCount", board.getNrOfDefinedComponents());
    return out;
  }

  /** Ids for board I/O components: the label if unique, else TYPE_n (n counts per type). */
  private static List<String> ioIds(List<FpgaIoInformationContainer> comps) {
    final var labelCount = new LinkedHashMap<String, Integer>();
    for (final var c : comps) {
      final var l = c.getLabel();
      if (l != null && !l.isBlank()) labelCount.merge(l, 1, Integer::sum);
    }
    final var typeCount = new LinkedHashMap<String, Integer>();
    final var ids = new ArrayList<String>();
    for (final var c : comps) {
      final var type = c.getType().toString();
      final var n = typeCount.merge(type, 1, Integer::sum) - 1;
      final var l = c.getLabel();
      ids.add(l != null && !l.isBlank() && labelCount.get(l) == 1 ? l : type + "_" + n);
    }
    return ids;
  }

  private static Map<String, Object> ioInfo(FpgaIoInformationContainer c, String id, boolean full) {
    final var out = new LinkedHashMap<String, Object>();
    out.put("id", id);
    out.put("type", c.getType().toString());
    if (c.getLabel() != null && !c.getLabel().isBlank()) out.put("label", c.getLabel());
    out.put("pins", c.getNrOfPins());
    if (!full) return out;
    out.put("inputs", c.nrInputs());
    out.put("outputs", c.nrOutputs());
    out.put("ios", c.getNrOfIoPins());
    if (c.getNrOfPins() == 1) {
      out.put("fpgaPin", c.getPinLocation(0));
    } else {
      final var names = new ArrayList<String>();
      final var locs = new ArrayList<String>();
      for (var i = 0; i < c.getNrOfPins(); i++) {
        names.add(c.getPinName(i));
        locs.add(c.getPinLocation(i));
      }
      out.put("pinNames", names);
      out.put("fpgaPins", locs);
    }
    out.put("activeLevel", c.getActivityLevel() == PinActivity.ACTIVE_LOW ? "low" : "high");
    out.put("ioStandard", safe(() -> IoStandards.getIoString(c.getIoStandard())));
    out.put("pull", safe(() -> PullBehaviors.getPullString(c.getPullBehavior())));
    out.put("drive", safe(() -> DriveStrength.getDriveString(c.getDrive())));
    final var r = c.getRectangle();
    if (r != null) {
      final var rect = new LinkedHashMap<String, Object>();
      rect.put("x", r.getXpos());
      rect.put("y", r.getYpos());
      rect.put("width", r.getWidth());
      rect.put("height", r.getHeight());
      out.put("rect", rect);
    }
    return out;
  }

  private static Object safe(Callable<Object> c) {
    try {
      return c.call();
    } catch (Exception e) {
      return null;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Report capture, dialog answers and temporary preferences
  // ---------------------------------------------------------------------------------------------

  /** Collects everything the FPGA code reports while an operation runs. */
  private static final class Capture implements Reporter.Listener, AutoCloseable {
    final McpContext ctx;
    final List<Object> errors = new ArrayList<>();
    final List<Object> warnings = new ArrayList<>();
    final List<String> infos = new ArrayList<>();
    final List<String> console = new ArrayList<>();
    final List<Object> dialogs = new ArrayList<>();
    int consoleDropped;

    Capture(McpContext ctx) {
      this.ctx = ctx;
      Reporter.report.addListener(this);
    }

    @Override
    public synchronized void reported(String kind, Object message) {
      switch (kind) {
        case "console" -> {
          console.add(String.valueOf(message));
          if (console.size() > MAX_LINES) {
            console.remove(0);
            consoleDropped++;
          }
        }
        case "info" -> {
          if (infos.size() < MAX_LINES) infos.add(String.valueOf(message));
        }
        case "warning", "severeWarning" -> warnings.add(entry(kind, message));
        default -> errors.add(entry(kind, message));
      }
    }

    private Object entry(String kind, Object message) {
      final var e = new LinkedHashMap<String, Object>();
      e.put("message", String.valueOf(message).trim());
      var severity = switch (kind) {
        case "fatal" -> "fatal";
        case "severe", "severeWarning" -> "severe";
        default -> "normal";
      };
      if (message instanceof SimpleDrcContainer drc) {
        severity = switch (drc.getSeverity()) {
          case SimpleDrcContainer.LEVEL_FATAL -> "fatal";
          case SimpleDrcContainer.LEVEL_SEVERE -> "severe";
          default -> "normal";
        };
        if (drc.hasCircuit()) e.put("circuit", drc.getCircuit().getName());
        final var comps = new ArrayList<Object>();
        for (final var o : drc.getDrcComponents()) {
          if (o instanceof Component c) {
            try {
              comps.add(ComponentRefs.summary(ctx, c));
            } catch (RuntimeException ex) {
              comps.add(String.valueOf(c));
            }
          }
        }
        if (!comps.isEmpty()) e.put("components", comps);
      }
      e.put("severity", severity);
      return e;
    }

    synchronized void putInto(Map<String, Object> out, boolean withConsole) {
      out.put("errors", new ArrayList<>(errors));
      out.put("warnings", new ArrayList<>(warnings));
      out.put("messages", new ArrayList<>(infos));
      if (withConsole) {
        out.put("toolOutput", new ArrayList<>(console));
        if (consoleDropped > 0) out.put("toolOutputDroppedLines", consoleDropped);
      }
      if (!dialogs.isEmpty()) out.put("dialogsAnswered", new ArrayList<>(dialogs));
    }

    /** Dialog interceptor: confirm dialogs (e.g. "map incomplete, continue?") get {@code yes}. */
    OptionPane.DialogInterceptor interceptor(boolean yes) {
      return (kind, title, message, options, initialValue) -> {
        final var rec = new LinkedHashMap<String, Object>();
        rec.put("kind", kind);
        if (title != null) rec.put("title", title);
        rec.put("message", String.valueOf(message));
        Object answer = null;
        if ("confirm".equals(kind) || "option".equals(kind)) {
          answer = yes ? OptionPane.YES_OPTION : JOptionPane.NO_OPTION;
          rec.put("answer", yes ? "yes" : "no");
        }
        synchronized (this) {
          dialogs.add(rec);
        }
        return answer;
      };
    }

    @Override
    public void close() {
      Reporter.report.removeListener(this);
    }
  }

  /** Runs {@code body} with the HDL type / workspace preferences temporarily changed. */
  private static <T> T withPrefs(String hdl, String workspace, Callable<T> body) throws Exception {
    final var oldHdl = AppPreferences.HdlType.get();
    final var oldWs = AppPreferences.FPGA_Workspace.get();
    try {
      if (hdl != null && !hdl.equals(oldHdl)) AppPreferences.HdlType.set(hdl);
      if (workspace != null && !workspace.equals(oldWs)) AppPreferences.FPGA_Workspace.set(workspace);
      return body.call();
    } finally {
      if (!AppPreferences.HdlType.get().equals(oldHdl)) AppPreferences.HdlType.set(oldHdl);
      if (!AppPreferences.FPGA_Workspace.get().equals(oldWs)) AppPreferences.FPGA_Workspace.set(oldWs);
    }
  }

  private static <T> T withInterceptor(OptionPane.DialogInterceptor icpt, Callable<T> body) throws Exception {
    final var previous = OptionPane.getInterceptor();
    OptionPane.setInterceptor(icpt);
    try {
      return body.call();
    } finally {
      OptionPane.setInterceptor(previous);
    }
  }

  private static String language(Args args) {
    if (args.has("language")) {
      final var l = args.str("language");
      if (l.equalsIgnoreCase("vhdl")) return HdlGeneratorFactory.VHDL;
      if (l.equalsIgnoreCase("verilog")) return HdlGeneratorFactory.VERILOG;
      throw new McpToolException("Unknown language '" + l + "'. Use VHDL or Verilog.");
    }
    final var pref = AppPreferences.HdlType.get();
    return DownloadBase.isHdlGenerationEnabled(pref) ? pref : HdlGeneratorFactory.VHDL;
  }

  private static String workspace(Args args) {
    if (!args.has("directory")) return AppPreferences.FPGA_Workspace.get();
    final var dir = new File(args.str("directory")).getAbsoluteFile();
    if (!dir.isDirectory() && !dir.mkdirs()) {
      throw new McpToolException("Cannot create directory " + dir);
    }
    return dir.getPath();
  }

  private static double tickFrequency(Args args, Circuit circ) {
    if (args.has("tickFrequency")) return args.dbl("tickFrequency");
    final var down = circ.getDownloadFrequency();
    return down > 0 ? down : circ.getTickFrequency();
  }

  private static String projDir(String workspace, Project proj, Circuit circ) {
    var dir = workspace + File.separator + proj.getLogisimFile().getName();
    if (!dir.endsWith(File.separator)) dir += File.separator;
    return dir + CorrectLabel.getCorrectLabel(circ.getName()) + File.separator;
  }

  private static String mapFile(Args args) {
    if (!args.has("mapFile")) return null;
    final var f = new File(args.str("mapFile")).getAbsoluteFile();
    if (!f.isFile()) throw new McpToolException("Map file not found: " + f);
    return f.getPath();
  }

  private static void checkProjectName(Project proj) {
    final var name = proj.getLogisimFile().getName();
    if (name.contains(" ")) {
      throw new McpToolException(
          "The project name '" + name + "' contains spaces, which HDL generation does not allow."
              + " Save the project under a file name without spaces (project_save with 'path').");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // hdl_generate / fpga_download
  // ---------------------------------------------------------------------------------------------

  private Object generate(McpContext ctx, Project proj, Circuit circ, Args args) throws Exception {
    checkProjectName(proj);
    final var lang = language(args);
    final var ws = workspace(args);
    final var boardName = resolveBoardName(args, circ);
    final var board = loadBoard(boardName);
    final var mapFile = mapFile(args);
    final var freq = tickFrequency(args, circ);
    final var out = new LinkedHashMap<String, Object>();
    out.put("circuit", circ.getName());
    out.put("language", lang);
    out.put("board", boardName);
    final var dir = projDir(ws, proj, circ);
    try (var cap = new Capture(ctx)) {
      final boolean ok =
          withInterceptor(
              cap.interceptor(true),
              () ->
                  withPrefs(
                      lang,
                      ws,
                      () ->
                          new Download(proj, circ.getName(), freq, board, mapFile, false, false,
                                  true, 1.0, 1.0, (String) null)
                              .runTty()));
      out.put("success", ok);
      out.put("outputDirectory", dir);
      out.put("files", listFiles(Path.of(dir), Path.of(dir)));
      cap.putInto(out, false);
      if (!ok && cap.errors.isEmpty()) {
        out.put("errors", List.of(Map.of("message", "HDL generation failed (see messages).", "severity", "fatal")));
      }
    }
    return out;
  }

  private static List<Object> listFiles(Path dir, Path base) {
    final var files = new ArrayList<Object>();
    if (!Files.isDirectory(dir)) return files;
    try (Stream<Path> s = Files.walk(dir)) {
      s.filter(Files::isRegularFile)
          .sorted()
          .limit(1000)
          .forEach(
              p -> {
                long size;
                try {
                  size = Files.size(p);
                } catch (IOException e) {
                  size = -1;
                }
                files.add(Map.of("path", base.relativize(p).toString(), "bytes", size));
              });
    } catch (IOException e) {
      files.add(Map.of("error", String.valueOf(e.getMessage())));
    }
    return files;
  }

  private Object download(McpContext ctx, Args args) throws Exception {
    // Preparation on the model thread.
    final var prep =
        ctx.onModelThread(
            () -> {
              final var proj = ctx.project(args);
              final var circ = ctx.circuit(proj, args);
              checkProjectName(proj);
              final var boardName = resolveBoardName(args, circ);
              final var board = loadBoard(boardName);
              final var vendor = board.fpga.getVendor();
              final var vendorName = VendorSoftware.getVendorString(vendor);
              final var toolPath = VendorSoftware.getToolPath(vendor);
              if (toolPath == null || !VendorSoftware.toolsPresent(vendor, toolPath)) {
                final var sw = VendorSoftware.getSoftware(vendor);
                throw new McpToolException(
                    "Cannot synthesize/download for board '" + boardName + "': the " + vendorName
                        + " toolchain was not found"
                        + (toolPath == null || toolPath.isBlank()
                            ? " (no tool path configured)"
                            : " in '" + toolPath + "'")
                        + ". Required programs: "
                        + (sw == null ? "?" : String.join(", ", sw.getBinaries()))
                        + ". Install the vendor tools and set the path with fpga_set_settings, or"
                        + " use hdl_generate to only generate the HDL files.");
              }
              if (args.has("cable") && vendor != VendorSoftware.VENDOR_ALTERA) {
                throw new McpToolException("'cable' is only supported for Altera/Intel boards.");
              }
              final var cap = new Capture(ctx);
              try {
                circ.annotate(proj, false, false);
              } catch (RuntimeException e) {
                cap.close();
                throw e;
              }
              return new Object[] {proj, circ, board, boardName, cap};
            });
    final var proj = (Project) prep[0];
    final var circ = (Circuit) prep[1];
    final var board = (BoardInformation) prep[2];
    final var cap = (Capture) prep[4];
    final var out = new LinkedHashMap<String, Object>();
    out.put("circuit", circ.getName());
    out.put("board", prep[3]);
    try (cap) {
      final var lang = language(args);
      final var ws = workspace(args);
      final var mapFile = mapFile(args);
      final var freq = tickFrequency(args, circ);
      final var cable = args.has("cable") ? args.str("cable") : null;
      final var flash = args.bool("writeToFlash", false);
      // Like the FPGA Commander, the flow itself runs outside the event thread.
      final boolean ok =
          withInterceptor(
              cap.interceptor(args.bool("allowIncompleteMap", false)),
              () ->
                  withPrefs(
                      lang,
                      ws,
                      () ->
                          new Download(proj, circ.getName(), freq, board, mapFile, flash, false,
                                  false, 1.0, 1.0, cable)
                              .runTty()));
      out.put("success", ok);
      out.put("language", lang);
      out.put("outputDirectory", projDir(ws, proj, circ));
      cap.putInto(out, true);
      if (!ok && cap.errors.isEmpty()) {
        out.put(
            "errors",
            List.of(
                Map.of(
                    "message",
                    "The FPGA flow stopped (incomplete map not allowed, or a toolchain step"
                        + " failed; see toolOutput/messages).",
                    "severity",
                    "fatal")));
      }
    }
    return out;
  }

  // ---------------------------------------------------------------------------------------------
  // fpga_map
  // ---------------------------------------------------------------------------------------------

  /** Gives access to the DRC and mapping steps of the download flow. */
  private static final class Mapper extends DownloadBase {
    Mapper(Project proj, BoardInformation board) {
      myProject = proj;
      myBoardInformation = board;
    }

    boolean drc(String circuit) {
      return performDrc(circuit, AppPreferences.HdlType.get());
    }

    MappableResourcesContainer resources(String circuit) {
      return mapDesign(circuit) ? myMappableResources : null;
    }
  }

  private Object map(McpContext ctx, Args args) throws Exception {
    final var proj = ctx.project(args);
    final var circ = ctx.circuit(proj, args);
    final var boardName = resolveBoardName(args, circ);
    final var board = loadBoard(boardName);
    final var out = new LinkedHashMap<String, Object>();
    out.put("circuit", circ.getName());
    out.put("board", boardName);
    try (var cap = new Capture(ctx)) {
      final var lang = language(args);
      final MappableResourcesContainer res =
          withInterceptor(
              cap.interceptor(false),
              () ->
                  withPrefs(
                      lang,
                      null,
                      () -> {
                        circ.annotate(proj, false, false);
                        final var mapper = new Mapper(proj, board);
                        if (!mapper.drc(circ.getName())) return null;
                        return mapper.resources(circ.getName());
                      }));
      if (res == null) {
        out.put("drcPassed", false);
        cap.putInto(out, false);
        out.remove("messages");
        return out;
      }
      out.put("drcPassed", true);
      final var ioComps = res.getIoComponents();
      final var ids = ioIds(ioComps);
      final var results = new ArrayList<Object>();
      var changed = false;
      if (args.has("mapFile")) {
        final var parser = new ComponentMapParser(new File(mapFile(args)), res, board);
        final var code = parser.parseFile();
        if (code != 0) throw new McpToolException("Loading the map file failed: " + parser.getError(code));
        changed = true;
        results.add(Map.of("op", "loadMapFile", "ok", true));
      }
      if (args.bool("unmapAll", false)) {
        res.unMapAll();
        changed = true;
      }
      for (final var o : args.list("mappings", List.of())) {
        try {
          results.add(applyMapping(Args.of(o), res, ioComps, ids));
        } catch (McpToolException e) {
          final var r = new LinkedHashMap<String, Object>();
          r.put("mapping", o);
          r.put("ok", false);
          r.put("reason", e.getMessage());
          results.add(r);
        }
        changed = true;
      }
      if (changed) res.markChanged();
      if (!results.isEmpty()) out.put("results", results);

      final var idOf = new IdentityHashMap<FpgaIoInformationContainer, String>();
      for (var i = 0; i < ioComps.size(); i++) idOf.put(ioComps.get(i), ids.get(i));
      final var comps = new ArrayList<Object>();
      var unmapped = 0;
      for (final var e : sortedResources(res)) {
        final var mc = e.getValue();
        final var c = new LinkedHashMap<String, Object>();
        c.put("key", key(e.getKey()));
        c.put("type", mc.getComponentFactory().getName());
        final var pins = new ArrayList<Object>();
        for (var i = 0; i < mc.getNrOfPins(); i++) {
          final var p = new LinkedHashMap<String, Object>();
          p.put("index", i);
          p.put("label", mc.getPinLabel(i));
          p.put("direction", mc.isInput(i) ? "input" : mc.isOutput(i) ? "output" : "inout");
          p.put("mapped", mc.isMapped(i));
          if (!mc.isMapped(i)) unmapped++;
          if (mc.isOpenMapped(i)) p.put("open", true);
          if (mc.isConstantMapped(i)) p.put("constant", mc.isZeroConstantMap(i) ? 0 : 1);
          final var io = mc.getFpgaInfo(i);
          if (io != null) {
            final var m = new LinkedHashMap<String, Object>();
            m.put("io", idOf.getOrDefault(io, io.getDisplayString()));
            m.put("ioType", io.getType().toString());
            m.put("ioPin", mc.getMapIoPin(i));
            m.put("fpgaPin", mc.getPinLocation(i));
            p.put("mappedTo", m);
          }
          pins.add(p);
        }
        c.put("pins", pins);
        comps.add(c);
      }
      out.put("components", comps);
      out.put("unmappedPins", unmapped);
      out.put("completelyMapped", res.isCompletelyMapped());
      if (args.bool("includeBoardIo", true)) {
        final var list = new ArrayList<Object>();
        for (var i = 0; i < ioComps.size(); i++) {
          final var info = ioInfo(ioComps.get(i), ids.get(i), false);
          info.put("inUse", ioComps.get(i).hasMap());
          list.add(info);
        }
        out.put("boardIo", list);
      }
      if (!cap.errors.isEmpty()) out.put("errors", new ArrayList<>(cap.errors));
      if (!cap.warnings.isEmpty()) out.put("warnings", new ArrayList<>(cap.warnings));
    }
    return out;
  }

  private static List<Map.Entry<ArrayList<String>, MapComponent>> sortedResources(
      MappableResourcesContainer res) {
    final var list = new ArrayList<>(res.getMappableResources().entrySet());
    list.sort((a, b) -> key(a.getKey()).compareTo(key(b.getKey())));
    return list;
  }

  private static String key(List<String> k) {
    final var sb = new StringBuilder();
    for (var i = 1; i < k.size(); i++) sb.append('/').append(k.get(i));
    return sb.toString();
  }

  private static Map<String, Object> applyMapping(
      Args m, MappableResourcesContainer res, List<FpgaIoInformationContainer> ioComps, List<String> ids) {
    final var r = new LinkedHashMap<String, Object>();
    final var want = m.str("component");
    r.put("component", want);
    MapComponent mc = null;
    final var norm = want.startsWith("/") ? want : "/" + want;
    for (final var e : res.getMappableResources().entrySet()) {
      if (key(e.getKey()).equals(norm)) mc = e.getValue();
    }
    if (mc == null) {
      for (final var e : res.getMappableResources().entrySet()) {
        if (key(e.getKey()).equalsIgnoreCase(norm)) mc = e.getValue();
      }
    }
    if (mc == null) {
      final var keys = new ArrayList<String>();
      for (final var k : res.getMappableResources().keySet()) keys.add(key(k));
      throw new McpToolException("Unknown mappable component '" + want + "'. Known: " + keys);
    }
    var pin = -1;
    if (m.has("pin")) {
      final var p = String.valueOf(m.get("pin"));
      for (var i = 0; i < mc.getNrOfPins(); i++) if (p.equals(mc.getPinLabel(i))) pin = i;
      if (pin < 0) {
        try {
          pin = (int) Double.parseDouble(p);
        } catch (NumberFormatException e) {
          pin = -1;
        }
      }
      if (pin < 0 || pin >= mc.getNrOfPins()) {
        throw new McpToolException("Component '" + want + "' has no pin '" + p + "'.");
      }
      r.put("pin", pin);
    }
    boolean ok;
    if (m.bool("unmap", false)) {
      if (pin >= 0) mc.unmap(pin);
      else mc.unmap();
      ok = true;
    } else if (m.bool("open", false)) {
      if (pin >= 0) {
        ok = mc.tryOpenMap(pin);
      } else {
        ok = true;
        for (var i = 0; i < mc.getNrOfPins(); i++) ok &= mc.tryOpenMap(i);
      }
    } else if (m.has("constant")) {
      final var v = m.lng("constant");
      if (pin >= 0) {
        ok = mc.tryConstantMap(pin, v);
      } else {
        ok = true;
        for (var i = 0; i < mc.getNrOfPins(); i++) ok &= mc.tryConstantMap(i, (v >> i) & 1L);
      }
    } else if (m.has("to")) {
      final var to = m.str("to");
      var idx = ids.indexOf(to);
      if (idx < 0) {
        for (var i = 0; i < ioComps.size(); i++) {
          final var l = ioComps.get(i).getLabel();
          if (to.equalsIgnoreCase(ids.get(i)) || (l != null && to.equalsIgnoreCase(l))) idx = i;
        }
      }
      if (idx < 0) {
        throw new McpToolException("Unknown board I/O '" + to + "'. Board I/O ids: " + ids);
      }
      final var io = ioComps.get(idx);
      final var saved = saveMap(mc);
      if (pin >= 0) {
        final var ioPin = m.integer("ioPin", 0);
        if (ioPin < 0 || ioPin >= io.getNrOfPins()) {
          throw new McpToolException("Board I/O '" + to + "' has no pin " + ioPin + ".");
        }
        mc.unmap(pin);
        ok = mc.tryMap(pin, io, ioPin);
      } else {
        mc.unmap();
        ok = mc.tryMap(io);
      }
      if (!ok) restoreMap(mc, saved);
      r.put("to", ids.get(idx));
    } else {
      throw new McpToolException("Mapping for '" + want + "' needs 'to', 'open', 'constant' or 'unmap'.");
    }
    r.put("ok", ok);
    if (!ok) {
      r.put(
          "reason",
          "Logisim refused the mapping (incompatible direction or size, pin already in use, or"
              + " open/constant not allowed for this pin direction).");
    }
    return r;
  }

  /** Per pin: {io component, io pin, open, constant} so a failed re-mapping can be undone. */
  private static Object[][] saveMap(MapComponent mc) {
    final var saved = new Object[mc.getNrOfPins()][];
    for (var i = 0; i < saved.length; i++) {
      saved[i] =
          new Object[] {
            mc.getFpgaInfo(i),
            mc.getMapIoPin(i),
            mc.isOpenMapped(i),
            mc.isConstantMapped(i) ? (mc.isZeroConstantMap(i) ? 0L : 1L) : null
          };
    }
    return saved;
  }

  private static void restoreMap(MapComponent mc, Object[][] saved) {
    mc.unmap();
    for (var i = 0; i < saved.length; i++) {
      if (saved[i][0] instanceof FpgaIoInformationContainer io) {
        mc.tryMap(i, io, (Integer) saved[i][1]);
      } else if ((Boolean) saved[i][2]) {
        mc.tryOpenMap(i);
      } else if (saved[i][3] instanceof Long v) {
        mc.tryConstantMap(i, v);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Settings
  // ---------------------------------------------------------------------------------------------

  private static final char[] VENDOR_IDS = {
    VendorSoftware.VENDOR_ALTERA, VendorSoftware.VENDOR_XILINX, VendorSoftware.VENDOR_VIVADO,
    VendorSoftware.VENDOR_OPENFPGA
  };
  private static final String[] VENDOR_KEYS = {"quartusPath", "isePath", "vivadoPath", "openFpgaPath"};

  private static boolean questaValid(String path) {
    if (path == null || path.isBlank()) return false;
    for (final var p : Softwares.QUESTA_BIN) {
      if (!new File(FileUtil.correctPath(path) + p).exists()) return false;
    }
    return true;
  }

  private static Map<String, Object> settings() {
    final var out = new LinkedHashMap<String, Object>();
    out.put("hdlType", AppPreferences.HdlType.get());
    out.put("workspace", AppPreferences.FPGA_Workspace.get());
    out.put("selectedBoard", AppPreferences.SelectedBoard.get());
    out.put("vhdlStandard", AppPreferences.VHDL_STANDARD.get());
    out.put("vhdlKeywordsUpperCase", AppPreferences.VhdlKeywordsUpperCase.get());
    out.put("suppressGatedClockWarnings", AppPreferences.SuppressGatedClockWarnings.get());
    out.put("suppressOpenPinWarnings", AppPreferences.SuppressOpenPinWarnings.get());
    final var tools = new ArrayList<Object>();
    for (var i = 0; i < VENDOR_IDS.length; i++) {
      final var v = VENDOR_IDS[i];
      final var t = new LinkedHashMap<String, Object>();
      t.put("vendor", VendorSoftware.getVendorString(v));
      t.put("setting", VENDOR_KEYS[i]);
      final var path = VendorSoftware.getToolPath(v);
      t.put("path", path);
      t.put("toolsFound", path != null && !path.isBlank() && VendorSoftware.toolsPresent(v, path));
      final var sw = VendorSoftware.getSoftware(v);
      if (sw != null) t.put("requiredPrograms", List.of(sw.getBinaries()));
      tools.add(t);
    }
    out.put("toolchains", tools);
    out.put("questaPath", AppPreferences.QUESTA_PATH.get());
    out.put("questaFound", questaValid(AppPreferences.QUESTA_PATH.get()));
    out.put("questaValidation", AppPreferences.QUESTA_VALIDATION.get());
    return out;
  }

  private static Object setSettings(Args args) {
    final var force = args.bool("force", false);
    if (args.has("hdlType")) {
      final var h = args.str("hdlType");
      final String v;
      if (h.equalsIgnoreCase("vhdl")) v = HdlGeneratorFactory.VHDL;
      else if (h.equalsIgnoreCase("verilog")) v = HdlGeneratorFactory.VERILOG;
      else if (h.equalsIgnoreCase("none")) v = HdlGeneratorFactory.NONE;
      else throw new McpToolException("hdlType must be VHDL, Verilog or None.");
      AppPreferences.HdlType.set(v);
    }
    if (args.has("workspace")) {
      final var ws = args.str("workspace");
      if (ws.contains(" ")) throw new McpToolException("The workspace path must not contain spaces.");
      AppPreferences.FPGA_Workspace.set(new File(ws).getAbsolutePath());
    }
    if (args.has("selectedBoard")) {
      AppPreferences.SelectedBoard.set(resolveBoardName(new Args(Map.of("board", args.str("selectedBoard"))), null));
    }
    for (var i = 0; i < VENDOR_IDS.length; i++) {
      if (!args.has(VENDOR_KEYS[i])) continue;
      final var path = args.str(VENDOR_KEYS[i]);
      final var v = VENDOR_IDS[i];
      if (!VendorSoftware.setToolPath(v, path)) {
        if (!force) {
          final var sw = VendorSoftware.getSoftware(v);
          throw new McpToolException(
              "The " + VendorSoftware.getVendorString(v) + " programs ("
                  + (sw == null ? "?" : String.join(", ", sw.getBinaries())) + ") were not found in '"
                  + path + "'. Pass force=true to store the path anyway.");
        }
        switch (v) {
          case VendorSoftware.VENDOR_ALTERA -> AppPreferences.QuartusToolPath.set(path);
          case VendorSoftware.VENDOR_XILINX -> AppPreferences.ISEToolPath.set(path);
          case VendorSoftware.VENDOR_VIVADO -> AppPreferences.VivadoToolPath.set(path);
          default -> AppPreferences.OpenFpgaToolPath.set(path);
        }
      }
    }
    if (args.has("questaPath")) {
      final var path = args.str("questaPath");
      if (!questaValid(path) && !force) {
        throw new McpToolException(
            "QuestaSim programs " + List.of(Softwares.QUESTA_BIN) + " not found in '" + path
                + "'. Pass force=true to store the path anyway.");
      }
      AppPreferences.QUESTA_PATH.set(path);
    }
    if (args.has("questaValidation")) {
      final var on = args.bool("questaValidation");
      if (on && !questaValid(AppPreferences.QUESTA_PATH.get())) {
        throw new McpToolException("Set a valid questaPath before enabling questaValidation.");
      }
      AppPreferences.QUESTA_VALIDATION.set(on);
    }
    if (args.has("vhdlKeywordsUpperCase")) AppPreferences.VhdlKeywordsUpperCase.set(args.bool("vhdlKeywordsUpperCase"));
    if (args.has("vhdlStandard")) {
      final var s = args.str("vhdlStandard");
      if (!List.of("1993", "2002", "2008").contains(s)) throw new McpToolException("vhdlStandard must be 1993, 2002 or 2008.");
      AppPreferences.VHDL_STANDARD.set(s);
    }
    if (args.has("suppressGatedClockWarnings")) {
      AppPreferences.SuppressGatedClockWarnings.set(args.bool("suppressGatedClockWarnings"));
    }
    if (args.has("suppressOpenPinWarnings")) {
      AppPreferences.SuppressOpenPinWarnings.set(args.bool("suppressOpenPinWarnings"));
    }
    return settings();
  }

  // ---------------------------------------------------------------------------------------------
  // VHDL entities
  // ---------------------------------------------------------------------------------------------

  private static VhdlContent vhdl(Project proj, String name) {
    final var v = proj.getLogisimFile().getVhdlContent(name);
    if (v != null) return v;
    for (final var c : proj.getLogisimFile().getVhdlContents()) {
      if (c.getName().equalsIgnoreCase(name)) return c;
    }
    final var names = new ArrayList<String>();
    for (final var c : proj.getLogisimFile().getVhdlContents()) names.add(c.getName());
    throw new McpToolException("Unknown VHDL entity '" + name + "'. Entities: " + names);
  }

  private static Map<String, Object> vhdlInfo(VhdlContent v, boolean withContent) {
    final var out = new LinkedHashMap<String, Object>();
    out.put("name", v.getName());
    out.put("valid", v.isValid());
    final var ports = new ArrayList<Object>();
    for (final var p : v.getPorts()) {
      ports.add(Map.of("name", p.getName(), "direction", p.getType(), "width", p.getWidth().getWidth()));
    }
    out.put("ports", ports);
    final var generics = new ArrayList<Object>();
    for (final var g : v.getGenerics()) {
      generics.add(Map.of("name", g.getName(), "type", g.getType(), "default", g.getDefaultValue()));
    }
    out.put("generics", generics);
    if (withContent) out.put("content", v.getContent());
    return out;
  }

  /**
   * Runs VHDL validation code without letting it open the QuestaSim path chooser: when QuestaSim
   * validation is enabled but QuestaSim is not found, validation is skipped for this call.
   */
  private static <T> T noQuestaChooser(Map<String, Object> out, Callable<T> body) throws Exception {
    final var disable =
        AppPreferences.QUESTA_VALIDATION.get() && !questaValid(AppPreferences.QUESTA_PATH.get());
    if (disable) {
      out.put("warning", "QuestaSim validation is enabled but QuestaSim was not found; skipped.");
      AppPreferences.QUESTA_VALIDATION.set(false);
    }
    try {
      return body.call();
    } finally {
      if (disable) AppPreferences.QUESTA_VALIDATION.set(true);
    }
  }

  private Object createVhdl(McpContext ctx, Args args) throws Exception {
    final var proj = ctx.project(args);
    final var file = proj.getLogisimFile();
    String source = null;
    if (args.has("path")) {
      final var p = Path.of(args.str("path"));
      if (!Files.isRegularFile(p)) throw new McpToolException("File not found: " + p.toAbsolutePath());
      source = Files.readString(p);
    } else if (args.has("content")) {
      source = args.str("content");
    }
    final var name = args.has("name") ? args.str("name").trim() : null;
    if (source == null && name == null) {
      throw new McpToolException("Give 'name' (to start from the template), 'content' or 'path'.");
    }
    if (name != null) checkVhdlName(name, file, null);
    final var out = new LinkedHashMap<String, Object>();
    final var src = source;
    final VhdlContent content =
        noQuestaChooser(
            out,
            () ->
                withInterceptor(
                    (kind, title, message, options, initial) -> null,
                    () -> {
                      // Start from the template under a temporary valid name, then set the source.
                      final var c = VhdlContent.create(name != null ? name : uniqueTempName(file), file);
                      if (src != null && !c.setContent(src)) {
                        throw new McpToolException(vhdlError(c, src, file));
                      }
                      if (!c.isValid()) throw new McpToolException(vhdlError(c, src, file));
                      return c;
                    }));
    if (src != null) {
      if (name != null && !content.getName().equals(name)) {
        throw new McpToolException(
            "The source defines entity '" + content.getName() + "' but name is '" + name
                + "'. Omit 'name' or make them match.");
      }
      checkVhdlName(content.getName(), file, null);
    }
    proj.doAction(LogisimFileActions.addVhdl(content));
    out.putAll(vhdlInfo(content, false));
    return out;
  }

  private static String uniqueTempName(LogisimFile file) {
    var i = 1;
    while (file.containsFactory("mcp_entity" + i)) i++;
    return "mcp_entity" + i;
  }

  private static void checkVhdlName(String name, LogisimFile file, VhdlContent self) {
    if (VhdlContent.labelVHDLInvalid(name)) {
      throw new McpToolException(
          "'" + name + "' is not a valid VHDL entity name (letters, digits and single '_', starting"
              + " with a letter, not ending with '_', not a VHDL keyword).");
    }
    for (final var c : file.getCircuits()) {
      if (c.getName().equalsIgnoreCase(name)) {
        throw new McpToolException("A circuit named '" + c.getName() + "' already exists.");
      }
    }
    for (final var v : file.getVhdlContents()) {
      if (v != self && v.getName().equalsIgnoreCase(name)) {
        throw new McpToolException("A VHDL entity named '" + v.getName() + "' already exists.");
      }
    }
  }

  private static String vhdlError(VhdlContent c, String src, LogisimFile file) {
    final var err = c.getErrorText();
    if (err != null) return "The VHDL source is not valid: " + err;
    return "The VHDL source was rejected: the entity name is not a valid VHDL identifier or is"
        + " already used in the project.";
  }

  /** Undoable replacement of a VHDL entity's source. */
  private static final class SetVhdlContent extends Action {
    private final VhdlContent vhdl;
    private final String oldText;
    private final String newText;

    SetVhdlContent(VhdlContent vhdl, String oldText, String newText) {
      this.vhdl = vhdl;
      this.oldText = oldText;
      this.newText = newText;
    }

    @Override
    public void doIt(Project proj) {
      apply(newText);
    }

    @Override
    public void undo(Project proj) {
      apply(oldText);
    }

    private void apply(String text) {
      final var disable =
          AppPreferences.QUESTA_VALIDATION.get() && !questaValid(AppPreferences.QUESTA_PATH.get());
      if (disable) AppPreferences.QUESTA_VALIDATION.set(false);
      try {
        vhdl.setContent(text);
      } finally {
        if (disable) AppPreferences.QUESTA_VALIDATION.set(true);
      }
    }

    @Override
    public String getName() {
      return "Edit VHDL entity " + vhdl.getName();
    }
  }

  private Object setVhdlContent(McpContext ctx, Args args) throws Exception {
    final var proj = ctx.project(args);
    final var v = vhdl(proj, args.str("name"));
    final var text = args.str("content");
    final var oldText = v.getContent();
    final var out = new LinkedHashMap<String, Object>();
    // Validate on a copy so a bad source leaves the entity untouched.
    final var probe = v.clone();
    final boolean ok =
        noQuestaChooser(
            out,
            () -> withInterceptor((kind, title, message, options, initial) -> null, () -> probe.setContent(text)));
    if (!ok) throw new McpToolException(vhdlError(probe, text, proj.getLogisimFile()));
    if (!probe.getName().equals(v.getName())) checkVhdlName(probe.getName(), proj.getLogisimFile(), v);
    if (!text.equals(oldText)) {
      withInterceptor(
          (kind, title, message, options, initial) -> null,
          () -> {
            proj.doAction(new SetVhdlContent(v, oldText, text));
            return null;
          });
    }
    if (!v.isValid()) {
      throw new McpToolException("The entity could not be updated: " + vhdlError(v, text, proj.getLogisimFile()));
    }
    out.putAll(vhdlInfo(v, false));
    return out;
  }

  // ---------------------------------------------------------------------------------------------
  // VHDL simulation
  // ---------------------------------------------------------------------------------------------

  private Object vhdlSimulation(McpContext ctx, Args args) {
    final var proj = ctx.project(args);
    final var sim = proj.getVhdlSimulator();
    final var action = args.str("action", "status");
    final var questa = questaValid(AppPreferences.QUESTA_PATH.get());
    switch (action) {
      case "status" -> { }
      case "enable", "restart" -> {
        if (!questa) {
          throw new McpToolException(
              "VHDL simulation needs QuestaSim, which was not found (questaPath='"
                  + AppPreferences.QUESTA_PATH.get() + "'). Install QuestaSim and set its bin"
                  + " directory with fpga_set_settings questaPath.");
        }
        if (!sim.hasVhdlComponent(proj)) {
          throw new McpToolException("The project has no VHDL entity instances to simulate.");
        }
        if ("enable".equals(action)) sim.setEnabled(true);
        else sim.restart();
      }
      case "disable" -> sim.setEnabled(false);
      default -> throw new McpToolException("Unknown action '" + action + "'.");
    }
    final var out = new LinkedHashMap<String, Object>();
    out.put("state", String.valueOf(sim.getState()));
    out.put("enabled", sim.isEnabled());
    out.put("running", sim.isRunning());
    out.put("questaFound", questa);
    out.put("questaPath", AppPreferences.QUESTA_PATH.get());
    out.put("hasVhdlInstances", sim.hasVhdlComponent(proj));
    return out;
  }

  // ---------------------------------------------------------------------------------------------
  // Verilog import
  // ---------------------------------------------------------------------------------------------

  private Object verilogImport(McpContext ctx, Args args) throws Exception {
    final var hasSources = args.has("sources");
    final var hasJson = args.has("jsonPath");
    if (hasSources == hasJson) throw new McpToolException("Give exactly one of 'sources' or 'jsonPath'.");
    final var prep =
        ctx.onModelThread(
            () -> {
              final var proj = ctx.project(args);
              final var file = proj.getLogisimFile();
              return new Object[] {proj, file, VerilogImporter.reservedNames(file)};
            });
    final var proj = (Project) prep[0];
    final var file = (LogisimFile) prep[1];
    @SuppressWarnings("unchecked")
    final var reserved = (Set<String>) prep[2];
    final var messages = new ArrayList<String>();
    final Consumer<String> log =
        m -> {
          synchronized (messages) {
            messages.add(m);
            if (messages.size() > MAX_LINES) messages.remove(0);
          }
        };
    final var undefined = args.str("undefined", "error");
    final var top = args.has("top") ? args.str("top") : null;
    final VerilogImporter.Result result;
    try {
      if (hasJson) {
        final var p = Path.of(args.str("jsonPath")).toAbsolutePath();
        if (!Files.isRegularFile(p)) throw new McpToolException("File not found: " + p);
        result = VerilogImporter.readJson(p, top, undefined, file, reserved, log);
      } else {
        final var sources = new ArrayList<Path>();
        for (final var s : args.list("sources")) sources.add(Path.of(String.valueOf(s)));
        final var includes = new ArrayList<Path>();
        for (final var s : args.list("includes", List.of())) includes.add(Path.of(String.valueOf(s)));
        final var defines = new ArrayList<String>();
        for (final var s : args.list("defines", List.of())) defines.add(String.valueOf(s));
        Path wd;
        if (args.has("workingDirectory")) {
          wd = Path.of(args.str("workingDirectory"));
        } else {
          final var first = sources.get(0).toAbsolutePath().getParent();
          wd = first == null ? Path.of(".").toAbsolutePath() : first;
        }
        final var exe =
            args.str("yosys", AppPreferences.getPrefs().get("verilogYosysExecutable", "yosys"));
        final YosysRunner.Memories mem;
        try {
          mem = YosysRunner.Memories.valueOf(args.str("memories", "AUTO").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
          throw new McpToolException("memories must be AUTO, NATIVE or LOGIC.");
        }
        final var options =
            new YosysRunner.Options(exe, sources, top == null ? "" : top, includes, defines, wd,
                args.bool("systemVerilog", false), undefined, mem);
        result = VerilogImporter.synthesize(options, file, reserved, log);
      }
    } catch (IOException | RuntimeException e) {
      if (e instanceof McpToolException mte) throw mte;
      final var msg = String.valueOf(e.getMessage());
      final var hint =
          hasSources && (msg.contains("No such file") || msg.contains("Cannot run program"))
              ? " (is Yosys installed? pass its path as 'yosys')"
              : "";
      final String tail;
      synchronized (messages) {
        tail = messages.isEmpty() ? "" : " Log: " + String.join(" | ", messages.subList(Math.max(0, messages.size() - 20), messages.size()));
      }
      throw new McpToolException("Verilog import failed: " + msg + hint + tail, e);
    }
    return ctx.onModelThread(
        () -> {
          if (proj.getLogisimFile() != file) {
            throw new McpToolException("The project's file changed during the import; try again.");
          }
          proj.doAction(result.action("Import Verilog"));
          final var out = new LinkedHashMap<String, Object>();
          final var names = new ArrayList<String>();
          for (final var c : result.circuits()) names.add(c.getName());
          out.put("circuits", names);
          out.put("top", result.top() == null ? null : result.top().getName());
          out.put("componentCount", result.componentCount());
          synchronized (messages) {
            out.put("messages", new ArrayList<>(messages));
          }
          return out;
        });
  }
}
