package protonbackup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Database + Scanner + SyncEngine as one system on a realistic multi-folder tree, across several
 * rounds of real change. The per-class tests cover edge cases; this one is about the whole pipeline
 * staying coherent.
 */
class SyncIntegrationTest {

    @TempDir Path home;

    private Database database;
    private FakeDriveClient cli;
    private SyncEngine engine;
    private Path tree;

    @BeforeEach
    void setUp() throws IOException {
        var paths = AppPaths.forHome(home);
        database = new Database(home.resolve("integration.db"));
        cli = new FakeDriveClient();
        engine = new SyncEngine(paths, database, cli, message -> {}, new Scanner(Duration.ZERO), Duration.ZERO);

        // files at the root, in a one-level folder and two levels deep
        tree = Files.createDirectories(home.resolve("source"));
        write("document.txt", "a top-level document");
        write("notes.md", "# Notes\n\nSome notes.");
        write("photos/vacation1.jpg", "fake jpeg bytes 1");
        write("photos/vacation2.jpg", "fake jpeg bytes 2");
        write("projects/report/draft.txt", "draft report contents");
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private void write(String relative, String content) throws IOException {
        var file = tree.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void theWholePipelineAcrossSeveralRoundsOfRealChange() throws Exception {
        var source = database.addSource(tree.toString(), "/my-files/Backup");

        // Round 1: everything is new.
        var result = engine.runOnce(null, new CancelToken());
        assertEquals(5, result.uploaded());
        assertEquals(0, result.failed());
        assertEquals(Set.of("document.txt", "notes.md"), cli.remoteFiles.get("/my-files/Backup").keySet());
        assertEquals(Set.of("vacation1.jpg", "vacation2.jpg"), cli.remoteFiles.get("/my-files/Backup/photos").keySet());
        assertEquals(Set.of("draft.txt"), cli.remoteFiles.get("/my-files/Backup/projects/report").keySet());
        assertTrue(database.getTrackedFiles(source).values().stream().allMatch(f -> f.status().equals(FileStatus.SYNCED)));

        // Round 2: nothing changed, so nothing uploads (3 calls came from round 1, one per folder).
        result = engine.runOnce(null, new CancelToken());
        assertEquals(0, result.uploaded());
        assertEquals(3, cli.uploadCalls.size());

        // Round 3: modify one nested file, add a new one.
        write("projects/report/draft.txt", "a longer draft now");
        write("projects/budget.txt", "new file");
        result = engine.runOnce(null, new CancelToken());
        assertEquals(2, result.uploaded());
        assertEquals((long) "a longer draft now".length(), cli.remoteFiles.get("/my-files/Backup/projects/report").get("draft.txt"));
        assertTrue(cli.remoteFiles.get("/my-files/Backup/projects").containsKey("budget.txt"));

        // Round 4: delete a file locally. It is marked missing and stays on Proton (the fake has no delete).
        Files.delete(tree.resolve("photos/vacation1.jpg"));
        result = engine.runOnce(null, new CancelToken());
        assertEquals(1, result.missing());
        assertEquals(FileStatus.MISSING, database.getTrackedFiles(source).get("photos/vacation1.jpg").status());
        assertTrue(cli.remoteFiles.get("/my-files/Backup/photos").containsKey("vacation1.jpg"));

        // Round 5: the CLI silently fails one file; it is an error and is retried successfully next round.
        write("notes.md", "# Notes\n\nUpdated notes.");
        cli.silentlySkipped.add("notes.md");
        result = engine.runOnce(null, new CancelToken());
        assertEquals(0, result.uploaded());
        assertEquals(1, result.failed());
        assertEquals(FileStatus.ERROR, database.getTrackedFiles(source).get("notes.md").status());

        cli.silentlySkipped.clear();
        result = engine.runOnce(null, new CancelToken());
        assertEquals(1, result.uploaded());
        assertEquals(FileStatus.SYNCED, database.getTrackedFiles(source).get("notes.md").status());

        // Round 6: force queues everything still trackable (not the missing file) and re-confirms it.
        var reset = database.markAllPending(source);
        assertEquals(database.getTrackedFiles(source).size() - 1, reset);
        result = engine.runOnce(null, new CancelToken());
        assertEquals(0, result.failed());
        assertEquals(FileStatus.MISSING, database.getTrackedFiles(source).get("photos/vacation1.jpg").status());
    }

    @Test
    void theUiReadModelsAgreeWithWhatTheEngineRecorded() throws Exception {
        var source = database.addSource(tree.toString(), "/my-files/Backup");
        engine.runOnce(null, new CancelToken());

        assertEquals(Map.of("synced", 5), database.getStatusCounts(source));
        assertEquals(Set.of("document.txt", "notes.md"),
                database.getFilesIn(source, "").stream().map(FileEntry::name).collect(Collectors.toSet()));
        assertEquals(Set.of("photos", "projects"),
                database.getChildFolders(source, "").stream().map(FolderEntry::name).collect(Collectors.toSet()));
        var report = database.getChildFolders(source, "projects");
        assertEquals(List.of("report"), report.stream().map(FolderEntry::name).toList());
        assertEquals(1, report.get(0).fileCount());
    }

    private void editDocument() {
        try {
            Files.writeString(tree.resolve("document.txt"), "a top-level document, edited during the upload");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void aFileThatChangesBetweenTheScanAndTheUploadIsRetriedNotLost() throws Exception {
        var source = database.addSource(tree.toString(), "/my-files/Backup");
        // The file changes after it was scanned but before it is uploaded: Proton ends up with a size
        // that differs from the one that was scanned, so it must not be recorded as synced.
        cli.beforeUpload = this::editDocument;

        var first = engine.runOnce(null, new CancelToken());
        assertEquals(1, first.failed());
        assertEquals(FileStatus.ERROR, database.getTrackedFiles(source).get("document.txt").status());

        cli.beforeUpload = null;
        var second = engine.runOnce(null, new CancelToken());
        assertEquals(0, second.failed());
        assertEquals(1, second.uploaded());
        assertEquals(FileStatus.SYNCED, database.getTrackedFiles(source).get("document.txt").status());
        assertEquals((long) "a top-level document, edited during the upload".length(),
                cli.remoteFiles.get("/my-files/Backup").get("document.txt"));
    }

    @Test
    void aFileEditedRightAfterItsUploadIsPickedUpByTheNextRun() throws Exception {
        var source = database.addSource(tree.toString(), "/my-files/Backup");
        cli.onUpload = this::editDocument; // the upload itself was complete and correct

        var first = engine.runOnce(null, new CancelToken());
        assertEquals(5, first.uploaded());

        cli.onUpload = null;
        var second = engine.runOnce(null, new CancelToken());
        assertEquals(1, second.uploaded(), "the edited file changed size and mtime, so it goes up again");
        assertEquals(FileStatus.SYNCED, database.getTrackedFiles(source).get("document.txt").status());
    }
}
