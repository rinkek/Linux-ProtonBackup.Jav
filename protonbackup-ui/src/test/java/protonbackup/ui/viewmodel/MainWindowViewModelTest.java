package protonbackup.ui.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import protonbackup.core.ScannedFile;
import protonbackup.ui.mvvm.Background;
import protonbackup.ui.testing.Fixture;

class MainWindowViewModelTest {

    @TempDir Path home;

    private Fixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new Fixture(home).withCli();
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    private MainWindowViewModel window(boolean welcome) {
        return new MainWindowViewModel(fixture.service, Background.direct(), welcome, FolderPicker.NONE);
    }

    private long cliCalls() {
        return fixture.system.cliCalls.size();
    }

    @Test
    void theNavigationHasThreePagesAndSettingsAndLogAreSeparate() {
        var window = window(false);

        assertEquals(List.of("Status", "Source folders", "File sync"), window.navigation().stream().map(p -> p.title()).toList());
        assertEquals("Settings", window.settings().title());
        assertEquals("Log", window.log().title());
        assertFalse(window.navigation().contains(window.settings()));
    }

    @Test
    void statusIsSelectedFirst() {
        var window = window(false);

        assertSame(window.status(), window.selectedPage().get());
    }

    @Test
    void selectingAPageShowsItAndRefreshesIt() {
        var window = window(false);

        window.select(window.status());
        window.select(window.settings());

        assertSame(window.settings(), window.selectedPage().get());
    }

    @Test
    void theWelcomeWizardReplacesTheNormalLayoutUntilItIsFinished() {
        assertTrue(window(true).showWelcome().get());
        assertFalse(window(false).showWelcome().get());
    }

    @Test
    void finishingTheWizardSwitchesToTheNormalLayout() {
        var window = window(true);
        fixture.service.database().addSource(home.toString(), "/my-files/Backup");
        window.tick(); // the wizard notices the CLI and the source
        window.start();

        window.welcome().finish().execute();

        assertFalse(window.showWelcome().get());
        assertEquals("1", fixture.service.database().getSetting("setup_completed"));
        assertFalse(fixture.service.setupNeeded());
    }

    @Test
    void whileTheWizardIsShownTicksDoNotRefreshTheOtherPages() {
        var window = window(true);

        window.tick();

        assertTrue(fixture.system.systemctlCalls.stream().noneMatch(c -> c.contains("is-active")), "the status page must not have been refreshed");
    }

    @Test
    void aTickRefreshesTheStatusPage() {
        var window = window(false);
        fixture.system.syncRunning = true;

        window.tick();

        assertTrue(window.status().running().get());
    }

    @Test
    void theSlowProbesRunEveryFifteenthTickNotEveryTick() {
        var window = window(false);

        for (var tick = 1; tick <= 14; tick++) window.tick();
        var before = cliCalls();
        assertEquals(0, before, "no CLI probe in the first 14 ticks");

        window.tick(); // the 15th

        assertTrue(cliCalls() > before, "the 15th tick runs the CLI probes");
        assertEquals("signed in", window.status().sessionText().get());
    }

    @Test
    void startRefreshesAndProbesAtOnce() {
        var window = window(false);

        window.start();

        assertEquals("signed in", window.status().sessionText().get());
        assertEquals("Proton Drive CLI cli-drive@0.8.0+06e8c605", window.status().cliVersion().get());
    }

    @Test
    void theCliMissingBannerFollowsWhetherTheCliExists() throws Exception {
        try (var bare = new Fixture(home.resolve("bare"))) {
            var window = new MainWindowViewModel(bare.service, Background.direct(), false, FolderPicker.NONE);
            assertTrue(window.cliMissing().get());

            bare.withCli();
            window.tick();

            assertFalse(window.cliMissing().get());
        }
    }

    @Test
    void theTrayStateIsBusyWhileARunGoesAndErrorWhenFilesFailed() {
        var window = window(false);
        assertEquals(TrayState.OK, window.trayState().getValue());

        var db = fixture.service.database();
        var source = db.addSource(home.toString(), "/my-files/Backup");
        db.upsertPending(source, List.of(new ScannedFile("a", 1, 1, null)));
        db.markError(source, List.of("a"), "boom");
        window.tick();
        assertEquals(TrayState.ERROR, window.trayState().getValue());

        fixture.system.syncRunning = true;
        window.tick();
        assertEquals(TrayState.BUSY, window.trayState().getValue(), "a running sync wins over old errors");

        fixture.system.syncRunning = false;
        db.markSynced(source, List.of("a"));
        window.tick();
        assertEquals(TrayState.OK, window.trayState().getValue());
    }

    @Test
    void afterTheRemovalStartedNothingTouchesTheDatabaseAgain() {
        var window = window(false);
        window.settings().cleanup().execute();
        window.tick();
        fixture.system.systemctlCalls.clear();

        window.settings().cleanup().execute(); // the second press: removal

        assertTrue(window.removing().get());
        assertTrue(window.removal().get() != null);
        window.tick();
        window.tick();
        assertTrue(fixture.system.systemctlCalls.stream().noneMatch(c -> c.contains("is-active")), "no polling after the removal");
    }

    @Test
    void aSignInRefreshesTheSessionAtOnce() {
        var window = window(false);
        fixture.system.loggedIn = false;
        window.status().refreshSlow();
        assertEquals("session expired", window.status().sessionText().get());

        window.settings().login().execute();

        assertEquals("signed in", window.status().sessionText().get());
    }

    @Test
    void theWizardAlsoProbesTheSessionEveryFifteenthTick() {
        var window = window(true);

        for (var tick = 1; tick <= 14; tick++) window.tick();
        var before = fixture.system.cliCalls.size();
        window.tick();

        assertTrue(fixture.system.cliCalls.size() > before);
    }
}
