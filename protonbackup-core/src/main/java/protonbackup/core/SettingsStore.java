package protonbackup.core;

/** Key/value settings persisted by the {@link Database}; a seam so settings code is testable without one. */
public interface SettingsStore {

    /** The stored value, or {@code null} when the key was never set. */
    String getSetting(String key);

    void setSetting(String key, String value);
}
