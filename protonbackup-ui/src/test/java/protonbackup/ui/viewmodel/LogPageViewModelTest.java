package protonbackup.ui.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import protonbackup.core.ScannedFile;
import protonbackup.ui.mvvm.Background;
import protonbackup.ui.testing.Fixture;

class LogPageViewModelTest {

    @TempDir Path home;

    private Fixture fixture;
    private LogPageViewModel page;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new Fixture(home);
        page = new LogPageViewModel(fixture.service, Background.direct(), ZoneOffset.UTC);
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void emptyAtFirst() {
        page.refresh();

        assertTrue(page.runs().isEmpty());
        assertTrue(page.failures().isEmpty());
    }

    @Test
    void runsAreListedNewestFirstWithWordedOutcomes() {
        var db = fixture.service.database();
        var first = db.startRun();
        db.finishRun(first, 3, 0, "ok");
        var second = db.startRun();
        db.finishRun(second, 1, 2, "partial");
        var third = db.startRun(); // still running

        page.refresh();

        assertEquals(List.of(third, second, first), page.runs().stream().map(LogPageViewModel.RunRow::id).toList());
        assertEquals("running", page.runs().get(0).outcome());
        assertEquals("partly failed", page.runs().get(1).outcome());
        assertEquals("1 uploaded, 2 failed", page.runs().get(1).counts());
        assertEquals("completed", page.runs().get(2).outcome());
        assertEquals(3, page.runs().get(2).uploaded());
    }

    @Test
    void failuresShowThePathAndTheReason() {
        var db = fixture.service.database();
        var source = db.addSource(home.toString(), "/my-files/Backup");
        db.upsertPending(source, List.of(new ScannedFile("docs/a.txt", 1, 1, null), new ScannedFile("b.txt", 1, 1, null)));
        db.markError(source, List.of("docs/a.txt"), "network unreachable");

        page.refresh();

        assertEquals(1, page.failures().size());
        assertEquals("docs/a.txt", page.failures().get(0).relativePath());
        assertEquals("network unreachable", page.failures().get(0).lastError());
    }

    @Test
    void anUnchangedLogIsNotRebuiltOnEveryRefreshSoASelectionSurvives() {
        var db = fixture.service.database();
        db.finishRun(db.startRun(), 1, 0, "ok");
        var source = db.addSource(home.toString(), "/my-files/Backup");
        db.upsertPending(source, List.of(new ScannedFile("a", 1, 1, null)));
        db.markError(source, List.of("a"), "boom");
        page.refresh();
        var changes = new ArrayList<Object>();
        page.runs().addListener((javafx.collections.ListChangeListener<Object>) changes::add);
        page.failures().addListener((javafx.collections.ListChangeListener<Object>) changes::add);

        page.refresh();
        page.refresh();

        assertTrue(changes.isEmpty());
    }

    @Test
    void aFailureThatWasFixedDisappearsAndANewRunAppears() {
        var db = fixture.service.database();
        var source = db.addSource(home.toString(), "/my-files/Backup");
        db.upsertPending(source, List.of(new ScannedFile("a", 1, 1, null)));
        db.markError(source, List.of("a"), "boom");
        page.refresh();
        assertEquals(1, page.failures().size());

        db.markSynced(source, List.of("a"));
        db.finishRun(db.startRun(), 1, 0, "ok");
        page.refresh();

        assertTrue(page.failures().isEmpty());
        assertEquals(1, page.runs().size());
    }
}
