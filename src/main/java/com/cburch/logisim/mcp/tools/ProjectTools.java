/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp.tools;

import static com.cburch.logisim.mcp.McpToolRegistry.PROJECT_ARG;

import com.cburch.logisim.generated.BuildInfo;
import com.cburch.logisim.mcp.LogisimMcpServer;
import com.cburch.logisim.mcp.McpContext;
import com.cburch.logisim.mcp.McpToolException;
import com.cburch.logisim.mcp.McpToolGroup;
import com.cburch.logisim.mcp.McpToolRegistry;
import com.cburch.logisim.mcp.Schema;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.proj.ProjectActions;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Server information and project (file) management: new, open, save, close, switch. */
public final class ProjectTools implements McpToolGroup {

  /** JSON description of a project, shared with other tool groups. */
  public static Map<String, Object> describe(McpContext ctx, Project proj) {
    final var out = new LinkedHashMap<String, Object>();
    final var file = proj.getLogisimFile();
    out.put("id", ctx.projectId(proj));
    out.put("name", file.getName());
    final var main = file.getLoader().getMainFile();
    out.put("file", main == null ? null : main.getAbsolutePath());
    out.put("dirty", proj.isFileDirty());
    out.put("current", proj == ctx.getCurrentProject());
    out.put("hasWindow", proj.getFrame() != null);
    out.put("circuits", McpContext.circuitNames(proj));
    out.put("mainCircuit", file.getMainCircuit() == null ? null : file.getMainCircuit().getName());
    out.put(
        "currentCircuit", proj.getCurrentCircuit() == null ? null : proj.getCurrentCircuit().getName());
    return out;
  }

  @Override
  public void register(McpToolRegistry registry) {
    registry
        .tool("server_info")
        .description(
            "Describes this Logisim instance: version, whether a GUI is attached (edits are then"
                + " visible live), the HTTP endpoint, open projects and the number of tools.")
        .readOnly()
        .handler(
            (ctx, args) -> {
              final var out = new LinkedHashMap<String, Object>();
              out.put("application", BuildInfo.displayName);
              out.put("version", BuildInfo.version.toString());
              out.put("gui", ctx.isGui());
              out.put("httpEndpoint", LogisimMcpServer.isHttpRunning() ? LogisimMcpServer.getHttpUrl() : null);
              out.put("toolCount", LogisimMcpServer.getRegistry().getTools().size());
              final var projects = new ArrayList<Object>();
              for (final var p : ctx.getProjects()) projects.add(describe(ctx, p));
              out.put("projects", projects);
              return out;
            });

    registry
        .tool("project_list")
        .description("Lists the open projects with their ids, files, circuits and dirty state.")
        .readOnly()
        .handler(
            (ctx, args) -> {
              final var projects = new ArrayList<Object>();
              for (final var p : ctx.getProjects()) projects.add(describe(ctx, p));
              return Map.of("projects", projects);
            });

    registry
        .tool("project_new")
        .description(
            "Creates a new project from the user's template (opens a new window when a GUI is"
                + " attached) and makes it the current project.")
        .handler((ctx, args) -> describe(ctx, ctx.newProject()));

    registry
        .tool("project_open")
        .description(
            "Opens a .circ file as a project and makes it current. If the file is already open"
                + " the existing project is returned.")
        .input(Schema.object().prop("path", Schema.string("Path of the .circ file."), true))
        .handler((ctx, args) -> describe(ctx, ctx.openProject(new File(args.str("path")))));

    registry
        .tool("project_set_current")
        .description(
            "Makes a project the default target of subsequent tools (and brings its window to the"
                + " front when a GUI is attached).")
        .input(Schema.object().prop("project", PROJECT_ARG, true))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              ctx.setCurrentProject(proj);
              if (proj.getFrame() != null) proj.getFrame().toFront();
              return describe(ctx, proj);
            });

    registry
        .tool("project_save")
        .description(
            "Saves a project. Without 'path' it is saved to its current file; with 'path' it is"
                + " saved there (like Save As) and subsequently associated with that file.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("path", Schema.string("Target .circ file (Save As)."))
                .prop(
                    "overwrite",
                    Schema.bool("Allow replacing an existing different file at 'path'.").with("default", false)))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var current = proj.getLogisimFile().getLoader().getMainFile();
              File target;
              if (args.has("path")) {
                var path = args.str("path");
                if (!path.endsWith(".circ")) path = path + ".circ";
                target = new File(path).getAbsoluteFile();
                final var same = current != null && current.getAbsoluteFile().equals(target);
                if (target.exists() && !same && !args.bool("overwrite", false)) {
                  throw new McpToolException(
                      "File " + target + " exists. Pass overwrite=true to replace it.");
                }
              } else if (current != null) {
                target = current;
              } else {
                throw new McpToolException("The project has never been saved; pass a 'path'.");
              }
              if (!ProjectActions.doSave(proj, target)) {
                throw new McpToolException("Saving to " + target + " failed (see dialogs).");
              }
              return describe(ctx, proj);
            });

    registry
        .tool("project_close")
        .description(
            "Closes a project (and its window). Refuses to close a project with unsaved changes"
                + " unless discardChanges=true.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("discardChanges", Schema.bool("Close even if there are unsaved changes.")))
        .destructive()
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              if (proj.isFileDirty() && !args.bool("discardChanges", false)) {
                throw new McpToolException(
                    "Project has unsaved changes. Save it first or pass discardChanges=true.");
              }
              final var id = ctx.projectId(proj);
              ctx.closeProject(proj);
              return Map.of("closed", id);
            });
  }
}
