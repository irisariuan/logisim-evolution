/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp.tools;

import static com.cburch.logisim.analyze.Strings.S;
import static com.cburch.logisim.mcp.McpToolRegistry.CIRCUIT_ARG;
import static com.cburch.logisim.mcp.McpToolRegistry.PROJECT_ARG;

import com.cburch.logisim.analyze.model.AnalyzerModel;
import com.cburch.logisim.analyze.model.Entry;
import com.cburch.logisim.analyze.model.Expression;
import com.cburch.logisim.analyze.model.Implicant;
import com.cburch.logisim.analyze.model.Parser;
import com.cburch.logisim.analyze.model.ParserException;
import com.cburch.logisim.analyze.model.TruthTable;
import com.cburch.logisim.analyze.model.Var;
import com.cburch.logisim.circuit.Analyze;
import com.cburch.logisim.circuit.AnalyzeException;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.file.LogisimFileActions;
import com.cburch.logisim.gui.menu.ProjectCircuitActions;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.mcp.Args;
import com.cburch.logisim.mcp.McpContext;
import com.cburch.logisim.mcp.McpToolException;
import com.cburch.logisim.mcp.McpToolGroup;
import com.cburch.logisim.mcp.McpToolRegistry;
import com.cburch.logisim.mcp.Schema;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.gates.CircuitBuilder;
import com.cburch.logisim.std.wiring.Pin;
import com.cburch.logisim.util.SyntaxChecker;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import javax.swing.JTextArea;

/**
 * Combinational analysis (Project > Analyze Circuit and the Combinational Analysis window):
 * truth tables, expressions, minimization and building circuits from expressions. Everything works
 * on a private {@link AnalyzerModel}; no analyzer window is needed (or opened, except by
 * analysis_open_analyzer).
 */
public final class AnalysisTools implements McpToolGroup {

  private static final String[] NOTATIONS = {
    "mathematical", "logic", "altlogic", "progbools", "progbits", "latex"
  };

  private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  private static final Pattern BIT_NAME = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)(?:\\[(\\d+)\\])?");
  private static final Pattern ASSIGNMENT =
      Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*(?:\\[\\d+\\])?)\\s*(?:=(?!=)|:)(.*)$", Pattern.DOTALL);

  // ---------------------------------------------------------------------------------------------
  // Shared schema pieces
  // ---------------------------------------------------------------------------------------------

  private static final String NOTATION_DOC =
      "Notation of returned expressions: 'mathematical' (default; ~a for NOT, ⋅ for AND, + for OR,"
          + " ⊕ for XOR), 'logic' (¬ ∧ ∨ ⊻), 'altlogic', 'progbools' (! && || !=), 'progbits'"
          + " (~ & | ^) or 'latex'.";

  private static final String EXPRESSION_SYNTAX =
      " Expression syntax (any mix is accepted): NOT as ~a, !a, a' or 'not a'; AND as a b"
          + " (juxtaposition), a*b, a&b, a&&b, a⋅b or 'a and b'; OR as a+b, a|b, a||b or 'a or b';"
          + " XOR as a^b, a⊕b or 'a xor b'; XNOR as a==b or a⊙b; constants 0 and 1; parentheses."
          + " Variable names are identifiers, bits of a multi-bit input are written a[2].";

  private static Schema withSpec(Schema s) {
    return s.prop(
            "inputs",
            Schema.array(
                "Input variable names in truth-table column order (first = most significant bit of"
                    + " the row index). A multi-bit input may be given as 'a[3..0]'. Optional when"
                    + " 'expressions' are given: then the variables found in them are used,"
                    + " sorted alphabetically.",
                Schema.string("Input name")))
        .prop(
            "outputs",
            Schema.array(
                "Output names (order of the outputs). Optional when 'expressions'/'table' is an"
                    + " object keyed by output name.",
                Schema.string("Output name")))
        .prop(
            "expressions",
            Schema.any(
                "Output expressions: an object {output: expression}, an array of strings"
                    + " 'out = expression' (or plain expressions, named after 'outputs' or"
                    + " out, out1, ...), or a single such string."
                    + EXPRESSION_SYNTAX))
        .prop(
            "table",
            Schema.object(
                    "Truth table per output, an object {output: spec}. spec is either a column"
                        + " string with one character per row (row 0 = all inputs 0; '0', '1' and"
                        + " '-'/'x' for don't care; length 2^inputs) or an object {\"minterms\":"
                        + " [row indexes that are 1], \"dontcares\": [...]} or {\"maxterms\":"
                        + " [row indexes that are 0], \"dontcares\": [...]}.")
                .additionalProperties(true))
        .prop(
            "rows",
            Schema.array(
                "Alternative truth-table form: rows 'INPUTS OUTPUTS', e.g. '1-0 01'. INPUTS has"
                    + " one character per input bit ('-' matches both 0 and 1), OUTPUTS one"
                    + " character per output bit (0, 1, -). Rows not covered get 'defaultValue'."
                    + " Requires 'inputs' and 'outputs'.",
                Schema.string("Row")))
        .prop(
            "defaultValue",
            Schema.enumeration(
                "Output value for rows not covered by 'rows' (default '0').", "0", "1", "-"));
  }

  private static Schema withOutputOptions(Schema s) {
    return s.prop("notation", Schema.enumeration(NOTATION_DOC, NOTATIONS))
        .prop(
            "includeTable",
            Schema.bool(
                "Include the truth table: 'rows' (every row, when at most 'maxRows' rows) and"
                    + " 'compactRows' (the analyzer's compact form where '-' in the inputs merges"
                    + " rows with equal outputs). Default true."))
        .prop(
            "maxRows",
            Schema.integer("Maximum number of rows listed in 'rows' (default 256)."))
        .prop(
            "forceMinimize",
            Schema.bool(
                "Run the minimizer even for more than 6 inputs (the analyzer only does this on"
                    + " request because its exact algorithm can take very long). Default false."))
        .prop(
            "minimizeTimeout",
            Schema.integer(
                "Seconds to wait for a forced minimization before giving up (default 20)."));
  }

  // ---------------------------------------------------------------------------------------------
  // Registration
  // ---------------------------------------------------------------------------------------------

  @Override
  public void register(McpToolRegistry registry) {
    registry
        .tool("analysis_analyze_circuit")
        .description(
            "Analyzes a combinational circuit like Project > Analyze Circuit: derives the boolean"
                + " expression of each output bit from the gates (or, if that is impossible,"
                + " computes the truth table by simulating every input combination), and returns"
                + " inputs/outputs (named after the pin labels; unlabeled pins get a, b, ... /"
                + " x, y, ...), per output the expression, minimal sum-of-products and"
                + " product-of-sums, minterms and don't cares (row index: first input is the"
                + " most significant bit), and optionally the truth table. Fails with a clear"
                + " message for circuits with more than 20 input bits or without pins. Nothing"
                + " is modified.")
        .input(
            withOutputOptions(
                Schema.object()
                    .prop("project", PROJECT_ARG)
                    .prop("circuit", CIRCUIT_ARG)
                    .prop(
                        "method",
                        Schema.enumeration(
                            "'auto' (default): expressions from the gate structure, falling back"
                                + " to simulation for circuits that cannot be expressed (memory,"
                                + " subcircuits of unsupported kind, cycles); 'expression': fail"
                                + " instead of falling back; 'table': always simulate.",
                            "auto",
                            "expression",
                            "table"))))
        .readOnly()
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var circ = ctx.circuit(proj, args);
              final var info = new LinkedHashMap<String, Object>();
              info.put("circuit", circ.getName());
              final var model = modelFromCircuit(proj, circ, args.str("method", "auto"), info);
              info.putAll(describe(model, args));
              return info;
            });

    registry
        .tool("analysis_minimize")
        .description(
            "Minimizes boolean functions given as expressions or as a truth table (like typing"
                + " them into the Combinational Analysis window) and returns, per output, the"
                + " minimal sum-of-products and product-of-sums (Quine-McCluskey + Petrick, as"
                + " Logisim does), minterms, don't cares and optionally the truth table. Give"
                + " either 'expressions', 'table' or 'rows'. At most 20 input bits. Nothing in any"
                + " project is modified.")
        .input(withOutputOptions(withSpec(Schema.object())))
        .readOnly()
        .handler((ctx, args) -> describe(modelFromSpec(args), args));

    registry
        .tool("analysis_parse_expression")
        .description(
            "Validates a boolean expression with Logisim's expression parser and returns"
                + " whether it is valid, its variables, and the normalized text in the requested"
                + " notation and in all notations. Invalid input is reported as valid=false with"
                + " the error message and character offset (not as a tool error)."
                + EXPRESSION_SYNTAX)
        .input(
            Schema.object()
                .prop(
                    "expression",
                    Schema.string("The expression, optionally prefixed by 'name =' or 'name:'."),
                    true)
                .prop(
                    "inputs",
                    Schema.array(
                        "Allowed variable names. Optional; by default every identifier is"
                            + " accepted as a variable.",
                        Schema.string("Variable name")))
                .prop("notation", Schema.enumeration(NOTATION_DOC, NOTATIONS)))
        .readOnly()
        .handler((ctx, args) -> parseExpressionTool(args));

    registry
        .tool("analysis_build_circuit")
        .description(
            "Creates a circuit of gates from expressions or a truth table, exactly like the"
                + " 'Build Circuit' button of the Combinational Analysis window (input pins on"
                + " the left with inverters, AND/OR (or NAND) gate network, output pins on the"
                + " right). The source is 'expressions', 'table'/'rows' (see analysis_minimize)"
                + " or 'sourceCircuit' (analyze an existing circuit and rebuild it). The new"
                + " circuit is added to the project as one undoable action. If a circuit with the"
                + " name exists it is only replaced (its contents rebuilt, undoable) when"
                + " replace=true. Returns the circuit name, the expressions used and the number"
                + " of components. No dialog or analyzer window is opened.")
        .input(
            withSpec(
                Schema.object()
                    .prop("project", PROJECT_ARG)
                    .prop("name", Schema.string("Name of the circuit to create."), true)
                    .prop(
                        "replace",
                        Schema.bool(
                            "Replace the contents of an existing circuit with that name (default"
                                + " false: fail if it exists)."))
                    .prop(
                        "sourceCircuit",
                        Schema.string(
                            "Instead of expressions/table: name of a combinational circuit of the"
                                + " project to analyze and rebuild."))
                    .prop(
                        "form",
                        Schema.enumeration(
                            "Which expression to build: 'as_given' (default; the given expressions"
                                + " as written, keeping XOR gates; minimal SOP for tables),"
                                + " 'sop' (minimal sum of products) or 'pos' (minimal product of"
                                + " sums).",
                            "as_given",
                            "sop",
                            "pos"))
                    .prop(
                        "twoInputGates",
                        Schema.bool("Use only gates with two inputs (default false)."))
                    .prop(
                        "nandGates",
                        Schema.bool(
                            "Use only NAND gates (default false). Not possible when an expression"
                                + " contains XOR/XNOR; use form='sop' or 'pos' then."))
                    .prop(
                        "setCurrent",
                        Schema.bool(
                            "Make the new circuit the current circuit (default true).")))
                .prop("notation", Schema.enumeration(NOTATION_DOC, NOTATIONS))
                .prop(
                    "minimizeTimeout",
                    Schema.integer(
                        "Seconds allowed for minimizing functions with more than 6 inputs"
                            + " (default 20).")))
        .handler(this::buildCircuit);

    registry
        .tool("analysis_open_analyzer")
        .description(
            "GUI only: opens the Combinational Analysis window (non-modal) for a circuit, like"
                + " Project > Analyze Circuit, so the user can see the truth table, expressions"
                + " and Karnaugh maps. Fails when no GUI is attached.")
        .input(Schema.object().prop("project", PROJECT_ARG).prop("circuit", CIRCUIT_ARG))
        .readOnly()
        .handler(
            (ctx, args) -> {
              if (!ctx.isGui()) {
                throw new McpToolException(
                    "The analyzer window needs a GUI (start Logisim with --mcp-gui). Use"
                        + " analysis_analyze_circuit to get the same information as data.");
              }
              final var proj = ctx.project(args);
              final var circ = ctx.circuit(proj, args);
              if (proj.getFrame() == null) throw new McpToolException("The project has no window.");
              ProjectCircuitActions.doAnalyze(proj, circ);
              return Map.of("opened", true, "circuit", circ.getName());
            });
  }

  // ---------------------------------------------------------------------------------------------
  // Model construction
  // ---------------------------------------------------------------------------------------------

  private static AnalyzerModel newModel() {
    final var model = new AnalyzerModel();
    // Without this, OutputExpressions drops per-output state (e.g. the POS format) on changes.
    model.getOutputExpressions().enableUpdates();
    return model;
  }

  static AnalyzerModel modelFromCircuit(
      Project proj, Circuit circ, String method, Map<String, Object> info) {
    final var pinNames = Analyze.getPinLabels(circ);
    final var inputVars = new ArrayList<Var>();
    final var outputVars = new ArrayList<Var>();
    var numInputs = 0;
    var numOutputs = 0;
    for (final var entry : pinNames.entrySet()) {
      final var pin = entry.getKey();
      final var width = pin.getAttributeValue(StdAttr.WIDTH).getWidth();
      final var v = new Var(entry.getValue(), width);
      if (Pin.FACTORY.isInputPin(pin)) {
        inputVars.add(v);
        numInputs += width;
      } else {
        outputVars.add(v);
        numOutputs += width;
      }
    }
    if (numInputs > AnalyzerModel.MAX_INPUTS) {
      throw new McpToolException(
          "Circuit '" + circ.getName() + "' has " + numInputs + " input bits; the analyzer handles"
              + " at most " + AnalyzerModel.MAX_INPUTS + ".");
    }
    if (numOutputs > AnalyzerModel.MAX_OUTPUTS) {
      throw new McpToolException(
          "Circuit '" + circ.getName() + "' has " + numOutputs + " output bits; the analyzer"
              + " handles at most " + AnalyzerModel.MAX_OUTPUTS + ".");
    }
    if (inputVars.isEmpty() || outputVars.isEmpty()) {
      throw new McpToolException(
          "Circuit '" + circ.getName() + "' needs at least one input pin and one output pin to be"
              + " analyzed (it has " + inputVars.size() + " inputs and " + outputVars.size()
              + " outputs).");
    }
    final var model = newModel();
    model.setCurrentCircuit(proj, circ);
    model.setVariables(inputVars, outputVars);
    var used = "table";
    if (!"table".equals(method)) {
      try {
        Analyze.computeExpression(model, circ, pinNames);
        used = "expression";
      } catch (AnalyzeException e) {
        if ("expression".equals(method)) {
          throw new McpToolException(
              "Cannot derive expressions for circuit '" + circ.getName() + "': " + e.getMessage()
                  + " The circuit is probably not purely combinational or contains components the"
                  + " analyzer cannot express; use method='table' to compute the truth table by"
                  + " simulation instead.");
        }
        info.put("expressionError", e.getMessage());
      }
    }
    if ("table".equals(used)) {
      Analyze.computeTable(model, proj, circ, pinNames);
      if (info.containsKey("expressionError")) {
        info.put(
            "note",
            "Expressions could not be derived from the gates, so the truth table was computed by"
                + " simulation. If the circuit has state (flip-flops, memories) the table only"
                + " reflects its reset state.");
      }
    }
    info.put("method", used);
    return model;
  }

  static AnalyzerModel modelFromSpec(Args args) {
    final var exprSpecs = new LinkedHashMap<String, String>();
    if (args.has("expressions")) collectExpressions(args, exprSpecs);
    final var tableSpec = args.map("table", null);
    final var rows = args.list("rows", null);
    final var sources =
        (exprSpecs.isEmpty() ? 0 : 1) + (tableSpec == null ? 0 : 1) + (rows == null ? 0 : 1);
    if (sources == 0) {
      throw new McpToolException("Give the function as 'expressions', 'table' or 'rows'.");
    }

    // Outputs.
    final var outputVars = new ArrayList<Var>();
    if (args.has("outputs")) {
      for (final var o : args.list("outputs")) outputVars.add(parseVar(String.valueOf(o), "output"));
    } else {
      final var names = new LinkedHashSet<String>();
      names.addAll(exprSpecs.keySet());
      if (tableSpec != null) names.addAll(tableSpec.keySet());
      if (names.isEmpty()) {
        throw new McpToolException("Give the output names with 'outputs'.");
      }
      for (final var n : names) outputVars.add(parseVar(n, "output"));
    }

    // Inputs.
    final var inputVars = new ArrayList<Var>();
    if (args.has("inputs")) {
      for (final var i : args.list("inputs")) inputVars.add(parseVar(String.valueOf(i), "input"));
    } else if (!exprSpecs.isEmpty()) {
      final var found = new TreeMap<String, Integer>();
      for (final var e : exprSpecs.entrySet()) discoverVariables(e.getKey(), e.getValue(), found);
      for (final var e : found.entrySet()) inputVars.add(new Var(e.getKey(), e.getValue()));
    } else {
      throw new McpToolException("Give the input names with 'inputs'.");
    }
    var nIn = 0;
    for (final var v : inputVars) nIn += v.width;
    var nOut = 0;
    for (final var v : outputVars) nOut += v.width;
    if (nIn == 0) throw new McpToolException("The function has no inputs.");
    if (nIn > AnalyzerModel.MAX_INPUTS) {
      throw new McpToolException(
          nIn + " input bits given; at most " + AnalyzerModel.MAX_INPUTS + " are supported.");
    }
    if (nOut > AnalyzerModel.MAX_OUTPUTS) {
      throw new McpToolException(
          nOut + " output bits given; at most " + AnalyzerModel.MAX_OUTPUTS + " are supported.");
    }
    final var allNames = new LinkedHashSet<String>();
    for (final var v : inputVars) {
      if (!allNames.add(v.name.toLowerCase(Locale.ROOT))) {
        throw new McpToolException("Duplicate variable name '" + v.name + "'.");
      }
    }
    for (final var v : outputVars) {
      if (!allNames.add(v.name.toLowerCase(Locale.ROOT))) {
        throw new McpToolException(
            "Duplicate name '" + v.name + "' (outputs must differ from each other and from the"
                + " inputs, ignoring case).");
      }
    }

    final var model = newModel();
    model.setVariables(inputVars, outputVars);
    final var outBits = model.getOutputs().bits;
    final var table = model.getTruthTable();
    final var rowCount = table.getRowCount();

    if (rows != null) {
      final var defEntry = entry(args.str("defaultValue", "0").charAt(0), "defaultValue");
      final var columns = new Entry[outBits.size()][rowCount];
      for (final var col : columns) Arrays.fill(col, defEntry);
      for (final var r : rows) applyRow(String.valueOf(r), nIn, columns);
      for (var i = 0; i < columns.length; i++) table.setOutputColumn(i, columns[i]);
    }
    if (tableSpec != null) {
      for (final var e : tableSpec.entrySet()) {
        final var idx = outBits.indexOf(e.getKey());
        if (idx < 0) {
          throw new McpToolException(
              "'table' names unknown output '" + e.getKey() + "'. Outputs: " + outBits);
        }
        table.setOutputColumn(idx, column(e.getKey(), e.getValue(), rowCount));
      }
    }
    for (final var e : exprSpecs.entrySet()) {
      if (!outBits.contains(e.getKey())) {
        throw new McpToolException(
            "Expression for unknown output '" + e.getKey() + "'. Outputs: " + outBits);
      }
      final Expression expr;
      try {
        expr = Parser.parse(e.getValue(), model);
      } catch (ParserException ex) {
        throw new McpToolException(parseErrorMessage(e.getKey(), e.getValue(), ex));
      }
      model.getOutputExpressions().setExpression(e.getKey(), expr, e.getValue().trim());
    }
    return model;
  }

  private static void collectExpressions(Args args, Map<String, String> out) {
    final var raw = args.get("expressions");
    final var outputs = args.list("outputs", null);
    final var plain = new ArrayList<String>();
    if (raw instanceof Map<?, ?> m) {
      for (final var e : m.entrySet()) out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
      return;
    }
    final var items = new ArrayList<String>();
    if (raw instanceof List<?> l) {
      for (final var o : l) items.add(String.valueOf(o));
    } else {
      // A single string; allow several assignments separated by newlines or ';'.
      for (final var part : String.valueOf(raw).split("[;\\n]")) {
        if (!part.isBlank()) items.add(part);
      }
    }
    for (final var item : items) {
      final var m = ASSIGNMENT.matcher(item);
      if (m.matches()) {
        out.put(m.group(1), m.group(2));
      } else {
        plain.add(item);
      }
    }
    for (var i = 0; i < plain.size(); i++) {
      String name;
      if (outputs != null && i < outputs.size()) {
        name = String.valueOf(outputs.get(i));
      } else {
        name = plain.size() == 1 && out.isEmpty() ? "out" : "out" + (i + 1);
      }
      out.put(name, plain.get(i));
    }
  }

  private static Var parseVar(String s, String what) {
    final Var v;
    try {
      v = Var.parse(s);
    } catch (ParserException e) {
      throw new McpToolException("Bad " + what + " name '" + s + "': " + e.getMessage());
    }
    if (!NAME.matcher(v.name).matches()) {
      throw new McpToolException(
          "Bad " + what + " name '" + s + "': use letters, digits and '_' (not starting with a"
              + " digit), optionally followed by [N..0] for a multi-bit " + what + ".");
    }
    return v;
  }

  /** Finds the variables of an expression by parsing it repeatedly against a growing model. */
  private static void discoverVariables(String output, String text, Map<String, Integer> found) {
    final var local = new LinkedHashMap<String, Integer>();
    for (var guard = 0; guard < 64; guard++) {
      final var model = new AnalyzerModel();
      final var vars = new ArrayList<Var>();
      for (final var e : local.entrySet()) vars.add(new Var(e.getKey(), e.getValue()));
      try {
        model.setVariables(vars, List.of());
      } catch (IllegalArgumentException e) {
        throw new McpToolException(
            "Too many input bits in the expressions (at most " + AnalyzerModel.MAX_INPUTS + ").");
      }
      try {
        Parser.parse(text, model);
        for (final var e : local.entrySet()) found.merge(e.getKey(), e.getValue(), Math::max);
        return;
      } catch (ParserException ex) {
        final var start = Math.max(0, Math.min(ex.getOffset(), text.length()));
        final var end = Math.max(start, Math.min(ex.getEndOffset(), text.length()));
        final var token = text.substring(start, end);
        final var m = BIT_NAME.matcher(token);
        if (token.isEmpty() || !m.matches()) {
          throw new McpToolException(parseErrorMessage(output, text, ex));
        }
        final var base = m.group(1);
        final var old = local.get(base);
        if (m.group(2) == null) {
          if (old != null) {
            throw new McpToolException(
                "Variable '" + base + "' is used both as a single bit and with a bit index in "
                    + where(output) + ".");
          }
          local.put(base, 1);
        } else {
          final var width = Integer.parseInt(m.group(2)) + 1;
          if (old != null && old == 1) {
            throw new McpToolException(
                "Variable '" + base + "' is used both as a single bit and with a bit index in "
                    + where(output) + ".");
          }
          if (old != null && old >= width) {
            throw new McpToolException(parseErrorMessage(output, text, ex));
          }
          if (width < 2 && old == null) {
            // A lone a[0] would be a 1-bit variable, which is named plain 'a'; assume >= 2 bits.
            local.put(base, 2);
          } else {
            local.put(base, Math.max(width, old == null ? 0 : old));
          }
        }
      }
    }
    throw new McpToolException("Could not determine the variables of '" + text + "'.");
  }

  private static String where(String output) {
    return output == null ? "the expression" : "the expression for '" + output + "'";
  }

  private static String parseErrorMessage(String output, String text, ParserException ex) {
    final var start = Math.max(0, Math.min(ex.getOffset(), text.length()));
    final var end = Math.max(start, Math.min(ex.getEndOffset(), text.length()));
    return "Syntax error in expression"
        + (output == null ? "" : " for '" + output + "'")
        + " at offset " + start + " ('" + text.substring(start, end) + "'): "
        + ex.getMessage().replaceAll("\\.+$", "") + ". Expression: " + text.trim();
  }

  private static Entry entry(char c, String where) {
    return switch (c) {
      case '0' -> Entry.ZERO;
      case '1' -> Entry.ONE;
      case '-', 'x', 'X', '?', '*' -> Entry.DONT_CARE;
      default -> throw new McpToolException("Bad truth value '" + c + "' in " + where + ".");
    };
  }

  private static Entry[] column(String output, Object spec, int rowCount) {
    final var values = new Entry[rowCount];
    if (spec instanceof String s) {
      final var str = s.replaceAll("[\\s_,]", "");
      if (str.length() != rowCount) {
        throw new McpToolException(
            "Column for '" + output + "' has " + str.length() + " values; expected " + rowCount
                + " (2^inputs).");
      }
      for (var i = 0; i < rowCount; i++) values[i] = entry(str.charAt(i), "column of '" + output + "'");
      return values;
    }
    final var a = Args.of(spec);
    final var hasMin = a.has("minterms");
    final var hasMax = a.has("maxterms");
    if (hasMin == hasMax) {
      throw new McpToolException(
          "Table spec for '" + output + "' needs exactly one of 'minterms' or 'maxterms'.");
    }
    Arrays.fill(values, hasMin ? Entry.ZERO : Entry.ONE);
    final var set = hasMin ? Entry.ONE : Entry.ZERO;
    for (final var o : a.list(hasMin ? "minterms" : "maxterms")) {
      values[rowIndex(o, rowCount, output)] = set;
    }
    for (final var o : a.list("dontcares", List.of())) {
      values[rowIndex(o, rowCount, output)] = Entry.DONT_CARE;
    }
    return values;
  }

  private static int rowIndex(Object o, int rowCount, String output) {
    final int idx;
    try {
      idx = o instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(o).trim());
    } catch (NumberFormatException e) {
      throw new McpToolException("Bad row index '" + o + "' for '" + output + "'.");
    }
    if (idx < 0 || idx >= rowCount) {
      throw new McpToolException(
          "Row index " + idx + " for '" + output + "' is out of range 0.." + (rowCount - 1) + ".");
    }
    return idx;
  }

  private static void applyRow(String row, int nIn, Entry[][] columns) {
    final var parts = row.trim().split("[\\s|:]+");
    if (parts.length != 2) {
      throw new McpToolException("Row '" + row + "' must look like 'INPUTS OUTPUTS', e.g. '1-0 01'.");
    }
    final var in = parts[0];
    final var out = parts[1];
    if (in.length() != nIn || out.length() != columns.length) {
      throw new McpToolException(
          "Row '" + row + "' must have " + nIn + " input and " + columns.length
              + " output characters.");
    }
    var base = 0;
    var dcMask = 0;
    for (var i = 0; i < nIn; i++) {
      final var c = in.charAt(i);
      final var bit = 1 << (nIn - 1 - i);
      if (c == '1') base |= bit;
      else if (c == '-' || c == 'x' || c == 'X') dcMask |= bit;
      else if (c != '0') throw new McpToolException("Bad input character '" + c + "' in row '" + row + "'.");
    }
    final var outs = new Entry[columns.length];
    for (var j = 0; j < outs.length; j++) outs[j] = entry(out.charAt(j), "row '" + row + "'");
    // Iterate over all subsets of dcMask.
    var sub = dcMask;
    while (true) {
      final var idx = base | sub;
      for (var j = 0; j < outs.length; j++) columns[j][idx] = outs[j];
      if (sub == 0) break;
      sub = (sub - 1) & dcMask;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Output
  // ---------------------------------------------------------------------------------------------

  static Expression.Notation notation(Args args) {
    final var n = args.str("notation", "mathematical").toUpperCase(Locale.ROOT);
    try {
      return Expression.Notation.valueOf(n);
    } catch (IllegalArgumentException e) {
      throw new McpToolException(
          "Unknown notation '" + args.str("notation") + "'. Use one of " + Arrays.toString(NOTATIONS));
    }
  }

  private static String text(Expression e, Expression.Notation notation) {
    return e == null ? null : e.toString(notation);
  }

  private static char entryChar(Entry e) {
    if (e == Entry.ZERO) return '0';
    if (e == Entry.ONE) return '1';
    if (e == Entry.DONT_CARE) return '-';
    if (e == Entry.OSCILLATE_ERROR) return 'O';
    return 'E';
  }

  /**
   * Minimal expressions (index 0: SOP, 1: POS) of every output; keeps the current expressions.
   * Without {@code force}, returns null for more than 6 inputs (like the analyzer). With {@code
   * force}, Logisim's exact (exponential) minimizer runs on a copy of the table in a worker thread
   * and gives up after {@code timeoutSeconds}.
   */
  private static Map<String, Expression[]> minimize(
      AnalyzerModel model, boolean force, int timeoutSeconds) {
    if (force) return minimizeForced(model, timeoutSeconds);
    if (model.getInputs().bits.size() > Implicant.MAXIMAL_NR_OF_INPUTS_FOR_AUTO_MINIMAL_FORM) {
      return null;
    }
    final var oe = model.getOutputExpressions();
    final var outs = model.getOutputs().bits;
    final var current = new LinkedHashMap<String, Expression>();
    final var currentText = new LinkedHashMap<String, String>();
    for (final var o : outs) {
      // Expressions that merely mirror the table (minimal form) are not restored: restoring
      // would recompute the column from them and lose the don't cares.
      if (oe.isExpressionMinimal(o)) continue;
      current.put(o, oe.getExpression(o));
      currentText.put(o, oe.getExpressionString(o));
    }
    final var result = new LinkedHashMap<String, Expression[]>();
    for (final var o : outs) result.put(o, new Expression[2]);
    for (var f = 0; f < 2; f++) {
      for (final var o : outs) oe.setMinimizedFormat(o, FORMATS[f]);
      for (final var o : outs) result.get(o)[f] = oe.getMinimalExpression(o);
    }
    for (final var o : outs) oe.setMinimizedFormat(o, AnalyzerModel.FORMAT_SUM_OF_PRODUCTS);
    // Restore the original expressions (format changes replace them by the minimal form).
    for (final var e : current.entrySet()) {
      if (e.getValue() != null) oe.setExpression(e.getKey(), e.getValue(), currentText.get(e.getKey()));
    }
    return result;
  }

  private static final int[] FORMATS = {
    AnalyzerModel.FORMAT_SUM_OF_PRODUCTS, AnalyzerModel.FORMAT_PRODUCT_OF_SUMS
  };

  private static Map<String, Expression[]> minimizeForced(AnalyzerModel model, int timeoutSeconds) {
    // Copy variables and table; minimal forms only depend on the table.
    final var copy = newModel();
    copy.setVariables(new ArrayList<>(model.getInputs().vars), new ArrayList<>(model.getOutputs().vars));
    final var table = model.getTruthTable();
    for (var i = 0; i < table.getOutputColumnCount(); i++) {
      copy.getTruthTable().setOutputColumn(i, table.getOutputColumn(i).clone());
    }
    final var outs = new ArrayList<>(copy.getOutputs().bits);
    final var task =
        new FutureTask<Map<String, Expression[]>>(
            () -> {
              final var oe = copy.getOutputExpressions();
              final var result = new LinkedHashMap<String, Expression[]>();
              for (final var o : outs) {
                oe.getExpression(o); // creates the per-output state used by forcedOptimize
                result.put(o, new Expression[2]);
              }
              for (var f = 0; f < 2; f++) {
                oe.forcedOptimize(new JTextArea(), FORMATS[f]);
                for (final var o : outs) result.get(o)[f] = oe.getMinimalExpression(o);
              }
              return result;
            });
    final var thread = new Thread(task, "logisim-mcp-minimizer");
    thread.setDaemon(true);
    thread.setPriority(Thread.MIN_PRIORITY);
    thread.start();
    try {
      return task.get(timeoutSeconds, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      thread.interrupt();
      throw new McpToolException(
          "Minimization did not finish within " + timeoutSeconds + " s: Logisim's exact minimizer"
              + " is exponential for functions with many inputs. Raise 'minimizeTimeout', or"
              + " give the function as expressions.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new McpToolException("Interrupted while minimizing.");
    } catch (ExecutionException e) {
      throw new McpToolException("Minimization failed: " + e.getCause(), e.getCause());
    }
  }

  private static boolean shouldForce(AnalyzerModel model, Args args) {
    final var n = model.getInputs().bits.size();
    if (n <= Implicant.MAXIMAL_NR_OF_INPUTS_FOR_AUTO_MINIMAL_FORM) return false;
    return args.bool("forceMinimize", false);
  }

  private static int timeout(Args args) {
    return Math.max(1, args.integer("minimizeTimeout", 20));
  }

  static Map<String, Object> describe(AnalyzerModel model, Args args) {
    final var notation = notation(args);
    final var out = new LinkedHashMap<String, Object>();
    final var inputs = model.getInputs();
    final var outputs = model.getOutputs();
    final var table = model.getTruthTable();
    final var rowCount = table.getRowCount();
    final var nIn = inputs.bits.size();
    out.put("inputs", new ArrayList<>(inputs.bits));
    out.put("outputs", new ArrayList<>(outputs.bits));
    if (inputs.vars.size() != inputs.bits.size() || outputs.vars.size() != outputs.bits.size()) {
      final var iv = new ArrayList<String>();
      for (final var v : inputs.vars) iv.add(v.toString());
      final var ov = new ArrayList<String>();
      for (final var v : outputs.vars) ov.add(v.toString());
      out.put("inputVariables", iv);
      out.put("outputVariables", ov);
    }
    out.put("rowCount", rowCount);
    out.put("notation", notation.name().toLowerCase(Locale.ROOT));
    Map<String, Expression[]> minimal;
    String minimizationNote = null;
    try {
      minimal = minimize(model, shouldForce(model, args), timeout(args));
      if (minimal == null) {
        minimizationNote =
            "Minimal forms are not computed automatically for more than "
                + Implicant.MAXIMAL_NR_OF_INPUTS_FOR_AUTO_MINIMAL_FORM + " inputs (" + nIn
                + " here); pass forceMinimize=true (may be slow).";
      }
    } catch (McpToolException e) {
      minimal = null;
      minimizationNote = e.getMessage();
    }
    final var minimizerRan = minimal != null;
    final var oe = model.getOutputExpressions();
    final var details = new ArrayList<Object>();
    var errorRows = 0;
    for (var col = 0; col < outputs.bits.size(); col++) {
      final var name = outputs.bits.get(col);
      final var d = new LinkedHashMap<String, Object>();
      d.put("name", name);
      final var expr = oe.getExpression(name);
      // For many inputs the analyzer's "expression" of a table-defined output is a placeholder.
      final var placeholder = !minimizerRan && oe.isExpressionMinimal(name);
      d.put("expression", placeholder ? null : text(expr, notation));
      if (minimizerRan) {
        d.put("minimalSop", text(minimal.get(name)[0], notation));
        d.put("minimalPos", text(minimal.get(name)[1], notation));
        d.put("expressionIsMinimal", oe.isExpressionMinimal(name));
      }
      final var minterms = new ArrayList<Integer>();
      final var dontcares = new ArrayList<Integer>();
      final var colStr = new StringBuilder();
      for (var r = 0; r < rowCount; r++) {
        final var e = table.getOutputEntry(r, col);
        if (e == Entry.ONE) minterms.add(r);
        else if (e == Entry.DONT_CARE) dontcares.add(r);
        else if (e.isError()) errorRows++;
        colStr.append(entryChar(e));
      }
      d.put("minterms", minterms);
      d.put("dontcares", dontcares);
      if (rowCount <= 1024) d.put("column", colStr.toString());
      details.add(d);
    }
    out.put("outputDetails", details);
    if (minimizationNote != null) out.put("minimizationNote", minimizationNote);
    if (errorRows > 0) {
      out.put(
          "errorEntries",
          errorRows + " table entries are errors ('E' = conflicting/bus error, 'O' = oscillation).");
    }
    if (args.bool("includeTable", true)) {
      final var maxRows = args.integer("maxRows", 256);
      if (rowCount <= maxRows) {
        final var rows = new ArrayList<String>();
        for (var r = 0; r < rowCount; r++) {
          final var sb = new StringBuilder();
          for (var c = 0; c < nIn; c++) sb.append(TruthTable.isInputSet(r, c, nIn) ? '1' : '0');
          sb.append(' ');
          for (var c = 0; c < outputs.bits.size(); c++) sb.append(entryChar(table.getOutputEntry(r, c)));
          rows.add(sb.toString());
        }
        out.put("rows", rows);
      } else {
        out.put("rowsOmitted", "The table has " + rowCount + " rows (> maxRows=" + maxRows + ").");
      }
      table.compactVisibleRows();
      final var visible = table.getVisibleRowCount();
      if (visible <= maxRows) {
        final var compact = new ArrayList<String>();
        for (var r = 0; r < visible; r++) {
          final var sb = new StringBuilder();
          for (var c = 0; c < nIn; c++) sb.append(entryChar(table.getVisibleInputEntry(r, c)));
          sb.append(' ');
          for (var c = 0; c < outputs.bits.size(); c++) {
            sb.append(entryChar(table.getVisibleOutputEntry(r, c)));
          }
          compact.add(sb.toString());
        }
        out.put("compactRows", compact);
      }
      out.put("rowFormat", "'INPUTS OUTPUTS', bits in the order of 'inputs' and 'outputs'.");
    }
    return out;
  }

  // ---------------------------------------------------------------------------------------------
  // analysis_parse_expression
  // ---------------------------------------------------------------------------------------------

  private static Object parseExpressionTool(Args args) {
    final var notation = notation(args);
    var text = args.str("expression");
    final var out = new LinkedHashMap<String, Object>();
    final var m = ASSIGNMENT.matcher(text);
    if (m.matches()) {
      out.put("output", m.group(1));
      text = m.group(2);
    }
    final var model = new AnalyzerModel();
    final var vars = new ArrayList<Var>();
    if (args.has("inputs")) {
      for (final var i : args.list("inputs")) vars.add(parseVar(String.valueOf(i), "input"));
    } else {
      final var found = new TreeMap<String, Integer>();
      try {
        discoverVariables(null, text, found);
      } catch (McpToolException e) {
        out.put("valid", false);
        out.put("error", e.getMessage());
        return out;
      }
      for (final var e : found.entrySet()) vars.add(new Var(e.getKey(), e.getValue()));
    }
    try {
      model.setVariables(vars, List.of());
    } catch (IllegalArgumentException e) {
      throw new McpToolException("Too many input bits (at most " + AnalyzerModel.MAX_INPUTS + ").");
    }
    try {
      final var expr = Parser.parse(text, model);
      if (expr == null) {
        out.put("valid", false);
        out.put("error", "The expression is empty.");
        return out;
      }
      out.put("valid", true);
      out.put("variables", new ArrayList<>(model.getInputs().bits));
      out.put("normalized", expr.toString(notation));
      final var all = new LinkedHashMap<String, String>();
      for (final var n : Expression.Notation.values()) {
        all.put(n.name().toLowerCase(Locale.ROOT), expr.toString(n));
      }
      out.put("notations", all);
      final var ops = new ArrayList<String>();
      for (final var op : Expression.Op.values()) {
        if (op != Expression.Op.EQ && expr.contains(op)) ops.add(op.name());
      }
      out.put("operators", ops);
    } catch (ParserException ex) {
      out.put("valid", false);
      out.put("error", ex.getMessage());
      out.put("offset", ex.getOffset());
      out.put("length", ex.getEndOffset() - ex.getOffset());
      out.put("message", parseErrorMessage(null, text, ex));
    }
    return out;
  }

  // ---------------------------------------------------------------------------------------------
  // analysis_build_circuit
  // ---------------------------------------------------------------------------------------------

  private Object buildCircuit(McpContext ctx, Args args) {
    final var proj = ctx.project(args);
    final var file = proj.getLogisimFile();
    final var info = new LinkedHashMap<String, Object>();
    final AnalyzerModel model;
    if (args.has("sourceCircuit")) {
      final var src = file.getCircuit(args.str("sourceCircuit"));
      if (src == null) {
        throw new McpToolException(
            "Unknown circuit '" + args.str("sourceCircuit") + "'. Available: "
                + McpContext.circuitNames(proj));
      }
      model = modelFromCircuit(proj, src, "auto", info);
    } else {
      model = modelFromSpec(args);
    }
    final var oe = model.getOutputExpressions();
    final var outs = model.getOutputs().bits;

    final var form = args.str("form", "as_given");
    if (!List.of("as_given", "sop", "pos").contains(form)) {
      throw new McpToolException("Unknown form '" + form + "'. Use as_given, sop or pos.");
    }
    final var many =
        model.getInputs().bits.size() > Implicant.MAXIMAL_NR_OF_INPUTS_FOR_AUTO_MINIMAL_FORM;
    if (!"as_given".equals(form)) {
      // Building needs the real minimal form, so the minimizer is always run here.
      final var min = minimize(model, many, timeout(args));
      final var f = "sop".equals(form) ? 0 : 1;
      for (final var o : outs) {
        final var e = min.get(o)[f];
        if (e != null) oe.setExpression(o, e);
      }
    } else if (many) {
      // Table-defined outputs only get a real expression from the (forced) minimizer.
      Map<String, Expression[]> min = null;
      for (final var o : outs) {
        if (oe.getExpression(o) != null && !oe.isExpressionMinimal(o)) continue;
        if (min == null) min = minimize(model, true, timeout(args));
        oe.setExpression(o, min.get(o)[0]);
      }
    }

    var anyExpr = false;
    var hasXor = false;
    for (final var o : outs) {
      final var e = oe.getExpression(o);
      if (e != null) {
        anyExpr = true;
        hasXor |= e.contains(Expression.Op.XOR) || e.contains(Expression.Op.XNOR)
            || e.contains(Expression.Op.EQ);
      }
    }
    if (!anyExpr) throw new McpToolException("There is no expression to build (all outputs undefined).");
    final var nands = args.bool("nandGates", false);
    if (nands && hasXor) {
      throw new McpToolException(
          "NAND-only circuits cannot be built from expressions containing XOR/XNOR. Use"
              + " form='sop' or form='pos', or nandGates=false.");
    }

    // Name checks, as in the Build Circuit dialog.
    var name = args.str("name").trim();
    if (name.isEmpty()) throw new McpToolException("The circuit name is empty.");
    final var err = SyntaxChecker.getErrorMessage(name);
    if (err != null) throw new McpToolException("Invalid circuit name '" + name + "': " + err);
    for (final var label : model.getInputs().getNames()) {
      if (label.equalsIgnoreCase(name)) {
        throw new McpToolException("The circuit name must differ from the input/output names.");
      }
    }
    for (final var label : model.getOutputs().getNames()) {
      if (label.equalsIgnoreCase(name)) {
        throw new McpToolException("The circuit name must differ from the input/output names.");
      }
    }
    Circuit existing = null;
    for (final var c : file.getCircuits()) {
      if (c.getName().equalsIgnoreCase(name)) {
        existing = c;
        name = c.getName();
      }
    }
    if (existing == null && file.containsFactory(name)) {
      throw new McpToolException("A VHDL entity named '" + name + "' exists; choose another name.");
    }
    final var twoInputs = args.bool("twoInputGates", false);
    final Circuit target;
    if (existing != null) {
      if (!args.bool("replace", false)) {
        throw new McpToolException(
            "Circuit '" + name + "' already exists. Pass replace=true to rebuild its contents"
                + " (undoable), or choose another name.");
      }
      final var xn = CircuitBuilder.build(existing, model, twoInputs, nands);
      proj.doAction(xn.toAction(S.getter("replaceCircuitAction")));
      target = existing;
      info.put("replaced", true);
    } else {
      target = new Circuit(name, file, proj);
      final var xn = CircuitBuilder.build(target, model, twoInputs, nands);
      xn.execute();
      proj.doAction(LogisimFileActions.addCircuit(target));
      info.put("replaced", false);
    }
    if (args.bool("setCurrent", true)) proj.setCurrentCircuit(target);
    info.put("circuit", target.getName());
    info.put("componentCount", target.getNonWires().size());
    info.put("wireCount", target.getWires().size());
    final var notation = notation(args);
    final var used = new LinkedHashMap<String, Object>();
    for (final var o : outs) used.put(o, text(oe.getExpression(o), notation));
    info.put("inputs", new ArrayList<>(model.getInputs().bits));
    info.put("expressions", used);
    return info;
  }
}
