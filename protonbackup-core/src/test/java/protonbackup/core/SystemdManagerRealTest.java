package protonbackup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Talks to the machine's real {@code systemd --user}, because the failures this guards against (a timer
 * that never fires) only exist there. Switched off by default; run it with
 * {@code -Dprotonbackup.realSystemd=true}.
 *
 * <p>Safety: it never uses the app's own unit names, only {@code protonbackup-test-<random>-sync.*}, which
 * it deletes again in a {@code finally}. It does write into the real {@code ~/.config/systemd/user}
 * (systemd reads units from nowhere else), and it never constructs a {@link Cleanup}, which would
 * delete the user's real configuration folder.
 */
@EnabledIfSystemProperty(named = "protonbackup.realSystemd", matches = "true")
@Timeout(120)
class SystemdManagerRealTest {

    @TempDir Path temp;

    private final ProcessRunner runner = new ProcessRunner();

    private String systemctl(String... arguments) throws Exception {
        var all = new java.util.ArrayList<String>(List.of("--user"));
        all.addAll(List.of(arguments));
        var result = runner.run("systemctl", all);
        return result.stdOut() + result.stdErr();
    }

    private String property(String unit, String name) throws Exception {
        var text = systemctl("show", unit, "-p", name).trim();
        return text.substring(text.indexOf('=') + 1);
    }

    private static Path realConfigHome() {
        var xdg = System.getenv("XDG_CONFIG_HOME");
        if (xdg != null && !xdg.isBlank()) return Path.of(xdg);
        var home = System.getenv("HOME");
        return Path.of(home != null && !home.isBlank() ? home : System.getProperty("user.home"), ".config");
    }

    @Test
    void theTimerGetsAFireTimeAndKeepsItAfterAnIntervalChange() throws Exception {
        var names = UnitNames.withBase("protonbackup-test-" + UUID.randomUUID().toString().substring(0, 8) + "-sync");
        var paths = new AppPaths(temp, temp.resolve("data"), realConfigHome(), temp.resolve("cache"));
        var script = temp.resolve("run.sh");
        Files.writeString(script, "#!/bin/sh\nsleep 8\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        var manager = new SystemdManager(paths, names, script.toString(), runner);

        try {
            manager.installUnits(Duration.ofMinutes(30));

            // systemd itself parsed the unit we wrote
            var cat = systemctl("cat", names.timer());
            assertTrue(cat.contains("OnActiveSec=2min"), cat);
            assertFalse(cat.contains("OnStartupSec"), cat);

            assertTrue(manager.enableTimer().ok());
            assertTrue(manager.isTimerEnabled());
            assertEquals("active", property(names.timer(), "ActiveState"));
            assertNotEquals("infinity", property(names.timer(), "NextElapseUSecMonotonic"), "a timer with no next fire time never runs");
            var timers = property(names.timer(), "TimersMonotonic");
            assertTrue(timers.contains("OnActiveUSec=2min"), timers);
            assertTrue(timers.contains("OnUnitActiveUSec=30min"), timers);

            // the interval change: the drop-in must keep OnActiveSec, or the timer stops firing
            manager.setInterval(Duration.ofMinutes(45));
            assertTrue(systemctl("restart", names.timer()).isBlank());

            var after = property(names.timer(), "TimersMonotonic");
            assertTrue(after.contains("OnActiveUSec=2min"), "the startup delay must survive the drop-in: " + after);
            assertTrue(after.contains("OnUnitActiveUSec=45min"), after);
            assertFalse(after.contains("30min"), after);
            assertNotEquals("infinity", property(names.timer(), "NextElapseUSecMonotonic"),
                    "the original bug: after the first interval change the timer never fired again");

            // start returns at once even though the run takes 8 s, and the run can be stopped
            var started = System.nanoTime();
            assertTrue(manager.startSync(null).ok());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 4, "start --no-block must not wait for the run");
            assertTrue(manager.isSyncRunning());
            assertTrue(manager.stopSync().ok());
            assertFalse(manager.isSyncRunning());

            assertTrue(manager.disableTimer().ok());
            assertFalse(manager.isTimerEnabled());
        } finally {
            cleanUp(names, paths);
        }

        assertTrue(systemctl("list-unit-files", names.service(), names.timer(), "--no-legend").isBlank()
                || systemctl("list-unit-files", names.service(), names.timer(), "--no-legend").contains("0 unit files"));
    }

    private void cleanUp(UnitNames names, AppPaths paths) throws Exception {
        systemctl("disable", "--now", names.timer());
        systemctl("stop", names.service());
        var units = paths.userUnitDir();
        for (var name : List.of(names.service(), names.timer(), names.sourceTemplate())) Files.deleteIfExists(units.resolve(name));
        var dropIn = units.resolve(names.timerDropInDir());
        if (Files.isDirectory(dropIn)) {
            try (var entries = Files.list(dropIn)) {
                for (var entry : entries.toList()) Files.deleteIfExists(entry);
            }
            Files.deleteIfExists(dropIn);
        }
        Files.deleteIfExists(units.resolve(names.timerWantsDir()).resolve(names.timer()));
        systemctl("daemon-reload");
        systemctl("reset-failed", names.service(), names.timer());
    }
}
