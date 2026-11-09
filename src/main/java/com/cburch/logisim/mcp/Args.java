/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Typed, validating view of the arguments of one MCP tool call. Every getter throws {@link
 * McpToolException} with a readable message when a required value is missing or has the wrong
 * type, so handlers can simply call them.
 */
public final class Args {
  private final Map<String, Object> raw;

  public Args(Map<String, Object> raw) {
    this.raw = raw == null ? Collections.emptyMap() : raw;
  }

  public Map<String, Object> raw() {
    return raw;
  }

  public boolean has(String name) {
    return raw.get(name) != null;
  }

  public Object get(String name) {
    return raw.get(name);
  }

  public String str(String name) {
    final var v = require(name);
    return v.toString();
  }

  public String str(String name, String def) {
    final var v = raw.get(name);
    return v == null ? def : v.toString();
  }

  public int integer(String name) {
    return toInt(name, require(name));
  }

  public int integer(String name, int def) {
    final var v = raw.get(name);
    return v == null ? def : toInt(name, v);
  }

  public long lng(String name) {
    return toLong(name, require(name));
  }

  public long lng(String name, long def) {
    final var v = raw.get(name);
    return v == null ? def : toLong(name, v);
  }

  public double dbl(String name) {
    return toDouble(name, require(name));
  }

  public double dbl(String name, double def) {
    final var v = raw.get(name);
    return v == null ? def : toDouble(name, v);
  }

  public boolean bool(String name) {
    return toBool(name, require(name));
  }

  public boolean bool(String name, boolean def) {
    final var v = raw.get(name);
    return v == null ? def : toBool(name, v);
  }

  @SuppressWarnings("unchecked")
  public List<Object> list(String name) {
    final var v = require(name);
    if (v instanceof List<?> l) return (List<Object>) l;
    throw new McpToolException("Argument '" + name + "' must be an array.");
  }

  @SuppressWarnings("unchecked")
  public List<Object> list(String name, List<Object> def) {
    final var v = raw.get(name);
    if (v == null) return def;
    if (v instanceof List<?> l) return (List<Object>) l;
    throw new McpToolException("Argument '" + name + "' must be an array.");
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> map(String name) {
    final var v = require(name);
    if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
    throw new McpToolException("Argument '" + name + "' must be an object.");
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> map(String name, Map<String, Object> def) {
    final var v = raw.get(name);
    if (v == null) return def;
    if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
    throw new McpToolException("Argument '" + name + "' must be an object.");
  }

  /** Wraps a nested object (e.g. one element of an array argument) as {@link Args}. */
  @SuppressWarnings("unchecked")
  public static Args of(Object nested) {
    if (nested instanceof Map<?, ?> m) return new Args((Map<String, Object>) m);
    throw new McpToolException("Expected a JSON object but got: " + nested);
  }

  private Object require(String name) {
    final var v = raw.get(name);
    if (v == null) throw new McpToolException("Missing required argument '" + name + "'.");
    return v;
  }

  private static int toInt(String name, Object v) {
    final var l = toLong(name, v);
    if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
      throw new McpToolException("Argument '" + name + "' is out of range: " + v);
    }
    return (int) l;
  }

  private static long toLong(String name, Object v) {
    if (v instanceof Number n) {
      if (n.doubleValue() != Math.rint(n.doubleValue())) {
        throw new McpToolException("Argument '" + name + "' must be an integer, got " + v);
      }
      return n.longValue();
    }
    try {
      final var s = v.toString().trim();
      if (s.startsWith("0x") || s.startsWith("0X")) return Long.parseLong(s.substring(2), 16);
      return Long.parseLong(s);
    } catch (NumberFormatException e) {
      throw new McpToolException("Argument '" + name + "' must be an integer, got " + v);
    }
  }

  private static double toDouble(String name, Object v) {
    if (v instanceof Number n) return n.doubleValue();
    try {
      return Double.parseDouble(v.toString().trim());
    } catch (NumberFormatException e) {
      throw new McpToolException("Argument '" + name + "' must be a number, got " + v);
    }
  }

  private static boolean toBool(String name, Object v) {
    if (v instanceof Boolean b) return b;
    final var s = v.toString().trim().toLowerCase();
    if (s.equals("true") || s.equals("1") || s.equals("yes")) return true;
    if (s.equals("false") || s.equals("0") || s.equals("no")) return false;
    throw new McpToolException("Argument '" + name + "' must be a boolean, got " + v);
  }
}
