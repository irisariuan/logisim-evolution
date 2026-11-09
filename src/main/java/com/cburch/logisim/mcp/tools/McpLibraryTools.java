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

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.file.LoadFailedException;
import com.cburch.logisim.file.LoadedLibrary;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.LogisimFileActions;
import com.cburch.logisim.file.ProjectBundlePaths;
import com.cburch.logisim.generated.BuildInfo;
import com.cburch.logisim.mcp.Args;
import com.cburch.logisim.mcp.McpContext;
import com.cburch.logisim.mcp.McpToolException;
import com.cburch.logisim.mcp.McpToolGroup;
import com.cburch.logisim.mcp.McpToolRegistry;
import com.cburch.logisim.mcp.Schema;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.proj.ProjectBundleManifest;
import com.cburch.logisim.proj.ProjectBundleReadme;
import com.cburch.logisim.tools.AddTool;
import com.cburch.logisim.tools.Library;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Library management (Project ▸ Load Library / Unload Libraries, File ▸ Merge, project bundles).
 *
 * <p>All modifications go through {@link Project#doAction} so that they can be undone, exactly as
 * the menu items do. File choosers and selection dialogs of the GUI are replaced by arguments.
 */
public final class McpLibraryTools implements McpToolGroup {

  private static final Schema LIBRARY_ARG =
      Schema.string(
          "Name of a top-level library of the project as returned by library_list ('name' or"
              + " 'displayName', case-insensitive), e.g. 'Gates', 'Memory' or the name of a"
              + " loaded .circ library.");

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  /** Descriptor string of a library as stored in the .circ file, or null if not available. */
  static String descriptor(Loader loader, Library lib) {
    try {
      return loader.getDescriptor(lib);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Kind of a library: builtin, logisim (.circ), jar or unknown. */
  static String kind(Loader loader, Library lib, String desc) {
    if (loader.getBuiltin().getLibraries().contains(lib)) return "builtin";
    if (desc != null) {
      if (desc.startsWith("#")) return "builtin";
      if (desc.startsWith("file#")) return "logisim";
      if (desc.startsWith("jar#")) return "jar";
    }
    if (lib instanceof LoadedLibrary loaded) {
      return loaded.getBase() instanceof LogisimFile ? "logisim" : "jar";
    }
    if (lib instanceof LogisimFile) return "logisim";
    return "builtin";
  }

  /** Absolute file a file/jar descriptor refers to, without ever asking the user. */
  static String descriptorFile(Loader loader, String desc) {
    if (desc == null) return null;
    String path;
    if (desc.startsWith("file#")) {
      path = desc.substring(5);
    } else if (desc.startsWith("jar#")) {
      final var rest = desc.substring(4);
      final var sep = rest.lastIndexOf('#');
      path = sep < 0 ? rest : rest.substring(0, sep);
    } else {
      return null;
    }
    path = ProjectBundlePaths.normalizeLibraryDescriptorPath(path);
    var file = new File(path);
    if (!file.isAbsolute()) {
      final var dir = loader.getCurrentDirectory();
      if (dir != null) file = new File(dir, path);
    }
    return file.getAbsolutePath();
  }

  static Map<String, Object> describeTools(Library lib) {
    final var out = new LinkedHashMap<String, Object>();
    final var tools = new ArrayList<Object>();
    for (final var tool : lib.getTools()) {
      final var t = new LinkedHashMap<String, Object>();
      t.put("name", tool.getName());
      t.put("displayName", tool.getDisplayName());
      if (tool instanceof AddTool add && add.getFactory() != null) {
        t.put("componentFactory", true);
      }
      tools.add(t);
    }
    out.put("tools", tools);
    return out;
  }

  static Map<String, Object> describeLibrary(
      Loader loader, Library lib, boolean includeTools, boolean top) {
    final var out = new LinkedHashMap<String, Object>();
    final var desc = descriptor(loader, lib);
    out.put("name", lib.getName());
    out.put("displayName", lib.getDisplayName());
    out.put("kind", kind(loader, lib, desc));
    out.put("descriptor", desc);
    if (desc == null && lib instanceof LoadedLibrary) {
      out.put("note", "The descriptor (path relative to the project file) is known once the project"
          + " has been saved.");
    }
    final var file = descriptorFile(loader, desc);
    if (file != null) out.put("file", file);
    out.put("hidden", lib.isHidden());
    if (lib instanceof LoadedLibrary) {
      out.put("dirty", lib.isDirty());
      out.put("reloadable", top);
    }
    out.put("toolCount", lib.getTools().size());
    if (includeTools) out.putAll(describeTools(lib));
    if (!lib.getLibraries().isEmpty()) {
      final var nested = new ArrayList<Object>();
      for (final var sub : lib.getLibraries()) {
        nested.add(describeLibrary(loader, sub, false, false));
      }
      out.put("libraries", nested);
    }
    return out;
  }

  static Library findTopLibrary(LogisimFile file, String name) {
    for (final var lib : file.getLibraries()) {
      if (lib.getName().equalsIgnoreCase(name)) return lib;
    }
    for (final var lib : file.getLibraries()) {
      if (lib.getDisplayName().equalsIgnoreCase(name)) return lib;
    }
    final var names = new ArrayList<String>();
    for (final var lib : file.getLibraries()) names.add(lib.getName());
    throw new McpToolException(
        "The project has no library '" + name + "'. Loaded libraries: " + names);
  }

  /** Circuits (name → number of components) that use a component of the library. */
  static Map<String, Integer> usage(LogisimFile file, Library lib) {
    final var factories = new HashSet<ComponentFactory>();
    for (final var tool : lib.getTools()) {
      if (tool instanceof AddTool add) factories.add(add.getFactory());
    }
    final var out = new LinkedHashMap<String, Integer>();
    for (final var circ : file.getCircuits()) {
      var count = 0;
      for (final var comp : circ.getNonWires()) {
        if (factories.contains(comp.getFactory())) count++;
      }
      if (count > 0) out.put(circ.getName(), count);
    }
    return out;
  }

  private static File existingFile(Args args, String name) {
    final var file = new File(args.str(name)).getAbsoluteFile();
    if (!file.isFile()) throw new McpToolException("File not found: " + file);
    return file;
  }

  private static Map<String, Object> loadResult(McpContext ctx, Project proj, Library lib) {
    final var out = new LinkedHashMap<String, Object>();
    out.put("project", ctx.projectId(proj));
    out.put("loaded", describeLibrary(proj.getLogisimFile().getLoader(), lib, true, true));
    final var names = new ArrayList<String>();
    for (final var l : proj.getLogisimFile().getLibraries()) names.add(l.getName());
    out.put("libraries", names);
    return out;
  }

  /** Runs the (undoable) load-library action and checks that the library really got added. */
  private static Map<String, Object> doLoad(McpContext ctx, Project proj, Library lib) {
    final var file = proj.getLogisimFile();
    if (file.getLibraries().contains(lib)) {
      throw new McpToolException("Library '" + lib.getName() + "' is already loaded.");
    }
    proj.doAction(LogisimFileActions.loadLibrary(lib, file));
    if (!file.getLibraries().contains(lib)) {
      throw new McpToolException(
          "Library '" + lib.getName() + "' could not be loaded (name conflict or invalid library;"
              + " see 'dialogs' for Logisim's message).");
    }
    return loadResult(ctx, proj, lib);
  }

  private static List<String> circuitNames(LogisimFile file) {
    final var names = new ArrayList<String>();
    for (final var c : file.getCircuits()) names.add(c.getName());
    return names;
  }

  // ---------------------------------------------------------------------------------------------
  // Tools
  // ---------------------------------------------------------------------------------------------

  @Override
  public void register(McpToolRegistry registry) {
    registry
        .tool("library_list")
        .description(
            "Lists the libraries of a project as shown in the explorer tree: built-in libraries"
                + " (Wiring, Gates, Memory...), loaded Logisim (.circ) libraries and JAR libraries,"
                + " with their nested libraries. Each entry has name, displayName, kind"
                + " (builtin|logisim|jar), descriptor (as stored in the .circ file, e.g. '#Gates' or"
                + " 'file#lib/adder.circ'), file (for logisim/jar), hidden, toolCount and, if"
                + " includeTools=true, the tools/component factories (name, displayName). Also"
                + " lists 'availableBuiltin': built-in libraries not loaded in the project (load"
                + " them with library_load_builtin).")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop(
                    "library",
                    Schema.string("Only describe this top-level library (name or display name)."))
                .prop(
                    "includeTools",
                    Schema.bool("Include the tools of every library. Default: true when 'library'"
                        + " is given, false otherwise.")))
        .readOnly()
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var file = proj.getLogisimFile();
              final var loader = file.getLoader();
              final var out = new LinkedHashMap<String, Object>();
              out.put("project", ctx.projectId(proj));
              if (args.has("library")) {
                final var lib = findTopLibrary(file, args.str("library"));
                final var desc = describeLibrary(loader, lib, args.bool("includeTools", true), true);
                desc.put("usedIn", usage(file, lib));
                out.put("library", desc);
                return out;
              }
              final var includeTools = args.bool("includeTools", false);
              final var libs = new ArrayList<Object>();
              for (final var lib : file.getLibraries()) {
                libs.add(describeLibrary(loader, lib, includeTools, true));
              }
              out.put("libraries", libs);
              final var available = new ArrayList<Object>();
              for (final var lib : loader.getBuiltin().getLibraries()) {
                if (!file.getLibraries().contains(lib)) {
                  available.add(Map.of("name", lib.getName(), "displayName", lib.getDisplayName()));
                }
              }
              out.put("availableBuiltin", available);
              out.put("circuits", circuitNames(file));
              return out;
            });

    registry
        .tool("library_load_builtin")
        .description(
            "Adds built-in libraries that are not (or no longer) part of the project, like"
                + " Project ▸ Load Library ▸ Built-in Library. Undoable. See 'availableBuiltin' of"
                + " library_list for valid names.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop(
                    "names",
                    Schema.array(
                        "Built-in library names or display names, e.g. ['Memory', 'TTL'].",
                        Schema.string("Library name")),
                    true))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var file = proj.getLogisimFile();
              final var builtin = file.getLoader().getBuiltin();
              final var libs = new ArrayList<Library>();
              for (final var o : args.list("names")) {
                final var name = String.valueOf(o);
                Library found = null;
                for (final var lib : builtin.getLibraries()) {
                  if (lib.getName().equalsIgnoreCase(name) || lib.getDisplayName().equalsIgnoreCase(name)) {
                    found = lib;
                  }
                }
                if (found == null) throw new McpToolException("Unknown built-in library '" + name + "'.");
                if (file.getLibraries().contains(found)) {
                  throw new McpToolException("Library '" + found.getName() + "' is already loaded.");
                }
                if (!libs.contains(found)) libs.add(found);
              }
              if (libs.isEmpty()) throw new McpToolException("'names' is empty.");
              proj.doAction(LogisimFileActions.loadLibraries(libs.toArray(new Library[0]), file));
              final var loaded = new ArrayList<String>();
              for (final var lib : libs) {
                if (file.getLibraries().contains(lib)) loaded.add(lib.getName());
              }
              if (loaded.isEmpty()) {
                throw new McpToolException("No library was loaded (see 'dialogs').");
              }
              final var names = new ArrayList<String>();
              for (final var l : file.getLibraries()) names.add(l.getName());
              return Map.of("loaded", loaded, "libraries", names);
            });

    registry
        .tool("library_load_logisim")
        .description(
            "Loads another Logisim project (.circ) as a library, like Project ▸ Load Library ▸"
                + " Logisim Library: its circuits become component types usable in this project"
                + " (place them with the library's name as library). The file is referenced (by a"
                + " path relative to the project file once saved), not copied — to copy circuits"
                + " use library_merge. Undoable. Fails if a library or circuit with a conflicting"
                + " name exists. Returns the loaded library with its tools.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("path", Schema.string("Path of the .circ file to load."), true))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var f = existingFile(args, "path");
              final var main = proj.getLogisimFile().getLoader().getMainFile();
              if (main != null && main.getAbsoluteFile().equals(f)) {
                throw new McpToolException("A project cannot load itself as a library.");
              }
              final var lib = proj.getLogisimFile().getLoader().loadLogisimLibrary(f);
              if (lib == null) {
                throw new McpToolException("Could not load " + f + " (see 'dialogs').");
              }
              return doLoad(ctx, proj, lib);
            });

    registry
        .tool("library_load_jar")
        .description(
            "Loads a JAR library (a Java class extending com.cburch.logisim.tools.Library), like"
                + " Project ▸ Load Library ▸ JAR Library. The class is read from the JAR manifest"
                + " attribute 'Library-Class' unless 'className' is given. Undoable. Only load JARs"
                + " you trust: their code runs inside Logisim.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("path", Schema.string("Path of the .jar file."), true)
                .prop("className", Schema.string("Fully qualified library class name.")))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var f = existingFile(args, "path");
              var className = args.str("className", null);
              if (className == null) {
                try (final var jar = new JarFile(f)) {
                  final var manifest = jar.getManifest();
                  if (manifest != null) {
                    className = manifest.getMainAttributes().getValue("Library-Class");
                  }
                } catch (IOException e) {
                  throw new McpToolException("Cannot read JAR " + f + ": " + e.getMessage());
                }
              }
              if (className == null || className.isBlank()) {
                throw new McpToolException(
                    "The JAR manifest has no 'Library-Class' attribute; pass 'className'.");
              }
              final var lib = proj.getLogisimFile().getLoader().loadJarLibrary(f, className.trim());
              if (lib == null) {
                throw new McpToolException("Could not load " + className + " from " + f + " (see 'dialogs').");
              }
              return doLoad(ctx, proj, lib);
            });

    registry
        .tool("library_unload")
        .description(
            "Removes a top-level library from the project, like Project ▸ Unload Libraries. As in"
                + " the GUI, a library cannot be unloaded while it is used: by components in any"
                + " circuit, by the toolbar or by a mouse mapping; the error then names the circuits"
                + " (with component counts) that use it. Undoable.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("library", LIBRARY_ARG, true))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var file = proj.getLogisimFile();
              final var lib = findTopLibrary(file, args.str("library"));
              final var message = file.getUnloadLibraryMessage(lib);
              if (message != null) {
                final var used = usage(file, lib);
                var text = "Library '" + lib.getName() + "' cannot be unloaded: " + message;
                if (!used.isEmpty()) text += " Used in circuits (component count): " + used + ".";
                throw new McpToolException(text);
              }
              proj.doAction(LogisimFileActions.unloadLibrary(lib));
              final var names = new ArrayList<String>();
              for (final var l : file.getLibraries()) names.add(l.getName());
              return Map.of("unloaded", lib.getName(), "libraries", names);
            });

    registry
        .tool("library_reload")
        .description(
            "Re-reads a loaded Logisim (.circ) or JAR library from disk (explorer popup ▸ Reload"
                + " Library), e.g. after the library file was edited. Components using it are"
                + " updated. Like in the GUI this is not an undoable action. Built-in libraries"
                + " cannot be reloaded.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("library", LIBRARY_ARG, true))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var file = proj.getLogisimFile();
              final var lib = findTopLibrary(file, args.str("library"));
              if (!(lib instanceof LoadedLibrary loaded)) {
                throw new McpToolException(
                    "Library '" + lib.getName() + "' is built in and cannot be reloaded.");
              }
              file.getLoader().reload(loaded);
              return Map.of(
                  "reloaded", describeLibrary(file.getLoader(), lib, true, true));
            });

    registry
        .tool("library_merge")
        .description(
            "Copies circuits from another .circ file into the project (File ▸ Merge). By default"
                + " all circuits are merged; 'circuits' selects some (with their subcircuit"
                + " dependencies unless includeDependencies=false). Libraries the source needs are"
                + " loaded too. When a merged circuit has the same name as an existing one,"
                + " 'onConflict' decides: 'rename' (default; adds a suffix like _1), 'replace' or"
                + " 'skip'. Undoable. Returns the circuits that were added.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("path", Schema.string("Path of the .circ file to merge from."), true)
                .prop(
                    "circuits",
                    Schema.array("Names of the circuits to merge. Default: all.", Schema.string("Circuit name")))
                .prop(
                    "includeDependencies",
                    Schema.bool("Also merge subcircuits used by the selected circuits. Default true."))
                .prop(
                    "onConflict",
                    Schema.enumeration("What to do with name conflicts. Default 'rename'.",
                        "rename", "replace", "skip")))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var f = existingFile(args, "path");
              final LogisimFile mergelib;
              try {
                mergelib = new Loader(proj.getFrame()).openLogisimFile(f);
              } catch (LoadFailedException e) {
                throw new McpToolException("Could not read " + f + ": " + e.getMessage());
              }
              if (mergelib == null) throw new McpToolException("Could not read " + f + ".");
              List<Circuit> selected = null;
              if (args.has("circuits")) {
                selected = new ArrayList<>();
                for (final var o : args.list("circuits")) {
                  final var c = mergelib.getCircuit(String.valueOf(o));
                  if (c == null) {
                    throw new McpToolException("No circuit '" + o + "' in " + f + ". Available: "
                        + circuitNames(mergelib));
                  }
                  selected.add(c);
                }
                if (selected.isEmpty()) throw new McpToolException("'circuits' is empty.");
              }
              final var answer =
                  switch (args.str("onConflict", "rename").toLowerCase(Locale.ROOT)) {
                    case "replace" -> 0;
                    case "rename" -> 1;
                    case "skip" -> 2;
                    default -> throw new McpToolException("onConflict must be rename, replace or skip.");
                  };
              final var answers = new ArrayList<Object>();
              for (var i = 0; i < mergelib.getCircuits().size(); i++) answers.add(answer);
              ctx.queueDialogAnswers(answers);
              final var file = proj.getLogisimFile();
              final var before = new LinkedHashSet<>(file.getCircuits());
              proj.doAction(
                  LogisimFileActions.mergeFile(
                      mergelib, file, selected, args.bool("includeDependencies", true)));
              final var added = new ArrayList<String>();
              for (final var c : file.getCircuits()) if (!before.contains(c)) added.add(c.getName());
              final var out = new LinkedHashMap<String, Object>();
              out.put("added", added);
              if (answer == 0) {
                // Replaced circuits keep their identity; report the candidates that clashed.
                final var candidates = new LinkedHashSet<Circuit>();
                if (selected == null) {
                  candidates.addAll(mergelib.getCircuits());
                } else {
                  for (final var c : selected) {
                    candidates.add(c);
                    if (args.bool("includeDependencies", true)) {
                      candidates.addAll(LogisimFileActions.getCircuitDependencies(c, mergelib));
                    }
                  }
                }
                final var replaced = new ArrayList<String>();
                for (final var c : candidates) {
                  for (final var old : before) {
                    if (old.getName().equalsIgnoreCase(c.getName())) replaced.add(old.getName());
                  }
                }
                out.put("replaced", replaced);
              }
              out.put("circuits", circuitNames(file));
              final var libs = new ArrayList<String>();
              for (final var l : file.getLibraries()) libs.add(l.getName());
              out.put("libraries", libs);
              return out;
            });

    registry
        .tool("library_export_bundle")
        .description(
            "Exports the project with all the .circ/.jar libraries it references into one project"
                + " bundle zip file (File ▸ Export Project Bundle, extension .lsebdl). An optional"
                + " README is generated from author/keywords/description. Does not modify the"
                + " project.")
        .input(
            Schema.object()
                .prop("project", PROJECT_ARG)
                .prop("path", Schema.string("Target bundle file; '.lsebdl' is appended if missing."), true)
                .prop("overwrite", Schema.bool("Replace an existing file. Default false."))
                .prop("projectName", Schema.string("Project name for the README. Default: file name."))
                .prop("author", Schema.string("Comma separated author names for the README."))
                .prop("keywords", Schema.string("Comma separated keywords for the README."))
                .prop("description", Schema.string("Project description (Markdown) for the README.")))
        .handler(
            (ctx, args) -> {
              final var proj = ctx.project(args);
              final var loader = proj.getLogisimFile().getLoader();
              var path = args.str("path");
              if (!path.endsWith(Loader.LOGISIM_PROJECT_BUNDLE_EXTENSION)) {
                path += Loader.LOGISIM_PROJECT_BUNDLE_EXTENSION;
              }
              final var target = new File(path).getAbsoluteFile();
              if (target.exists() && !args.bool("overwrite", false)) {
                throw new McpToolException("File " + target + " exists. Pass overwrite=true to replace it.");
              }
              final var mainFileName =
                  target.getName()
                      .substring(0, target.getName().length() - Loader.LOGISIM_PROJECT_BUNDLE_EXTENSION.length())
                      + Loader.LOGISIM_EXTENSION;
              final var projName = args.str("projectName", mainFileName.replace(Loader.LOGISIM_EXTENSION, ""));
              final var readme =
                  new ProjectBundleReadme.ReadmeInfo(
                      projName, args.str("author", ""), args.str("keywords", ""), args.str("description", ""));
              final var oldTool = proj.getTool();
              proj.setTool(null);
              try (final var out = new FileOutputStream(target);
                  final var zip = new ZipOutputStream(out)) {
                ProjectBundleReadme.writeReadmeFile(zip, readme);
                zip.putNextEntry(new ZipEntry(ProjectBundlePaths.libraryDirectoryEntry()));
                loader.export(proj.getLogisimFile(), zip, mainFileName);
                ProjectBundleManifest.writeManifest(
                    zip, ProjectBundleManifest.getInfoContainer(BuildInfo.displayName, mainFileName));
              } catch (IOException e) {
                throw new McpToolException("Cannot write " + target + ": " + e.getMessage());
              } finally {
                proj.setTool(oldTool);
              }
              final var entries = new ArrayList<String>();
              try (final var zip = new ZipFile(target)) {
                zip.stream().forEach(e -> entries.add(e.getName()));
              }
              return Map.of("bundle", target.getAbsolutePath(), "mainFile", mainFileName, "entries", entries);
            });

    registry
        .tool("library_import_bundle")
        .description(
            "Extracts a project bundle (.lsebdl, see library_export_bundle) into a directory and"
                + " opens its main .circ file as a new project (File ▸ Import Project Bundle). The"
                + " directory must not already contain the main file or a 'library' folder. Returns"
                + " the extracted files, the bundle README (if any) and the opened project.")
        .input(
            Schema.object()
                .prop("bundle", Schema.string("Path of the .lsebdl bundle file."), true)
                .prop("directory", Schema.string("Directory to extract into (created if missing)."), true)
                .prop("open", Schema.bool("Open the extracted main file as a project. Default true.")))
        .handler(
            (ctx, args) -> {
              final var bundle = existingFile(args, "bundle");
              final var dir = Path.of(args.str("directory")).toAbsolutePath().normalize();
              final var out = new LinkedHashMap<String, Object>();
              final File mainFile;
              try (final var zip = new ZipFile(bundle)) {
                final var info = ProjectBundleManifest.getManifestInfo(zip, null);
                if (info == null) {
                  throw new McpToolException("Not a valid Logisim project bundle (see 'dialogs').");
                }
                final var mainEntry = zip.getEntry(info.getMainLogisimFilename());
                final var mainPath = ProjectBundlePaths.resolveMainFile(dir, info.getMainLogisimFilename());
                if (mainEntry == null || mainPath == null) {
                  throw new McpToolException("The bundle's main file is missing or invalid.");
                }
                final var libDir = dir.resolve(Loader.LOGISIM_LIBRARY_DIR);
                if (Files.exists(mainPath) || Files.exists(libDir)) {
                  throw new McpToolException(
                      "Directory " + dir + " already contains " + mainPath.getFileName() + " or a '"
                          + Loader.LOGISIM_LIBRARY_DIR + "' folder; choose another directory.");
                }
                Files.createDirectories(dir);
                final var extracted = new ArrayList<String>();
                try (final var in = zip.getInputStream(mainEntry)) {
                  Files.copy(in, mainPath);
                }
                extracted.add(mainPath.toString());
                final var seen = new HashSet<Path>();
                final var entries = zip.entries();
                while (entries.hasMoreElements()) {
                  final var entry = entries.nextElement();
                  if (ProjectBundlePaths.isLibraryDirectory(entry.getName())) {
                    Files.createDirectories(libDir);
                    continue;
                  }
                  final var libFile = ProjectBundlePaths.resolveLibraryFile(dir, entry.getName());
                  if (libFile == null || !seen.add(libFile)) continue;
                  final var lower = libFile.toString().toLowerCase(Locale.ROOT);
                  if (!lower.endsWith(Loader.LOGISIM_EXTENSION) && !lower.endsWith(".jar")) continue;
                  Files.createDirectories(libDir);
                  try (final var in = zip.getInputStream(entry)) {
                    Files.copy(in, libFile);
                  }
                  extracted.add(libFile.toString());
                }
                out.put("extracted", extracted);
                final var readme = zip.getEntry(ProjectBundleReadme.README_FILE_NAME);
                if (readme != null) {
                  try (final var in = zip.getInputStream(readme)) {
                    out.put("readme", new String(in.readAllBytes(), StandardCharsets.UTF_8));
                  }
                }
                mainFile = mainPath.toFile();
              } catch (IOException e) {
                throw new McpToolException("Cannot import bundle " + bundle + ": " + e.getMessage());
              }
              out.put("mainFile", mainFile.getAbsolutePath());
              if (args.bool("open", true)) {
                out.put("project", ProjectTools.describe(ctx, ctx.openProject(mainFile)));
              }
              return out;
            });
  }
}
