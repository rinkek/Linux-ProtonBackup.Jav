package protonbackup.ui.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import protonbackup.core.ScannedFile;
import protonbackup.ui.mvvm.Background;
import protonbackup.ui.testing.Fixture;

class StructurePageViewModelTest {

    @TempDir Path home;

    private Fixture fixture;
    private StructurePageViewModel page;
    private long source;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new Fixture(home);
        page = new StructurePageViewModel(fixture.service, Background.direct(), ZoneOffset.UTC);
        var db = fixture.service.database();
        source = db.addSource(home.toString(), "/my-files/Backup");
        db.upsertPending(source, List.of(
                new ScannedFile("top.txt", 1, 1, null),
                new ScannedFile("docs/a.txt", 1, 1, null),
                new ScannedFile("docs/sub/b.txt", 1, 1, null),
                new ScannedFile("docs/sub/c.txt", 1, 1, null),
                new ScannedFile("photos/p.jpg", 1, 1, null)));
        db.markSynced(source, List.of("top.txt", "docs/a.txt", "docs/sub/b.txt"));
        db.markError(source, List.of("photos/p.jpg"), "network unreachable");
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    private FolderNode node(List<FolderNode> nodes, String name) {
        return nodes.stream().filter(n -> n.name().equals(name)).findFirst().orElseThrow(() -> new AssertionError("no node " + name));
    }

    // ---- sources ------------------------------------------------------------------------

    @Test
    void withoutSourcesThereIsNothingToShow() throws Exception {
        try (var empty = new Fixture(home.resolve("empty"))) {
            var other = new StructurePageViewModel(empty.service, Background.direct(), ZoneOffset.UTC);

            other.refresh();

            assertFalse(other.hasSources().get());
            assertNull(other.selectedSource().get());
            assertTrue(other.nodes().isEmpty());
        }
    }

    @Test
    void theFirstSourceIsSelectedAndItsTreeLoaded() {
        page.refresh();

        assertTrue(page.hasSources().get());
        assertEquals(source, page.selectedSource().get().id());
        assertEquals(List.of("docs", "photos", "top.txt"), page.nodes().stream().map(FolderNode::name).toList(), "folders first, then files");
    }

    @Test
    void theSelectionSurvivesAChangeOfTheOtherSources() {
        var second = fixture.service.database().addSource(home.resolve("second").toString(), "/my-files/Second");
        page.refresh();
        page.selectedSource().set(page.sources().get(1));

        fixture.service.database().addSource(home.resolve("third").toString(), "/my-files/Third");
        page.refresh();

        assertEquals(second, page.selectedSource().get().id());
        assertEquals(3, page.sources().size());
    }

    @Test
    void whenTheSelectedSourceIsRemovedTheFirstOneIsSelected() {
        var other = fixture.service.database().addSource(home.resolve("other").toString(), "/my-files/Other");
        page.refresh();
        page.selectedSource().set(page.sources().get(1));

        fixture.service.database().removeSource(other);
        page.refresh();

        assertEquals(source, page.selectedSource().get().id());
    }

    @Test
    void anUnchangedListOfSourcesDoesNotReloadTheTree() {
        page.refresh();
        var first = page.nodes().get(0);

        page.refresh();
        page.refresh();

        assertTrue(page.nodes().get(0) == first, "a refresh every two seconds must not collapse what the user opened");
    }

    // ---- the tree -----------------------------------------------------------------------

    @Test
    void foldersShowHowManyFilesTheyHoldAndHowTheyAreDoing() {
        page.refresh();

        assertEquals("3 files, 1 queued", node(page.nodes(), "docs").summary());
        assertEquals("1 file, 1 failed", node(page.nodes(), "photos").summary());
    }

    @Test
    void aFolderWithFilesLooksExpandableBeforeItIsLoaded() {
        page.refresh();
        var docs = node(page.nodes(), "docs");

        assertEquals(1, docs.children().size());
        assertTrue(docs.children().get(0).isPlaceholder());
        assertEquals("loading…", docs.children().get(0).name());
    }

    @Test
    void expandingAFolderLoadsItsContentsOnce() {
        page.refresh();
        var docs = node(page.nodes(), "docs");

        docs.expand();

        assertEquals(List.of("sub", "a.txt"), docs.children().stream().map(FolderNode::name).toList());
        var sub = node(docs.children(), "sub");
        sub.expand();
        assertEquals(List.of("b.txt", "c.txt"), sub.children().stream().map(FolderNode::name).toList());

        var children = List.copyOf(docs.children());
        docs.expand(); // a second expand must not read again
        assertEquals(children, docs.children());
    }

    @Test
    void filesShowTheirState() {
        page.refresh();
        var docs = node(page.nodes(), "docs");
        docs.expand();
        var sub = node(docs.children(), "sub");
        sub.expand();

        assertTrue(node(docs.children(), "a.txt").summary().startsWith("synced "), node(docs.children(), "a.txt").summary());
        assertEquals("waiting to upload", node(sub.children(), "c.txt").summary());
        var photos = node(page.nodes(), "photos");
        photos.expand();
        assertEquals("network unreachable", node(photos.children(), "p.jpg").summary());
    }

    @Test
    void aFileThatIsGoneLocallySaysSo() {
        // everything except docs/a.txt is still on disk
        fixture.service.database().markMissing(source, List.of("top.txt", "docs/sub/b.txt", "docs/sub/c.txt", "photos/p.jpg"));
        page.refresh();
        var docs = node(page.nodes(), "docs");
        docs.expand();

        assertEquals("gone locally", node(docs.children(), "a.txt").summary());
    }

    @Test
    void aFileNodeCannotBeExpanded() {
        page.refresh();
        var file = node(page.nodes(), "top.txt");

        file.expand();

        assertTrue(file.children().isEmpty());
        assertTrue(file.isFile());
    }

    @Test
    void aFailedLoadShowsWhyAndCanBeTriedAgain() {
        page.refresh();
        var docs = node(page.nodes(), "docs");
        fixture.service.database().close(); // the database vanished under the page

        docs.expand();

        assertTrue(docs.children().get(0).name().startsWith("could not load: "), docs.children().get(0).name());
    }

    // ---- errors only --------------------------------------------------------------------

    @Test
    void errorsOnlyShowsTheFailedFilesInsteadOfTheTree() {
        page.refresh();

        page.onlyErrors().set(true);

        assertTrue(page.nodes().isEmpty());
        assertEquals(1, page.failures().size());
        assertEquals("photos/p.jpg", page.failures().get(0).relativePath());
        assertEquals("network unreachable", page.failures().get(0).lastError());

        page.onlyErrors().set(false);

        assertTrue(page.failures().isEmpty());
        assertEquals(3, page.nodes().size());
    }

    @Test
    void refreshReloadsTheTreeAfterSomethingChanged() {
        page.refresh();
        assertEquals("1 file, 1 failed", node(page.nodes(), "photos").summary());
        fixture.service.database().markSynced(source, List.of("photos/p.jpg"));

        page.reload().execute();

        assertEquals("1 file", node(page.nodes(), "photos").summary());
    }

    @Test
    void selectingANewSourceLoadsItsTree() {
        var other = fixture.service.database().addSource(home.resolve("other").toString(), "/my-files/Other");
        fixture.service.database().upsertPending(other, List.of(new ScannedFile("only.txt", 1, 1, null)));
        page.refresh();

        page.selectedSource().set(page.sources().get(1));

        assertEquals(List.of("only.txt"), page.nodes().stream().map(FolderNode::name).toList());
        assertNotNull(page.selectedSource().get());
    }

    // ---- an old load must not overwrite a newer one ----------------------------------------

    @Test
    void aLoadForASourceTheUserLeftIsDropped() {
        var other = fixture.service.database().addSource(home.resolve("other").toString(), "/my-files/Other");
        fixture.service.database().upsertPending(other, List.of(new ScannedFile("only.txt", 1, 1, null)));
        var manualUi = new java.util.ArrayDeque<Runnable>();
        var manualWorker = new java.util.ArrayDeque<Runnable>();
        var background = Background.withExecutors(manualWorker::add, manualWorker::add, manualWorker::add, manualUi::add, () -> true);
        var slow = new StructurePageViewModel(fixture.service, background, ZoneOffset.UTC);

        slow.refresh(); // list of sources
        manualWorker.poll().run();
        manualUi.poll().run(); // selects the first source and asks for its tree
        slow.selectedSource().set(slow.sources().get(1)); // the user switches before that tree arrived
        while (!manualWorker.isEmpty() || !manualUi.isEmpty()) {
            if (!manualWorker.isEmpty()) manualWorker.poll().run();
            if (!manualUi.isEmpty()) manualUi.poll().run();
        }

        assertEquals(List.of("only.txt"), slow.nodes().stream().map(FolderNode::name).toList());
    }
}
