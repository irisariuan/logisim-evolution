/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import com.cburch.logisim.Main;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.file.LoadFailedException;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.gui.generic.OptionPane;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.proj.ProjectActions;
import com.cburch.logisim.proj.Projects;
import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.SwingUtilities;

/**
 * Shared state and helpers for all MCP tool handlers.
 *
 * <p>There is exactly one context per Logisim process ({@link #get()}). It knows whether the
 * process runs with a GUI, which projects are open, which project is "current" for the MCP
 * client, and it hands out stable identifiers for projects and components.
 *
 * <p><b>Threading.</b> Logisim's model (circuits, projects, Swing frames) is not thread-safe and
 * is normally driven from the Swing event thread. Every tool handler is therefore executed via
 * {@link #onModelThread} by the server: on the EDT when a GUI is present, on a dedicated single
 * "logisim-mcp-model" thread otherwise. Handlers registered with {@code modelThread=false} run on
 * the transport thread instead and must call {@link #onModelThread} themselves for model access
 * (useful for tools that wait, e.g. for the simulator to settle).
 *
 * <p><b>Dialogs.</b> While a handler runs on the model thread, every dialog raised through {@link
 * OptionPane} is intercepted: it is recorded (and returned to the client in the tool result as
 * {@code "dialogs"}) and answered with the next value queued via {@link #queueDialogAnswers}, or
 * with "cancel"/"closed"/null if nothing is queued. Code paths that open other modal windows
 * (JFileChooser, custom JDialogs) must not be used by tools.
 */
public final class McpContext {

  private static McpContext instance;

  private final boolean gui;
  private final List<Project> headlessProjects = new ArrayList<>();
  private Project currentProject;
  private final ExecutorService headlessExecutor;

  private final Map<Project, String> projectIds = new WeakHashMap<>();
  private final AtomicLong nextProjectId = new AtomicLong(1);

  private final Map<Component, String> componentIds = new WeakHashMap<>();
  private final Map<String, WeakReference<Component>> componentsById = new HashMap<>();
  private final AtomicLong nextComponentId = new AtomicLong(1);

  // Dialog interception state; only touched on the model thread.
  private final Deque<Object> queuedDialogAnswers = new ArrayDeque<>();
  private List<Map<String, Object>> recordedDialogs = new ArrayList<>();

  private McpContext(boolean gui) {
    this.gui = gui;
    this.headlessExecutor =
        gui
            ? null
            : Executors.newSingleThreadExecutor(
                r -> {
                  final var t = new Thread(r, "logisim-mcp-model");
                  t.setDaemon(true);
                  return t;
                });
  }

  /** Returns the process-wide context, creating it on first use. */
  public static synchronized McpContext get() {
    if (instance == null) instance = new McpContext(Main.hasGui());
    return instance;
  }

  /** True when Logisim runs with its normal windows (projects have frames). */
  public boolean isGui() {
    return gui;
  }

  // ---------------------------------------------------------------------------------------------
  // Threading
  // ---------------------------------------------------------------------------------------------

  /** True if the calling thread is the thread that owns the Logisim model. */
  public boolean isModelThread() {
    if (gui) return SwingUtilities.isEventDispatchThread();
    return Thread.currentThread().getName().equals("logisim-mcp-model");
  }

  /**
   * Runs {@code task} on the model thread (EDT with GUI, dedicated thread headless) and waits for
   * its result. Re-entrant: if already on the model thread the task runs directly. Exceptions are
   * unwrapped and re-thrown.
   */
  public <T> T onModelThread(Callable<T> task) {
    if (isModelThread()) {
      try {
        return callIntercepted(task);
      } catch (Exception e) {
        throw rethrow(e);
      }
    }
    try {
      if (gui) {
        final var result = new Object[1];
        final var error = new Throwable[1];
        SwingUtilities.invokeAndWait(
            () -> {
              try {
                result[0] = callIntercepted(task);
              } catch (Throwable t) {
                error[0] = t;
              }
            });
        if (error[0] != null) throw rethrow(error[0]);
        @SuppressWarnings("unchecked")
        final var r = (T) result[0];
        return r;
      }
      return headlessExecutor.submit(() -> callIntercepted(task)).get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new McpToolException("Interrupted while waiting for the Logisim model thread.", e);
    } catch (ExecutionException | InvocationTargetException e) {
      throw rethrow(e.getCause());
    }
  }

  /** Convenience overload of {@link #onModelThread(Callable)} for code without a result. */
  public void onModelThread(Runnable task) {
    onModelThread(
        () -> {
          task.run();
          return null;
        });
  }

  private <T> T callIntercepted(Callable<T> task) throws Exception {
    final var previous = OptionPane.getInterceptor();
    OptionPane.setInterceptor(this::interceptDialog);
    try {
      return task.call();
    } finally {
      OptionPane.setInterceptor(previous);
    }
  }

  private static RuntimeException rethrow(Throwable t) {
    if (t instanceof RuntimeException re) return re;
    if (t instanceof Error err) throw err;
    return new McpToolException(String.valueOf(t.getMessage()), t);
  }

  // ---------------------------------------------------------------------------------------------
  // Dialog interception
  // ---------------------------------------------------------------------------------------------

  /**
   * Queues answers for dialogs raised by the next model-thread operations, in order. Use an
   * Integer option index / *_OPTION constant for confirm and option dialogs and a String for input
   * dialogs. Unused answers are discarded at the end of the tool call.
   */
  public void queueDialogAnswers(List<?> answers) {
    queuedDialogAnswers.addAll(answers);
  }

  private Object interceptDialog(
      String kind, String title, Object message, Object[] options, Object initialValue) {
    final var rec = new LinkedHashMap<String, Object>();
    rec.put("kind", kind);
    if (title != null) rec.put("title", title);
    rec.put("message", message instanceof String ? message : String.valueOf(message));
    if (options != null) {
      final var opts = new ArrayList<String>();
      for (final var o : options) opts.add(String.valueOf(o));
      rec.put("options", opts);
    }
    Object answer = null;
    if (!queuedDialogAnswers.isEmpty() && !"message".equals(kind)) {
      answer = queuedDialogAnswers.poll();
      // Allow answering option dialogs by label.
      if (answer instanceof String s && options != null && !"input".equals(kind)) {
        for (var i = 0; i < options.length; i++) {
          if (s.equalsIgnoreCase(String.valueOf(options[i]))) answer = i;
        }
      }
      if (answer instanceof Number n && !"input".equals(kind)) answer = n.intValue();
      rec.put("answer", answer);
    } else if (!"message".equals(kind)) {
      rec.put("answer", "input".equals(kind) ? null : "cancel");
    }
    recordedDialogs.add(rec);
    return answer;
  }

  /** Returns and clears the dialogs recorded since the last call. Called by the server. */
  List<Map<String, Object>> drainDialogs() {
    final var out = recordedDialogs;
    recordedDialogs = new ArrayList<>();
    queuedDialogAnswers.clear();
    return out;
  }

  // ---------------------------------------------------------------------------------------------
  // Projects
  // ---------------------------------------------------------------------------------------------

  /** All projects the MCP client can work with, in a stable order. */
  public List<Project> getProjects() {
    if (gui) return new ArrayList<>(Projects.getOpenProjects());
    return Collections.unmodifiableList(new ArrayList<>(headlessProjects));
  }

  /** Stable identifier ("p1", "p2", ...) of a project for this server session. */
  public synchronized String projectId(Project proj) {
    return projectIds.computeIfAbsent(proj, p -> "p" + nextProjectId.getAndIncrement());
  }

  /** The project tools operate on when no "project" argument is given. */
  public Project getCurrentProject() {
    final var all = getProjects();
    if (currentProject != null && all.contains(currentProject)) return currentProject;
    // A freshly created window registers itself with Projects asynchronously.
    if (currentProject != null && gui && currentProject.getFrame() != null
        && currentProject.getFrame().isDisplayable()) {
      return currentProject;
    }
    if (gui) {
      final var top = Projects.getTopFrame();
      if (top != null && top.getProject() != null) return top.getProject();
    }
    return all.isEmpty() ? null : all.get(all.size() - 1);
  }

  public void setCurrentProject(Project proj) {
    currentProject = proj;
  }

  /**
   * Resolves the "project" argument: a project id ("p1"), the file name / path of the project, or
   * its display name. Without the argument the current project is returned.
   */
  public Project project(Args args) {
    if (!args.has("project")) {
      final var cur = getCurrentProject();
      if (cur == null) {
        throw new McpToolException(
            "No project is open. Use 'project_new' or 'project_open' first.");
      }
      return cur;
    }
    return findProject(args.str("project"));
  }

  public Project findProject(String key) {
    final var all = getProjects();
    for (final var p : all) if (projectId(p).equals(key)) return p;
    for (final var p : all) {
      final var f = p.getLogisimFile().getLoader().getMainFile();
      if (f != null && (f.getPath().equals(key) || f.getAbsolutePath().equals(new File(key).getAbsolutePath()))) {
        return p;
      }
    }
    for (final var p : all) if (p.getLogisimFile().getName().equals(key)) return p;
    for (final var p : all) {
      final var f = p.getLogisimFile().getLoader().getMainFile();
      if (f != null && f.getName().equals(key)) return p;
    }
    throw new McpToolException(
        "Unknown project '" + key + "'. Use 'project_list' to see open projects.");
  }

  /**
   * Resolves the "circuit" argument (circuit name) within the project; defaults to the project's
   * current circuit, then its main circuit.
   */
  public Circuit circuit(Project proj, Args args) {
    if (args.has("circuit")) {
      final var name = args.str("circuit");
      final var circ = proj.getLogisimFile().getCircuit(name);
      if (circ == null) {
        throw new McpToolException(
            "Unknown circuit '" + name + "'. Available: " + circuitNames(proj));
      }
      return circ;
    }
    var circ = proj.getCurrentCircuit();
    if (circ == null) circ = proj.getLogisimFile().getMainCircuit();
    if (circ == null) throw new McpToolException("The project has no circuits.");
    return circ;
  }

  public static List<String> circuitNames(Project proj) {
    final var names = new ArrayList<String>();
    for (final var c : proj.getLogisimFile().getCircuits()) names.add(c.getName());
    return names;
  }

  /** Creates a new project from the user's template (with a window when a GUI is present). */
  public Project newProject() {
    return onModelThread(
        () -> {
          final Project proj;
          if (gui) {
            proj = ProjectActions.doNew(getCurrentProject());
          } else {
            final var file = ProjectActions.createNewFile(null);
            proj = new Project(file);
            for (final var circ : file.getCircuits()) circ.setProject(proj);
            headlessProjects.add(proj);
            proj.getSimulator().nudge();
          }
          currentProject = proj;
          return proj;
        });
  }

  /** Opens a .circ file as a project (re-using it when already open). */
  public Project openProject(File file) {
    if (!file.exists()) throw new McpToolException("File not found: " + file.getAbsolutePath());
    return onModelThread(
        () -> {
          for (final var p : getProjects()) {
            final var f = p.getLogisimFile().getLoader().getMainFile();
            if (f != null && f.getAbsoluteFile().equals(file.getAbsoluteFile())) {
              currentProject = p;
              return p;
            }
          }
          final Project proj;
          if (gui) {
            proj = ProjectActions.doOpen(null, null, file);
            if (proj == null) throw new McpToolException("Could not open " + file + " (see dialogs).");
          } else {
            try {
              proj = ProjectActions.doOpenNoWindow(null, file);
            } catch (LoadFailedException e) {
              throw new McpToolException("Could not open " + file + ": " + e.getMessage(), e);
            }
            headlessProjects.add(proj);
            // Without a window nothing kicks off the first propagation.
            proj.getSimulator().nudge();
          }
          currentProject = proj;
          return proj;
        });
  }

  /** Registers a project created elsewhere (headless mode only; GUI projects register themselves). */
  public void addHeadlessProject(Project proj) {
    if (!gui && !headlessProjects.contains(proj)) {
      headlessProjects.add(proj);
      proj.getSimulator().nudge();
    }
    if (currentProject == null) currentProject = proj;
  }

  /**
   * Closes a project without asking to save. In GUI mode the window is disposed; headless the
   * project's simulator is shut down.
   */
  public void closeProject(Project proj) {
    onModelThread(
        () -> {
          if (gui) {
            final var frame = proj.getFrame();
            if (frame != null) {
              proj.setFileAsClean();
              // dispose() fires WINDOW_CLOSED, which unregisters the project from Projects.
              frame.dispose();
            }
          } else {
            headlessProjects.remove(proj);
            proj.getSimulator().shutDown();
          }
          if (currentProject == proj) currentProject = null;
        });
  }

  /** A loader usable for operations that need one but have no project (e.g. reading files). */
  public static Loader newLoader() {
    return new Loader(null);
  }

  // ---------------------------------------------------------------------------------------------
  // Component identifiers
  // ---------------------------------------------------------------------------------------------

  /**
   * Stable id ("c17") for a component (including wires) for as long as the component object
   * lives. Note that Logisim replaces component objects on some edits (e.g. moving), so tools that
   * modify components should report the ids of the resulting components.
   */
  public synchronized String componentId(Component comp) {
    var id = componentIds.get(comp);
    if (id == null) {
      id = "c" + nextComponentId.getAndIncrement();
      componentIds.put(comp, id);
      componentsById.put(id, new WeakReference<>(comp));
    }
    return id;
  }

  /** Looks up a component id previously handed out by {@link #componentId}; null if unknown. */
  public synchronized Component componentById(String id) {
    final var ref = componentsById.get(id);
    final var comp = ref == null ? null : ref.get();
    if (ref != null && comp == null) componentsById.remove(id);
    return comp;
  }

  /**
   * Looks up a component id that must belong to {@code circ} (currently placed in it).
   *
   * @throws McpToolException if the id is unknown or not in the circuit.
   */
  public Component component(Circuit circ, String id) {
    final var comp = componentById(id);
    if (comp == null || !(circ.contains(comp))) {
      throw new McpToolException(
          "Unknown component id '" + id + "' in circuit '" + circ.getName()
              + "'. Ids change when components are moved/replaced; list components again.");
    }
    return comp;
  }
}
