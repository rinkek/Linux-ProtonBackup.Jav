package protonbackup.ui.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import protonbackup.core.CliResult;
import protonbackup.core.CliSettings;
import protonbackup.core.ScannedFile;
import protonbackup.ui.mvvm.Background;
import protonbackup.ui.testing.Fixture;

/**
 * The Status page is the reference implementation of the UI conventions, so these tests double as
 * their specification: snapshots shown on refresh, commands guarded and reporting back, a toggle whose
 * side effect only fires for the user's own action.
 */
class StatusPageViewModelTest {

    @TempDir Path home;

    private Fixture fixture;
    private StatusPageViewModel status;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new Fixture(home).withCli();
        status = new StatusPageViewModel(fixture.service, Background.direct(), ZoneOffset.UTC);
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    private long source() {
        return fixture.service.database().addSource(home.toString(), "/my-files/Backup");
    }

    // ---- what is shown ------------------------------------------------------------------

    @Test
    void beforeTheFirstRefreshTheDefaultsAreShown() {
        assertEquals("no runs yet", status.lastRun().get());
        assertEquals("no timer", status.nextRun().get());
        assertEquals("unknown", status.sessionText().get());
        assertEquals("unknown", status.cliVersion().get());
        assertEquals(0, status.syncedCount().get());
        assertFalse(status.running().get());
        assertFalse(status.updateAvailable().get());
        assertNull(status.message().get());
    }

    @Test
    void aRefreshShowsCountsRunsAndSystemdState() {
        var db = fixture.service.database();
        var source = source();
        db.upsertPending(source, List.of(new ScannedFile("a", 1, 1, null), new ScannedFile("b", 1, 1, null), new ScannedFile("c", 1, 1, null)));
        db.markSynced(source, List.of("a"));
        db.markError(source, List.of("b"), "boom");
        var run = db.startRun();
        db.finishRun(run, 1, 1, "partial");
        fixture.system.timerEnabled = true;
        fixture.system.syncRunning = true;
        fixture.system.listTimers = "NEXT LEFT LAST PASSED UNIT ACTIVATES\nThu 2026-10-01 08:47:17 CEST 9min -  - protonbackup-sync.timer protonbackup-sync.service\n";

        status.refresh();

        assertEquals(1, status.syncedCount().get());
        assertEquals(1, status.pendingCount().get());
        assertEquals(1, status.errorCount().get());
        assertTrue(status.lastRun().get().contains("1 uploaded, 1 failed (partly failed)"), status.lastRun().get());
        assertEquals("Thu 2026-10-01 08:47:17 CEST", status.nextRun().get());
        assertTrue(status.running().get());
        assertTrue(status.autoSync().get());
    }

    @Test
    void slowRefreshShowsTheCliVersionAndSession() {
        status.refreshSlow();
        assertEquals("Proton Drive CLI cli-drive@0.8.0+06e8c605", status.cliVersion().get());
        assertEquals("signed in", status.sessionText().get());

        fixture.system.loggedIn = false;
        status.refreshSlow();
        assertEquals("session expired", status.sessionText().get());
    }

    @Test
    void withoutACliTheVersionSaysNotFound() throws Exception {
        try (var bare = new Fixture(home.resolve("bare"))) {
            var page = new StatusPageViewModel(bare.service, Background.direct(), ZoneOffset.UTC);

            page.refreshSlow();

            assertEquals("not found", page.cliVersion().get());
            assertEquals("unknown", page.sessionText().get());
        }
    }

    @Test
    void whenSystemdCannotBeReachedThePageDegradesAndSaysSoOnce() {
        fixture.system.systemctlMissing = true;

        status.refresh();
        status.refresh();
        status.refresh();

        assertFalse(status.running().get());
        assertEquals("no timer", status.nextRun().get());
        assertTrue(status.message().get().startsWith("systemd could not be reached"), status.message().get());
        var seen = new ArrayList<String>();
        status.message().addListener((o, oldValue, newValue) -> seen.add(newValue));
        status.refresh();
        assertTrue(seen.isEmpty(), "the same problem is not announced again on every tick");
    }

    // ---- the update banner --------------------------------------------------------------

    @Test
    void aNewerCliShowsTheBanner() {
        status.refreshSlow();

        assertTrue(status.updateAvailable().get());
        assertEquals("0.9.0", status.availableVersion().get());
    }

    @Test
    void theUpdateCheckHappensOnlyWhenDue() {
        status.refreshSlow();
        fixture.fetched.clear();

        status.refreshSlow();

        assertTrue(fixture.fetched.isEmpty(), "checked less than a day ago");
    }

    @Test
    void aDismissedVersionDoesNotShowTheBanner() {
        new protonbackup.core.CliUpdateCheck(fixture.service.database(), fixture.service.installer(), m -> {}).dismiss("0.9.0");

        status.refreshSlow();

        assertFalse(status.updateAvailable().get());
        assertEquals("0.9.0", status.availableVersion().get());
    }

    @Test
    void dismissingHidesTheBannerAndRemembersIt() {
        status.refreshSlow();
        assertTrue(status.updateAvailable().get());

        status.dismissUpdate().execute();

        assertFalse(status.updateAvailable().get());
        assertEquals("0.9.0", new CliSettings(fixture.service.database()).dismissedVersion());
    }

    @Test
    void updatingInstallsTheCliAndHidesTheBanner() throws Exception {
        new CliSettings(fixture.service.database()).setSkipChecksum(true);
        status.refreshSlow();
        var messages = new ArrayList<String>();
        status.message().addListener((o, oldValue, newValue) -> messages.add(newValue));

        status.updateCli().execute();

        assertEquals(List.of("Downloading and verifying...", "CLI updated to 0.9.0."), messages);
        assertFalse(status.updateAvailable().get());
        assertEquals(List.of("https://proton.me/download/drive/cli/0.9.0/linux-x64/proton-drive"), fixture.downloaded);
    }

    @Test
    void updatingWaitsWhileARunIsInProgress() {
        fixture.system.syncRunning = true;
        status.refreshSlow();

        status.updateCli().execute();

        assertEquals("A run is in progress and will finish first. Try again shortly.", status.message().get());
        assertTrue(fixture.downloaded.isEmpty());
    }

    @Test
    void aFailedUpdateExplainsWhy() {
        status.refreshSlow();

        status.updateCli().execute(); // the page lists no checksum and that is not allowed

        assertTrue(status.message().get().contains("No checksum is listed"), status.message().get());
        assertTrue(status.updateAvailable().get(), "the banner stays: nothing was installed");
    }

    // ---- sync now and cancel ------------------------------------------------------------

    @Test
    void syncNowStartsARunAndShowsIt() {
        status.refresh();
        assertTrue(status.syncNow().canExecute().get());

        status.syncNow().execute();

        assertEquals("Run started.", status.message().get());
        assertTrue(fixture.system.systemctlCalls.contains("--user start --no-block protonbackup-sync.service"));
        assertTrue(status.running().get(), "the refresh after the action shows the run");
        assertFalse(status.syncNow().canExecute().get(), "Sync now is disabled while a run is going");
        assertTrue(status.cancel().canExecute().get());
    }

    @Test
    void syncNowChecksAgainAndDoesNotStartASecondRun() {
        // The page still thinks nothing runs (no refresh yet) but a run just started elsewhere.
        fixture.system.syncRunning = true;

        status.syncNow().execute();

        assertEquals("A run is already in progress.", status.message().get());
        assertTrue(fixture.system.systemctlCalls.stream().noneMatch(c -> c.contains(" start ")));
    }

    @Test
    void aFailedStartShowsWhatSystemctlSaid() {
        fixture.system.systemctlResults.put("start", new CliResult(1, "", "Unit protonbackup-sync.service not found.\n"));

        status.syncNow().execute();

        assertEquals("Unit protonbackup-sync.service not found.", status.message().get());
    }

    @Test
    void cancelStopsTheRun() {
        fixture.system.syncRunning = true;
        status.refresh();
        assertTrue(status.cancel().canExecute().get());

        status.cancel().execute();

        assertEquals("Stopped; the next run continues where this one left off.", status.message().get());
        assertFalse(status.running().get());
        assertFalse(status.cancel().canExecute().get(), "nothing left to cancel");
    }

    @Test
    void cancelIsDisabledWhenNothingRuns() {
        status.refresh();

        assertFalse(status.cancel().canExecute().get());
        status.cancel().execute();
        assertTrue(fixture.system.systemctlCalls.stream().noneMatch(c -> c.contains(" stop ")));
    }

    // ---- automatic syncing --------------------------------------------------------------

    @Test
    void turningAutomaticSyncingOnEnablesTheTimerAndShowsTheRealState() {
        status.refresh();
        assertFalse(status.autoSync().get());

        status.setAutoSync().execute(true);

        assertEquals("Automatic syncing is on.", status.message().get());
        assertTrue(fixture.system.systemctlCalls.contains("--user enable --now protonbackup-sync.timer"));
        assertTrue(status.autoSync().get());
    }

    @Test
    void turningItOffDisablesTheTimer() {
        fixture.system.timerEnabled = true;
        status.refresh();

        status.setAutoSync().execute(false);

        assertEquals("Automatic syncing is off.", status.message().get());
        assertFalse(status.autoSync().get());
    }

    /** The control shows what the user picked; a failure must not leave it lying: the refresh puts it back. */
    @Test
    void aFailedChangeShowsTheErrorAndTheStateStaysWhatItReallyIs() {
        fixture.system.systemctlResults.put("enable", new CliResult(1, "", "Failed to enable unit: Access denied\n"));
        status.refresh();

        status.setAutoSync().execute(true);

        assertEquals("Failed to enable unit: Access denied", status.message().get());
        assertFalse(status.autoSync().get());
    }

    @Test
    void aRefreshThatChangesTheStateNeverRunsTheSideEffect() {
        fixture.system.timerEnabled = true; // changed outside the app

        status.refresh();

        assertTrue(status.autoSync().get());
        assertTrue(fixture.system.systemctlCalls.stream().noneMatch(c -> c.contains(" enable ") || c.contains(" disable ")),
                "reflecting the timer's state must not enable or disable anything");
    }

    @Test
    void aCommandThatBlowsUpIsReportedToTheUserNotThrownIntoTheEventLoop() {
        fixture.system.systemctlMissing = true;

        status.setAutoSync().execute(true);

        assertTrue(status.message().get().startsWith("Something went wrong: "), status.message().get());
        assertTrue(status.setAutoSync().canExecute().get(), "and the control works again afterwards");
    }
}
