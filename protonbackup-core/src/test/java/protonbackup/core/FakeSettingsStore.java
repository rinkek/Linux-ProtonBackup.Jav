package protonbackup.core;

import java.util.HashMap;
import java.util.Map;

/** In-memory {@link SettingsStore} for tests that do not need a database. */
final class FakeSettingsStore implements SettingsStore {

    private final Map<String, String> values = new HashMap<>();

    @Override
    public String getSetting(String key) {
        return values.get(key);
    }

    @Override
    public void setSetting(String key, String value) {
        values.put(key, value);
    }
}
