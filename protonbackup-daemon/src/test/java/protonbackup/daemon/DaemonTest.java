package protonbackup.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import protonbackup.core.AppPaths;
import protonbackup.core.CancelToken;
import protonbackup.core.CliResult;
import protonbackup.core.Database;
import protonbackup.core.FileStatus;
import protonbackup.core.ScannedFile;
import protonbackup.core.Scanner;
import protonbackup.core.SyncLock;

/**
 * Program.cs had no C# tests; these cover every command, its output and its exit code, against a
 * temporary home folder, with fakes for systemctl, the Proton CLI and the network.
 */
class DaemonTest {

    private static final String VERSION_PAGE = """
            <h1>Proton Drive CLI 0.9.0</h1>
            <table><tr><td>linux/x64</td>
            <td><a href="https://proton.me/download/drive/cli/0.9.0/linux-x64/proton-drive">link</a></td></tr></table>
            """;

    @TempDir Path home;

    private AppPaths paths;
    private FakeSystem system;
    private CancelToken token;
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;
    private String stdin = "";
    private String pathVariable = "";
    private String versionPage = VERSION_PAGE;
    private boolean pageFetchFails;
    private final List<String> fetched = new ArrayList<>();
    private final List<String> downloaded = new ArrayList<>();

    @BeforeEach
    void setUp() {
        paths = AppPaths.forHome(home);
        system = new FakeSystem();
        token = new CancelToken();
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
    }

    private int run(String... args) {
        out.reset();
        err.reset();
        var environment = new Daemon.Environment(
                paths,
                system,
                url -> {
                    fetched.add(url);
                    if (pageFetchFails) throw new IOException("no route to host");
                    return versionPage;
                },
                (url, destination) -> {
                    downloaded.add(url);
                    Files.writeString(destination, "#!/bin/sh\necho ok\n");
                },
                duration -> {},
                new Scanner(Duration.ZERO),
                Duration.ZERO,
                "/usr/bin/protonbackup",
                pathVariable,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                new BufferedReader(new StringReader(stdin)));
        return new Daemon(environment).run(args, token);
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private void installFakeCli() throws IOException {
        Files.createDirectories(paths.binDir());
        Files.writeString(paths.binDir().resolve("proton-drive"), "fake");
    }

    private Path source(String name) throws IOException {
        return Files.createDirectories(home.resolve(name));
    }

    private Database database() {
        return new Database(paths.databasePath());
    }

    // ---- help ---------------------------------------------------------------------------

    @Test
    void noArgumentsPrintTheHelpAndSucceed() {
        assertEquals(0, run());

        assertTrue(out().startsWith("protonbackup\n  --run-once [--source ID] [--force]\n"));
        assertTrue(out().contains("--cleanup [--yes]          Removes database, settings, CLI and units"));
        assertEquals("", err());
    }

    @Test
    void anUnknownCommandPrintsTheHelpToo() {
        assertEquals(0, run("frobnicate"));

        assertTrue(out().startsWith("protonbackup\n"));
    }

    @Test
    void helpDoesNotCreateAnyFolders() {
        run();

        assertFalse(Files.exists(paths.dataDir()), "the original created the database even for --help");
    }

    @Test
    void everyCommandOfTheOriginalIsListedInTheHelp() {
        run("--help");

        for (var command : List.of("--run-once", "add-source", "remove-source", "list-sources", "force-all", "install-units",
                "enable-timer", "disable-timer", "set-interval", "start", "stop", "status", "check-update", "update-cli",
                "rollback-cli", "--cleanup")) {
            assertTrue(out().contains(command), command);
        }
    }

    // ---- sources ------------------------------------------------------------------------

    @Test
    void addSourceAndListSources() throws Exception {
        var local = source("docs");

        assertEquals(0, run("add-source", local.toString(), "/my-files/Backup"));
        assertEquals("Source 1: " + local + " -> /my-files/Backup\n", out());

        assertEquals(0, run("list-sources"));
        assertEquals("1\ton\t" + local + " -> /my-files/Backup\n", out());
    }

    @Test
    void addSourceRequiresTwoArguments() {
        assertEquals(2, run("add-source", "onlyone"));

        assertTrue(err().contains("Usage: add-source"));
    }

    @Test
    void removeSource() throws Exception {
        run("add-source", source("docs").toString(), "/my-files/Backup");

        assertEquals(0, run("remove-source", "1"));
        run("list-sources");

        assertEquals("", out());
    }

    @Test
    void removeSourceNeedsANumber() {
        assertEquals(2, run("remove-source"));
        assertEquals(2, run("remove-source", "abc"));
        assertTrue(err().contains("Usage: remove-source <id>"));
    }

    @Test
    void forceAllQueuesFilesAgain() throws Exception {
        run("add-source", source("docs").toString(), "/my-files/Backup");
        try (var db = database()) {
            db.upsertPending(1, List.of(new ScannedFile("a.txt", 1, 1, null)));
            db.markSynced(1, List.of("a.txt"));
        }

        assertEquals(0, run("force-all"));

        assertTrue(out().contains("1 file(s) queued again."));
        assertTrue(out().contains("Run a sync with: protonbackup --run-once"));
    }

    // ---- systemd ------------------------------------------------------------------------

    @Test
    void installUnitsWritesTheUnitsAndReportsTheExecutable() throws Exception {
        assertEquals(0, run("install-units", "20"));

        assertTrue(out().contains("Units written for /usr/bin/protonbackup"));
        assertTrue(out().contains("Turn on with: protonbackup enable-timer"));
        var timer = Files.readString(paths.userUnitDir().resolve("protonbackup-sync.timer"));
        assertTrue(timer.contains("OnUnitActiveSec=20min"));
        assertTrue(Files.readString(paths.userUnitDir().resolve("protonbackup-sync.service")).contains("ExecStart=/usr/bin/protonbackup --run-once"));
        assertEquals(List.of("--user daemon-reload"), system.systemctlCalls);
    }

    @Test
    void installUnitsWithoutANumberUsesTheDefaultInterval() throws Exception {
        assertEquals(0, run("install-units"));

        assertTrue(Files.readString(paths.userUnitDir().resolve("protonbackup-sync.timer")).contains("OnUnitActiveSec=30min"));
    }

    @Test
    void installUnitsIgnoresAnInvalidInterval() throws Exception {
        assertEquals(0, run("install-units", "-5"));
        assertTrue(Files.readString(paths.userUnitDir().resolve("protonbackup-sync.timer")).contains("OnUnitActiveSec=30min"));

        assertEquals(0, run("install-units", "abc"));
        assertTrue(Files.readString(paths.userUnitDir().resolve("protonbackup-sync.timer")).contains("OnUnitActiveSec=30min"));
    }

    @Test
    void enableAndDisableTheTimer() {
        assertEquals(0, run("enable-timer"));
        assertEquals("Timer is on.\n", out());
        assertEquals(0, run("disable-timer"));
        assertEquals("Timer is off.\n", out());
        assertEquals(List.of("--user enable --now protonbackup-sync.timer", "--user disable --now protonbackup-sync.timer"), system.systemctlCalls);
    }

    @Test
    void aFailingSystemctlPrintsItsOutputAndFails() {
        system.systemctlResults.put("enable", new CliResult(1, "", "Failed to enable unit: Unit file protonbackup-sync.timer does not exist.\n"));

        assertEquals(1, run("enable-timer"));

        assertEquals("Failed to enable unit: Unit file protonbackup-sync.timer does not exist.\n", out());
    }

    @Test
    void setIntervalWritesTheDropInAndRemembersTheValue() throws Exception {
        assertEquals(0, run("set-interval", "45"));

        assertEquals("Interval set to 45 minutes.\n", out());
        assertTrue(Files.readString(paths.userUnitDir().resolve("protonbackup-sync.timer.d/interval.conf")).contains("OnUnitActiveSec=45min"));
        try (var db = database()) {
            assertEquals("45", db.getSetting("interval_minutes"));
        }
    }

    @Test
    void setIntervalWithoutANumberFailsWithUsage() {
        assertEquals(2, run("set-interval"));
        assertEquals(2, run("set-interval", "0"));
        assertTrue(err().contains("Usage: set-interval <minutes>"));
    }

    @Test
    void startReportsAnAlreadyRunningRunWithoutStartingAnother() {
        system.syncRunning = true;

        assertEquals(0, run("start"));

        assertEquals("A run is already in progress.\n", out());
        assertTrue(system.systemctlCalls.stream().noneMatch(c -> c.contains("start")));
    }

    @Test
    void startBeginsARunWithoutBlocking() {
        assertEquals(0, run("start"));

        assertEquals("Run started.\n", out());
        assertTrue(system.systemctlCalls.contains("--user start --no-block protonbackup-sync.service"));
    }

    @Test
    void startWithASourceIdUsesTheTemplateUnit() {
        assertEquals(0, run("start", "7"));

        assertTrue(system.systemctlCalls.contains("--user start --no-block protonbackup-sync@7.service"));
    }

    @Test
    void stopStopsTheRun() {
        assertEquals(0, run("stop"));

        assertEquals("Run stopped; the next one continues where this one left off.\n", out());
        assertTrue(system.systemctlCalls.contains("--user stop protonbackup-sync.service"));
    }

    @Test
    void aMissingSystemctlIsReportedNotACrash() {
        system.systemctlMissing = true;

        assertEquals(1, run("enable-timer"));

        assertTrue(err().startsWith("protonbackup: "));
        assertTrue(err().contains("systemctl"));
    }

    // ---- status -------------------------------------------------------------------------

    @Test
    void statusWithoutACli() throws Exception {
        run("add-source", source("docs").toString(), "/my-files/Backup");

        assertEquals(0, run("status"));

        assertTrue(out().contains("CLI:      not found"));
        assertTrue(out().contains("Database: " + paths.databasePath()));
        assertTrue(out().contains("Timer:    off"));
        assertTrue(out().contains("Run:      not active"));
        assertTrue(out().contains("(on)"));
        assertFalse(out().contains("Version:"));
    }

    @Test
    void statusWithACliShowsVersionAndSession() throws Exception {
        installFakeCli();
        system.timerEnabled = true;
        system.syncRunning = true;

        assertEquals(0, run("status"));

        assertTrue(out().contains("CLI:      " + paths.binDir().resolve("proton-drive")));
        assertTrue(out().contains("Version:  Proton Drive CLI cli-drive@0.8.0+06e8c605"));
        assertTrue(out().contains("Session:  Active"));
        assertTrue(out().contains("Timer:    on"));
        assertTrue(out().contains("Run:      in progress"));
        assertTrue(out().contains("NEXT LEFT"), "the timer description follows after a blank line");
    }

    @Test
    void statusShowsAnExpiredSession() throws Exception {
        installFakeCli();
        system.loggedIn = false;

        run("status");

        assertTrue(out().contains("Session:  Expired"));
    }

    // ---- --run-once ---------------------------------------------------------------------

    @Test
    void runOnceReportsAMissingCli() {
        assertEquals(2, run("--run-once"));

        assertTrue(err().contains("The proton-drive CLI was not found. Put it in " + paths.binDir() + " or on PATH."));
    }

    @Test
    void runOnceSyncsAndReturnsZero() throws Exception {
        installFakeCli();
        var local = source("docs");
        Files.writeString(local.resolve("a.txt"), "hello");
        run("add-source", local.toString(), "/my-files/Backup");

        assertEquals(0, run("--run-once"));

        assertTrue(out().contains("Done: 1 uploaded, 0 failed, 0 gone locally."));
        assertEquals(5L, system.remote.get("/my-files/Backup").get("a.txt"));
    }

    @Test
    void runOnceReturnsOneWhenAFileFailed() throws Exception {
        installFakeCli();
        var local = source("docs");
        Files.writeString(local.resolve("a.txt"), "hello");
        run("add-source", local.toString(), "/my-files/Backup");
        system.silentlySkipped.add("a.txt");

        assertEquals(1, run("--run-once"));

        assertTrue(out().contains("Done: 0 uploaded, 1 failed"));
    }

    @Test
    void runOnceReturnsZeroWhenCancelled() throws Exception {
        installFakeCli();
        var local = source("docs");
        Files.writeString(Files.createDirectories(local.resolve("one")).resolve("a.txt"), "x");
        Files.writeString(Files.createDirectories(local.resolve("two")).resolve("b.txt"), "x");
        run("add-source", local.toString(), "/my-files/Backup");
        system.onUpload = token::cancel;

        assertEquals(0, run("--run-once"));

        assertTrue(out().contains("Cancelled after 1 file(s)"));
        try (var db = database()) {
            assertEquals("cancelled", db.getRecentRuns(1).get(0).result());
        }
        assertTrue(fetched.isEmpty(), "no update check after a cancelled run");
    }

    @Test
    void runOnceIsBlockedWhileAnotherRunHoldsTheLock() throws Exception {
        installFakeCli();
        try (var held = SyncLock.tryAcquire(paths)) {
            assertNotNull(held);

            assertEquals(0, run("--run-once"));
        }

        assertTrue(out().contains("A run is already in progress; this one is skipped."));
    }

    @Test
    void runOnceWithAnExpiredSessionTellsTheUserToSignInAndStillSucceeds() throws Exception {
        installFakeCli();
        system.loggedIn = false;

        assertEquals(0, run("--run-once"));

        assertTrue(out().contains("Session expired. Sign in again with: proton-drive auth login"));
    }

    @Test
    void runOnceForceQueuesEverythingAgain() throws Exception {
        installFakeCli();
        var local = source("docs");
        run("add-source", local.toString(), "/my-files/Backup");
        try (var db = database()) {
            db.upsertPending(1, List.of(new ScannedFile("a.txt", 1, 1, null)));
            db.markSynced(1, List.of("a.txt"));
        }

        assertEquals(0, run("--run-once", "--force"));

        assertTrue(out().contains("Force: 1 file(s) queued again."));
        try (var db = database()) {
            assertEquals(FileStatus.MISSING, db.getTrackedFiles(1).get("a.txt").status(), "the file is not on disk, so it ends up missing");
        }
    }

    @Test
    void runOnceSourceRestrictsTheRunToOneSource() throws Exception {
        installFakeCli();
        var first = source("first");
        var second = source("second");
        Files.writeString(first.resolve("a.txt"), "x");
        Files.writeString(second.resolve("b.txt"), "x");
        run("add-source", first.toString(), "/my-files/First");
        run("add-source", second.toString(), "/my-files/Second");

        assertEquals(0, run("--run-once", "--source", "2"));

        assertTrue(system.remote.containsKey("/my-files/Second"));
        assertFalse(system.remote.containsKey("/my-files/First"));
    }

    @Test
    void runOnceWithAnInvalidSourceIdIsAUsageErrorNotAFullSync() throws Exception {
        installFakeCli();
        var local = source("docs");
        Files.writeString(local.resolve("a.txt"), "x");
        run("add-source", local.toString(), "/my-files/Backup");

        assertEquals(2, run("--run-once", "--source", "not-a-number"));

        assertTrue(err().contains("ID must be a number"));
        assertFalse(system.remote.containsKey("/my-files/Backup"), "nothing may have been synced");
    }

    @Test
    void runOnceFailureIsReturnedAsOneAndRecorded() throws Exception {
        installFakeCli();
        var gone = source("gone");
        run("add-source", gone.toString(), "/my-files/Gone");
        Files.delete(gone);

        assertEquals(1, run("--run-once"));

        assertTrue(out().contains("Run failed: "));
        try (var db = database()) {
            assertEquals("error", db.getRecentRuns(1).get(0).result());
        }
    }

    // ---- the update check after a run ---------------------------------------------------

    @Test
    void aDueUpdateCheckRunsAfterTheSyncAndAnnouncesANewerVersion() throws Exception {
        installFakeCli();

        assertEquals(0, run("--run-once"));

        assertEquals(List.of("https://proton.me/download/drive/cli/index.html"), fetched);
        assertTrue(out().contains("A newer Proton Drive CLI is available: 0.9.0 (currently 0.8.0)."));
    }

    @Test
    void theUpdateCheckHappensOnlyOnceADay() throws Exception {
        installFakeCli();
        run("--run-once");
        fetched.clear();

        run("--run-once");

        assertTrue(fetched.isEmpty());
    }

    @Test
    void aFailingUpdateCheckNeverChangesTheOutcome() throws Exception {
        installFakeCli();
        pageFetchFails = true;

        assertEquals(0, run("--run-once"));

        assertTrue(out().contains("Update check failed: no route to host"));
    }

    // ---- CLI management -----------------------------------------------------------------

    @Test
    void checkUpdateReportsInstalledAvailableAndWhetherThereIsANewerVersion() throws Exception {
        installFakeCli();

        assertEquals(0, run("check-update"));

        assertTrue(out().contains("Installed: 0.8.0"));
        assertTrue(out().contains("Available: 0.9.0"));
        assertTrue(out().contains("A newer version is available."));
    }

    @Test
    void checkUpdateWithoutAnInstalledCliSaysNone() throws Exception {
        assertEquals(0, run("check-update"));

        assertTrue(out().contains("Installed: none"));
        assertTrue(out().contains("You are up to date."));
    }

    @Test
    void checkUpdateReportsAnUnreadablePage() throws Exception {
        pageFetchFails = true;

        assertEquals(1, run("check-update"));

        assertTrue(out().contains("The version page could not be read."));
    }

    @Test
    void updateCliRefusesAMissingChecksumUnlessAllowed() throws Exception {
        assertEquals(1, run("update-cli"));
        assertTrue(out().contains("No checksum is listed for linux-x64"));
        assertTrue(downloaded.isEmpty());

        assertEquals(0, run("update-cli", "--allow-missing-checksum"));
        assertTrue(out().contains("CLI updated to 0.9.0."));
        assertTrue(Files.isRegularFile(paths.binDir().resolve("proton-drive")));
    }

    @Test
    void updateCliReportsAnUnreadablePageOnStderr() throws Exception {
        pageFetchFails = true;

        assertEquals(1, run("update-cli"));

        assertTrue(err().contains("The version page could not be read."));
    }

    @Test
    void rollbackCliRestoresThePreviousVersion() throws Exception {
        Files.createDirectories(paths.binDir());
        Files.writeString(paths.binDir().resolve("proton-drive"), "current");
        Files.writeString(paths.binDir().resolve("proton-drive.previous"), "previous");

        assertEquals(0, run("rollback-cli"));

        assertEquals("The previous version was restored.\n", out());
        assertEquals("previous", Files.readString(paths.binDir().resolve("proton-drive")));
    }

    @Test
    void rollbackCliFailsWhenNothingWasKept() {
        assertEquals(1, run("rollback-cli"));

        assertEquals("No previous version was kept.\n", out());
    }

    // ---- --cleanup ----------------------------------------------------------------------

    @Test
    void cleanupAbortsWithoutConfirmation() throws Exception {
        run("add-source", source("docs").toString(), "/my-files/Backup");
        stdin = "n\n";

        assertEquals(1, run("--cleanup"));

        assertTrue(out().contains("Cancelled."));
        assertTrue(Files.exists(paths.dataDir()), "nothing may be removed");
        assertTrue(system.systemctlCalls.stream().noneMatch(c -> c.contains("disable")));
    }

    @Test
    void cleanupAbortsOnEndOfInput() throws Exception {
        run("add-source", source("docs").toString(), "/my-files/Backup");
        stdin = "";

        assertEquals(1, run("--cleanup"));

        assertTrue(Files.exists(paths.dataDir()));
    }

    @Test
    void cleanupRemovesEverythingAfterAYes() throws Exception {
        installFakeCli();
        run("add-source", source("docs").toString(), "/my-files/Backup");
        run("install-units");
        stdin = "yes\n";

        assertEquals(0, run("--cleanup"));

        assertTrue(out().contains("This deletes the database, the settings, the downloaded CLI and the systemd units."));
        assertTrue(out().contains("  ok   Signed out of Proton"));
        assertTrue(out().contains("Done. Remove the package with: sudo apt remove protonbackup"));
        assertFalse(Files.exists(paths.dataDir()));
        assertFalse(Files.exists(paths.userUnitDir().resolve("protonbackup-sync.timer")));
        assertTrue(system.systemctlCalls.contains("--user disable --now protonbackup-sync.timer"));
        assertTrue(system.cliCalls.contains(List.of("auth", "logout")));
    }

    @Test
    void cleanupYesSkipsTheQuestionAndSkipsSignOutWithoutACli() {
        assertEquals(0, run("--cleanup", "--yes"));

        assertTrue(out().contains("Sign-out skipped (the CLI was not found)"));
        assertTrue(system.systemctlCalls.contains("--user disable --now protonbackup-sync.timer"));
    }

    @Test
    void cleanupReturnsOneWhenAStepFailed() {
        system.systemctlResults.put("disable", new CliResult(1, "", "Failed to disable unit\n"));

        assertEquals(1, run("--cleanup", "--yes"));

        assertTrue(out().contains("failed  Timer turned off (Failed to disable unit)"));
    }

    @Test
    void cleanupLeavesTheAppUnableToRecreateItsFolders() throws Exception {
        run("add-source", source("docs").toString(), "/my-files/Backup");

        run("--cleanup", "--yes");

        assertFalse(Files.exists(paths.dataDir()), "the open database must not have brought the folder back");
        assertFalse(Files.exists(paths.databasePath()));
    }

    // ---- failures -----------------------------------------------------------------------

    @Test
    void aCorruptDatabaseIsReportedNotAStackTrace() throws Exception {
        Files.createDirectories(paths.dataDir());
        Files.writeString(paths.databasePath(), "this is not a database, it is long enough to be mistaken for one ........");

        assertEquals(1, run("list-sources"));

        assertTrue(err().startsWith("protonbackup: "), err());
    }
}
