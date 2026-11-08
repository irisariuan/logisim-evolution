[![Logisim-evolution](img/logisim-evolution-logo.png)](https://github.com/logisim-evolution/logisim-evolution)

---

# Controlling Logisim-evolution from an AI assistant (MCP) #

* [« Go back](../README.md)
* **AI control (MCP server)**
  * [What it is](#what-it-is)
  * [Starting the server](#starting-the-server)
    * [stdio: the client starts Logisim-evolution](#stdio-the-client-starts-logisim-evolution)
    * [HTTP: connect to a running Logisim-evolution](#http-connect-to-a-running-logisim-evolution)
  * [Security](#security)
  * [Conventions](#conventions)
  * [Available tools](#available-tools)
  * [Troubleshooting](#troubleshooting)

---

## What it is ##

`Logisim-evolution` contains a [Model Context Protocol](https://modelcontextprotocol.io/) (MCP) server.
MCP is an open protocol that lets AI assistants and other programs ("MCP clients") call *tools*
offered by an application. Through these tools a client can do everything a user can do in the
GUI: create and open projects, place and wire components, edit attributes, simulate and poke
inputs, read values, edit memory contents, run test vectors, analyze circuits, generate HDL,
manage libraries, change project options and preferences, and take screenshots.

The server can run in two ways:

| Mode | Started with | Windows | Typical use |
|------|--------------|---------|-------------|
| stdio, headless | `--mcp` | none | The MCP client launches Logisim-evolution in the background. |
| stdio with GUI | `--mcp --mcp-gui` | normal windows | Same, but you see every change live and can work alongside the assistant. |
| HTTP | `--mcp-http <port>` or **Window ▸ MCP Server (AI Control)** | normal windows | Connect a client to a Logisim-evolution you already have open. |

## Starting the server ##

All examples below use the portable JAR `logisim-evolution-<version>-all.jar` (see
[Download](../README.md#download)) started with `java -jar`. If you installed a package, you can
use its launcher instead (for example `/opt/logisim-evolution/bin/logisim-evolution` on Linux) and
pass the same options. On Windows, prefer `java -jar` for the stdio mode, as the GUI launcher does
not forward standard input/output.

### stdio: the client starts Logisim-evolution ###

With `--mcp` Logisim-evolution speaks MCP (JSON-RPC) on its standard input and output and shows no
window. Everything else Logisim-evolution would print is sent to standard error. Files given on
the command line are opened as projects. The process ends when the client disconnects.

Most desktop MCP clients are configured with a JSON file. A typical entry looks like this
(adapt the path to the JAR):

```json
{
  "mcpServers": {
    "logisim": {
      "command": "java",
      "args": ["-jar", "/path/to/logisim-evolution-<version>-all.jar", "--mcp"]
    }
  }
}
```

To also see the Logisim-evolution windows, add `--mcp-gui`:

```json
{
  "mcpServers": {
    "logisim": {
      "command": "java",
      "args": ["-jar", "/path/to/logisim-evolution-<version>-all.jar", "--mcp", "--mcp-gui"]
    }
  }
}
```

With a command-line client such as Claude Code the same server can be registered with:

```bash
claude mcp add logisim -- java -jar /path/to/logisim-evolution-<version>-all.jar --mcp
```

### HTTP: connect to a running Logisim-evolution ###

To let a client drive the Logisim-evolution you are working in, start the HTTP endpoint in one of
these ways:

* choose **Window ▸ MCP Server (AI Control)** in a running Logisim-evolution (choose it again to
  stop the server); the dialog shows the URL to use. Port `9725` is used when it is free, otherwise
  a free port is picked;
* or start Logisim-evolution with `--mcp-http 9725` (`--mcp-http 0` picks a free port, which is
  printed on standard error).

The endpoint uses the MCP *Streamable HTTP* transport at:

```text
http://127.0.0.1:9725/mcp
```

For example, with Claude Code:

```bash
claude mcp add --transport http logisim http://127.0.0.1:9725/mcp
```

or, for clients configured with JSON and supporting HTTP servers:

```json
{
  "mcpServers": {
    "logisim": { "type": "http", "url": "http://127.0.0.1:9725/mcp" }
  }
}
```

`--mcp-http` can be combined with `--mcp` to offer both transports at the same time; they share
the same open projects.

## Security ##

* The HTTP server only listens on `127.0.0.1` (the local machine) and is **not reachable from the
  network**.
* There is **no authentication**: while the HTTP server runs, every program and every user on this
  computer can control Logisim-evolution, which includes reading and writing files with your
  permissions (opening, saving and exporting projects, loading libraries). Only run it on a computer
  you trust and stop it when you no longer need it.
* Loading a JAR library (`library_load_jar`) runs the code contained in that JAR. Only load JARs you
  trust, exactly as in the GUI.
* The stdio mode has no network endpoint at all; only the program that launched Logisim-evolution
  can talk to it.

## Conventions ##

These rules are also sent to the client in the server's instructions:

* **Projects** are identified by ids like `p1` (see `project_list`). Most tools take optional
  `project` and `circuit` arguments and default to the current project and circuit.
* **Components** (including wires) are identified by ids like `c12` returned by the listing tools.
  Moving or replacing a component can give it a new id: always use the ids of the latest response.
  Many tools also accept a component's label or a location such as `@120,80`.
* **Coordinates** are canvas coordinates; the grid is 10 units. Wires connect only at exact port
  locations.
* **Undo:** edits go through the same undoable actions as the GUI, so they can be undone and redone
  (also from the Edit menu when the windows are shown), and they mark the project as modified.
  Application preferences and file operations are not undoable, as in the GUI.
* **Dialogs:** Logisim-evolution never opens a modal window on behalf of a tool. Messages and
  questions it would show are returned in the tool result under `dialogs` (with the answer that was
  used). Some tools take arguments for those decisions (for example `onConflict` of
  `library_merge`), and the generic `dialogAnswers` argument can pre-answer them.
* Use `server_info` to find out whether a GUI is attached (changes are then visible live).

## Available tools ##

The authoritative, always up-to-date list of tools with their descriptions and argument schemas is
returned by the MCP `tools/list` request, which every MCP client shows. The tools are organized in
groups:

| Group | What it covers |
|-------|----------------|
| Project (`server_info`, `project_*`) | Server information; new, open, save (as), close and switch projects. |
| Editing | Circuits, components, wires, attributes, selection, clipboard, undo/redo. |
| Simulation | Poking inputs, reading values, clocks and ticks, stepping, reset, simulation hierarchy. |
| Memory and tests | RAM/ROM/register contents, test vectors. |
| View and windows | Screenshots/rendering, windows and canvas, tool selection, appearance editing. |
| Analysis | Combinational analysis: truth tables, expressions, minimization, building circuits. |
| HDL and FPGA | VHDL/Verilog generation, HDL components, FPGA synthesis and download. |
| Libraries (`library_*`) | List, load (built-in, `.circ`, JAR), unload, reload libraries; merge circuits from another file; export/import project bundles. |
| Options and preferences (`options_*`, `toolbar_*`, `mouse_*`, `prefs_*`) | Project simulation options, toolbar and mouse mappings (saved in the `.circ` file), application preferences such as gate shape (ANSI/IEC), language and the template for new projects. |

## Troubleshooting ##

* *The client reports that the server does not start:* run the configured command yourself in a
  terminal. With `--mcp` it should wait silently for input; errors are printed on standard error.
  Check that `java` (Java 21 or newer) is on the `PATH` the client uses, or give its full path.
* *Port already in use:* choose another port with `--mcp-http <port>`, or use `0` and read the port
  from standard error. The **Window** menu entry falls back to a free port automatically.
* *Changes are not visible:* in the headless `--mcp` mode there are no windows; add `--mcp-gui`, or
  use the HTTP mode with a Logisim-evolution you started yourself.
