package protonbackup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppPathsTest {

    @Test
    void defaultLayoutIsUnderTheProtonBackupFolder(@TempDir Path home) {
        var paths = AppPaths.forHome(home);

        assertEquals(home.resolve(".local/share/ProtonBackup"), paths.dataDir());
        assertEquals(home.resolve(".config/ProtonBackup"), paths.configDir());
        assertEquals(home.resolve(".cache/ProtonBackup"), paths.cacheDir());
        assertEquals(paths.dataDir().resolve("bin"), paths.binDir());
        assertEquals(paths.dataDir().resolve("protonbackup.db"), paths.databasePath());
        assertEquals(paths.dataDir().resolve("sync.lock"), paths.lockPath());
        assertEquals(home.resolve(".config/systemd/user"), paths.userUnitDir());
        assertEquals(home.resolve(".local/share/systemd/timers"), paths.timerStampDir());
        assertEquals(home.resolve(".openjfx"), paths.openJfxDir());
    }

    @Test
    void ensureCreatedMakesAllThreeDirectories(@TempDir Path home) throws Exception {
        var paths = AppPaths.forHome(home);

        paths.ensureCreated();

        assertTrue(Files.isDirectory(paths.dataDir()));
        assertTrue(Files.isDirectory(paths.configDir()));
        assertTrue(Files.isDirectory(paths.binDir()));
    }

    @Test
    void xdgVariablesOverrideTheDefaults() {
        var paths = AppPaths.fromEnvironment(
                Map.of("HOME", "/home/x", "XDG_DATA_HOME", "/d", "XDG_CONFIG_HOME", "/c", "XDG_CACHE_HOME", "/k"),
                "/ignored");

        assertEquals(Path.of("/d/ProtonBackup"), paths.dataDir());
        assertEquals(Path.of("/c/ProtonBackup"), paths.configDir());
        assertEquals(Path.of("/k/ProtonBackup"), paths.cacheDir());
        assertEquals(Path.of("/c/systemd/user"), paths.userUnitDir());
    }

    @Test
    void fallsBackToDotfileDefaultsWhenTheVariablesAreUnsetOrBlank() {
        var paths = AppPaths.fromEnvironment(Map.of("HOME", "/home/x", "XDG_DATA_HOME", "  "), "/ignored");

        assertEquals(Path.of("/home/x/.local/share/ProtonBackup"), paths.dataDir());
        assertEquals(Path.of("/home/x/.config/ProtonBackup"), paths.configDir());
    }

    @Test
    void homeVariableWinsOverTheJvmsUserHome() {
        var paths = AppPaths.fromEnvironment(Map.of("HOME", "/from/env"), "/from/passwd");

        assertEquals(Path.of("/from/env"), paths.home());
    }

    @Test
    void userHomeIsUsedWhenHomeIsMissing() {
        var paths = AppPaths.fromEnvironment(Map.of(), "/from/passwd");

        assertEquals(Path.of("/from/passwd"), paths.home());
    }

    @Test
    void aRetiredInstanceRefusesToRecreateTheFolders(@TempDir Path home) {
        var paths = AppPaths.forHome(home);
        paths.retire();

        assertTrue(paths.isRetired());
        assertThrows(IllegalStateException.class, paths::ensureCreated);
        assertFalse(Files.exists(paths.dataDir()));
    }
}
