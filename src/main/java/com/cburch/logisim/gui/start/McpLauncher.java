/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.start;

import com.cburch.logisim.file.LoadFailedException;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.mcp.LogisimMcpServer;
import com.cburch.logisim.mcp.McpContext;
import com.cburch.logisim.proj.Project;
import java.io.File;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Starts the MCP server from the command line ({@code --mcp}, {@code --mcp-gui}, {@code --mcp-http}). */
final class McpLauncher {
  static final Logger logger = LoggerFactory.getLogger(McpLauncher.class);

  private McpLauncher() {}

  /**
   * Headless mode: opens the given files as window-less projects, serves MCP on stdio (and HTTP
   * when {@code httpPort >= 0}) and returns the exit code once the client disconnects.
   */
  static int runHeadless(List<File> files, Map<File, File> substitutions, int httpPort) {
    final var ctx = McpContext.get();
    for (final var file : files) {
      try {
        ctx.onModelThread(
            () -> {
              final var loader = new Loader(null);
              final var lib = loader.openLogisimFile(file, substitutions);
              final var proj = new Project(lib);
              for (final var circ : lib.getCircuits()) circ.setProject(proj);
              ctx.addHeadlessProject(proj);
              return proj;
            });
      } catch (RuntimeException e) {
        final var cause = e.getCause() instanceof LoadFailedException lfe ? lfe : e;
        logger.error("{} : {}", file.getName(), cause.getMessage());
      }
    }
    try {
      if (httpPort >= 0) LogisimMcpServer.startHttp(httpPort);
      final var closed = LogisimMcpServer.startStdio();
      closed.await();
      return 0;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return 0;
    } catch (Exception e) {
      logger.error("MCP server failed", e);
      return 1;
    }
  }

  /** GUI mode: start the requested transports next to the normal windows. */
  static void startWithGui(boolean stdio, int httpPort) {
    try {
      if (httpPort >= 0) {
        LogisimMcpServer.startHttp(httpPort);
        System.err.println("Logisim MCP server: " + LogisimMcpServer.getHttpUrl());
      }
      if (stdio) {
        final var closed = LogisimMcpServer.startStdio();
        final var watcher =
            new Thread(
                () -> {
                  try {
                    closed.await();
                  } catch (InterruptedException ignored) {
                    // exit below
                  }
                  logger.info("MCP stdio client disconnected");
                },
                "logisim-mcp-stdio-watch");
        watcher.setDaemon(true);
        watcher.start();
      }
    } catch (Exception e) {
      logger.error("Could not start the MCP server", e);
    }
  }
}
