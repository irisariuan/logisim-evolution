/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects tool definitions from all {@link McpToolGroup}s.
 *
 * <pre>{@code
 * registry.tool("circuit_list")
 *     .description("Lists the circuits of a project.")
 *     .input(Schema.object().prop("project", McpToolRegistry.PROJECT_ARG))
 *     .readOnly()
 *     .handler((ctx, args) -> ...);
 * }</pre>
 *
 * <p>Handler return values are converted as follows: {@code null} becomes {@code {"ok":true}}, a
 * String is sent as text, an {@link ImageResult} as image content, a {@code CallToolResult} is
 * passed through, anything else (maps, lists, records, numbers) is serialized as JSON. Throw
 * {@link McpToolException} to report a user-facing error.
 */
public final class McpToolRegistry {

  /** Common optional "project" argument understood by {@link McpContext#project(Args)}. */
  public static final Schema PROJECT_ARG =
      Schema.string("Project id (e.g. 'p1'), file path or name. Defaults to the current project.");

  /** Common optional "circuit" argument understood by {@link McpContext#circuit}. */
  public static final Schema CIRCUIT_ARG =
      Schema.string("Circuit name. Defaults to the circuit currently shown/selected in the project.");

  /** Implementation of one tool. */
  @FunctionalInterface
  public interface Handler {
    Object call(McpContext ctx, Args args) throws Exception;
  }

  /** A fully defined tool. */
  public record ToolDef(
      String name,
      String description,
      Map<String, Object> inputSchema,
      boolean readOnly,
      boolean destructive,
      boolean onModelThread,
      Handler handler) {}

  /** Fluent builder returned by {@link #tool(String)}; the tool is registered by {@link #handler}. */
  public final class Builder {
    private final String name;
    private String description = "";
    private Map<String, Object> schema = Schema.object().toMap();
    private boolean readOnly;
    private boolean destructive;
    private boolean onModelThread = true;

    private Builder(String name) {
      this.name = name;
    }

    public Builder description(String value) {
      description = value;
      return this;
    }

    public Builder input(Schema value) {
      schema = value.toMap();
      return this;
    }

    /** The tool does not modify anything. */
    public Builder readOnly() {
      readOnly = true;
      return this;
    }

    /** The tool may irreversibly destroy data (closing without save, overwriting files...). */
    public Builder destructive() {
      destructive = true;
      return this;
    }

    /**
     * Run the handler on the transport thread instead of the model thread. The handler must then
     * use {@link McpContext#onModelThread} for every model access. Use for tools that wait.
     */
    public Builder offModelThread() {
      onModelThread = false;
      return this;
    }

    public void handler(Handler handler) {
      add(new ToolDef(name, description, schema, readOnly, destructive, onModelThread, handler));
    }
  }

  private final Map<String, ToolDef> tools = new LinkedHashMap<>();

  public Builder tool(String name) {
    return new Builder(name);
  }

  public void add(ToolDef def) {
    if (tools.containsKey(def.name())) {
      throw new IllegalStateException("Duplicate MCP tool name: " + def.name());
    }
    tools.put(def.name(), def);
  }

  public List<ToolDef> getTools() {
    return Collections.unmodifiableList(new ArrayList<>(tools.values()));
  }

  public ToolDef getTool(String name) {
    return tools.get(name);
  }
}
