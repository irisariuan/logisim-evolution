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
import static com.cburch.logisim.mcp.tools.SimulationSupport.DEFAULT_TIMEOUT_MS;
import static com.cburch.logisim.mcp.tools.SimulationSupport.PATH_ARG;
import static com.cburch.logisim.mcp.tools.SimulationSupport.TIMEOUT_ARG;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.circuit.TestVectorEvaluator;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.TestException;
import com.cburch.logisim.data.TestVector;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.gui.hex.HexFile;
import com.cburch.logisim.gui.log.LoggableContract;
import com.cburch.logisim.instance.InstanceComponent;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.mcp.Args;
import com.cburch.logisim.mcp.ComponentRefs;
import com.cburch.logisim.mcp.McpContext;
import com.cburch.logisim.mcp.McpToolException;
import com.cburch.logisim.mcp.McpToolGroup;
import com.cburch.logisim.mcp.McpToolRegistry;
import com.cburch.logisim.mcp.Schema;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.memory.Mem;
import com.cburch.logisim.std.memory.MemContents;
import com.cburch.logisim.std.memory.Rom;
import com.cburch.logisim.std.wiring.Pin;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Memory contents (RAM/ROM), other stateful components (registers, counters...) and test vectors.
 *
 * <p>ROM contents are stored in the ROM component's "Contents" attribute: they are part of the
 * circuit file, shared by every simulation state, and edits are undoable. RAM contents are part of
 * the simulation state: every instance of a RAM in every simulation state (subcircuit instance)
 * has its own contents, edits are not undoable and a simulation reset clears volatile RAMs.
 */
public final class MemoryTools implements McpToolGroup {

  /** Chunk size used for in-place updates (one page of {@link MemContents}). */
  private static final int CHUNK = 4096;

  private static final String MEM_DOC =
      " ROM contents belong to the circuit (saved in the .circ file, shared by all simulation"
          + " states, undoable edits); RAM contents belong to one simulation state (select the"
          + " instance with 'path', not saved in the file, cleared by sim_reset for volatile RAMs).";

  private static final String[] SAVE_FORMATS = {
    "v3.0 hex words plain",
    "v3.0 hex words addressed",
    "v3.0 hex bytes plain big-endian",
    "v3.0 hex bytes plain little-endian",
    "v3.0 hex bytes addressed big-endian",
    "v3.0 hex bytes addressed little-endian",
    "v2.0 raw",
    "Binary data big-endian",
    "Binary data little-endian",
    "ASCII bytes, with escapes, big-endian",
    "ASCII bytes, with escapes, little-endian"
  };

  private static Schema memSchema() {
    return Schema.object()
        .prop("project", PROJECT_ARG)
        .prop("circuit", CIRCUIT_ARG)
        .prop("path", PATH_ARG)
        .prop("component", Schema.string("Memory component (label, id 'c12' or '@x,y')."), true);
  }

  private record MemRef(Project proj, CircuitState state, Component comp, Mem mem, MemContents contents,
      boolean rom, String path) {}

  private static MemRef memRef(McpContext ctx, Args args) {
    final var proj = ctx.project(args);
    final var t = SimulationSupport.target(ctx, proj, args);
    final var comp = ComponentRefs.resolve(ctx, t.state().getCircuit(), args.str("component"));
    if (!(comp.getFactory() instanceof Mem mem)) {
      throw new McpToolException(
          "'" + args.str("component") + "' is a " + comp.getFactory().getName()
              + ", not a RAM/ROM. Use memory_list.");
    }
    final var inst = ((InstanceComponent) comp).getInstance();
    if (mem instanceof Rom) {
      return new MemRef(proj, t.state(), comp, mem, Rom.getMemContents(inst), true, t.path());
    }
    return new MemRef(proj, t.state(), comp, mem, mem.getContents(t.state().getInstanceState(comp)), false, t.path());
  }

  private static Map<String, Object> memInfo(McpContext ctx, CircuitState state, Component comp) {
    final var out = new LinkedHashMap<String, Object>();
    out.put("id", ctx.componentId(comp));
    out.put("type", comp.getFactory().getName());
    final var label = ComponentRefs.label(comp);
    if (label != null && !label.isEmpty()) out.put("label", label);
    out.put("path", SimulationSupport.pathOf(state));
    out.put("location", ComponentRefs.loc(comp.getLocation()));
    final var attrs = comp.getAttributeSet();
    final var addrBits = attrs.getValue(Mem.ADDR_ATTR).getWidth();
    out.put("addressBits", addrBits);
    out.put("dataBits", attrs.getValue(Mem.DATA_ATTR).getWidth());
    out.put("words", 1L << addrBits);
    final var rom = comp.getFactory() instanceof Rom;
    out.put("storage", rom ? "rom-attribute (in circuit file, undoable)" : "ram-simulation-state (per instance)");
    final MemContents contents = rom
        ? Rom.getMemContents(((InstanceComponent) comp).getInstance())
        : ((Mem) comp.getFactory()).getContents(state.getInstanceState(comp));
    out.put("allZero", contents.isClear());
    return out;
  }

  private static Map<String, Object> stateInfo(McpContext ctx, CircuitState state, Component comp) {
    final var out = new LinkedHashMap<String, Object>();
    out.put("id", ctx.componentId(comp));
    out.put("type", comp.getFactory().getName());
    final var label = ComponentRefs.label(comp);
    if (label != null && !label.isEmpty()) out.put("label", label);
    out.put("path", SimulationSupport.pathOf(state));
    out.put("location", ComponentRefs.loc(comp.getLocation()));
    if (comp.getAttributeSet().containsAttribute(StdAttr.WIDTH)) {
      out.put("dataBits", comp.getAttributeSet().getValue(StdAttr.WIDTH).getWidth());
    }
    final var logger = (LoggableContract) comp.getFeature(LoggableContract.class);
    try {
      final var opts = logger.getLogOptions();
      if (opts == null || opts.length == 0) {
        final var v = logger.getLogValue(state, null);
        if (v != null) out.put("value", SimulationSupport.valueJson(v));
      } else if (opts.length <= 64) {
        final var vals = new ArrayList<Object>();
        for (final var o : opts) {
          final var v = logger.getLogValue(state, o);
          final var m = new LinkedHashMap<String, Object>();
          m.put("name", String.valueOf(logger.getLogName(o)));
          m.put("value", v == null ? null : SimulationSupport.binary(v));
          vals.add(m);
        }
        out.put("values", vals);
      }
    } catch (RuntimeException e) {
      out.put("value", null);
    }
    return out;
  }

  private static boolean isStateful(Component comp) {
    final var f = comp.getFactory();
    return !(f instanceof Mem)
        && f.getClass().getPackageName().equals("com.cburch.logisim.std.memory")
        && comp.getFeature(LoggableContract.class) != null;
  }

  private static String word(long v, int width, String fmt) {
    final var masked = width >= 64 ? v : v & ((1L << width) - 1);
    return switch (fmt) {
      case "decimal" -> Long.toUnsignedString(masked);
      case "binary" -> {
        final var s = Long.toBinaryString(masked);
        yield "0".repeat(Math.max(0, width - s.length())) + s;
      }
      default -> {
        final var s = Long.toHexString(masked);
        yield "0".repeat(Math.max(0, (width + 3) / 4 - s.length())) + s;
      }
    };
  }

  private static long checkAddress(MemRef ref, long addr) {
    final var last = ref.contents().getLastOffset();
    if (addr < 0 || addr > last) {
      throw new McpToolException("Address " + addr + " out of range 0.." + last + " (0x" + Long.toHexString(last) + ").");
    }
    return addr;
  }

  /** Copies {@code values} to {@code start}; for a ROM this goes through the undo log. */
  private static int writeWords(MemRef ref, long start, long[] values) {
    if (ref.rom()) Rom.registerUndoListener(((InstanceComponent) ref.comp()).getInstance(), ref.proj());
    var changed = 0;
    var pos = 0;
    while (pos < values.length) {
      final var addr = start + pos;
      final var len = (int) Math.min(values.length - pos, CHUNK - (addr % CHUNK));
      final var chunk = new long[len];
      System.arraycopy(values, pos, chunk, 0, len);
      for (var i = 0; i < len; i++) if (ref.contents().get(addr + i) != (chunk[i] & mask(ref))) changed++;
      ref.contents().set(addr, chunk);
      pos += len;
    }
    return changed;
  }

  private static long mask(MemRef ref) {
    final var w = ref.contents().getWidth();
    return w >= 64 ? -1L : (1L << w) - 1;
  }

  /** Makes the simulation see changed contents. */
  private static void invalidate(MemRef ref) {
    if (ref.rom()) {
      ((InstanceComponent) ref.comp()).fireInvalidated();
    } else {
      ref.state().getInstanceState(ref.comp()).fireInvalidated();
    }
    SimulationSupport.nudge(ref.proj());
  }

  /** Runs a memory change on the model thread, then settles the simulation. */
  private static Object changeAndSettle(McpContext ctx, Args args, java.util.function.Function<MemRef, Map<String, Object>> change) {
    final var res =
        ctx.onModelThread(
            () -> {
              final var ref = memRef(ctx, args);
              final var out = new LinkedHashMap<String, Object>();
              out.put("component", ctx.componentId(ref.comp()));
              out.put("path", ref.path());
              out.put("storage", ref.rom() ? "rom" : "ram");
              out.putAll(change.apply(ref));
              invalidate(ref);
              if (ref.rom()) out.put("undoable", true);
              return Map.entry(ref.proj(), out);
            });
    res.getValue().put("settle", SimulationSupport.settle(ctx, res.getKey(), args.lng("timeoutMs", DEFAULT_TIMEOUT_MS)));
    return res.getValue();
  }

  @Override
  public void register(McpToolRegistry registry) {
    registry
        .tool("memory_list")
        .description(
            "Lists the memories (RAM, ROM, dual-port RAM) in the target simulation state and, by"
                + " default, all its subcircuit instances: id, label, state path, address/data"
                + " widths, number of words, where the contents live and whether they are all zero."
                + " Also lists other stateful memory-library components (registers, counters, shift"
                + " registers, flip-flops, random generators) with their current value." + MEM_DOC)
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("circuit", CIRCUIT_ARG)
                .prop("path", PATH_ARG)
                .prop("recursive", Schema.bool("Include subcircuit instances (default true).")))
        .readOnly()
        .offModelThread()
        .handler(
            SimulationSupport.settledFirst((ctx, args) -> {
              final var proj = ctx.project(args);
              final var t = SimulationSupport.target(ctx, proj, args);
              final var recursive = args.bool("recursive", true);
              final var mems = new ArrayList<Map.Entry<CircuitState, Component>>();
              SimulationSupport.collect(t.state(), c -> c.getFactory() instanceof Mem, recursive, 0, mems);
              final var others = new ArrayList<Map.Entry<CircuitState, Component>>();
              SimulationSupport.collect(t.state(), MemoryTools::isStateful, recursive, 0, others);
              final var memList = new ArrayList<Object>();
              for (final var e : mems) memList.add(memInfo(ctx, e.getKey(), e.getValue()));
              final var otherList = new ArrayList<Object>();
              for (final var e : others) otherList.add(stateInfo(ctx, e.getKey(), e.getValue()));
              final var out = new LinkedHashMap<String, Object>();
              out.put("path", t.path());
              out.put("memories", memList);
              out.put("stateful", otherList);
              return out;
            }));

    registry
        .tool("memory_read")
        .description(
            "Reads 'count' consecutive words of a RAM/ROM starting at 'address'. Returns the words"
                + " as strings in the chosen format (hex without prefix, zero-padded, by default)"
                + " and a classic hex-dump text (8 words per line)." + MEM_DOC)
        .input(
            memSchema()
                .prop("address", Schema.any("Start address (integer or '0x..', default 0)."))
                .prop("count", Schema.integer("Number of words (default 16, max 65536)."))
                .prop("format", Schema.enumeration("Word format (default hex).", "hex", "decimal", "binary")))
        .readOnly()
        .handler(
            (ctx, args) -> {
              final var ref = memRef(ctx, args);
              final var start = checkAddress(ref, args.lng("address", 0));
              var count = args.integer("count", 16);
              if (count < 1 || count > 65536) throw new McpToolException("count must be 1..65536.");
              count = (int) Math.min(count, ref.contents().getLastOffset() - start + 1);
              final var fmt = args.str("format", "hex");
              final var width = ref.contents().getWidth();
              final var words = new ArrayList<String>();
              final var dump = new StringBuilder();
              final var addrDigits = (ref.contents().getLogLength() + 3) / 4;
              for (var i = 0; i < count; i++) {
                final var v = ref.contents().get(start + i);
                words.add(word(v, width, fmt));
                if (i % 8 == 0) {
                  if (i > 0) dump.append('\n');
                  dump.append(word(start + i, addrDigits * 4, "hex")).append(':');
                }
                dump.append(' ').append(word(v, width, "hex"));
              }
              final var out = new LinkedHashMap<String, Object>();
              out.put("component", ctx.componentId(ref.comp()));
              out.put("path", ref.path());
              out.put("storage", ref.rom() ? "rom" : "ram");
              out.put("address", start);
              out.put("count", count);
              out.put("dataBits", width);
              out.put("format", fmt);
              out.put("words", words);
              out.put("dump", dump.toString());
              return out;
            });

    registry
        .tool("memory_write")
        .description(
            "Writes words into a RAM/ROM starting at 'address': either the list 'values' or"
                + " 'value' repeated 'fillCount' times. Values are integers or strings ('0x1f',"
                + " '0b101', decimal) that must fit the data width. ROM writes are undoable and mark"
                + " the project as modified. Waits for the simulation to settle." + MEM_DOC)
        .input(
            memSchema()
                .prop("address", Schema.any("Start address (integer or '0x..', default 0)."))
                .prop("values", Schema.array("Words to write.", Schema.any("Word value.")))
                .prop("value", Schema.any("Single word value (with fillCount)."))
                .prop("fillCount", Schema.integer("How many times to write 'value' (default 1)."))
                .prop("timeoutMs", TIMEOUT_ARG))
        .offModelThread()
        .handler(
            (ctx, args) ->
                changeAndSettle(
                    ctx,
                    args,
                    ref -> {
                      final var start = checkAddress(ref, args.lng("address", 0));
                      final var width = ref.contents().getWidth();
                      final List<Object> raw;
                      if (args.has("values")) {
                        raw = args.list("values");
                      } else if (args.has("value")) {
                        final var n = args.integer("fillCount", 1);
                        if (n < 1) throw new McpToolException("fillCount must be >= 1.");
                        raw = new ArrayList<>();
                        for (var i = 0; i < n; i++) raw.add(args.get("value"));
                      } else {
                        throw new McpToolException("Pass 'values' or 'value'.");
                      }
                      if (raw.isEmpty()) throw new McpToolException("'values' is empty.");
                      if (start + raw.size() - 1 > ref.contents().getLastOffset()) {
                        throw new McpToolException("Writing " + raw.size() + " words at " + start + " exceeds the memory size.");
                      }
                      final var vals = new long[raw.size()];
                      for (var i = 0; i < vals.length; i++) {
                        final var v = SimulationSupport.parseValue(raw.get(i), width, "values[" + i + "]");
                        if (!v.isFullyDefined()) throw new McpToolException("Memory words must be fully defined (values[" + i + "]).");
                        vals[i] = v.toLongValue();
                      }
                      final var out = new LinkedHashMap<String, Object>();
                      out.put("address", start);
                      out.put("written", vals.length);
                      out.put("changed", writeWords(ref, start, vals));
                      return out;
                    }));

    registry
        .tool("memory_clear")
        .description("Sets every word of a RAM/ROM to zero (ROM: undoable)." + MEM_DOC)
        .input(memSchema().prop("timeoutMs", TIMEOUT_ARG))
        .offModelThread()
        .handler(
            (ctx, args) ->
                changeAndSettle(
                    ctx,
                    args,
                    ref -> {
                      if (ref.rom()) Rom.registerUndoListener(((InstanceComponent) ref.comp()).getInstance(), ref.proj());
                      ref.contents().clear();
                      return Map.of("cleared", true);
                    }));

    registry
        .tool("memory_load_file")
        .description(
            "Loads a memory image into a RAM/ROM (like the component's 'Load Image...' menu, but"
                + " without file dialogs). Give a file 'file' or the image text 'content'. The format"
                + " is detected from the header line: 'v3.0 hex words plain|addressed', 'v3.0 hex"
                + " bytes ...', 'v2.0 raw' (e.g. 'v2.0 raw\\n1f 2a 4*0 ff'), otherwise binary/ASCII"
                + " data. Words beyond the memory size are rejected. Addresses not in the image are"
                + " set to 0. ROM loads are undoable." + MEM_DOC)
        .input(
            memSchema()
                .prop("file", Schema.string("Path of the image file."))
                .prop("content", Schema.string("Image text, used instead of 'file'."))
                .prop("timeoutMs", TIMEOUT_ARG))
        .offModelThread()
        .handler(
            (ctx, args) ->
                changeAndSettle(
                    ctx,
                    args,
                    ref -> {
                      File file;
                      File temp = null;
                      try {
                        if (args.has("content")) {
                          temp = File.createTempFile("logisim-mcp-mem", ".txt");
                          Files.writeString(temp.toPath(), args.str("content"), StandardCharsets.UTF_8);
                          file = temp;
                        } else if (args.has("file")) {
                          file = new File(args.str("file"));
                          if (!file.isFile()) throw new McpToolException("File not found: " + file.getAbsolutePath());
                        } else {
                          throw new McpToolException("Pass 'file' or 'content'.");
                        }
                        final var tmp = MemContents.create(ref.contents().getLogLength(), ref.contents().getWidth(), false);
                        if (!HexFile.open(tmp, file)) {
                          throw new McpToolException("The image could not be decoded (see dialogs for details).");
                        }
                        final var size = (int) (tmp.getLastOffset() + 1);
                        final var all = new long[size];
                        var nonZero = 0;
                        for (var i = 0; i < size; i++) {
                          all[i] = tmp.get(i);
                          if (all[i] != 0) nonZero++;
                        }
                        final var out = new LinkedHashMap<String, Object>();
                        out.put("changed", writeWords(ref, 0, all));
                        out.put("nonZeroWords", nonZero);
                        if (!args.has("content")) {
                          out.put("file", file.getAbsolutePath());
                          ref.mem().setCurrentImage(((InstanceComponent) ref.comp()).getInstance(), file);
                        }
                        return out;
                      } catch (IOException e) {
                        throw new McpToolException("Loading the image failed: " + e.getMessage(), e);
                      } finally {
                        if (temp != null) temp.delete();
                      }
                    }));

    registry
        .tool("memory_save_file")
        .description(
            "Saves the contents of a RAM/ROM to an image file (like 'Save Image...', without"
                + " dialogs). 'format' is one of: " + String.join(", ", SAVE_FORMATS)
                + " (default 'v3.0 hex words plain'). Refuses to replace an existing file unless"
                + " overwrite=true. With returnContent=true and no 'file', returns the image text"
                + " instead." + MEM_DOC)
        .input(
            memSchema()
                .prop("file", Schema.string("Target file path."))
                .prop("format", Schema.enumeration("Image format.", SAVE_FORMATS))
                .prop("overwrite", Schema.bool("Replace an existing file (default false)."))
                .prop("returnContent", Schema.bool("Return the image text (no file needed).")))
        .handler(
            (ctx, args) -> {
              final var ref = memRef(ctx, args);
              final var fmt = args.str("format", SAVE_FORMATS[0]);
              if (!List.of(SAVE_FORMATS).contains(fmt)) {
                throw new McpToolException("Unknown format '" + fmt + "'. Use one of " + List.of(SAVE_FORMATS));
              }
              final var out = new LinkedHashMap<String, Object>();
              out.put("component", ctx.componentId(ref.comp()));
              out.put("path", ref.path());
              out.put("format", fmt);
              try {
                if (args.has("file")) {
                  final var file = new File(args.str("file")).getAbsoluteFile();
                  if (file.exists() && !args.bool("overwrite", false)) {
                    throw new McpToolException("File " + file + " exists. Pass overwrite=true to replace it.");
                  }
                  HexFile.save(file, ref.contents(), fmt);
                  ref.mem().setCurrentImage(((InstanceComponent) ref.comp()).getInstance(), file);
                  out.put("file", file.getPath());
                  out.put("bytes", file.length());
                  if (args.bool("returnContent", false)) out.put("content", Files.readString(file.toPath(), StandardCharsets.ISO_8859_1));
                } else if (args.bool("returnContent", false)) {
                  final var temp = File.createTempFile("logisim-mcp-mem", ".txt");
                  try {
                    HexFile.save(temp, ref.contents(), fmt);
                    out.put("content", Files.readString(temp.toPath(), StandardCharsets.ISO_8859_1));
                  } finally {
                    temp.delete();
                  }
                } else {
                  throw new McpToolException("Pass 'file' and/or returnContent=true.");
                }
              } catch (IOException e) {
                throw new McpToolException("Saving the image failed: " + e.getMessage(), e);
              }
              return out;
            });

    registerTests(registry);
  }

  // ---------------------------------------------------------------------------------------------
  // Test vectors and test benches
  // ---------------------------------------------------------------------------------------------

  private void registerTests(McpToolRegistry registry) {
    registry
        .tool("test_run_vector")
        .description(
            "Runs a Logisim test vector against a circuit (like Simulate > Test Vector, without the"
                + " window) on a fresh private simulation state, so the project's simulation is not"
                + " disturbed. Give the vector as a file ('file') or inline text ('content'). Format:"
                + " a header line of column names = input/output pin labels, optionally with widths"
                + " like 'sum[4]' (and '<clk>'/clock labels), then one line per vector with values in"
                + " binary (or 0x.. hex, decimal with '<radix>' rules of Logisim), 'x' for don't-care"
                + " outputs, '#' comments; 'set'/'seq' columns enable sequential tests. Example:"
                + " 'a b sum\\n0 0 0\\n0 1 1\\n1 1 0'. Returns totals and, per failing vector, its"
                + " number, source line and the mismatching columns (expected vs actual).")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("circuit", CIRCUIT_ARG)
                .prop("file", Schema.string("Test vector file path."))
                .prop("content", Schema.string("Test vector text (instead of 'file')."))
                .prop("maxFailures", Schema.integer("Maximum failing vectors to detail (default 50)."))
                .prop("includePassing", Schema.bool("Also list passing vector numbers (default false).")))
        .readOnly()
        .offModelThread()
        .handler(this::runVector);

    registry
        .tool("test_run_testbench")
        .description(
            "Runs a self-checking test-bench circuit on a fresh private simulation state: the"
                + " clock is ticked until the output pin 'doneSignal' (default 'test_bench_done_o')"
                + " becomes 1, then the test passes iff output pin 'okSignal' (default"
                + " 'test_bench_ok_o') is 1. This mirrors Logisim's test-bench convention (circuit"
                + " 'logisim_test_verif' is used by default when it exists) but does not need an"
                + " external VHDL simulator. Fails on oscillation, missing clocks, 'maxTicks' half"
                + " ticks or timeout.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("circuit", CIRCUIT_ARG)
                .prop("doneSignal", Schema.string("Label of the 'done' output pin."))
                .prop("okSignal", Schema.string("Label of the 'ok' output pin."))
                .prop("maxTicks", Schema.integer("Maximum half ticks (default 100000)."))
                .prop("timeoutMs", Schema.integer("Time limit in ms (default 30000).")))
        .readOnly()
        .offModelThread()
        .handler(this::runTestBench);
  }

  private Object runVector(McpContext ctx, Args args) {
    final var maxFailures = args.integer("maxFailures", 50);
    final var includePassing = args.bool("includePassing", false);
    final var prep =
        ctx.onModelThread(
            () -> {
              final var proj = ctx.project(args);
              return Map.entry(proj, ctx.circuit(proj, args));
            });
    TestVector vec;
    File temp = null;
    try {
      File file;
      if (args.has("content")) {
        temp = File.createTempFile("logisim-mcp-vector", ".txt");
        Files.writeString(temp.toPath(), args.str("content"), StandardCharsets.UTF_8);
        file = temp;
      } else if (args.has("file")) {
        file = new File(args.str("file"));
        if (!file.isFile()) throw new McpToolException("File not found: " + file.getAbsolutePath());
      } else {
        throw new McpToolException("Pass 'file' or 'content'.");
      }
      vec = new TestVector(file);
    } catch (IOException e) {
      throw new McpToolException("Cannot read the test vector: " + e.getMessage(), e);
    } finally {
      if (temp != null) temp.delete();
    }
    final Circuit circ = prep.getValue();
    final var state = CircuitState.createRootState(prep.getKey(), circ, Thread.currentThread());
    final TestVectorEvaluator evaluator;
    try {
      evaluator = new TestVectorEvaluator(state, vec);
    } catch (TestException e) {
      throw new McpToolException("Test vector does not match circuit '" + circ.getName() + "': " + e.getMessage(), e);
    }
    final var failures = new ArrayList<Object>();
    final var passing = new ArrayList<Integer>();
    final var failCount = new int[1];
    final var result =
        evaluator.evaluate(
            (row, report) -> {
              if (report == null || report.isEmpty()) {
                if (includePassing) passing.add(row + 1);
                return;
              }
              failCount[0]++;
              if (failures.size() >= maxFailures) return;
              final var f = new LinkedHashMap<String, Object>();
              f.put("vector", row + 1);
              f.put("line", vec.lineNumbers[row]);
              if (vec.setNumbers[row] > 0) {
                f.put("set", vec.setNumbers[row]);
                f.put("seq", vec.seqNumbers[row]);
              }
              final var mism = new ArrayList<Object>();
              for (final var r : report) {
                final var m = new LinkedHashMap<String, Object>();
                m.put("column", r.columnName());
                m.put("expected", SimulationSupport.binary(r.expected()));
                m.put("actual", SimulationSupport.binary(r.computed()));
                if (r.expected().getWidth() > 4) {
                  m.put("expectedHex", SimulationSupport.hex(r.expected()));
                  m.put("actualHex", SimulationSupport.hex(r.computed()));
                }
                if (r.oscillating()) m.put("oscillating", true);
                mism.add(m);
              }
              f.put("mismatches", mism);
              final var inputs = new LinkedHashMap<String, Object>();
              final var pins = evaluator.getPins();
              for (var i = 0; i < pins.length; i++) {
                if (pins[i].getFactory() instanceof Pin && Pin.FACTORY.isInputPin(pins[i])) {
                  inputs.put(vec.columnName[i], SimulationSupport.binary(vec.data.get(row)[i]));
                }
              }
              f.put("inputs", inputs);
              failures.add(f);
            });
    final var out = new LinkedHashMap<String, Object>();
    out.put("circuit", circ.getName());
    final var cols = new ArrayList<String>();
    for (var i = 0; i < vec.columnName.length; i++) {
      final var w = vec.columnWidth[i].getWidth();
      cols.add(w == 1 ? vec.columnName[i] : vec.columnName[i] + "[" + w + "]");
    }
    out.put("columns", cols);
    out.put("vectors", vec.data.size());
    out.put("passed", result[0]);
    out.put("failed", result[1]);
    out.put("allPassed", result[1] == 0);
    out.put("failures", failures);
    if (failCount[0] > failures.size()) out.put("failuresTruncated", failCount[0] - failures.size());
    if (includePassing) out.put("passingVectors", passing);
    return out;
  }

  private Object runTestBench(McpContext ctx, Args args) {
    final var doneName = args.str("doneSignal", "test_bench_done_o");
    final var okName = args.str("okSignal", "test_bench_ok_o");
    record Prep(Project proj, Circuit circ, Component done, Component ok) {}
    final var prep =
        ctx.onModelThread(
            () -> {
              final var proj = ctx.project(args);
              Circuit circ;
              if (args.has("circuit")) {
                circ = ctx.circuit(proj, args);
              } else {
                circ = proj.getLogisimFile().getCircuit("logisim_test_verif");
                if (circ == null) circ = ctx.circuit(proj, args);
              }
              final var done = findOutput(circ, doneName);
              final var ok = findOutput(circ, okName);
              return new Prep(proj, circ, done, ok);
            });
    final var maxTicks = args.integer("maxTicks", 100_000);
    final var deadline = System.currentTimeMillis() + args.lng("timeoutMs", 30_000);
    final var state = CircuitState.createRootState(prep.proj(), prep.circ(), Thread.currentThread());
    final var prop = state.getPropagator();
    prop.propagate();
    var ticks = 0;
    String reason;
    while (true) {
      if (prop.isOscillating()) {
        reason = "oscillation";
        break;
      }
      final var done = Pin.FACTORY.getValue(state.getInstanceState(prep.done()));
      if (done == Value.TRUE) {
        reason = "done";
        break;
      }
      if (ticks >= maxTicks) {
        reason = "maxTicks";
        break;
      }
      if (System.currentTimeMillis() > deadline) {
        reason = "timeout";
        break;
      }
      if (!prop.toggleClocks()) {
        reason = "noClock";
        break;
      }
      prop.propagate();
      ticks++;
    }
    final var okVal = Pin.FACTORY.getValue(state.getInstanceState(prep.ok()));
    final var out = new LinkedHashMap<String, Object>();
    out.put("circuit", prep.circ().getName());
    out.put("passed", "done".equals(reason) && okVal == Value.TRUE);
    out.put("stopReason", reason);
    out.put("halfTicks", ticks);
    out.put(doneName, SimulationSupport.binary(Pin.FACTORY.getValue(state.getInstanceState(prep.done()))));
    out.put(okName, SimulationSupport.binary(okVal));
    return out;
  }

  private static Component findOutput(Circuit circ, String label) {
    for (final var pin : SimulationSupport.pins(circ, false)) {
      if (label.equals(ComponentRefs.label(pin))) return pin;
    }
    throw new McpToolException("Circuit '" + circ.getName() + "' has no output pin labeled '" + label + "'.");
  }
}
