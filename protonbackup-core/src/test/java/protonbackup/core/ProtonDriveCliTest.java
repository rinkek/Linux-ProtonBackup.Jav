package protonbackup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ProtonDriveCli had no C# tests: it was verified by hand against the real CLI. A scripted
 * runner makes the documented behavior testable.
 */
class ProtonDriveCliTest {

    private static final String CLI = "/usr/bin/proton-drive";

    /** Replays one scripted result per call and records the calls. */
    private static final class ScriptedRunner implements CommandRunner {
        final LinkedList<CliResult> results = new LinkedList<>();
        final List<List<String>> calls = new ArrayList<>();

        ScriptedRunner(CliResult... scripted) {
            results.addAll(List.of(scripted));
        }

        @Override
        public CliResult run(String executable, List<String> arguments) {
            assertEquals(CLI, executable);
            calls.add(List.copyOf(arguments));
            if (results.isEmpty()) throw new AssertionError("ran out of scripted results");
            return results.removeFirst();
        }
    }

    private static CliResult ok(String out) {
        return new CliResult(0, out, "");
    }

    private static CliResult fail(String err) {
        return new CliResult(1, "", err);
    }

    private static ProtonDriveCli cli(ScriptedRunner runner, List<Duration> sleeps) {
        return new ProtonDriveCli(CLI, runner, sleeps::add);
    }

    private static ProtonDriveCli cli(ScriptedRunner runner) {
        return cli(runner, new ArrayList<>());
    }

    // ---- locate -------------------------------------------------------------------------

    @Test
    void locatePrefersTheConfiguredPath(@TempDir Path home) throws Exception {
        var configured = Files.writeString(Files.createDirectories(home.resolve("configured")).resolve("proton-drive"), "x");

        assertEquals(configured.toString(), ProtonDriveCli.locate(configured.toString(), AppPaths.forHome(home), ""));
    }

    @Test
    void locateFallsBackToTheAppDataDir(@TempDir Path home) throws Exception {
        var paths = AppPaths.forHome(home);
        Files.createDirectories(paths.binDir());
        var inData = Files.writeString(paths.binDir().resolve("proton-drive"), "x");

        assertEquals(inData.toString(), ProtonDriveCli.locate(null, paths, ""));
    }

    @Test
    void locateFallsBackToPath(@TempDir Path home) throws Exception {
        var onPath = Files.createDirectories(home.resolve("on-path"));
        Files.writeString(onPath.resolve("proton-drive"), "x");

        var found = ProtonDriveCli.locate(null, AppPaths.forHome(home), "/does/not/exist::" + onPath);

        assertEquals(onPath.resolve("proton-drive").toString(), found);
    }

    @Test
    void locateReturnsNullWhenNowhereIsFound(@TempDir Path home) {
        assertNull(ProtonDriveCli.locate(null, AppPaths.forHome(home), "/does/not/exist"));
        assertNull(ProtonDriveCli.locate(null, AppPaths.forHome(home), null));
    }

    @Test
    void locateIgnoresAConfiguredPathThatDoesNotExist(@TempDir Path home) {
        assertNull(ProtonDriveCli.locate(home.resolve("missing/proton-drive").toString(), AppPaths.forHome(home), "/does/not/exist"));
        assertNull(ProtonDriveCli.locate("   ", AppPaths.forHome(home), ""));
    }

    @Test
    void locateIgnoresADirectoryNamedProtonDrive(@TempDir Path home) throws Exception {
        var paths = AppPaths.forHome(home);
        Files.createDirectories(paths.binDir().resolve("proton-drive"));

        assertNull(ProtonDriveCli.locate(null, paths, ""));
    }

    // ---- getVersion ---------------------------------------------------------------------

    @Test
    void getVersionTakesTheFirstLineTrimmed() throws Exception {
        var runner = new ScriptedRunner(ok("Proton Drive CLI cli-drive@0.8.0+06e8c605\nmore\n"));

        assertEquals("Proton Drive CLI cli-drive@0.8.0+06e8c605", cli(runner).getVersion());
    }

    @Test
    void getVersionFallsBackToUnknownOnEmptyOutput() throws Exception {
        assertEquals("unknown", cli(new ScriptedRunner(fail("boom"))).getVersion());
    }

    // ---- checkSession: three attempts with backoff --------------------------------------

    @Test
    void checkSessionSucceedsImmediatelyWhenTheFirstAttemptWorks() throws Exception {
        var runner = new ScriptedRunner(ok("[]"));
        var sleeps = new ArrayList<Duration>();

        assertEquals(SessionState.ACTIVE, cli(runner, sleeps).checkSession());
        assertEquals(1, runner.calls.size());
        assertTrue(sleeps.isEmpty());
        assertEquals(List.of("filesystem", "list", "/my-files", "--json"), runner.calls.get(0));
    }

    @Test
    void checkSessionRetriesWithBackoffBeforeGivingUp() throws Exception {
        var runner = new ScriptedRunner(
                fail("You need to login first"), fail("You need to login first"), fail("You need to login first"));
        var sleeps = new ArrayList<Duration>();

        assertEquals(SessionState.EXPIRED, cli(runner, sleeps).checkSession());
        assertEquals(3, runner.calls.size());
        assertEquals(List.of(Duration.ofSeconds(2), Duration.ofSeconds(4)), sleeps); // none after the last attempt
    }

    /** The scenario that motivated the retry: a transient false "not logged in" right after heavy traffic. */
    @Test
    void checkSessionRecoversIfALaterAttemptSucceeds() throws Exception {
        var runner = new ScriptedRunner(fail("You need to login first"), ok("[]"));

        assertEquals(SessionState.ACTIVE, cli(runner).checkSession());
        assertEquals(2, runner.calls.size());
    }

    @Test
    void checkSessionReportsUnknownForANonLoginFailure() throws Exception {
        var runner = new ScriptedRunner(fail("boom"), fail("boom"), fail("boom"));

        assertEquals(SessionState.UNKNOWN, cli(runner).checkSession());
    }

    // ---- list: tolerant of a failed or malformed fetch ----------------------------------

    @Test
    void listReturnsNullWhenTheCallFails() throws Exception {
        assertNull(cli(new ScriptedRunner(fail("boom"))).list("/my-files/A"));
    }

    @Test
    void listReturnsNullOnMalformedJsonInsteadOfThrowing() throws Exception {
        assertNull(cli(new ScriptedRunner(ok("not json"))).list("/my-files/A"));
    }

    @Test
    void listReturnsAnEmptyListForAGenuinelyEmptyFolder() throws Exception {
        assertEquals(List.of(), cli(new ScriptedRunner(ok("[]"))).list("/my-files/A"));
    }

    @Test
    void listParsesTheFolderEntries() throws Exception {
        var runner = new ScriptedRunner(ok("[{\"uid\":\"u\",\"type\":\"file\",\"name\":{\"ok\":true,\"value\":\"a.txt\"}}]"));

        var nodes = cli(runner).list("/my-files/A");

        assertEquals("a.txt", nodes.get(0).fileName());
        assertEquals(List.of("filesystem", "list", "/my-files/A", "--json"), runner.calls.get(0));
    }

    // ---- ensureFolder: top-down creation ------------------------------------------------

    @Test
    void ensureFolderDoesNothingWhenItAlreadyExists() throws Exception {
        var runner = new ScriptedRunner(ok("{}"));

        cli(runner).ensureFolder("/my-files/A");

        assertEquals(List.of(List.of("filesystem", "info", "/my-files/A", "--json")), runner.calls);
    }

    /** Recursion walks up to "/my-files", the account root, which always exists: that is where it stops. */
    @Test
    void ensureFolderCreatesMissingParentsTopDown() throws Exception {
        var runner = new ScriptedRunner(
                fail("not found"), // info /my-files/A/B
                fail("not found"), // info /my-files/A
                ok("{}"), // info /my-files: exists, recursion stops
                ok(""), // create-folder /my-files A
                ok("")); // create-folder /my-files/A B

        cli(runner).ensureFolder("/my-files/A/B");

        assertEquals(List.of(
                List.of("filesystem", "info", "/my-files/A/B", "--json"),
                List.of("filesystem", "info", "/my-files/A", "--json"),
                List.of("filesystem", "info", "/my-files", "--json"),
                List.of("filesystem", "create-folder", "/my-files", "A"),
                List.of("filesystem", "create-folder", "/my-files/A", "B")), runner.calls);
    }

    @Test
    void ensureFolderToleratesAnAlreadyExistsRace() throws Exception {
        var runner = new ScriptedRunner(
                fail("not found"), ok("{}"), fail("A file or folder with that name already exists"));

        cli(runner).ensureFolder("/my-files/A"); // must not throw
    }

    @Test
    void ensureFolderThrowsOnAGenuineFailure() {
        var runner = new ScriptedRunner(fail("not found"), ok("{}"), fail("permission denied"));

        var error = assertThrows(IOException.class, () -> cli(runner).ensureFolder("/my-files/A"));
        assertTrue(error.getMessage().contains("permission denied"));
    }

    // ---- upload: the mandatory conflict-strategy flags ----------------------------------

    @Test
    void uploadAlwaysIncludesTheMandatoryStrategyFlags() throws Exception {
        var runner = new ScriptedRunner(ok("Transfer summary: 2 items"));

        cli(runner).upload(List.of("/tmp/a.txt", "/tmp/b.txt"), "/my-files/Backup");

        assertEquals(List.of(List.of(
                "filesystem", "upload",
                "--file-conflict-strategy", "create-new-revision",
                "--folder-conflict-strategy", "merge",
                "--skip-thumbnails",
                "/tmp/a.txt", "/tmp/b.txt", "/my-files/Backup")), runner.calls);
    }

    @Test
    void uploadNeverAsksTheCliToReplaceARemoteFile() throws Exception {
        var runner = new ScriptedRunner(ok(""));

        cli(runner).upload(List.of("/tmp/a.txt"), "/my-files/Backup");

        assertTrue(runner.calls.get(0).stream().noneMatch(argument -> argument.equals("replace")));
    }
}
