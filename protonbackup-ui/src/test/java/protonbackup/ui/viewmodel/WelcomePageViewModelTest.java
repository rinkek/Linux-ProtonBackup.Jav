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
import protonbackup.core.CliSettings;
import protonbackup.ui.mvvm.Background;
import protonbackup.ui.testing.Fixture;

class WelcomePageViewModelTest {

    @TempDir Path home;

    private Fixture fixture;
    private WelcomePageViewModel page;
    private Optional<Path> picked = Optional.empty();

    @BeforeEach
    void setUp() throws Exception {
        fixture = new Fixture(home);
        page = new WelcomePageViewModel(fixture.service, Background.direct(), title -> picked);
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    private void refreshAll() {
        page.refresh();
        page.refreshSlow();
    }

    @Test
    void nothingIsDoneAtTheStart() {
        refreshAll();

        assertFalse(page.hasCli().get());
        assertFalse(page.signedIn().get());
        assertFalse(page.hasSource().get());
        assertFalse(page.timerEnabled().get());
        assertFalse(page.canFinish().get());
        assertFalse(page.finish().canExecute().get());
    }

    @Test
    void theStepsReflectWhatIsAlreadyInPlace() throws Exception {
        fixture.withCli();
        fixture.service.database().addSource(home.toString(), "/my-files/Backup");
        fixture.system.timerEnabled = true;

        refreshAll();

        assertTrue(page.hasCli().get());
        assertTrue(page.signedIn().get());
        assertTrue(page.hasSource().get());
        assertTrue(page.timerEnabled().get());
        assertTrue(page.canFinish().get());
    }

    @Test
    void theTimerIsOptionalForFinishing() throws Exception {
        fixture.withCli();
        fixture.service.database().addSource(home.toString(), "/my-files/Backup");

        refreshAll();

        assertFalse(page.timerEnabled().get());
        assertTrue(page.canFinish().get());
    }

    @Test
    void aSessionWithoutACliNeverCountsAsSignedIn() {
        refreshAll();

        assertFalse(page.signedIn().get());
    }

    @Test
    void downloadingTheCliInstallsItAndTicksTheStep() {
        new CliSettings(fixture.service.database()).setSkipChecksum(true);
        var messages = new ArrayList<String>();
        page.message().addListener((o, before, now) -> messages.add(now));

        page.downloadCli().execute();

        assertEquals(List.of("Downloading and verifying...", "CLI updated to 0.9.0."), messages);
        assertTrue(page.hasCli().get());
        assertTrue(page.signedIn().get());
    }

    @Test
    void anUnreadableVersionPageIsExplained() {
        fixture.pageFetchFails = true;

        page.downloadCli().execute();

        assertEquals("The version page could not be read.", page.message().get());
        assertFalse(page.hasCli().get());
    }

    /** The sign-in button works even before the CLI step was done, because it installs the CLI itself. */
    @Test
    void signingInInstallsTheCliWhenTheUserSkippedThatStep() {
        new CliSettings(fixture.service.database()).setSkipChecksum(true);
        fixture.system.loggedIn = false;

        assertTrue(page.signIn().canExecute().get(), "enabled although there is no CLI yet");
        page.signIn().execute();

        assertEquals("Signed in.", page.message().get());
        assertTrue(page.hasCli().get());
        assertTrue(page.signedIn().get());
    }

    @Test
    void aFailedSignInKeepsTheStepOpen() throws Exception {
        fixture.withCli();
        fixture.system.loggedIn = false;
        fixture.system.loginResult = new protonbackup.core.CliResult(1, "", "Login was cancelled");

        page.signIn().execute();

        assertEquals("Login was cancelled", page.message().get());
        assertFalse(page.signedIn().get());
    }

    @Test
    void addingTheFirstSource() throws Exception {
        var docs = Files.createDirectories(home.resolve("docs"));
        page.localPath().set(docs.toString());

        page.addSource().execute();

        assertEquals("Source folder added.", page.message().get());
        assertTrue(page.hasSource().get());
        assertEquals("/my-files/Backup", fixture.service.database().getSources().get(0).remotePath());
    }

    @Test
    void theSourceIsValidatedLikeOnTheSourceFoldersPage() throws Exception {
        page.localPath().set(home.resolve("missing").toString());
        page.addSource().execute();
        assertEquals("Choose an existing folder first.", page.message().get());

        page.localPath().set(Files.createDirectories(home.resolve("docs")).toString());
        page.remotePath().set("no-slash");
        page.addSource().execute();
        assertEquals("The destination path must start with /, for example /my-files/Backup.", page.message().get());
        assertFalse(page.hasSource().get());
    }

    @Test
    void browseUsesTheDialog() throws Exception {
        picked = Optional.of(home.resolve("chosen"));

        page.browse().execute();

        assertEquals(home.resolve("chosen").toString(), page.localPath().get());
    }

    @Test
    void turningOnTheTimer() {
        page.enableTimer().execute();

        assertEquals("Automatic syncing is on.", page.message().get());
        assertTrue(page.timerEnabled().get());
    }

    @Test
    void getStartedIsOnlyPossibleWhenTheEssentialsAreDoneAndMarksSetupAsCompleted() throws Exception {
        var finished = new ArrayList<String>();
        page.onFinished(() -> finished.add("finished"));
        page.finish().execute();
        assertTrue(finished.isEmpty(), "not possible yet");

        fixture.withCli();
        fixture.service.database().addSource(home.toString(), "/my-files/Backup");
        refreshAll();
        page.finish().execute();

        assertEquals(List.of("finished"), finished);
        assertEquals("1", fixture.service.database().getSetting("setup_completed"));
        assertFalse(fixture.service.setupNeeded());
    }
}
