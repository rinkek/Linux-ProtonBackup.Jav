package protonbackup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SystemdManager had no C# tests, yet it holds the two least obvious, hardest-won details of the
 * whole app. The two regression tests at the top are the most important ones in this file.
 */
class SystemdManagerTest {

    @TempDir Path home;

    private final List<String> calls = new ArrayList<>();
    private CliResult result = new CliResult(0, "", "");

    private SystemdManager manager() {
        return new SystemdManager(AppPaths.forHome(home), "/usr/bin/protonbackup", (executable, arguments) -> {
            calls.add(executable + " " + String.join(" ", arguments));
            return result;
        });
    }

    private Path unitDir() {
        return home.resolve(".config/systemd/user");
    }

    private String read(String name) throws IOException {
        return Files.readString(unitDir().resolve(name));
    }

    // ---- the two hard-won regressions ---------------------------------------------------

    /**
     * OnStartupSec counts from boot and would already have "elapsed" by the time the timer is enabled
     * later in the day; systemd never catches up an elapsed monotonic timer, so it would never fire.
     */
    @Test
    void theTimerUsesOnActiveSecNotOnStartupSec() throws Exception {
        manager().installUnits();

        var timer = read("protonbackup-sync.timer");
        assertTrue(timer.contains("OnActiveSec="));
        assertFalse(timer.contains("OnStartupSec"));
    }

    /**
     * A bare "OnUnitActiveSec=" reset clears all monotonic timers on the unit, not just that key: a
     * drop-in that only set the new interval would wipe OnActiveSec, and the timer would never fire
     * again after the first interval change.
     */
    @Test
    void theIntervalDropInRespecifiesBothKeysNotJustTheInterval() throws Exception {
        manager().setInterval(Duration.ofMinutes(45));

        var dropIn = read("protonbackup-sync.timer.d/interval.conf");
        var keyLines = dropIn.lines().filter(l -> l.startsWith("OnUnitActiveSec=") || l.startsWith("OnActiveSec=")).toList();
        assertEquals(List.of("OnUnitActiveSec=", "OnActiveSec=2min", "OnUnitActiveSec=45min"), keyLines);
        assertEquals(Duration.ofMinutes(2), SystemdManager.STARTUP_DELAY, "pins the literal above to the constant");
        assertTrue(dropIn.startsWith("[Timer]\n"));
    }

    // ---- unit content -------------------------------------------------------------------

    @Test
    void installUnitsWritesAllThreeUnitsAndReloads() throws Exception {
        manager().installUnits(Duration.ofMinutes(15));

        var service = read("protonbackup-sync.service");
        var template = read("protonbackup-sync@.service");
        var timer = read("protonbackup-sync.timer");
        assertTrue(service.contains("Type=oneshot"));
        assertTrue(service.contains("ExecStart=/usr/bin/protonbackup --run-once\n"));
        assertTrue(template.contains("ExecStart=/usr/bin/protonbackup --run-once --source %i\n"));
        assertTrue(timer.contains("OnActiveSec=2min\n"));
        assertTrue(timer.contains("OnUnitActiveSec=15min\n"));
        assertTrue(timer.contains("Unit=protonbackup-sync.service\n"));
        assertTrue(timer.contains("WantedBy=timers.target\n"));
        assertEquals(List.of("systemctl --user daemon-reload"), calls);
    }

    @Test
    void installUnitsDefaultsToThirtyMinutes() throws Exception {
        manager().installUnits();

        assertTrue(read("protonbackup-sync.timer").contains("OnUnitActiveSec=30min\n"));
    }

    @Test
    void theUnitsMatchTheOnesTheDebShips() throws Exception {
        manager().installUnits();

        assertTrue(read("protonbackup-sync.service").contains("Description=Proton Drive backup, a sync run\n"));
        assertTrue(read("protonbackup-sync@.service").contains("Description=Proton Drive backup, a sync run for source %i\n"));
        assertTrue(read("protonbackup-sync.timer").contains("Description=Proton Drive backup on an interval\n"));
    }

    @Test
    void installUnitsOverwritesExistingUnitsWithTheCurrentExecutable() throws Exception {
        Files.createDirectories(unitDir());
        Files.writeString(unitDir().resolve("protonbackup-sync.service"), "ExecStart=/old/path --run-once\n");

        manager().installUnits();

        assertFalse(read("protonbackup-sync.service").contains("/old/path"));
    }

    @Test
    void anExecutablePathWithSpacesOrPercentSignsIsEscapedForSystemd() throws Exception {
        new SystemdManager(AppPaths.forHome(home), "/opt/My Apps/50%/protonbackup", (e, a) -> result).installUnits();

        assertTrue(read("protonbackup-sync.service").contains("ExecStart=\"/opt/My Apps/50%%/protonbackup\" --run-once\n"));
    }

    @Test
    void intervalsAreFormattedInWholeMinutesOrSeconds() {
        assertEquals("2min", SystemdManager.formatInterval(Duration.ofMinutes(2)));
        assertEquals("30min", SystemdManager.formatInterval(Duration.ofMinutes(30)));
        assertEquals("90s", SystemdManager.formatInterval(Duration.ofSeconds(90)));
        assertEquals("30s", SystemdManager.formatInterval(Duration.ofSeconds(30)));
    }

    // ---- systemctl argument construction ------------------------------------------------

    @Test
    void enableAndDisableTimerCallSystemctlCorrectly() throws Exception {
        var manager = manager();
        manager.enableTimer();
        manager.disableTimer();

        assertEquals(List.of(
                "systemctl --user enable --now protonbackup-sync.timer",
                "systemctl --user disable --now protonbackup-sync.timer"), calls);
    }

    @Test
    void isTimerEnabledReflectsSystemctlOutput() throws Exception {
        result = new CliResult(0, "enabled\n", "");
        assertTrue(manager().isTimerEnabled());

        result = new CliResult(1, "disabled\n", "");
        assertFalse(manager().isTimerEnabled());
    }

    /** Without --no-block systemctl waits for the whole oneshot run, which would freeze the UI. */
    @Test
    void startSyncUsesNoBlock() throws Exception {
        manager().startSync(null);

        assertEquals(List.of("systemctl --user start --no-block protonbackup-sync.service"), calls);
    }

    @Test
    void startSyncWithASourceIdUsesTheTemplatedUnit() throws Exception {
        manager().startSync(7L);

        assertEquals(List.of("systemctl --user start --no-block protonbackup-sync@7.service"), calls);
    }

    @Test
    void stopSyncStopsTheService() throws Exception {
        manager().stopSync();

        assertEquals(List.of("systemctl --user stop protonbackup-sync.service"), calls);
    }

    @Test
    void isSyncRunningTreatsActivatingAsRunningToo() throws Exception {
        result = new CliResult(0, "activating\n", "");
        assertTrue(manager().isSyncRunning());
        result = new CliResult(0, "active\n", "");
        assertTrue(manager().isSyncRunning());
        result = new CliResult(0, "inactive\n", "");
        assertFalse(manager().isSyncRunning());
        result = new CliResult(3, "failed\n", "");
        assertFalse(manager().isSyncRunning());
    }

    @Test
    void describeTimerStripsWhitespace() throws Exception {
        result = new CliResult(0, "  some output  \n", "");

        assertEquals("some output", manager().describeTimer());
        assertEquals(List.of("systemctl --user list-timers protonbackup-sync.timer --no-pager"), calls);
    }

    @Test
    void aMissingSystemctlSurfacesAsAnIoException() {
        var broken = new SystemdManager(AppPaths.forHome(home), "/usr/bin/protonbackup", (e, a) -> {
            throw new IOException("Cannot run program \"systemctl\"");
        });

        assertThrows(IOException.class, broken::enableTimer);
    }

    // ---- the "next run" shown in the UI -------------------------------------------------

    @Test
    void nextRunIsReadFromTheRealListTimersFormat() {
        // Captured from the real systemctl: columns are separated by single spaces here.
        var output = """
                NEXT                         LEFT LAST PASSED UNIT                     ACTIVATES
                Thu 2026-10-01 08:47:17 CEST 9min -         - protonbackup-sync.timer protonbackup-sync.service

                1 timers listed.
                Pass --all to see loaded but inactive timers, too.
                """;

        assertEquals("Thu 2026-10-01 08:47:17 CEST", SystemdManager.parseNextRun(output, "protonbackup-sync.timer"));
    }

    @Test
    void nextRunWorksWhenTheLeftColumnIsWider() {
        var output = "NEXT                         LEFT          LAST PASSED UNIT                      ACTIVATES\n"
                + "Thu 2026-10-01 10:47:17 CEST 1h 59min left -    -      protonbackup-sync.timer   protonbackup-sync.service\n";

        assertEquals("Thu 2026-10-01 10:47:17 CEST", SystemdManager.parseNextRun(output, "protonbackup-sync.timer"));
    }

    @Test
    void nextRunIsNullWhenTheTimerIsNotListedOrNotScheduled() {
        assertNull(SystemdManager.parseNextRun("NEXT LEFT LAST PASSED UNIT ACTIVATES\n\n0 timers listed.\n", "protonbackup-sync.timer"));
        assertNull(SystemdManager.parseNextRun("", "protonbackup-sync.timer"));
        assertNull(SystemdManager.parseNextRun("-                            -    -    -      protonbackup-sync.timer protonbackup-sync.service\n", "protonbackup-sync.timer"));
    }

    @Test
    void nextRunOfAnotherTimerIsIgnored() {
        var output = "Thu 2026-10-01 08:47:17 CEST 9min -  - other.timer other.service\n";

        assertNull(SystemdManager.parseNextRun(output, "protonbackup-sync.timer"));
    }

    @Test
    void nextRunAsksSystemctlForTheTimer() throws Exception {
        result = new CliResult(0, "Thu 2026-10-01 08:47:17 CEST 9min -  - protonbackup-sync.timer protonbackup-sync.service\n", "");

        assertEquals("Thu 2026-10-01 08:47:17 CEST", manager().nextRun());
        assertEquals(List.of("systemctl --user list-timers protonbackup-sync.timer --no-pager"), calls);
    }

    // ---- other unit names (used by tests that talk to the real systemd) ----------------

    @Test
    void customUnitNamesAreUsedEverywhere() throws Exception {
        var names = UnitNames.withBase("protonbackup-test-abc-sync");
        var manager = new SystemdManager(AppPaths.forHome(home), names, "/bin/true", (e, a) -> {
            calls.add(String.join(" ", a));
            return result;
        });

        manager.installUnits();
        manager.setInterval(Duration.ofMinutes(5));
        manager.startSync(3L);

        assertTrue(Files.exists(unitDir().resolve("protonbackup-test-abc-sync.service")));
        assertTrue(Files.exists(unitDir().resolve("protonbackup-test-abc-sync@.service")));
        assertTrue(Files.exists(unitDir().resolve("protonbackup-test-abc-sync.timer.d/interval.conf")));
        assertTrue(read("protonbackup-test-abc-sync.timer").contains("Unit=protonbackup-test-abc-sync.service"));
        assertTrue(calls.contains("--user start --no-block protonbackup-test-abc-sync@3.service"));
    }

    @Test
    void unitNamesDeriveTheirRelatedNames() {
        var names = UnitNames.DEFAULT;

        assertEquals("protonbackup-sync.service", names.service());
        assertEquals("protonbackup-sync.timer", names.timer());
        assertEquals("protonbackup-sync@.service", names.sourceTemplate());
        assertEquals("protonbackup-sync@12.service", names.sourceService(12));
        assertEquals("protonbackup-sync.timer.d", names.timerDropInDir());
        assertEquals("timers.target.wants", names.timerWantsDir());
        assertEquals("stamp-protonbackup-sync.timer", names.timerStamp());
    }
}
