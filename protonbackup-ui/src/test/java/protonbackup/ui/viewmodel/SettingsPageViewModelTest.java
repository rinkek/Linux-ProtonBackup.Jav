package protonbackup.ui.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import protonbackup.core.Cleanup;
import protonbackup.core.CliResult;
import protonbackup.core.CliSettings;
import protonbackup.ui.mvvm.Background;
import protonbackup.ui.testing.Fixture;

class SettingsPageViewModelTest {

    @TempDir Path home;

    private Fixture fixture;
    private SettingsPageViewModel page;
    private int accountChanges;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new Fixture(home).withCli();
        page = new SettingsPageViewModel(fixture.service, Background.direct());
        page.onAccountChanged(() -> accountChanges++);
    }

    @AfterEach
    void tearDown() {
        if (!fixture.service.isClosed()) fixture.close();
    }

    private CliSettings cliSettings() {
        return new CliSettings(fixture.service.database());
    }

    // ---- the form -----------------------------------------------------------------------

    @Test
    void theFormShowsTheStoredValues() {
        fixture.service.database().setSetting("interval_minutes", "45");
        cliSettings().setPlatform("linux-x64-baseline");
        cliSettings().setSkipChecksum(true);

        page.refresh();

        assertEquals(45, page.intervalMinutes().get());
        assertEquals(CliSettings.DEFAULT_VERSION_PAGE_URL, page.versionPageUrl().get());
        assertEquals(CliSettings.DEFAULT_DOWNLOAD_TEMPLATE, page.downloadTemplate().get());
        assertEquals("linux-x64-baseline", page.platform().get());
        assertTrue(page.skipChecksum().get());
        assertEquals(fixture.paths.binDir().resolve("proton-drive").toString(), page.cliPath().get());
    }

    @Test
    void withoutAStoredIntervalTheDefaultStaysAndWithoutACliThePathIsEmpty() throws Exception {
        try (var bare = new Fixture(home.resolve("bare"))) {
            var other = new SettingsPageViewModel(bare.service, Background.direct());

            other.refresh();

            assertEquals(30, other.intervalMinutes().get());
            assertEquals("", other.cliPath().get());
        }
    }

    /** The original overwrote the text boxes on every tick, throwing away what the user was typing. */
    @Test
    void refreshingNeverOverwritesWhatTheUserIsTyping() {
        page.refresh();
        page.versionPageUrl().set("https://example.test/being-typed");
        page.intervalMinutes().set(99);

        page.refresh();
        page.refresh();

        assertEquals("https://example.test/being-typed", page.versionPageUrl().get());
        assertEquals(99, page.intervalMinutes().get());
    }

    @Test
    void savingTheCliSettingsStoresThemAndReloadsTheForm() {
        page.refresh();
        page.versionPageUrl().set("https://example.test/versions.html");
        page.downloadTemplate().set("https://example.test/{version}/{platform}");
        page.platform().set("linux-x64-baseline");
        page.skipChecksum().set(true);

        page.saveCliSettings().execute();

        assertEquals("CLI settings saved.", page.message().get());
        assertEquals("https://example.test/versions.html", cliSettings().versionPageUrl());
        assertEquals("https://example.test/{version}/{platform}", cliSettings().downloadTemplate());
        assertEquals("linux-x64-baseline", cliSettings().platform());
        assertTrue(cliSettings().skipChecksum());
    }

    @Test
    void restoringDefaultsResetsTheCliSettingsAndTheIntervalFieldButNotTheStoredInterval() {
        fixture.service.database().setSetting("interval_minutes", "45");
        cliSettings().setVersionPageUrl("https://example.test/versions.html");
        cliSettings().setSkipChecksum(true);
        page.refresh();
        assertEquals(45, page.intervalMinutes().get());

        page.restoreDefaults().execute();

        assertEquals("Defaults restored; apply the interval with Save interval.", page.message().get());
        assertEquals(CliSettings.DEFAULT_VERSION_PAGE_URL, page.versionPageUrl().get());
        assertFalse(page.skipChecksum().get());
        assertEquals(30, page.intervalMinutes().get(), "the field shows the default and stays there until it is saved");
        assertEquals("45", fixture.service.database().getSetting("interval_minutes"));
    }

    // ---- syncing ------------------------------------------------------------------------

    @Test
    void applyingTheIntervalWritesTheDropInAndRemembersTheValue() throws Exception {
        page.intervalMinutes().set(45);

        page.applyInterval().execute();

        assertEquals("Interval set to 45 minutes.", page.message().get());
        assertTrue(Files.readString(fixture.paths.userUnitDir().resolve("protonbackup-sync.timer.d/interval.conf")).contains("OnUnitActiveSec=45min"));
        assertEquals("45", fixture.service.database().getSetting("interval_minutes"));
    }

    @Test
    void anIntervalBelowOneMinuteIsRefused() {
        page.intervalMinutes().set(0);

        page.applyInterval().execute();

        assertEquals("The interval must be at least one minute.", page.message().get());
        assertNull(fixture.service.database().getSetting("interval_minutes"));
        assertTrue(fixture.system.systemctlCalls.isEmpty());
    }

    @Test
    void rewritingTheUnitsUsesTheIntervalOnScreen() throws Exception {
        page.intervalMinutes().set(15);

        page.installUnits().execute();

        assertEquals("systemd units written.", page.message().get());
        assertTrue(Files.readString(fixture.paths.userUnitDir().resolve("protonbackup-sync.timer")).contains("OnUnitActiveSec=15min"));
    }

    // ---- the account --------------------------------------------------------------------

    @Test
    void signingInOpensTheBrowserStepAndShowsTheSession() {
        fixture.system.loggedIn = false;
        var messages = new ArrayList<String>();
        page.message().addListener((o, before, now) -> messages.add(now));

        page.login().execute();

        assertEquals(List.of("Your browser will open; finish signing in there.", "Signed in."), messages);
        assertEquals("signed in", page.sessionText().get());
        assertEquals(1, accountChanges, "the rest of the window re-reads the session at once");
    }

    @Test
    void signInIsFadedWhileSignedInAndComesBackAfterSigningOut() {
        fixture.system.loggedIn = true;
        page.refreshSession();
        assertEquals("signed in", page.sessionText().get());
        assertFalse(page.login().canExecute().get(), "there is nothing to sign in to");

        page.logout().execute();

        assertTrue(page.login().canExecute().get());
    }

    /** The user's requirement: one click on Sign in works even when the CLI has not been downloaded. */
    @Test
    void signingInWithoutTheCliInstallsItFirst() throws Exception {
        try (var bare = new Fixture(home.resolve("bare"))) {
            new CliSettings(bare.service.database()).setSkipChecksum(true);
            bare.system.loggedIn = false;
            var other = new SettingsPageViewModel(bare.service, Background.direct());
            var messages = new ArrayList<String>();
            other.message().addListener((o, before, now) -> messages.add(now));

            other.login().execute();

            assertEquals(List.of(
                    "The Proton Drive CLI is not installed yet; downloading it first...",
                    "Your browser will open; finish signing in there.",
                    "Signed in."), messages);
            assertEquals(bare.paths.binDir().resolve("proton-drive").toString(), other.cliPath().get(), "the new CLI is shown at once");
            assertEquals("signed in", other.sessionText().get());
        }
    }

    @Test
    void aFailedSignInShowsTheReason() {
        fixture.system.loggedIn = false;
        fixture.system.loginResult = new CliResult(1, "", "Login was cancelled\n");

        page.login().execute();

        assertEquals("Login was cancelled", page.message().get());
        assertEquals("session expired", page.sessionText().get());
    }

    @Test
    void signingOut() {
        page.logout().execute();

        assertEquals("Signed out.", page.message().get());
        assertEquals("session expired", page.sessionText().get());
        assertFalse(fixture.system.loggedIn);
    }

    @Test
    void signingOutWithoutACliSaysSo() throws Exception {
        try (var bare = new Fixture(home.resolve("bare"))) {
            var other = new SettingsPageViewModel(bare.service, Background.direct());

            other.logout().execute();

            assertEquals("CLI not found", other.message().get());
        }
    }

    // ---- the CLI ------------------------------------------------------------------------

    @Test
    void checkingForUpdatesSaysWhatIsAvailable() {
        page.checkForUpdate().execute();

        assertEquals("Version 0.9.0 is available (currently 0.8.0).", page.message().get());

        fixture.system.version = "Proton Drive CLI cli-drive@0.9.0+abc";
        page.checkForUpdate().execute();
        assertEquals("You are up to date on version 0.9.0.", page.message().get());
    }

    @Test
    void checkingWithAnUnreadablePageSaysSo() {
        fixture.pageFetchFails = true;

        page.checkForUpdate().execute();

        assertEquals("The version page could not be read.", page.message().get());
    }

    @Test
    void updatingTheCliShowsProgressThenTheOutcome() {
        cliSettings().setSkipChecksum(true);
        var messages = new ArrayList<String>();
        page.message().addListener((o, before, now) -> messages.add(now));

        page.updateCli().execute();

        assertEquals(List.of("Downloading and verifying...", "CLI updated to 0.9.0."), messages);
        assertEquals(1, accountChanges);
    }

    @Test
    void restoringThePreviousVersionNeedsAKeptVersion() throws Exception {
        page.rollbackCli().execute();
        assertEquals("No previous version was kept.", page.message().get());

        Files.writeString(fixture.paths.binDir().resolve("proton-drive.previous"), "previous");
        page.rollbackCli().execute();
        assertEquals("The previous version was restored.", page.message().get());
        assertEquals("previous", Files.readString(fixture.paths.binDir().resolve("proton-drive")));
    }

    // ---- the buttons that must not run at the same time -------------------------------------

    @Test
    void whileOneLongActionRunsTheOthersAreDisabled() {
        var worker = new ArrayDeque<Runnable>();
        var ui = new ArrayDeque<Runnable>();
        var background = Background.withExecutors(worker::add, worker::add, worker::add, ui::add, () -> true);
        var slow = new SettingsPageViewModel(fixture.service, background);

        slow.login().execute();
        assertTrue(slow.busy().get());
        assertFalse(slow.updateCli().canExecute().get());
        assertFalse(slow.logout().canExecute().get());
        assertFalse(slow.login().canExecute().get());
        assertFalse(slow.cleanup().canExecute().get());

        slow.updateCli().execute(); // ignored while busy
        assertEquals(1, worker.size());

        while (!worker.isEmpty() || !ui.isEmpty()) {
            if (!worker.isEmpty()) worker.poll().run();
            if (!ui.isEmpty()) ui.poll().run();
        }
        assertFalse(slow.busy().get());
        assertTrue(slow.updateCli().canExecute().get());
    }

    // ---- removal ------------------------------------------------------------------------

    @Test
    void theFirstPressOnlyAsksForConfirmation() {
        page.cleanup().execute();

        assertEquals(SettingsPageViewModel.CONFIRM_CLEANUP, page.message().get());
        assertTrue(page.confirmCleanup().get());
        assertTrue(Files.exists(fixture.paths.dataDir()), "nothing is removed yet");
    }

    @Test
    void theSecondPressRemovesEverythingAndReportsTheSteps() throws Exception {
        var started = new ArrayList<String>();
        var finished = new ArrayList<List<Cleanup.Step>>();
        page.onRemoval(() -> started.add("started"), finished::add);
        new protonbackup.core.SystemdManager(fixture.paths, "/usr/bin/protonbackup", fixture.system).installUnits();

        page.cleanup().execute();
        page.cleanup().execute();

        assertEquals(List.of("started"), started);
        assertEquals(1, finished.size());
        assertTrue(Cleanup.succeeded(finished.get(0)), finished.get(0).toString());
        assertFalse(Files.exists(fixture.paths.dataDir()));
        assertFalse(Files.exists(fixture.paths.userUnitDir().resolve("protonbackup-sync.timer")));
        assertFalse(page.confirmCleanup().get());
        assertTrue(fixture.system.cliCalls.contains("auth logout"), "signed out of Proton first");
    }

    @Test
    void lateBackgroundWorkAfterTheRemovalStaysQuiet() {
        page.cleanup().execute();
        page.cleanup().execute();
        page.clearMessage();

        page.refresh();
        page.refreshSession();

        assertNull(page.message().get(), "a failure of work still in flight must not show up after the removal");
    }
}
