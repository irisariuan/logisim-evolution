/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.prefs;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.List;
import java.util.prefs.PreferenceChangeEvent;
import java.util.prefs.PreferenceChangeListener;

public interface PrefMonitor<E> extends PreferenceChangeListener {
  void addPropertyChangeListener(PropertyChangeListener listener);

  E get();

  boolean getBoolean();

  String getIdentifier();

  boolean isSource(PropertyChangeEvent event);

  @Override
  void preferenceChange(PreferenceChangeEvent e);

  void removePropertyChangeListener(PropertyChangeListener listener);

  void set(E value);

  void setBoolean(boolean value);

  /** Returns the value used when the preference has never been set, or null if not known. */
  default E getDefault() {
    return null;
  }

  /**
   * Returns the allowed values of a preference that is restricted to a fixed set of strings, or
   * null if any value of the preference's type is accepted.
   */
  default List<String> getChoices() {
    return null;
  }

  /** Sets the preference back to its default value. */
  default void restoreDefault() {
    set(getDefault());
  }
}
