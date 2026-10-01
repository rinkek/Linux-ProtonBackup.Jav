package protonbackup.ui.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import protonbackup.core.CliResult;
import protonbackup.core.FileStatus;
import protonbackup.core.ScannedFile;
import protonbackup.ui.mvvm.Background;
import protonbackup.ui.testing.Fixture;

class SourceFoldersPageViewModelTest {

    @TempDir Path home;

    private Fixture fixture;
    private Optional<Path> picked = Optional.empty();
    private final List<String> asked = new ArrayList<>();
    private SourceFoldersPageViewModel page;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new Fixture(home);
        page = new SourceFoldersPageViewModel(fixture.service, Background.direct(), title -> {
            asked.add(title);
            return picked;
        });
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    private Path folder(String name) throws Exception {
        return Files.createDirectories(home.resolve(name));
    }

    // ---- showing the list ---------------------------------------------------------------

    @Test
    void theListIsEmptyAtFirstAndFollowsTheDatabase() throws Exception {
        page.refresh();
        assertTrue(page.sources().isEmpty());

        fixture.service.database().addSource(folder("docs").toString(), "/my-files/Docs");
        page.refresh();

        assertEquals(1, page.sources().size());
        assertEquals("/my-files/Docs", page.sources().get(0).remotePath());
    }

    @Test
    void anUnchangedListIsNotRebuiltOnEveryRefresh() throws Exception {
        fixture.service.database().addSource(folder("docs").toString(), "/my-files/Docs");
        page.refresh();
        var changes = new ArrayList<Object>();
        page.sources().addListener((javafx.collections.ListChangeListener<Object>) change -> changes.add(change));

        page.refresh();
        page.refresh();

        assertTrue(changes.isEmpty(), "rebuilding the rows every two seconds would drop hover and focus");
    }

    // ---- adding -------------------------------------------------------------------------

    @Test
    void theFormStartsWithTheExampleDestination() {
        assertEquals("", page.newLocalPath().get());
        assertEquals("/my-files/Backup", page.newRemotePath().get());
    }

    @Test
    void addingAFolderStoresItClearsTheFieldAndShowsTheNewRow() throws Exception {
        var docs = folder("docs");
        page.newLocalPath().set(docs.toString());
        page.newRemotePath().set("/my-files/Docs/");

        page.addSource().execute();

        assertEquals("Source folder added.", page.message().get());
        assertEquals("", page.newLocalPath().get());
        assertEquals(1, page.sources().size());
        assertEquals("/my-files/Docs", page.sources().get(0).remotePath(), "the trailing slash is dropped");
        assertEquals(docs.toString(), page.sources().get(0).localPath());
    }

    @Test
    void aFolderThatDoesNotExistIsRefused() {
        page.newLocalPath().set(home.resolve("missing").toString());

        page.addSource().execute();

        assertEquals("Choose an existing folder first.", page.message().get());
        assertTrue(page.sources().isEmpty());
    }

    @Test
    void anEmptyLocalFieldIsRefused() {
        page.addSource().execute();

        assertEquals("Choose an existing folder first.", page.message().get());
    }

    @Test
    void aFileInsteadOfAFolderIsRefused() throws Exception {
        var file = Files.writeString(home.resolve("a.txt"), "x");
        page.newLocalPath().set(file.toString());

        page.addSource().execute();

        assertEquals("Choose an existing folder first.", page.message().get());
    }

    @Test
    void aDestinationWithoutALeadingSlashIsRefused() throws Exception {
        page.newLocalPath().set(folder("docs").toString());
        page.newRemotePath().set("my-files/Backup");

        page.addSource().execute();

        assertEquals("The destination path must start with /, for example /my-files/Backup.", page.message().get());
        assertTrue(page.sources().isEmpty());
        assertFalse(page.newLocalPath().get().isEmpty(), "what the user typed is kept");
    }

    @Test
    void theRootAloneIsNotAValidDestination() throws Exception {
        page.newLocalPath().set(folder("docs").toString());
        page.newRemotePath().set("/");

        page.addSource().execute();

        assertEquals("The destination must be a folder below /, for example /my-files/Backup.", page.message().get());
        assertTrue(page.sources().isEmpty());
    }

    @Test
    void addingTheSameFolderAgainUpdatesItsDestination() throws Exception {
        var docs = folder("docs");
        page.newLocalPath().set(docs.toString());
        page.newRemotePath().set("/my-files/A");
        page.addSource().execute();

        page.newLocalPath().set(docs.toString());
        page.newRemotePath().set("/my-files/B");
        page.addSource().execute();

        assertEquals(1, page.sources().size());
        assertEquals("/my-files/B", page.sources().get(0).remotePath());
    }

    // ---- the folder picker --------------------------------------------------------------

    @Test
    void browseFillsInTheChosenFolder() throws Exception {
        picked = Optional.of(folder("chosen"));

        page.browse().execute();

        assertEquals(home.resolve("chosen").toString(), page.newLocalPath().get());
        assertEquals(List.of("Choose a source folder"), asked);
    }

    @Test
    void cancellingTheDialogKeepsWhatWasThere() {
        page.newLocalPath().set("/typed/by/hand");

        page.browse().execute();

        assertEquals("/typed/by/hand", page.newLocalPath().get());
    }

    // ---- the row buttons ----------------------------------------------------------------

    private protonbackup.core.SyncSource addRow(String name) throws Exception {
        fixture.service.database().addSource(folder(name).toString(), "/my-files/" + name);
        page.refresh();
        return page.sources().get(page.sources().size() - 1);
    }

    @Test
    void removingAFolderKeepsEverythingOnProton() throws Exception {
        var row = addRow("docs");

        page.removeSource().execute(row);

        assertTrue(page.sources().isEmpty());
        assertEquals("Source folder removed. Whatever is already on Proton stays there.", page.message().get());
        assertTrue(fixture.system.cliCalls.isEmpty(), "no CLI call at all: nothing on Proton is touched");
    }

    @Test
    void syncNowForOneFolderStartsThatFoldersRun() throws Exception {
        var row = addRow("docs");

        page.syncSource().execute(row);

        assertEquals("Run started for " + row.localPath() + ".", page.message().get());
        assertTrue(fixture.system.systemctlCalls.contains("--user start --no-block protonbackup-sync@" + row.id() + ".service"));
    }

    @Test
    void aFailedStartShowsWhatSystemctlSaid() throws Exception {
        var row = addRow("docs");
        fixture.system.systemctlResults.put("start", new CliResult(1, "", "Unit protonbackup-sync@1.service not found.\n"));

        page.syncSource().execute(row);

        assertEquals("Unit protonbackup-sync@1.service not found.", page.message().get());
    }

    @Test
    void goingThroughEverythingAgainQueuesTheFilesAndStartsTheRun() throws Exception {
        var row = addRow("docs");
        var db = fixture.service.database();
        db.upsertPending(row.id(), List.of(new ScannedFile("a.txt", 1, 1, null), new ScannedFile("b.txt", 1, 1, null)));
        db.markSynced(row.id(), List.of("a.txt", "b.txt"));

        page.forceSource().execute(row);

        assertEquals("2 file(s) queued again; the run has started.", page.message().get());
        assertEquals(FileStatus.PENDING, db.getTrackedFiles(row.id()).get("a.txt").status());
        assertTrue(fixture.system.systemctlCalls.contains("--user start --no-block protonbackup-sync@" + row.id() + ".service"));
    }

    @Test
    void aRowActionWithoutARowDoesNothing() {
        page.removeSource().execute(null);
        page.syncSource().execute(null);
        page.forceSource().execute(null);

        assertTrue(fixture.system.systemctlCalls.isEmpty());
        assertEquals(null, page.message().get());
    }
}
