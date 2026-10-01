package protonbackup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SyncEngine had no C# tests despite being "probably the most critical part of the functional
 * migration". These come from the Python port's tests, which were written against the C# behavior,
 * plus cases for the Java-specific paths (interruption, cancel token) and for data safety.
 */
class SyncEngineTest {

    @TempDir Path home;

    private AppPaths paths;
    private Database database;
    private FakeDriveClient cli;
    private final List<String> log = new ArrayList<>();
    private CancelToken token;

    @BeforeEach
    void setUp() {
        paths = AppPaths.forHome(home);
        database = new Database(home.resolve("test.db"));
        cli = new FakeDriveClient();
        token = new CancelToken();
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    // settle time 0: the test files are written moments before scanning, and the default 5 s would
    // (correctly, see ScannerTest) leave them all for the next round.
    private SyncEngine engine() {
        return new SyncEngine(paths, database, cli, log::add, new Scanner(Duration.ZERO), Duration.ZERO);
    }

    private SyncResult run() throws IOException {
        return engine().runOnce(null, token);
    }

    private long source(String name, String remote) throws IOException {
        var local = Files.createDirectories(home.resolve(name));
        return database.addSource(local.toString(), remote);
    }

    private Path local(String name) {
        return home.resolve(name);
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    // ---- the golden path ----------------------------------------------------------------

    @Test
    void uploadsNewFilesAndMarksThemSynced() throws Exception {
        var source = source("src", "/my-files/Backup");
        write(local("src").resolve("a.txt"), "hello");

        var result = run();

        assertEquals(new SyncResult(1, 0, 0, "ok"), result);
        assertEquals(FileStatus.SYNCED, database.getTrackedFiles(source).get("a.txt").status());
        assertEquals(5L, cli.remoteFiles.get("/my-files/Backup").get("a.txt"));
    }

    @Test
    void aRunIsRecordedWithItsCounts() throws Exception {
        source("src", "/my-files/Backup");
        write(local("src").resolve("a.txt"), "hello");

        run();

        var runs = database.getRecentRuns(10);
        assertEquals(1, runs.size());
        assertEquals("ok", runs.get(0).result());
        assertEquals(1, runs.get(0).uploaded());
        assertNotNull(runs.get(0).finishedUtc());
        assertTrue(log.contains("Done: 1 uploaded, 0 failed, 0 gone locally."), log.toString());
    }

    @Test
    void aSecondRunWithNothingChangedUploadsNothing() throws Exception {
        source("src", "/my-files/Backup");
        write(local("src").resolve("a.txt"), "hello");
        run();

        var result = run();

        assertEquals(0, result.uploaded());
        assertEquals(1, cli.uploadCalls.size(), "only the first run uploaded anything");
        assertTrue(log.contains("  Nothing to do."));
    }

    @Test
    void aChangedFileIsUploadedAgain() throws Exception {
        source("src", "/my-files/Backup");
        var file = local("src").resolve("a.txt");
        write(file, "v1");
        run();

        write(file, "v1 longer");
        var result = run();

        assertEquals(1, result.uploaded());
        assertEquals((long) "v1 longer".length(), cli.remoteFiles.get("/my-files/Backup").get("a.txt"));
    }

    @Test
    void anEmptySetOfSourcesIsAnOkRunThatDoesNothing() throws Exception {
        var result = run();

        assertEquals(new SyncResult(0, 0, 0, "ok"), result);
        assertEquals(1, database.getRecentRuns(10).size());
    }

    @Test
    void disabledSourcesAreSkipped() throws Exception {
        var source = source("src", "/my-files/Backup");
        write(local("src").resolve("a.txt"), "x");
        database.setSourceEnabled(source, false);

        var result = run();

        assertEquals(0, result.uploaded());
        assertTrue(cli.uploadCalls.isEmpty());
        assertTrue(database.getTrackedFiles(source).isEmpty());
    }

    // ---- locking ------------------------------------------------------------------------

    @Test
    void aSecondConcurrentRunIsBlockedAndRecordsNoRun() throws Exception {
        source("src", "/my-files/Backup");
        write(local("src").resolve("a.txt"), "x");

        try (var held = SyncLock.tryAcquire(paths)) {
            assertNotNull(held);
            var result = run();

            assertEquals(SyncResult.blocked("already-running"), result);
        }
        assertTrue(database.getRecentRuns(10).isEmpty());
        assertTrue(cli.uploadCalls.isEmpty());
        assertTrue(log.contains("A run is already in progress; this one is skipped."));
    }

    @Test
    void theLockIsReleasedAfterARunSoTheNextOneCanStart() throws Exception {
        run();

        try (var lock = SyncLock.tryAcquire(paths)) {
            assertNotNull(lock);
        }
    }

    @Test
    void theLockIsReleasedEvenWhenTheRunFails() throws Exception {
        source("src", "/my-files/Backup");
        write(local("src").resolve("a.txt"), "x");
        cli.ensureFolderFailure = new IOException("boom");
        assertThrows(IOException.class, this::run);

        try (var lock = SyncLock.tryAcquire(paths)) {
            assertNotNull(lock);
        }
    }

    // ---- session handling ---------------------------------------------------------------

    @Test
    void anExpiredSessionPausesWithoutRecordingARun() throws Exception {
        source("src", "/my-files/Backup");
        write(local("src").resolve("a.txt"), "x");
        cli.session = SessionState.EXPIRED;

        var result = run();

        assertEquals("session-expired", result.outcome());
        assertTrue(database.getRecentRuns(10).isEmpty());
        assertTrue(cli.uploadCalls.isEmpty());
        assertTrue(log.contains("Session expired. Sign in again with: proton-drive auth login"));
    }

    @Test
    void anUnknownSessionStateAlsoPauses() throws Exception {
        source("src", "/my-files/Backup");
        cli.session = SessionState.UNKNOWN;

        var result = run();

        assertEquals("session-unknown", result.outcome());
        assertTrue(log.contains("Cannot check the session; the run is paused."));
        assertTrue(database.getRecentRuns(10).isEmpty());
    }

    // ---- locally deleted files: never touched remotely ----------------------------------

    @Test
    void locallyDeletedFilesAreMarkedMissingAndNeverRemovedRemotely() throws Exception {
        var source = source("src", "/my-files/Backup");
        var file = local("src").resolve("a.txt");
        write(file, "hello");
        run();

        Files.delete(file);
        var result = run();

        assertEquals(1, result.missing());
        assertEquals(FileStatus.MISSING, database.getTrackedFiles(source).get("a.txt").status());
        assertEquals(5L, cli.remoteFiles.get("/my-files/Backup").get("a.txt"), "still on Proton: the fake has no delete");
        assertTrue(log.contains("  1 file(s) gone locally; they stay on Proton."));
    }

    // ---- batching and grouping by directory ---------------------------------------------

    @Test
    void batchesLargerThanTheBatchSizeAreSplitIntoSeveralUploads() throws Exception {
        source("src", "/my-files/Backup");
        var count = SyncEngine.BATCH_SIZE + 10;
        for (var i = 0; i < count; i++) write(local("src").resolve("f" + i + ".txt"), "x");

        var result = run();

        assertEquals(count, result.uploaded());
        assertEquals(2, cli.uploadCalls.size());
        assertEquals(SyncEngine.BATCH_SIZE, cli.uploadCalls.get(0).size());
        assertEquals(10, cli.uploadCalls.get(1).size());
    }

    @Test
    void filesInDifferentDirectoriesGetSeparateFoldersAndUploads() throws Exception {
        source("src", "/my-files/Backup");
        write(local("src").resolve("docs/a.txt"), "a");
        write(local("src").resolve("photos/b.txt"), "b");

        run();

        assertEquals(Set.of("/my-files/Backup/docs", "/my-files/Backup/photos"), Set.copyOf(cli.ensuredFolders));
        assertEquals(Set.of("/my-files/Backup/docs", "/my-files/Backup/photos"), Set.copyOf(cli.uploadParents));
    }

    @Test
    void groupByDirectoryDoesNotSplitADirectoryWhenTheInputIsNotPresorted() {
        var files = List.of(
                new TrackedFile("b/x.txt", 1, 1, FileStatus.PENDING),
                new TrackedFile("a/y.txt", 1, 1, FileStatus.PENDING),
                new TrackedFile("b/z.txt", 1, 1, FileStatus.PENDING));

        var groups = SyncEngine.groupByDirectory(files);

        assertEquals(List.of("b", "a"), List.copyOf(groups.keySet()), "order of first occurrence");
        assertEquals(List.of("b/x.txt", "b/z.txt"), groups.get("b").stream().map(TrackedFile::relativePath).toList());
    }

    @Test
    void sameNamedFilesInDifferentDirectoriesAreConfirmedPerFolder() throws Exception {
        var source = source("src", "/my-files/Backup");
        write(local("src").resolve("a/same.txt"), "from a");
        write(local("src").resolve("b/same.txt"), "from b!");

        var result = run();

        assertEquals(2, result.uploaded());
        assertEquals(6L, cli.remoteFiles.get("/my-files/Backup/a").get("same.txt"));
        assertEquals(7L, cli.remoteFiles.get("/my-files/Backup/b").get("same.txt"));
        assertEquals(Map.of("synced", 2), database.getStatusCounts(source));
    }

    @Test
    void awkwardFileNamesTravelUnchanged() throws Exception {
        source("src", "/my-files/Backup");
        write(local("src").resolve("ünï cödé/日本語 😀.txt"), "x");

        var result = run();

        assertEquals(1, result.uploaded());
        assertEquals(Set.of("日本語 😀.txt"), cli.remoteFiles.get("/my-files/Backup/ünï cödé").keySet());
    }

    // ---- the exit-0-but-not-uploaded quirk ----------------------------------------------

    @Test
    void aFileTheCliSilentlySkippedIsMarkedErrorNotSynced() throws Exception {
        var source = source("src", "/my-files/Backup");
        write(local("src").resolve("a.txt"), "hello");
        write(local("src").resolve("b.txt"), "world");
        cli.silentlySkipped.add("b.txt");

        var result = run();

        assertEquals(1, result.uploaded());
        assertEquals(1, result.failed());
        assertEquals("partial", result.outcome());
        var tracked = database.getTrackedFiles(source);
        assertEquals(FileStatus.SYNCED, tracked.get("a.txt").status());
        assertEquals(FileStatus.ERROR, tracked.get("b.txt").status());
        assertEquals("Not found on Proton after uploading.", database.getFailedFiles(10).get(0).lastError());
    }

    @Test
    void aFailedFileIsRetriedOnTheNextRun() throws Exception {
        var source = source("src", "/my-files/Backup");
        write(local("src").resolve("a.txt"), "hello");
        cli.silentlySkipped.add("a.txt");
        run();

        cli.silentlySkipped.clear();
        var result = run();

        assertEquals(1, result.uploaded());
        assertEquals(FileStatus.SYNCED, database.getTrackedFiles(source).get("a.txt").status());
    }

    @Test
    void aFileWhoseClaimedSizeDiffersIsNotConfirmed() throws Exception {
        var source = source("src", "/my-files/Backup");
        write(local("src").resolve("a.txt"), "hello");
        cli.claimedSizeOffset = 1;

        var result = run();

        assertEquals(0, result.uploaded());
        assertEquals(1, result.failed());
        assertEquals(FileStatus.ERROR, database.getTrackedFiles(source).get("a.txt").status());
    }

    @Test
    void theCliOutputBecomesTheErrorMessageWhenTheUploadFailed() throws Exception {
        source("src", "/my-files/Backup");
        write(local("src").resolve("a.txt"), "hello");
        cli.silentlySkipped.add("a.txt");
        cli.uploadResult = new CliResult(1, "", "  network unreachable\n");

        run();

        assertEquals("network unreachable", database.getFailedFiles(10).get(0).lastError());
    }

    @Test
    void aFailedUploadWithoutAnyOutputStillGetsAMessage() throws Exception {
        source("src", "/my-files/Backup");
        write(local("src").resolve("a.txt"), "hello");
        cli.silentlySkipped.add("a.txt");
        cli.uploadResult = new CliResult(143, "", "");

        run();

        assertEquals("Upload failed with exit code 143.", database.getFailedFiles(10).get(0).lastError());
    }

    @Test
    void theListingDecidesNotTheExitCode() throws Exception {
        var source = source("src", "/my-files/Backup");
        write(local("src").resolve("a.txt"), "hello");
        cli.uploadResult = new CliResult(1, "", "partial failure"); // the file did get stored

        var result = run();

        assertEquals(1, result.uploaded());
        assertEquals(FileStatus.SYNCED, database.getTrackedFiles(source).get("a.txt").status());
    }

    // ---- a failing listing right after a big upload -------------------------------------

    @Test
    void oneTransientListingFailureIsRetriedOnceAndStillConfirms() throws Exception {
        source("src", "/my-files/Backup");
        write(local("src").resolve("a.txt"), "hello");
        cli.listFailuresLeft = 1;

        var result = run();

        assertEquals(1, result.uploaded());
        assertEquals(2, cli.listCalls.size(), "one failure, one retry");
    }

    @Test
    void twoConsecutiveListingFailuresLeaveTheBatchPendingNotLost() throws Exception {
        var source = source("src", "/my-files/Backup");
        write(local("src").resolve("a.txt"), "hello");
        cli.listFailuresLeft = 2;

        var result = run();

        assertEquals(0, result.uploaded());
        assertEquals(0, result.failed());
        assertEquals(FileStatus.PENDING, database.getTrackedFiles(source).get("a.txt").status());
        assertEquals(2, cli.listCalls.size(), "exactly one retry, not a loop");
        assertTrue(log.stream().anyMatch(m -> m.contains("could not fetch the folder listing")));
    }

    @Test
    void aBatchLeftPendingByAListingFailureIsUploadedAgainNextTime() throws Exception {
        var source = source("src", "/my-files/Backup");
        write(local("src").resolve("a.txt"), "hello");
        cli.listFailuresLeft = 2;
        run();

        var result = run();

        assertEquals(1, result.uploaded());
        assertEquals(FileStatus.SYNCED, database.getTrackedFiles(source).get("a.txt").status());
    }

    // ---- cancellation: no data loss -----------------------------------------------------

    /**
     * Two directories mean two batches, so there is a checkpoint between them for the cancellation to
     * land on; with a single batch no checkpoint is left and the run correctly completes as "ok".
     */
    @Test
    void cancellingMidRunFinishesTheCurrentBatchAndPreservesProgress() throws Exception {
        var source = source("src", "/my-files/Backup");
        write(local("src").resolve("dir_a/a.txt"), "x");
        write(local("src").resolve("dir_b/b.txt"), "x");
        cli.onUpload = token::cancel; // SIGTERM arrives right after the first upload

        assertThrows(SyncCancelledException.class, this::run);

        var tracked = database.getTrackedFiles(source);
        assertEquals(1, tracked.values().stream().filter(f -> f.status().equals(FileStatus.SYNCED)).count());
        assertEquals(1, tracked.values().stream().filter(f -> f.status().equals(FileStatus.PENDING)).count());
        var run = database.getRecentRuns(1).get(0);
        assertEquals("cancelled", run.result());
        assertEquals(1, run.uploaded());
        assertNotNull(run.finishedUtc());
        assertTrue(log.stream().anyMatch(m -> m.startsWith("Cancelled after 1 file(s)")), log.toString());
    }

    @Test
    void cancellingBetweenBatchesOfOneFolderStopsBeforeTheNextBatch() throws Exception {
        var source = source("src", "/my-files/Backup");
        var count = SyncEngine.BATCH_SIZE + 5;
        for (var i = 0; i < count; i++) write(local("src").resolve("f" + i + ".txt"), "x");
        cli.onUpload = token::cancel;

        assertThrows(SyncCancelledException.class, this::run);

        assertEquals(1, cli.uploadCalls.size());
        var counts = database.getStatusCounts(source);
        assertEquals(SyncEngine.BATCH_SIZE, counts.get("synced"));
        assertEquals(5, counts.get("pending"));
        assertEquals(SyncEngine.BATCH_SIZE, database.getRecentRuns(1).get(0).uploaded());
    }

    @Test
    void cancellingBetweenSourcesLeavesTheNextSourceUntouched() throws Exception {
        var first = source("first", "/my-files/First");
        var second = source("second", "/my-files/Second");
        write(local("first").resolve("a.txt"), "x");
        write(local("second").resolve("b.txt"), "x");
        cli.onUpload = token::cancel;

        assertThrows(SyncCancelledException.class, this::run);

        assertEquals(FileStatus.SYNCED, database.getTrackedFiles(first).get("a.txt").status());
        assertTrue(database.getTrackedFiles(second).isEmpty(), "never scanned or uploaded");
    }

    @Test
    void cancellationBeforeAnythingStartedRecordsACancelledRun() throws Exception {
        source("src", "/my-files/Backup");
        write(local("src").resolve("a.txt"), "x");
        token.cancel();

        assertThrows(SyncCancelledException.class, this::run);

        assertTrue(cli.uploadCalls.isEmpty());
        assertEquals("cancelled", database.getRecentRuns(1).get(0).result());
    }

    @Test
    void aCancellationDuringTheListingRetryWaitIsHonouredImmediately() throws Exception {
        source("src", "/my-files/Backup");
        write(local("src").resolve("a.txt"), "x");
        cli.listFailuresLeft = 5;
        cli.onFirstListFailure = token::cancel;
        var engine = new SyncEngine(paths, database, cli, log::add, new Scanner(Duration.ZERO), Duration.ofMinutes(10));

        var started = System.nanoTime();
        assertThrows(SyncCancelledException.class, () -> engine.runOnce(null, token));

        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 30, "must not sit out the 10 minute wait");
        assertEquals("cancelled", database.getRecentRuns(1).get(0).result());
    }

    @Test
    void aThreadInterruptDuringAnUploadIsTreatedAsCancellation() throws Exception {
        source("src", "/my-files/Backup");
        write(local("src").resolve("a.txt"), "x");
        cli.uploadInterruption = new InterruptedException("stop");

        try {
            assertThrows(SyncCancelledException.class, this::run);
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag must be restored");
        } finally {
            Thread.interrupted(); // clear the flag for the other tests
        }
        assertEquals("cancelled", database.getRecentRuns(1).get(0).result());
    }

    // ---- failures -----------------------------------------------------------------------

    @Test
    void aFailureIsRecordedAsAnErrorRunAndRethrown() throws Exception {
        source("src", "/my-files/Backup");
        write(local("src").resolve("a.txt"), "x");
        cli.ensureFolderFailure = new IOException("Could not create folder /my-files/Backup: permission denied");

        var error = assertThrows(IOException.class, this::run);

        assertTrue(error.getMessage().contains("permission denied"));
        assertEquals("error", database.getRecentRuns(1).get(0).result());
        assertTrue(log.stream().anyMatch(m -> m.startsWith("Run failed: ") && m.contains("permission denied")), log.toString());
    }

    @Test
    void aMissingSourceFolderFailsTheRunAndKeepsEarlierProgress() throws Exception {
        var first = source("first", "/my-files/First");
        write(local("first").resolve("a.txt"), "x");
        var gone = Files.createDirectories(home.resolve("gone"));
        database.addSource(gone.toString(), "/my-files/Gone");
        Files.delete(gone);

        var error = assertThrows(NoSuchFileException.class, this::run);

        assertTrue(error.getMessage().contains("Source folder does not exist"));
        assertEquals("error", database.getRecentRuns(1).get(0).result());
        assertEquals(1, database.getRecentRuns(1).get(0).uploaded(), "the first source was synced before the failure");
        assertEquals(FileStatus.SYNCED, database.getTrackedFiles(first).get("a.txt").status());
    }

    @Test
    void anUnexpectedRuntimeFailureIsRecordedAndRethrown() throws Exception {
        source("src", "/my-files/Backup");
        write(local("src").resolve("a.txt"), "x");
        cli.onUpload = () -> {
            throw new IllegalStateException("surprise");
        };

        assertThrows(IllegalStateException.class, this::run);

        assertEquals("error", database.getRecentRuns(1).get(0).result());
    }

    // ---- several sources / restricting to one -------------------------------------------

    @Test
    void onlySourceIdRestrictsTheRunToOneSource() throws Exception {
        var first = source("first", "/my-files/First");
        var second = source("second", "/my-files/Second");
        write(local("first").resolve("a.txt"), "x");
        write(local("second").resolve("b.txt"), "x");

        var result = engine().runOnce(first, token);

        assertEquals(1, result.uploaded());
        assertEquals(FileStatus.SYNCED, database.getTrackedFiles(first).get("a.txt").status());
        assertTrue(database.getTrackedFiles(second).isEmpty());
    }

    @Test
    void twoSourcesInOneDatabaseStayIndependent() throws Exception {
        var first = source("first", "/my-files/First");
        var second = source("second", "/my-files/Second");
        write(local("first").resolve("a.txt"), "a");
        write(local("second").resolve("b.txt"), "b");

        var result = run();

        assertEquals(2, result.uploaded());
        assertEquals(Map.of("a.txt", 1L), cli.remoteFiles.get("/my-files/First"));
        assertEquals(Map.of("b.txt", 1L), cli.remoteFiles.get("/my-files/Second"));
        assertEquals(Map.of("synced", 1), database.getStatusCounts(first));
        assertEquals(Map.of("synced", 1), database.getStatusCounts(second));
    }

    @Test
    void theEngineOnlyEverCallsTheFourDriveOperations() {
        // Structural guard for the never-delete invariant: the interface the engine is written against
        // has exactly these methods, so nothing like delete/trash/replace can be reached from it.
        var methods = java.util.Arrays.stream(DriveClient.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName).sorted().toList();

        assertEquals(List.of("checkSession", "ensureFolder", "list", "upload"), methods);
        assertFalse(methods.contains("delete"));
    }
}
