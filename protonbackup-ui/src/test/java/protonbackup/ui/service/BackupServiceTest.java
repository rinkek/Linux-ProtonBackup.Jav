package protonbackup.ui.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import protonbackup.core.CliResult;
import protonbackup.core.SessionState;
import protonbackup.ui.testing.Fixture;

class BackupServiceTest {

    @TempDir Path home;

    // ---- finding and probing the CLI ----------------------------------------------------

    @Test
    void withoutACliTheProbesAnswerUnknownAndNotFound() throws Exception {
        try (var fixture = new Fixture(home)) {
            var service = fixture.service;

            assertNull(service.cliPath());
            assertEquals(SessionState.UNKNOWN, service.session());
            assertEquals("not found", service.cliVersion());
            assertEquals(new CliResult(1, "", "CLI not found"), service.login());
            assertEquals(new CliResult(1, "", "CLI not found"), service.logout());
            assertTrue(fixture.system.cliCalls.isEmpty(), "no program may be started when there is no CLI");
        }
    }

    @Test
    void theCliIsFoundOnceItIsInstalledAndLookedUpAgain() throws Exception {
        try (var fixture = new Fixture(home)) {
            assertNull(fixture.service.cliPath());

            fixture.withCli();

            assertEquals(fixture.paths.binDir().resolve("proton-drive").toString(), fixture.service.cliPath());
        }
    }

    @Test
    void sessionMapsTheCliAnswerToActiveExpiredOrUnknown() throws Exception {
        try (var fixture = new Fixture(home).withCli()) {
            assertEquals(SessionState.ACTIVE, fixture.service.session());

            fixture.system.loggedIn = false;
            assertEquals(SessionState.EXPIRED, fixture.service.session());

            fixture.system.cliBroken = true;
            assertEquals(SessionState.UNKNOWN, fixture.service.session());
        }
    }

    @Test
    void theSessionIsProbedWithASingleCallNotTheDaemonsThreeAttempts() throws Exception {
        try (var fixture = new Fixture(home).withCli()) {
            fixture.system.loggedIn = false;

            fixture.service.session();

            assertEquals(List.of("filesystem list /my-files --json"), fixture.system.cliCalls);
        }
    }

    @Test
    void theVersionIsTheFirstLineOrUnknownWhenTheCliWillNotStart() throws Exception {
        try (var fixture = new Fixture(home).withCli()) {
            assertEquals("Proton Drive CLI cli-drive@0.8.0+06e8c605", fixture.service.cliVersion());

            fixture.system.cliBroken = true;
            assertEquals("unknown", fixture.service.cliVersion());
        }
    }

    @Test
    void aCliThatCannotStartMakesLoginFailWithTheReasonNotAnException() throws Exception {
        try (var fixture = new Fixture(home).withCli()) {
            fixture.system.cliBroken = true;

            var result = fixture.service.login();

            assertFalse(result.ok());
            assertTrue(result.output().contains("Exec format error"), result.output());
        }
    }

    // ---- sign in, installing the CLI first when it is missing ---------------------------

    @Test
    void signInWithAnInstalledCliOnlyOpensTheBrowserStep() throws Exception {
        try (var fixture = new Fixture(home).withCli()) {
            fixture.system.loggedIn = false;
            var messages = new ArrayList<String>();

            var outcome = fixture.service.signIn(messages::add);

            assertEquals(new SignInOutcome(true, "Signed in."), outcome);
            assertEquals(List.of("Your browser will open; finish signing in there."), messages);
            assertTrue(fixture.fetched.isEmpty(), "an installed CLI is not downloaded again");
            assertTrue(fixture.system.cliCalls.contains("auth login"));
        }
    }

    /** The user's requirement: one click on "Sign in" must work even when the CLI has not been downloaded yet. */
    @Test
    void signInWithoutTheCliDownloadsAndInstallsItFirstThenLogsIn() throws Exception {
        try (var fixture = new Fixture(home)) {
            fixture.system.loggedIn = false;
            fixture.versionPage = Fixture.VERSION_PAGE.replace("</a></td>", "</a></td><td><code>" + "0".repeat(128) + "</code></td>");
            new protonbackup.core.CliSettings(fixture.service.database()).setSkipChecksum(true);
            var messages = new ArrayList<String>();

            var outcome = fixture.service.signIn(messages::add);

            assertEquals(new SignInOutcome(true, "Signed in."), outcome);
            assertEquals(List.of(
                    "The Proton Drive CLI is not installed yet; downloading it first...",
                    "Your browser will open; finish signing in there."), messages);
            assertEquals(List.of("https://proton.me/download/drive/cli/index.html"), fixture.fetched);
            assertEquals(List.of("https://proton.me/download/drive/cli/0.9.0/linux-x64/proton-drive"), fixture.downloaded);
            assertNotNull(fixture.service.cliPath(), "the new CLI is found again");
            // order: the smoke test of the downloaded binary, then the login
            var calls = fixture.system.cliCalls;
            assertTrue(calls.indexOf("version") < calls.indexOf("auth login"), calls.toString());
        }
    }

    @Test
    void signInStopsWhenTheVersionPageCannotBeRead() throws Exception {
        try (var fixture = new Fixture(home)) {
            fixture.pageFetchFails = true;

            var outcome = fixture.service.signIn(m -> {});

            assertEquals(new SignInOutcome(false, "The version page could not be read."), outcome);
            assertFalse(fixture.system.cliCalls.contains("auth login"), "no login without a CLI");
            assertNull(fixture.service.cliPath());
        }
    }

    @Test
    void signInStopsWhenTheInstallFailsAndSaysWhy() throws Exception {
        try (var fixture = new Fixture(home)) {
            // the page lists no checksum and the user has not allowed that

            var outcome = fixture.service.signIn(m -> {});

            assertFalse(outcome.success());
            assertTrue(outcome.message().contains("No checksum is listed"), outcome.message());
            assertTrue(fixture.downloaded.isEmpty());
            assertFalse(fixture.system.cliCalls.contains("auth login"));
        }
    }

    @Test
    void aFailedLoginReportsTheCliOutput() throws Exception {
        try (var fixture = new Fixture(home).withCli()) {
            fixture.system.loginResult = new CliResult(1, "", "  Login was cancelled\n");

            var outcome = fixture.service.signIn(m -> {});

            assertEquals(new SignInOutcome(false, "Login was cancelled"), outcome);
        }
    }

    // ---- installing the CLI -------------------------------------------------------------

    @Test
    void installCliReportsProgressAndLooksTheCliUpAgain() throws Exception {
        try (var fixture = new Fixture(home)) {
            new protonbackup.core.CliSettings(fixture.service.database()).setSkipChecksum(true);
            var messages = new ArrayList<String>();

            var outcome = fixture.service.installCli(messages::add);

            assertTrue(outcome.success(), outcome.message());
            assertEquals(List.of("Downloading and verifying..."), messages);
            assertNotNull(fixture.service.cliPath());
            assertTrue(Files.isRegularFile(fixture.paths.binDir().resolve("proton-drive")));
        }
    }

    @Test
    void installCliWithAnUnreadablePageFailsWithoutInstalling() throws Exception {
        try (var fixture = new Fixture(home)) {
            fixture.pageFetchFails = true;

            var outcome = fixture.service.installCli(m -> {});

            assertFalse(outcome.success());
            assertEquals("The version page could not be read.", outcome.message());
        }
    }

    // ---- setup and the daemon's path ----------------------------------------------------

    @Test
    void setupIsNeededUntilItWasCompleted() throws Exception {
        try (var fixture = new Fixture(home)) {
            assertTrue(fixture.service.setupNeeded());

            fixture.service.database().setSetting("setup_completed", "1");

            assertFalse(fixture.service.setupNeeded());
        }
    }

    @Test
    void theUnitsPointAtTheDaemonNextToTheUiLauncherOrAtLeastNameAPath() throws Exception {
        try (var fixture = new Fixture(home)) {
            // /usr/bin/protonbackup-ui is the fake own launcher; nothing named protonbackup sits next to it or at /usr/bin here
            var executable = fixture.service.systemd().executable();

            assertTrue(executable.endsWith("/protonbackup"), executable);
        }
    }
}
