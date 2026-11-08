/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.fpga.gui;

import com.cburch.logisim.fpga.designrulecheck.SimpleDrcContainer;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.JProgressBar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Reporter {

  public static final Reporter report = new Reporter();
  private static final Logger logger = LoggerFactory.getLogger(Reporter.class);
  private FpgaReportTabbedPane myCommander = null;
  private JProgressBar progress = null;

  /** Receives a copy of every report (used by tools that need the messages as data). */
  public interface Listener {
    /**
     * Called for each report.
     *
     * @param kind one of "error", "fatal", "severe", "warning", "severeWarning", "info", "console".
     * @param message a String or a {@link SimpleDrcContainer}.
     */
    void reported(String kind, Object message);
  }

  private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

  public void addListener(Listener l) {
    listeners.addIfAbsent(l);
  }

  public void removeListener(Listener l) {
    listeners.remove(l);
  }

  private void fire(String kind, Object message) {
    for (final var l : listeners) l.reported(kind, message);
  }

  public JProgressBar getProgressBar() {
    return progress;
  }

  public void setGuiLogger(FpgaReportTabbedPane gui) {
    myCommander = gui;
  }

  public void setProgressBar(JProgressBar progressBar) {
    progress = progressBar;
  }

  public void addErrorIncrement(String message) {
    fire("error", message);
    if (myCommander == null)
      logger.error(message);
    else
      myCommander.addErrors(new SimpleDrcContainer(message, SimpleDrcContainer.LEVEL_NORMAL, true));
  }

  public void addError(Object message) {
    fire("error", message);
    if (myCommander == null) {
      if (message instanceof String msg) logger.error(msg);
    } else {
      myCommander.addErrors((message instanceof String)
          ? new SimpleDrcContainer(message, SimpleDrcContainer.LEVEL_NORMAL)
          : message);
    }
  }

  public void addFatalErrorFmt(String fmt, Object... args) {
    addFatalError(String.format(fmt, args));
  }

  public void addFatalError(String message) {
    fire("fatal", message);
    if (myCommander == null)
      logger.error(message);
    else
      myCommander.addErrors(new SimpleDrcContainer(message, SimpleDrcContainer.LEVEL_FATAL));
  }

  public void addSevereError(String message) {
    fire("severe", message);
    if (myCommander == null)
      logger.error(message);
    else
      myCommander.addErrors(new SimpleDrcContainer(message, SimpleDrcContainer.LEVEL_SEVERE));
  }

  public void addInfo(String message) {
    fire("info", message);
    if (myCommander == null)
      logger.info(message);
    else
      myCommander.addInfo(message);
  }

  public void addSevereWarning(String message) {
    fire("severeWarning", message);
    if (myCommander == null)
      logger.warn(message);
    else
      myCommander.addWarning(new SimpleDrcContainer(message, SimpleDrcContainer.LEVEL_SEVERE));
  }

  public void addWarningIncrement(String message) {
    fire("warning", message);
    if (myCommander == null)
      logger.warn(message);
    else
      myCommander.addWarning(new SimpleDrcContainer(message, SimpleDrcContainer.LEVEL_NORMAL, true));
  }

  public void addWarning(Object message) {
    fire("warning", message);
    if (myCommander == null) {
      if (message instanceof String msg) logger.warn(msg);
    } else {
      myCommander.addWarning(message instanceof String
          ? new SimpleDrcContainer(message, SimpleDrcContainer.LEVEL_NORMAL)
          : message);
    }
  }

  public void clearConsole() {
    if (myCommander != null)
      myCommander.clearConsole();
  }

  public void print(String message) {
    fire("console", message);
    if (myCommander == null)
      logger.info(message);
    else
      myCommander.addConsole(message);
  }
}
