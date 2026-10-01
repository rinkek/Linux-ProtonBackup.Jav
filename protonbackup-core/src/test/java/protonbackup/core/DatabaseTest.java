package protonbackup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The first six tests are ported from DatabaseTests.cs. The rest cover the read models the UI
 * uses, the query patterns the plan says must survive, and concurrent access.
 */
class DatabaseTest {

    @TempDir Path directory;

    private Database database;

    @BeforeEach
    void open() {
        database = new Database(directory.resolve("protonbackup-test.db"));
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    void addingTheSameSourceTwiceUpdatesTheTarget() {
        var first = database.addSource("/tmp/bron", "/my-files/A");
        var second = database.addSource("/tmp/bron", "/my-files/B");

        assertEquals(first, second);
        var sources = database.getSources();
        assertEquals(1, sources.size());
        assertEquals("/my-files/B", sources.get(0).remotePath());
    }

    @Test
    void pendingFilesBecomeSyncedAndStaySkipped() {
        var source = database.addSource("/tmp/bron2", "/my-files/A");
        database.upsertPending(source, List.of(new ScannedFile("a.txt", 10, 1000, 42L)));
        assertEquals(1, database.getPending(source).size());

        database.markSynced(source, List.of("a.txt"));

        assertTrue(database.getPending(source).isEmpty());
        assertEquals(FileStatus.SYNCED, database.getTrackedFiles(source).get("a.txt").status());
    }

    @Test
    void failedFilesAreRetriedInTheNextRound() {
        var source = database.addSource("/tmp/bron3", "/my-files/A");
        database.upsertPending(source, List.of(new ScannedFile("a.txt", 10, 1000, null)));
        database.markError(source, List.of("a.txt"), "network error");

        assertEquals(1, database.getPending(source).size());
    }

    @Test
    void locallyDeletedFilesAreMarkedMissing() {
        var source = database.addSource("/tmp/bron4", "/my-files/A");
        database.upsertPending(source, List.of(
                new ScannedFile("weg.txt", 10, 1000, null), new ScannedFile("blijft.txt", 10, 1000, null)));
        database.markSynced(source, List.of("weg.txt", "blijft.txt"));

        assertEquals(1, database.markMissing(source, List.of("blijft.txt")));

        var tracked = database.getTrackedFiles(source);
        assertEquals(FileStatus.MISSING, tracked.get("weg.txt").status());
        assertEquals(FileStatus.SYNCED, tracked.get("blijft.txt").status());
    }

    @Test
    void forcingResetsSyncedFilesButLeavesMissingOnesAlone() {
        var source = database.addSource("/tmp/bron5", "/my-files/A");
        database.upsertPending(source, List.of(
                new ScannedFile("a.txt", 10, 1000, null), new ScannedFile("weg.txt", 10, 1000, null)));
        database.markSynced(source, List.of("a.txt", "weg.txt"));
        database.markMissing(source, List.of("a.txt"));

        assertEquals(1, database.markAllPending(source));

        var tracked = database.getTrackedFiles(source);
        assertEquals(FileStatus.PENDING, tracked.get("a.txt").status());
        assertEquals(FileStatus.MISSING, tracked.get("weg.txt").status());
    }

    @Test
    void forcingWithoutASourceCoversEverything() {
        var first = database.addSource("/tmp/bron6", "/my-files/A");
        var second = database.addSource("/tmp/bron7", "/my-files/B");
        database.upsertPending(first, List.of(new ScannedFile("a.txt", 1, 1, null)));
        database.upsertPending(second, List.of(new ScannedFile("b.txt", 1, 1, null)));
        database.markSynced(first, List.of("a.txt"));
        database.markSynced(second, List.of("b.txt"));

        assertEquals(2, database.markAllPending());
        assertEquals(1, database.getPending(first).size());
        assertEquals(1, database.getPending(second).size());
    }

    // ---- read models used by the UI -----------------------------------------------------

    @Test
    void getChildFoldersSummarisesStatusOneLevelDeep() {
        var source = database.addSource("/tmp/bron8", "/my-files/A");
        database.upsertPending(source, List.of(
                new ScannedFile("docs/a.txt", 1, 1, null),
                new ScannedFile("docs/sub/b.txt", 1, 1, null),
                new ScannedFile("top.txt", 1, 1, null)));
        database.markError(source, List.of("docs/a.txt"), "boom");

        var folders = database.getChildFolders(source, "");

        assertEquals(1, folders.size());
        assertEquals("docs", folders.get(0).name());
        assertEquals("docs", folders.get(0).relativePath());
        assertEquals(2, folders.get(0).fileCount());
        assertEquals(1, folders.get(0).errorCount());
        assertEquals(1, folders.get(0).pendingCount());
    }

    @Test
    void getChildFoldersDescendsIntoASubfolder() {
        var source = database.addSource("/tmp/bron8b", "/my-files/A");
        database.upsertPending(source, List.of(
                new ScannedFile("docs/sub/b.txt", 1, 1, null), new ScannedFile("docs/sub/deep/c.txt", 1, 1, null)));

        var folders = database.getChildFolders(source, "docs");

        assertEquals(1, folders.size());
        assertEquals("sub", folders.get(0).name());
        assertEquals("docs/sub", folders.get(0).relativePath());
        assertEquals(2, folders.get(0).fileCount());
    }

    @Test
    void getFilesInListsOnlyDirectChildren() {
        var source = database.addSource("/tmp/bron9", "/my-files/A");
        database.upsertPending(source, List.of(
                new ScannedFile("docs/a.txt", 1, 1, null), new ScannedFile("docs/sub/b.txt", 1, 1, null)));

        var files = database.getFilesIn(source, "docs");

        assertEquals(List.of("a.txt"), files.stream().map(FileEntry::name).toList());
        assertEquals("docs/a.txt", files.get(0).relativePath());
    }

    @Test
    void folderNamesWithLikeWildcardsDoNotMatchOtherFolders() {
        var source = database.addSource("/tmp/bron9b", "/my-files/A");
        database.upsertPending(source, List.of(
                new ScannedFile("a_b/one.txt", 1, 1, null),
                new ScannedFile("axb/two.txt", 1, 1, null),
                new ScannedFile("100%/three.txt", 1, 1, null),
                new ScannedFile("100x/four.txt", 1, 1, null)));

        assertEquals(List.of("one.txt"), database.getFilesIn(source, "a_b").stream().map(FileEntry::name).toList());
        assertEquals(List.of("three.txt"), database.getFilesIn(source, "100%").stream().map(FileEntry::name).toList());
        assertEquals(List.of("axb", "a_b").stream().sorted().toList(),
                database.getChildFolders(source, "").stream().map(FolderEntry::name).filter(n -> n.startsWith("a")).sorted().toList());
    }

    @Test
    void getFailedFilesNeverReportsALastSyncTime() {
        var source = database.addSource("/tmp/bron10", "/my-files/A");
        database.upsertPending(source, List.of(new ScannedFile("a.txt", 1, 1, null)));
        database.markSynced(source, List.of("a.txt"));
        database.markError(source, List.of("a.txt"), "boom");

        var failed = database.getFailedFiles(200).get(0);

        assertEquals("boom", failed.lastError());
        assertNull(failed.lastSyncUtc());
    }

    @Test
    void getFilesInReportsTheLastSyncTimeOfSyncedFiles() {
        var source = database.addSource("/tmp/bron10b", "/my-files/A");
        var before = Instant.now().minusSeconds(1);
        database.upsertPending(source, List.of(new ScannedFile("a.txt", 1, 1, null)));
        database.markSynced(source, List.of("a.txt"));

        var entry = database.getFilesIn(source, "").get(0);

        assertNotNull(entry.lastSyncUtc());
        assertTrue(entry.lastSyncUtc().isAfter(before));
    }

    @Test
    void getStatusCountsCanBeScopedToOneSource() {
        var first = database.addSource("/tmp/bron11", "/my-files/A");
        var second = database.addSource("/tmp/bron12", "/my-files/B");
        database.upsertPending(first, List.of(new ScannedFile("a.txt", 1, 1, null)));
        database.upsertPending(second, List.of(new ScannedFile("b.txt", 1, 1, null), new ScannedFile("c.txt", 1, 1, null)));

        assertEquals(Map.of("pending", 1), database.getStatusCounts(first));
        assertEquals(Map.of("pending", 2), database.getStatusCounts(second));
        assertEquals(Map.of("pending", 3), database.getStatusCounts());
    }

    @Test
    void getRecentRunsOrdersNewestFirstAndParsesTimestamps() {
        var first = database.startRun();
        database.finishRun(first, 1, 0, "ok");
        var second = database.startRun();
        database.finishRun(second, 0, 1, "partial");

        var runs = database.getRecentRuns(20);

        assertEquals(List.of(second, first), runs.stream().map(RunRecord::id).toList());
        assertEquals("partial", runs.get(0).result());
        assertNotNull(runs.get(0).startedUtc());
        assertNotNull(runs.get(0).finishedUtc());
    }

    @Test
    void aRunThatIsStillBusyHasNoFinishTimeOrResult() {
        database.startRun();

        var run = database.getRecentRuns(1).get(0);

        assertNull(run.finishedUtc());
        assertNull(run.result());
    }

    @Test
    void settingsRoundTripAndDefaultToNull() {
        assertNull(database.getSetting("does_not_exist"));

        database.setSetting("cli_platform", "linux-x64");
        assertEquals("linux-x64", database.getSetting("cli_platform"));

        database.setSetting("cli_platform", "linux-x64-baseline");
        assertEquals("linux-x64-baseline", database.getSetting("cli_platform"));
    }

    // ---- behavior the plan says must survive --------------------------------------------

    @Test
    void upsertResetsAChangedFileToPendingAndClearsItsError() {
        var source = database.addSource("/tmp/bron13", "/my-files/A");
        database.upsertPending(source, List.of(new ScannedFile("a.txt", 1, 1, null)));
        database.markError(source, List.of("a.txt"), "boom");

        database.upsertPending(source, List.of(new ScannedFile("a.txt", 2, 5, 7L)));

        var tracked = database.getTrackedFiles(source).get("a.txt");
        assertEquals(FileStatus.PENDING, tracked.status());
        assertEquals(2L, tracked.size());
        assertEquals(5L, tracked.modifiedUnixMs());
    }

    @Test
    void removingASourceRemovesItsTrackedFiles() {
        var source = database.addSource("/tmp/bron14", "/my-files/A");
        database.upsertPending(source, List.of(new ScannedFile("a.txt", 1, 1, null)));

        database.removeSource(source);

        assertTrue(database.getSources().isEmpty());
        assertTrue(database.getStatusCounts().isEmpty());
    }

    @Test
    void disabledSourcesAreLeftOutWhenOnlyEnabledOnesAreRequested() {
        var source = database.addSource("/tmp/bron15", "/my-files/A");
        database.setSourceEnabled(source, false);

        assertTrue(database.getSources(true).isEmpty());
        assertEquals(1, database.getSources(false).size());
    }

    @Test
    void markMissingHandlesFarMoreFilesThanSqlitesParameterLimit() {
        var source = database.addSource("/tmp/bron16", "/my-files/A");
        var files = new ArrayList<ScannedFile>();
        var present = new ArrayList<String>();
        for (var i = 0; i < 40_000; i++) {
            files.add(new ScannedFile("dir/file" + i + ".txt", 1, 1, null));
            if (i != 39_999) present.add("dir/file" + i + ".txt");
        }
        database.upsertPending(source, files);

        assertEquals(1, database.markMissing(source, present));
    }

    @Test
    void theDatabaseFileIsInWalMode() throws Exception {
        var file = directory.resolve("pragmas.db");
        try (var db = new Database(file);
                var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement();
                var rows = statement.executeQuery("PRAGMA journal_mode")) {
            rows.next();
            assertEquals("wal", rows.getString(1));
            assertNotNull(db);
        }
    }

    @Test
    void aSecondConnectionCanReadWhileTheFirstHoldsAnOpenWrite() throws Exception {
        var file = directory.resolve("concurrent.db");
        try (var writer = new Database(file); var reader = new Database(file)) {
            writer.setSetting("k", "committed");

            // The writer keeps a transaction open on its raw connection while the reader reads.
            var seen = new String[1];
            var done = new Thread(() -> seen[0] = reader.getSetting("k"));
            var raw = DriverManager.getConnection("jdbc:sqlite:" + file);
            raw.setAutoCommit(false);
            raw.createStatement().executeUpdate("INSERT INTO settings (key, value) VALUES ('open', 'x')");
            done.start();
            done.join(5000);
            raw.commit();
            raw.close();

            assertEquals("committed", seen[0]);
            assertNull(reader.getSetting("missing"));
            assertEquals("x", reader.getSetting("open"));
        }
    }

    @Test
    void dataSurvivesReopeningTheFile() {
        var file = directory.resolve("reopen.db");
        try (var first = new Database(file)) {
            first.addSource("/tmp/bron17", "/my-files/A");
            first.setSetting("k", "v");
        }
        try (var second = new Database(file)) {
            assertEquals(1, second.getSources().size());
            assertEquals("v", second.getSetting("k"));
        }
    }

    @Test
    void openCreatesTheDataFoldersFirst() throws Exception {
        var paths = AppPaths.forHome(directory.resolve("home"));
        try (var db = Database.open(paths)) {
            db.setSetting("k", "v");
        }
        assertTrue(java.nio.file.Files.isRegularFile(paths.databasePath()));
    }
}
