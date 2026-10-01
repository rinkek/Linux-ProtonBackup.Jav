package protonbackup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cleanup has no C# tests; the Python port added some for its helpers. These cover the whole
 * contract of MIGRATION_PLAN.md section 6.9 in a temporary home folder.
 */
class CleanupTest {

    @TempDir Path home;

    private final FakeSystemd systemd = new FakeSystemd();
    private final List<String> signOuts = new ArrayList<>();

    private Cleanup cleanup(AppPaths paths, Supplier<String> cli, Runnable quiesce) {
        return new Cleanup(paths, UnitNames.DEFAULT, systemd, cli, path -> {
            signOuts.add(path);
            return new CliResult(0, "", "");
        }, quiesce, duration -> {});
    }

    /** Everything the app can leave behind, as a real installation would. */
    private void populate(AppPaths paths) throws Exception {
        Files.createDirectories(paths.binDir());
        Files.writeString(paths.binDir().resolve("proton-drive"), "cli");
        Files.writeString(paths.binDir().resolve("proton-drive.previous"), "old");
        Files.writeString(paths.databasePath(), "db");
        Files.writeString(Path.of(paths.databasePath() + "-wal"), "wal");
        Files.writeString(Path.of(paths.databasePath() + "-shm"), "shm");
        Files.writeString(paths.lockPath(), "123");
        Files.createDirectories(paths.configDir());
        Files.writeString(paths.configDir().resolve("x.conf"), "x");
        Files.createDirectories(paths.cacheDir());
        Files.writeString(paths.cacheDir().resolve("c"), "x");
        Files.createDirectories(paths.openJfxDir().resolve("cache"));
        Files.writeString(paths.openJfxDir().resolve("cache/libglass.so"), "x");

        var units = paths.userUnitDir();
        Files.createDirectories(units.resolve(UnitNames.DEFAULT.timerDropInDir()));
        Files.writeString(units.resolve(UnitNames.DEFAULT.service()), "x");
        Files.writeString(units.resolve(UnitNames.DEFAULT.timer()), "x");
        Files.writeString(units.resolve(UnitNames.DEFAULT.sourceTemplate()), "x");
        Files.writeString(units.resolve(UnitNames.DEFAULT.timer() + ".d/interval.conf"), "x");
        Files.createDirectories(units.resolve(UnitNames.DEFAULT.timerWantsDir()));
        Files.createSymbolicLink(units.resolve(UnitNames.DEFAULT.timerWantsDir()).resolve(UnitNames.DEFAULT.timer()), units.resolve(UnitNames.DEFAULT.timer()));
        Files.writeString(units.resolve("unrelated.service"), "belongs to someone else");

        Files.createDirectories(paths.timerStampDir());
        Files.writeString(paths.timerStampDir().resolve("stamp-protonbackup-sync.timer"), "");
        Files.writeString(paths.timerStampDir().resolve("stamp-other.timer"), "");
    }

    @Test
    void removesEverythingAndLeavesOtherPeoplesFilesAlone() throws Exception {
        var paths = AppPaths.forHome(home);
        populate(paths);

        var steps = cleanup(paths, () -> null, () -> {}).run();

        assertTrue(Cleanup.succeeded(steps), steps.toString());
        for (var gone : List.of(paths.dataDir(), paths.configDir(), paths.cacheDir(), paths.openJfxDir(),
                paths.userUnitDir().resolve(UnitNames.DEFAULT.service()), paths.userUnitDir().resolve(UnitNames.DEFAULT.timer()),
                paths.userUnitDir().resolve(UnitNames.DEFAULT.sourceTemplate()),
                paths.userUnitDir().resolve(UnitNames.DEFAULT.timerDropInDir()),
                paths.userUnitDir().resolve(UnitNames.DEFAULT.timerWantsDir()).resolve(UnitNames.DEFAULT.timer()),
                paths.timerStampDir().resolve("stamp-protonbackup-sync.timer"))) {
            assertFalse(Files.exists(gone, LinkOption.NOFOLLOW_LINKS), gone + " should be gone");
        }
        assertTrue(Files.exists(paths.userUnitDir().resolve("unrelated.service")));
        assertTrue(Files.exists(paths.timerStampDir().resolve("stamp-other.timer")));
    }

    @Test
    void stepsAreReportedInTheDocumentedOrder() throws Exception {
        var paths = AppPaths.forHome(home);
        populate(paths);

        var steps = cleanup(paths, () -> "/usr/bin/proton-drive", () -> {}).run();

        assertEquals(List.of(
                "Background work stopped", "Timer turned off", "Run in progress stopped", "Signed out of Proton",
                "Data folder removed", "Config folder removed", "Cache folder removed", "systemd units cleaned up",
                "Timer stamp files removed", "Interface cache removed", "systemd reloaded",
                "Verified that nothing is left behind"),
                steps.stream().map(Cleanup.Step::description).toList());
    }

    @Test
    void theTimerAndTheRunAreStoppedBeforeFilesAreDeletedAndUnitsAreReloadedAfterwards() throws Exception {
        var paths = AppPaths.forHome(home);
        populate(paths);
        var seenWhenDisabled = new ArrayList<Boolean>();
        var watching = new FakeSystemd() {
            @Override
            public CliResult disableTimer() throws java.io.IOException {
                seenWhenDisabled.add(Files.exists(paths.dataDir()));
                return super.disableTimer();
            }
        };

        new Cleanup(paths, UnitNames.DEFAULT, watching, () -> null, p -> new CliResult(0, "", ""), () -> {}, d -> {}).run();

        assertEquals(List.of(true), seenWhenDisabled);
        assertEquals(List.of("disable", "stop", "reload"), watching.calls);
    }

    @Test
    void signsOutWhenTheCliIsFoundAndSkipsWhenItIsNot() throws Exception {
        var paths = AppPaths.forHome(home);

        var withCli = cleanup(paths, () -> "/usr/bin/proton-drive", () -> {}).run();
        assertEquals(List.of("/usr/bin/proton-drive"), signOuts);
        assertTrue(withCli.stream().anyMatch(s -> s.description().equals("Signed out of Proton") && s.succeeded()));

        var without = cleanup(AppPaths.forHome(home.resolve("other")), () -> null, () -> {}).run();
        var skipped = without.stream().filter(s -> s.description().equals("Sign-out skipped")).findFirst().orElseThrow();
        assertEquals(new Cleanup.Step("Sign-out skipped", true, "the CLI was not found"), skipped);
        assertEquals(1, signOuts.size());
    }

    @Test
    void aFailingSystemctlIsReportedButTheFilesAreStillRemoved() throws Exception {
        var paths = AppPaths.forHome(home);
        populate(paths);
        systemd.failDisable = true;

        var steps = cleanup(paths, () -> null, () -> {}).run();

        var timer = steps.stream().filter(s -> s.description().equals("Timer turned off")).findFirst().orElseThrow();
        assertFalse(timer.succeeded());
        assertFalse(Cleanup.succeeded(steps));
        assertFalse(Files.exists(paths.dataDir()));
    }

    @Test
    void nothingToRemoveIsASuccess() throws Exception {
        var steps = cleanup(AppPaths.forHome(home.resolve("never-used")), () -> null, () -> {}).run();

        assertTrue(Cleanup.succeeded(steps), steps.toString());
        var units = steps.stream().filter(s -> s.description().equals("systemd units cleaned up")).findFirst().orElseThrow();
        assertEquals("nothing to remove", units.detail());
    }

    @Test
    void theAppCannotRecreateItsFoldersAfterwards() throws Exception {
        var paths = AppPaths.forHome(home);
        populate(paths);

        cleanup(paths, () -> null, () -> {}).run();

        assertThrows(IllegalStateException.class, () -> Database.open(paths));
        assertFalse(Files.exists(paths.dataDir()));
    }

    @Test
    void removalWorksWhileTheDatabaseIsOpenAndAPollTaskIsRunning() throws Exception {
        var paths = AppPaths.forHome(home);
        paths.ensureCreated();
        var database = Database.open(paths);
        database.setSetting("k", "v");
        populate(paths);

        var polling = new AtomicBoolean(true);
        var failure = new ArrayList<Throwable>();
        var poller = new Thread(() -> {
            while (polling.get()) {
                try {
                    database.getSources();
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    // expected only after the database was closed by quiesce
                    if (polling.get()) failure.add(e);
                    return;
                }
            }
        });
        poller.start();

        Runnable quiesce = () -> {
            polling.set(false);
            try {
                poller.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            database.close();
        };

        var steps = cleanup(paths, () -> null, quiesce).run();

        assertTrue(Cleanup.succeeded(steps), steps.toString());
        assertTrue(failure.isEmpty(), failure.toString());
        assertFalse(Files.exists(paths.dataDir()), "database, WAL and lock files must be gone");
    }

    @Test
    void aSymlinkInsideTheDataFolderIsRemovedNotFollowed() throws Exception {
        var paths = AppPaths.forHome(home);
        var outside = Files.createDirectories(home.resolve("precious"));
        Files.writeString(outside.resolve("keep.txt"), "keep me");
        Files.createDirectories(paths.dataDir());
        Files.createSymbolicLink(paths.dataDir().resolve("link"), outside);

        var steps = cleanup(paths, () -> null, () -> {}).run();

        assertTrue(Cleanup.succeeded(steps), steps.toString());
        assertFalse(Files.exists(paths.dataDir()));
        assertTrue(Files.exists(outside.resolve("keep.txt")), "the link target must not be touched");
    }

    @Test
    void aFailedDeletionIsReportedByTheFinalCheckAndFailsTheRun() throws Exception {
        assumeFalse("root".equals(System.getProperty("user.name")), "permission bits are not enforced for root");
        var paths = AppPaths.forHome(home);
        var locked = Files.createDirectories(paths.cacheDir().resolve("locked"));
        Files.writeString(locked.resolve("stuck.txt"), "x");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            var steps = cleanup(paths, () -> null, () -> {}).run();

            assertFalse(Cleanup.succeeded(steps));
            var verify = steps.get(steps.size() - 1);
            assertFalse(verify.succeeded());
            assertTrue(verify.detail().contains("cache"), verify.detail());
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void aFailingQuiesceIsReportedButRemovalContinues() throws Exception {
        var paths = AppPaths.forHome(home);
        populate(paths);

        var steps = cleanup(paths, () -> null, () -> {
            throw new IllegalStateException("could not stop");
        }).run();

        assertFalse(steps.get(0).succeeded());
        assertEquals("could not stop", steps.get(0).detail());
        assertFalse(Files.exists(paths.dataDir()));
        assertTrue(paths.isRetired());
    }
}
