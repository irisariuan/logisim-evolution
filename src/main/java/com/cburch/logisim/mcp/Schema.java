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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny fluent builder for the JSON schemas that describe MCP tool inputs.
 *
 * <pre>{@code
 * Schema.object()
 *     .prop("circuit", Schema.string("Circuit name"))
 *     .prop("x", Schema.integer("X coordinate"), true)
 * }</pre>
 */
public final class Schema {
  private final Map<String, Object> map = new LinkedHashMap<>();
  private final Map<String, Object> props = new LinkedHashMap<>();
  private final List<String> required = new ArrayList<>();

  private Schema(String type, String description) {
    if (type != null) map.put("type", type);
    if (description != null) map.put("description", description);
  }

  /** An object schema; add properties with {@link #prop}. */
  public static Schema object() {
    return new Schema("object", null);
  }

  public static Schema object(String description) {
    return new Schema("object", description);
  }

  public static Schema string(String description) {
    return new Schema("string", description);
  }

  public static Schema integer(String description) {
    return new Schema("integer", description);
  }

  public static Schema number(String description) {
    return new Schema("number", description);
  }

  public static Schema bool(String description) {
    return new Schema("boolean", description);
  }

  /** A string restricted to the given values. */
  public static Schema enumeration(String description, String... values) {
    final var s = new Schema("string", description);
    s.map.put("enum", Arrays.asList(values));
    return s;
  }

  public static Schema array(String description, Schema items) {
    final var s = new Schema("array", description);
    s.map.put("items", items.toMap());
    return s;
  }

  /** A value of any JSON type (string, number, boolean, object, array). */
  public static Schema any(String description) {
    return new Schema(null, description);
  }

  /** Adds an optional property. */
  public Schema prop(String name, Schema schema) {
    return prop(name, schema, false);
  }

  /** Adds a property, optionally marking it as required. */
  public Schema prop(String name, Schema schema, boolean isRequired) {
    props.put(name, schema.toMap());
    if (isRequired) required.add(name);
    return this;
  }

  /** Sets an arbitrary schema keyword (e.g. "default", "minimum"). */
  public Schema with(String keyword, Object value) {
    map.put(keyword, value);
    return this;
  }

  /** Allows (or forbids) properties that are not declared (objects only). */
  public Schema additionalProperties(boolean allowed) {
    map.put("additionalProperties", allowed);
    return this;
  }

  public Map<String, Object> toMap() {
    final var out = new LinkedHashMap<>(map);
    if ("object".equals(map.get("type"))) {
      out.put("properties", new LinkedHashMap<>(props));
      if (!required.isEmpty()) out.put("required", new ArrayList<>(required));
    }
    return out;
  }
}
