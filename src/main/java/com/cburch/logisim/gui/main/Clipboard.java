/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.main;

import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.util.PropertyChangeWeakSupport;
import java.beans.PropertyChangeListener;
import java.util.Collection;
import java.util.HashSet;

public class Clipboard {
  public static final String CONTENTS_PROPERTY = "contents";
  private static final PropertyChangeWeakSupport propertySupport =
      new PropertyChangeWeakSupport(Clipboard.class);
  private static Clipboard current = null;
  //
  // instance variables and methods
  //
  private final HashSet<Component> components;
  private AttributeSet oldAttrs;
  private AttributeSet newAttrs;

  /*
   * This function is in charge of copy paste.
   * Now the tunnels' labels are not cleared except if it is requested to.
   */
  private Clipboard(Selection sel, AttributeSet viewAttrs) {
    components = new HashSet<>();
    oldAttrs = null;
    newAttrs = null;
    for (final var base : sel.getComponents()) {
      final var baseAttrs = base.getAttributeSet();
      final var copyAttrs = (AttributeSet) baseAttrs.clone();

      final var copy = base.getFactory().createComponent(base.getLocation(), copyAttrs);
      components.add(copy);
      if (baseAttrs == viewAttrs) {
        oldAttrs = baseAttrs;
        newAttrs = copyAttrs;
      }
    }
  }

  /** Clipboard holding copies of the given components (used without a selection, e.g. by MCP). */
  private Clipboard(Collection<? extends Component> comps) {
    components = new HashSet<>();
    oldAttrs = null;
    newAttrs = null;
    for (final var base : comps) {
      final var copyAttrs = (AttributeSet) base.getAttributeSet().clone();
      components.add(base.getFactory().createComponent(base.getLocation(), copyAttrs));
    }
  }

  /** Replaces the clipboard contents with copies of {@code comps}. */
  public static void setComponents(Collection<? extends Component> comps) {
    set(new Clipboard(comps));
  }

  //
  // PropertyChangeSource methods
  //
  public static void addPropertyChangeListener(PropertyChangeListener listener) {
    propertySupport.addPropertyChangeListener(listener);
  }

  public static void addPropertyChangeListener(
      String propertyName, PropertyChangeListener listener) {
    propertySupport.addPropertyChangeListener(propertyName, listener);
  }

  public static Clipboard get() {
    return current;
  }

  public static boolean isEmpty() {
    return current == null || current.components.isEmpty();
  }

  public static void removePropertyChangeListener(PropertyChangeListener listener) {
    propertySupport.removePropertyChangeListener(listener);
  }

  public static void removePropertyChangeListener(
      String propertyName, PropertyChangeListener listener) {
    propertySupport.removePropertyChangeListener(propertyName, listener);
  }

  public static void set(Clipboard value) {
    final var old = current;
    current = value;
    try {
      final var sysClip = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
      if (sysClip.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.imageFlavor)) {
        sysClip.setContents(new java.awt.datatransfer.StringSelection(""), null);
      }
    } catch (Exception e) {
      // ignore
    }
    propertySupport.firePropertyChange(CONTENTS_PROPERTY, old, current);
  }

  public static void set(Selection value, AttributeSet oldAttrs) {
    set(new Clipboard(value, oldAttrs));
  }

  public Collection<Component> getComponents() {
    return components;
  }

  public AttributeSet getNewAttributeSet() {
    return newAttrs;
  }

  public AttributeSet getOldAttributeSet() {
    return oldAttrs;
  }

  void setOldAttributeSet(AttributeSet value) {
    oldAttrs = value;
  }
}
