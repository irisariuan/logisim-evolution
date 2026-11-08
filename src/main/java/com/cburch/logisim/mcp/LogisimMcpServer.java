/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import com.cburch.logisim.generated.BuildInfo;
import com.cburch.logisim.mcp.tools.McpToolGroups;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of Logisim's Model Context Protocol server.
 *
 * <p>Two transports are offered and may run at the same time, sharing one {@link McpContext}:
 *
 * <ul>
 *   <li>stdio ({@code --mcp}): the MCP client launches Logisim; JSON-RPC goes over stdin/stdout.
 *       Anything else the application prints to stdout is redirected to stderr.
 *   <li>Streamable HTTP ({@code --mcp-http PORT} or Window ▸ MCP server): bound to 127.0.0.1, so
 *       an MCP client can drive an already running Logisim window. Endpoint: {@code /mcp}.
 * </ul>
 */
public final class LogisimMcpServer {
  static final Logger logger = LoggerFactory.getLogger(LogisimMcpServer.class);

  public static final int DEFAULT_HTTP_PORT = 9725;
  public static final String HTTP_ENDPOINT = "/mcp";

  static final String INSTRUCTIONS =
      """
      This server controls Logisim-evolution, a digital logic designer and simulator, with the \
      same power as a human user. Conventions:
      - Projects are identified by ids like 'p1' (see project_list); most tools take optional \
      'project' and 'circuit' arguments and default to the current project / circuit.
      - Components (including wires) are identified by ids like 'c12' returned by listing tools. \
      Moving or replacing a component can give it a new id; always use the ids from the latest \
      response.
      - Coordinates are Logisim canvas coordinates; the grid is 10 units. Pins/ports of components \
      should sit on grid points and wires must connect exactly at port locations.
      - Edits are undoable (edit_undo / edit_redo) exactly as in the GUI.
      - Dialogs that Logisim would show are not displayed; they are returned under 'dialogs' in \
      the tool result. Some tools accept 'dialogAnswers' to answer them.
      - Use server_info to learn whether a GUI is attached (changes are then visible live).
      """;

  private static final List<McpSyncServer> servers = new ArrayList<>();
  private static Server jetty;
  private static McpSyncServer httpServer;
  private static int httpPort = -1;
  private static McpToolRegistry registry;

  private LogisimMcpServer() {}

  /** All tools, built once from {@link McpToolGroups}. */
  public static synchronized McpToolRegistry getRegistry() {
    if (registry == null) {
      registry = new McpToolRegistry();
      for (final var group : McpToolGroups.all()) group.register(registry);
    }
    return registry;
  }

  private static McpJsonMapper mapper() {
    return McpJsonDefaults.getMapper();
  }

  private static McpSchema.Implementation serverInfo() {
    return McpSchema.Implementation.builder("logisim-evolution", BuildInfo.version.toString())
        .title(BuildInfo.displayName)
        .build();
  }

  private static List<McpServerFeatures.SyncToolSpecification> toolSpecs() {
    final var specs = new ArrayList<McpServerFeatures.SyncToolSpecification>();
    for (final var def : getRegistry().getTools()) {
      final var annotations =
          new McpSchema.ToolAnnotations(
              null, def.readOnly(), def.destructive(), def.readOnly(), false, null);
      final var tool =
          McpSchema.Tool.builder(def.name(), def.inputSchema())
              .description(def.description())
              .annotations(annotations)
              .build();
      specs.add(
          McpServerFeatures.SyncToolSpecification.builder()
              .tool(tool)
              .callHandler((exchange, request) -> invoke(def, request.arguments()))
              .build());
    }
    return specs;
  }

  /**
   * Executes one tool call and converts the outcome to an MCP result. Public so that tests (and
   * in-process callers) can invoke tools without a transport.
   */
  public static McpSchema.CallToolResult invoke(String toolName, Map<String, Object> arguments) {
    final var def = getRegistry().getTool(toolName);
    if (def == null) return error("Unknown tool: " + toolName);
    return invoke(def, arguments);
  }

  private static McpSchema.CallToolResult invoke(
      McpToolRegistry.ToolDef def, Map<String, Object> arguments) {
    final var ctx = McpContext.get();
    final var args = new Args(arguments);
    Object result;
    try {
      if (args.has("dialogAnswers")) {
        final var answers = args.list("dialogAnswers");
        ctx.onModelThread(() -> ctx.queueDialogAnswers(answers));
      }
      if (def.onModelThread()) {
        result = ctx.onModelThread(() -> def.handler().call(ctx, args));
      } else {
        result = def.handler().call(ctx, args);
      }
    } catch (McpToolException e) {
      return withDialogs(error(e.getMessage()), ctx);
    } catch (Throwable t) {
      logger.warn("MCP tool {} failed", def.name(), t);
      final var sw = new StringWriter();
      t.printStackTrace(new PrintWriter(sw));
      var trace = sw.toString();
      if (trace.length() > 4000) trace = trace.substring(0, 4000) + "...";
      return withDialogs(error("Internal error in tool '" + def.name() + "': " + t + "\n" + trace), ctx);
    }
    return withDialogs(toResult(result), ctx);
  }

  private static McpSchema.CallToolResult withDialogs(McpSchema.CallToolResult res, McpContext ctx) {
    final List<Map<String, Object>> dialogs = ctx.onModelThread(ctx::drainDialogs);
    if (dialogs.isEmpty()) return res;
    final var content = new ArrayList<McpSchema.Content>(res.content());
    content.add(McpSchema.TextContent.builder(json(Map.of("dialogs", dialogs))).build());
    return McpSchema.CallToolResult.builder()
        .content(content)
        .isError(res.isError())
        .structuredContent(res.structuredContent())
        .build();
  }

  private static McpSchema.CallToolResult error(String message) {
    return McpSchema.CallToolResult.builder().addTextContent(message).isError(true).build();
  }

  static String json(Object value) {
    try {
      return mapper().writeValueAsString(value);
    } catch (Exception e) {
      return String.valueOf(value);
    }
  }

  private static McpSchema.CallToolResult toResult(Object result) {
    if (result instanceof McpSchema.CallToolResult r) return r;
    if (result == null) result = Map.of("ok", true);
    if (result instanceof String s) {
      return McpSchema.CallToolResult.builder().addTextContent(s).build();
    }
    if (result instanceof ImageResult img) {
      final var b = McpSchema.CallToolResult.builder();
      b.addContent(McpSchema.ImageContent.builder(img.base64Data(), img.mimeType()).build());
      if (img.details() != null) b.addTextContent(json(img.details()));
      return b.build();
    }
    final var b = McpSchema.CallToolResult.builder().addTextContent(json(result));
    if (result instanceof Map<?, ?> m) {
      final var structured = new LinkedHashMap<String, Object>();
      m.forEach((k, v) -> structured.put(String.valueOf(k), v));
      b.structuredContent(structured);
    }
    return b.build();
  }

  // ---------------------------------------------------------------------------------------------
  // stdio transport
  // ---------------------------------------------------------------------------------------------

  /**
   * Starts the stdio server. stdout is reserved for the protocol from now on; System.out is
   * redirected to stderr. Returns a latch released when the client closes stdin.
   */
  public static synchronized CountDownLatch startStdio() {
    final var protocolOut = new FileOutputStream(FileDescriptor.out);
    final var protocolIn = new FileInputStream(FileDescriptor.in);
    System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true));
    final var closed = new CountDownLatch(1);
    // Release the latch when the client closes stdin (end of stream).
    final var watchedIn =
        new FilterInputStream(protocolIn) {
          @Override
          public int read() throws IOException {
            final var b = super.read();
            if (b < 0) closed.countDown();
            return b;
          }

          @Override
          public int read(byte[] buf, int off, int len) throws IOException {
            final var n = super.read(buf, off, len);
            if (n < 0) closed.countDown();
            return n;
          }
        };
    final var transport = new StdioServerTransportProvider(mapper(), watchedIn, protocolOut);
    final var server =
        McpServer.sync(transport)
            .serverInfo(serverInfo())
            .instructions(INSTRUCTIONS)
            .capabilities(McpSchema.ServerCapabilities.builder().tools(true).logging().build())
            .tools(toolSpecs())
            .build();
    servers.add(server);
    logger.info("Logisim MCP server listening on stdio ({} tools)", getRegistry().getTools().size());
    return closed;
  }

  // ---------------------------------------------------------------------------------------------
  // HTTP transport
  // ---------------------------------------------------------------------------------------------

  public static synchronized boolean isHttpRunning() {
    return jetty != null;
  }

  public static synchronized int getHttpPort() {
    return httpPort;
  }

  public static String getHttpUrl() {
    return "http://127.0.0.1:" + getHttpPort() + HTTP_ENDPOINT;
  }

  /** Starts the HTTP endpoint on 127.0.0.1:{@code port} (no-op if already running). */
  public static synchronized void startHttp(int port) throws Exception {
    if (jetty != null) return;
    final var transport =
        HttpServletStreamableServerTransportProvider.builder()
            .jsonMapper(mapper())
            .mcpEndpoint(HTTP_ENDPOINT)
            .build();
    final var server =
        McpServer.sync(transport)
            .serverInfo(serverInfo())
            .instructions(INSTRUCTIONS)
            .capabilities(McpSchema.ServerCapabilities.builder().tools(true).logging().build())
            .tools(toolSpecs())
            .build();

    final var newJetty = new Server();
    final var connector = new ServerConnector(newJetty);
    connector.setHost("127.0.0.1");
    connector.setPort(port);
    newJetty.addConnector(connector);
    final var context = new ServletContextHandler();
    context.setContextPath("/");
    final var holder = new ServletHolder("mcp", transport);
    holder.setAsyncSupported(true);
    context.addServlet(holder, HTTP_ENDPOINT);
    newJetty.setHandler(context);
    try {
      newJetty.start();
    } catch (Exception e) {
      server.closeGracefully();
      newJetty.stop();
      throw e;
    }
    jetty = newJetty;
    httpServer = server;
    httpPort = connector.getLocalPort();
    logger.info("Logisim MCP server listening on {}", getHttpUrl());
  }

  /** Stops the HTTP endpoint if it is running. */
  public static synchronized void stopHttp() {
    if (jetty == null) return;
    try {
      httpServer.closeGracefully();
    } catch (Exception e) {
      logger.debug("Error closing MCP HTTP sessions", e);
    }
    try {
      jetty.stop();
    } catch (Exception e) {
      logger.warn("Error stopping MCP HTTP server", e);
    }
    jetty = null;
    httpServer = null;
    httpPort = -1;
  }
}
