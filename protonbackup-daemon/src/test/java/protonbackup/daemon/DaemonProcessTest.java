package protonbackup.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import protonbackup.core.Database;
import protonbackup.core.FileStatus;
import protonbackup.daemon.DaemonProcess.World;

/**
 * The real daemon in a second JVM, against a fake Proton CLI and a folder standing in for Proton
 * Drive: exit codes, a stop signal half way through a run, two runs at once, and the launcher wrapper.
 */
@Timeout(180)
class DaemonProcessTest {

    @TempDir Path root;

    private Set<String> names(Path folder) throws Exception {
        try (var entries = Files.list(folder)) {
            return entries.map(p -> p.getFileName().toString()).collect(Collectors.toSet());
        }
    }

    private int run(World world, String... args) throws Exception {
        return DaemonProcess.start(world, false, args).waitFor();
    }

    // ---- a normal run -------------------------------------------------------------------

    @Test
    void aFullRunUploadsEverythingAndExitsZero() throws Exception {
        var world = new World(root).withCli();
        var source = world.source("docs");
        world.writeFile(source.resolve("a.txt"), "hello");
        world.writeFile(source.resolve("sub/b.txt"), "world");
        assertEquals(0, run(world, "add-source", source.toString(), "/my-files/Backup"));

        var daemon = DaemonProcess.start(world, false, "--run-once");
        assertEquals(0, daemon.waitFor(), daemon.out() + daemon.err());

        assertTrue(daemon.out().contains("Done: 2 uploaded, 0 failed, 0 gone locally."), daemon.out());
        assertEquals("hello", Files.readString(root.resolve("remote/my-files/Backup/a.txt")));
        assertEquals("world", Files.readString(root.resolve("remote/my-files/Backup/sub/b.txt")));
        try (var db = new Database(world.paths.databasePath())) {
            assertEquals("ok", db.getRecentRuns(1).get(0).result());
        }
        assertTrue(world.logText().contains("--file-conflict-strategy create-new-revision --folder-conflict-strategy merge --skip-thumbnails"),
                "the upload must always carry the conflict strategy flags");
    }

    @Test
    void aSecondRoundWithNothingNewUploadsNothing() throws Exception {
        var world = new World(root).withCli();
        var source = world.source("docs");
        world.writeFile(source.resolve("a.txt"), "hello");
        run(world, "add-source", source.toString(), "/my-files/Backup");
        run(world, "--run-once");
        var uploadsAfterFirst = world.logText().lines().filter(l -> l.contains(" upload ")).count();

        assertEquals(0, run(world, "--run-once"));

        assertEquals(uploadsAfterFirst, world.logText().lines().filter(l -> l.contains(" upload ")).count());
    }

    @Test
    void nonAsciiNamesGoThroughTheWholeDaemonUnchanged() throws Exception {
        var world = new World(root).withCli();
        var source = world.source("docs");
        world.writeFile(source.resolve("ünï cödé/日本語 😀.txt"), "x");
        world.writeFile(source.resolve("café.txt"), "x");
        run(world, "add-source", source.toString(), "/my-files/Backup");

        assertEquals(0, run(world, "--run-once"));

        assertEquals(Set.of("café.txt", "ünï cödé"), names(root.resolve("remote/my-files/Backup")));
        assertEquals(Set.of("日本語 😀.txt"), names(root.resolve("remote/my-files/Backup/ünï cödé")));
    }

    // ---- exit codes ---------------------------------------------------------------------

    @Test
    void exitCodeTwoWhenTheCliIsMissing() throws Exception {
        var world = new World(root); // no CLI installed
        world.environment.put("PATH", "/usr/bin:/bin");

        var daemon = DaemonProcess.start(world, false, "--run-once");

        assertEquals(2, daemon.waitFor());
        assertTrue(daemon.err().contains("The proton-drive CLI was not found."), daemon.err());
    }

    @Test
    void exitCodeOneWhenAFileWasNotStored() throws Exception {
        var world = new World(root).withCli();
        world.environment.put("FAKE_SKIP", "b.txt");
        var source = world.source("docs");
        world.writeFile(source.resolve("a.txt"), "x");
        world.writeFile(source.resolve("b.txt"), "x");
        run(world, "add-source", source.toString(), "/my-files/Backup");

        var daemon = DaemonProcess.start(world, false, "--run-once");

        assertEquals(1, daemon.waitFor());
        assertTrue(daemon.out().contains("Done: 1 uploaded, 1 failed"), daemon.out());
        try (var db = new Database(world.paths.databasePath())) {
            assertEquals("partial", db.getRecentRuns(1).get(0).result());
        }
    }

    @Test
    void exitCodeZeroWithAMessageWhenTheSessionIsGone() throws Exception {
        var world = new World(root).withCli();
        world.environment.put("FAKE_LOGGED_OUT", "1");
        var source = world.source("docs");
        world.writeFile(source.resolve("a.txt"), "x");
        run(world, "add-source", source.toString(), "/my-files/Backup");

        var daemon = DaemonProcess.start(world, false, "--run-once");

        assertEquals(0, daemon.waitFor());
        assertTrue(daemon.out().contains("Session expired. Sign in again with: proton-drive auth login"), daemon.out());
        try (var db = new Database(world.paths.databasePath())) {
            assertTrue(db.getRecentRuns(10).isEmpty(), "a paused run is not recorded");
        }
    }

    // ---- a stop signal half way through a run ------------------------------------------

    private World twoFolderWorld(String uploadDelaySeconds) throws Exception {
        var world = new World(root).withCli();
        world.environment.put("FAKE_UPLOAD_DELAY", uploadDelaySeconds);
        var source = world.source("docs");
        world.writeFile(source.resolve("dir_a/a.txt"), "aaa");
        world.writeFile(source.resolve("dir_b/b.txt"), "bbb");
        assertEquals(0, run(world, "add-source", source.toString(), "/my-files/Backup"));
        return world;
    }

    /**
     * SIGTERM to the daemon alone: the batch in flight finishes and is recorded, the next one never
     * starts, the run is recorded as cancelled with its real counts, and the exit code is 0 (a plain JVM
     * would say 143).
     */
    @Test
    void aStopSignalFinishesTheCurrentBatchThenStopsCleanlyWithExitCodeZero() throws Exception {
        var world = twoFolderWorld("3");
        var daemon = DaemonProcess.start(world, false, "--run-once");
        DaemonProcess.awaitText(world.log, " upload ", Duration.ofSeconds(30));

        daemon.process.destroy(); // SIGTERM, to the JVM only; the upload in flight carries on
        var exit = daemon.waitFor();

        assertEquals(0, exit, daemon.out() + daemon.err());
        assertTrue(daemon.out().contains("Cancelled after 1 file(s); the next run continues where this one left off."), daemon.out());
        try (var db = new Database(world.paths.databasePath())) {
            var run = db.getRecentRuns(1).get(0);
            assertEquals("cancelled", run.result());
            assertEquals(1, run.uploaded());
            assertEquals(0, run.failed());
            assertTrue(run.finishedUtc() != null);
            var statuses = db.getTrackedFiles(1).values().stream().map(f -> f.status()).sorted().toList();
            assertEquals(List.of(FileStatus.PENDING, FileStatus.SYNCED), statuses);
        }
        assertEquals(Set.of("a.txt"), names(root.resolve("remote/my-files/Backup/dir_a")));
        assertFalse(Files.exists(root.resolve("remote/my-files/Backup/dir_b")), "the second batch never started, not even its folder");
    }

    /**
     * What systemd does: SIGTERM to every process of the service at once, the CLI included. The upload in
     * flight dies, so its file is an error (to be retried), and the run is still recorded as cancelled.
     */
    @Test
    void aStopSignalToTheWholeGroupLeavesNothingLostAndExitsZero() throws Exception {
        var world = twoFolderWorld("5");
        var daemon = DaemonProcess.start(world, true, "--run-once");
        DaemonProcess.awaitText(world.log, " upload ", Duration.ofSeconds(30));

        DaemonProcess.terminateGroup(daemon.process);
        var exit = daemon.waitFor();

        assertEquals(0, exit, daemon.out() + daemon.err());
        try (var db = new Database(world.paths.databasePath())) {
            var run = db.getRecentRuns(1).get(0);
            assertEquals("cancelled", run.result());
            assertEquals(0, run.uploaded());
            assertEquals(1, run.failed());
            var failed = db.getFailedFiles(10);
            assertEquals(1, failed.size());
            assertTrue(failed.get(0).lastError().contains("exit code 143"), failed.get(0).lastError());
        }

        // The next run picks everything up again: nothing was lost.
        world.environment.remove("FAKE_UPLOAD_DELAY");
        assertEquals(0, run(world, "--run-once"));
        assertEquals(Set.of("a.txt"), names(root.resolve("remote/my-files/Backup/dir_a")));
        assertEquals(Set.of("b.txt"), names(root.resolve("remote/my-files/Backup/dir_b")));
    }

    @Test
    void cleanupWithoutAnAnswerOnStdinCancelsInsteadOfWaiting() throws Exception {
        var world = new World(root).withCli();
        var daemon = DaemonProcess.start(world, false, "--cleanup"); // asks for a confirmation; stdin is /dev/null

        assertEquals(1, daemon.waitFor());
        assertTrue(daemon.out().contains("Cancelled."), daemon.out());
        assertTrue(Files.exists(world.paths.binDir().resolve("proton-drive")), "nothing may be removed without a yes");
    }

    // ---- two runs at once ---------------------------------------------------------------

    @Test
    void aSecondRunWhileOneIsActiveIsSkippedAndLeavesNoTrace() throws Exception {
        var world = new World(root).withCli();
        world.environment.put("FAKE_UPLOAD_DELAY", "4");
        var source = world.source("docs");
        world.writeFile(source.resolve("a.txt"), "x");
        run(world, "add-source", source.toString(), "/my-files/Backup");

        var first = DaemonProcess.start(world, false, "--run-once");
        DaemonProcess.awaitText(world.log, " upload ", Duration.ofSeconds(30));
        var second = DaemonProcess.start(world, false, "--run-once");

        assertEquals(0, second.waitFor());
        assertTrue(second.out().contains("A run is already in progress; this one is skipped."), second.out());
        assertEquals(0, first.waitFor());
        try (var db = new Database(world.paths.databasePath())) {
            assertEquals(1, db.getRecentRuns(10).size(), "only the first run is recorded");
        }
    }

    @Test
    void aRunThatWasKilledHardDoesNotBlockTheNextOne() throws Exception {
        var world = new World(root).withCli();
        world.environment.put("FAKE_UPLOAD_DELAY", "30");
        var source = world.source("docs");
        world.writeFile(source.resolve("a.txt"), "x");
        run(world, "add-source", source.toString(), "/my-files/Backup");
        var victim = DaemonProcess.start(world, true, "--run-once");
        DaemonProcess.awaitText(world.log, " upload ", Duration.ofSeconds(30));

        DaemonProcess.terminateGroupWith("KILL", victim.process); // a crash: no cleanup at all
        victim.process.waitFor();

        world.environment.remove("FAKE_UPLOAD_DELAY");
        var next = DaemonProcess.start(world, false, "--run-once");
        assertEquals(0, next.waitFor(), next.out() + next.err());
        assertTrue(next.out().contains("Done: 1 uploaded"), next.out());
        assertFalse(next.out().contains("already in progress"));
    }

    // ---- the launcher wrapper -----------------------------------------------------------

    /**
     * The wrapper (packaging/launcher.sh) sits in front of the real launcher. Here the "real launcher" is a
     * script that starts the daemon on this test's class path. The environment is hostile on purpose:
     * LC_ALL=C, under which a plain JVM mangles every non-ASCII file name.
     */
    @Test
    void theWrapperMakesNonAsciiNamesSurviveAHostileLocale() throws Exception {
        var world = new World(root).withCli();
        var source = world.source("docs");
        world.writeFile(source.resolve("café.txt"), "x");
        world.writeFile(source.resolve("日本語.txt"), "x");
        run(world, "add-source", source.toString(), "/my-files/Backup");

        var appHome = Files.createDirectories(root.resolve("app/bin"));
        var launcher = appHome.resolve("protonbackup");
        Files.writeString(launcher, "#!/bin/sh\nexec '" + Path.of(System.getProperty("java.home"), "bin", "java") + "' -cp '"
                + System.getProperty("java.class.path") + "' protonbackup.daemon.Main \"$@\"\n");
        Files.setPosixFilePermissions(launcher, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        var wrapperDir = Files.createDirectories(root.resolve("usr/bin"));
        var wrapper = wrapperDir.resolve("protonbackup");
        Files.copy(Path.of("..", "packaging", "launcher.sh"), wrapper);
        Files.setPosixFilePermissions(wrapper, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        world.environment.put("PROTONBACKUP_HOME", root.resolve("app").toString());

        var daemon = DaemonProcess.launch(world, List.of(wrapper.toString(), "--run-once"), "C");

        assertEquals(0, daemon.waitFor(), daemon.out() + daemon.err());
        assertEquals(Set.of("café.txt", "日本語.txt"), names(root.resolve("remote/my-files/Backup")));
    }

    @Test
    void theWrapperTellsTheDaemonItsOwnPathSoTheUnitsCallTheWrapper() throws Exception {
        var world = new World(root).withCli();
        var appHome = Files.createDirectories(root.resolve("app/bin"));
        var launcher = appHome.resolve("protonbackup");
        Files.writeString(launcher, "#!/bin/sh\nexec '" + Path.of(System.getProperty("java.home"), "bin", "java") + "' -cp '"
                + System.getProperty("java.class.path") + "' protonbackup.daemon.Main \"$@\"\n");
        Files.setPosixFilePermissions(launcher, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        var wrapperDir = Files.createDirectories(root.resolve("usr/bin"));
        var wrapper = wrapperDir.resolve("protonbackup");
        Files.copy(Path.of("..", "packaging", "launcher.sh"), wrapper);
        Files.setPosixFilePermissions(wrapper, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        world.environment.put("PROTONBACKUP_HOME", root.resolve("app").toString());
        // systemctl is not available to a test; point PATH at a folder with a harmless stand-in
        var bin = Files.createDirectories(root.resolve("fakebin"));
        var systemctl = bin.resolve("systemctl");
        Files.writeString(systemctl, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(systemctl, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        world.environment.put("PATH", bin + ":" + System.getenv("PATH"));

        var daemon = DaemonProcess.launch(world, List.of(wrapper.toString(), "install-units"), "C.UTF-8");

        assertEquals(0, daemon.waitFor(), daemon.out() + daemon.err());
        assertTrue(daemon.out().contains("Units written for " + wrapper), daemon.out());
        assertTrue(Files.readString(world.home.resolve(".config/systemd/user/protonbackup-sync.service")).contains("ExecStart=" + wrapper + " --run-once"));
    }
}
