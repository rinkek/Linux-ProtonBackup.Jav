package protonbackup.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import protonbackup.core.CliRelease.CliDownload;

/**
 * CliInstaller had no C# tests; its behavior (checksum check, baseline fallback, rollback) was
 * verified by hand. Injected fetch, download and run steps make all of it testable offline.
 */
class CliInstallerTest {

    private static final String VERSION_PAGE = """
            <h1>Proton Drive CLI 0.9.0</h1>
            <table><tr><td>linux/x64</td>
            <td><a href="https://proton.me/download/drive/cli/0.9.0/linux-x64/proton-drive">link</a></td>
            <td><code>%s</code></td></tr></table>
            """;

    @TempDir Path home;

    private AppPaths paths;
    private final FakeSettingsStore store = new FakeSettingsStore();
    private final List<String> messages = new ArrayList<>();
    private final List<String> runCalls = new ArrayList<>();
    private final LinkedList<CliResult> runResults = new LinkedList<>();
    private final List<Path> downloadTargets = new ArrayList<>();
    private byte[] downloadedBytes = "#!/bin/sh\necho ok\n".getBytes(StandardCharsets.UTF_8);
    private String pageHtml = VERSION_PAGE.formatted("0".repeat(128));
    private IOException fetchFailure;
    private IOException downloadFailure;

    @BeforeEach
    void setUp() {
        paths = AppPaths.forHome(home);
        runResults.add(new CliResult(0, "ok", ""));
    }

    private CliInstaller installer() {
        return new CliInstaller(
                paths,
                store,
                url -> {
                    if (fetchFailure != null) throw fetchFailure;
                    return pageHtml;
                },
                (url, destination) -> {
                    if (downloadFailure != null) throw downloadFailure;
                    downloadTargets.add(destination);
                    Files.write(destination, downloadedBytes);
                },
                (executable, arguments) -> {
                    runCalls.add(String.join(" ", arguments));
                    return runResults.size() > 1 ? runResults.removeFirst() : runResults.getFirst();
                },
                messages::add);
    }

    private static CliRelease release(String sha512) {
        return new CliRelease("0.9.0", List.of(new CliDownload("linux/x64", "https://proton.me/.../proton-drive", sha512)));
    }

    private static String sha512Of(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(bytes));
    }

    private List<String> filesInBinDir() throws IOException {
        if (!Files.isDirectory(paths.binDir())) return List.of();
        try (var entries = Files.list(paths.binDir())) {
            return entries.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    // ---- fetchRelease -------------------------------------------------------------------

    @Test
    void fetchReleaseParsesARealLookingPage() throws Exception {
        pageHtml = VERSION_PAGE.formatted(sha512Of(downloadedBytes));

        var release = installer().fetchRelease();

        assertEquals("0.9.0", release.version());
    }

    @Test
    void fetchReleaseReturnsNullAndLogsOnANetworkFailure() throws Exception {
        fetchFailure = new IOException("no route to host");

        assertNull(installer().fetchRelease());
        assertTrue(messages.stream().anyMatch(m -> m.contains("Update check failed") && m.contains("no route to host")));
    }

    @Test
    void fetchReleaseLogsWhenThePageIsNotRecognised() throws Exception {
        pageHtml = "<html>maintenance</html>";

        assertNull(installer().fetchRelease());
        assertTrue(messages.stream().anyMatch(m -> m.contains("could not be read")));
    }

    // ---- getInstalledVersion ------------------------------------------------------------

    @Test
    void getInstalledVersionParsesTheAtSignAndStripsTheBuildSuffix() throws Exception {
        runResults.clear();
        runResults.add(new CliResult(0, "Proton Drive CLI cli-drive@0.9.0+06e8c605\n", ""));
        Files.createDirectories(paths.binDir());
        Files.writeString(installer().installedPath(), "x");

        assertEquals("0.9.0", installer().getInstalledVersion());
    }

    @Test
    void getInstalledVersionIsNullWhenNothingIsInstalled() throws Exception {
        assertNull(installer().getInstalledVersion());
    }

    @Test
    void getInstalledVersionIsNullWhenTheBinaryDoesNotStart() throws Exception {
        Files.createDirectories(paths.binDir());
        Files.writeString(installer().installedPath(), "x");
        var broken = new CliInstaller(paths, store, u -> "", (u, d) -> {}, (e, a) -> {
            throw new IOException("exec format error");
        }, messages::add);

        assertNull(broken.getInstalledVersion());
    }

    // ---- install: checksum verification -------------------------------------------------

    @Test
    void installRefusesWhenNoChecksumIsListedAndNoneIsAllowed() throws Exception {
        var outcome = installer().install(release(null));

        assertFalse(outcome.success());
        assertTrue(outcome.message().contains("No checksum is listed"));
        assertTrue(downloadTargets.isEmpty(), "nothing may be downloaded without a checksum");
    }

    @Test
    void installWithAllowMissingChecksumProceedsAnyway() throws Exception {
        var installer = installer();

        var outcome = installer.install(release(null), true);

        assertTrue(outcome.success());
        assertTrue(Files.isRegularFile(installer.installedPath()));
    }

    @Test
    void theSkipChecksumSettingAlsoAllowsAMissingChecksum() throws Exception {
        new CliSettings(store).setSkipChecksum(true);

        assertTrue(installer().install(release(null)).success());
    }

    @Test
    void installRejectsAMismatchedChecksumAndDoesNotInstall() throws Exception {
        downloadedBytes = "actual bytes".getBytes(StandardCharsets.UTF_8);
        var installer = installer();

        var outcome = installer.install(release("0".repeat(128)));

        assertFalse(outcome.success());
        assertTrue(outcome.message().contains("checksum did not match"));
        assertFalse(Files.exists(installer.installedPath()));
        assertEquals(List.of(), filesInBinDir(), "the rejected download must not stay behind");
    }

    @Test
    void installAcceptsAMatchingChecksumCaseInsensitively() throws Exception {
        downloadedBytes = "actual bytes".getBytes(StandardCharsets.UTF_8);
        var installer = installer();

        var outcome = installer.install(release(sha512Of(downloadedBytes).toUpperCase()));

        assertTrue(outcome.success());
        assertEquals("0.9.0", outcome.version());
        assertArrayEquals(downloadedBytes, Files.readAllBytes(installer.installedPath()));
        assertEquals("0.9.0", new CliSettings(store).lastSeenVersion());
    }

    @Test
    void theInstalledFileIsMadeExecutable() throws Exception {
        var installer = installer();

        installer.install(release(null), true);

        assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), Files.getPosixFilePermissions(installer.installedPath()));
    }

    @Test
    void theDownloadGoesNextToTheFinalFileNotIntoTmp() throws Exception {
        var installer = installer();

        installer.install(release(null), true);

        assertEquals(1, downloadTargets.size());
        assertEquals(paths.binDir(), downloadTargets.get(0).getParent());
        assertEquals(List.of("proton-drive"), filesInBinDir());
    }

    @Test
    void aFailedDownloadIsReportedAndLeavesNothingBehind() throws Exception {
        downloadFailure = new IOException("connection reset");

        var outcome = installer().install(release(null), true);

        assertFalse(outcome.success());
        assertTrue(outcome.message().contains("Download failed"));
        assertTrue(outcome.message().contains("connection reset"));
        assertEquals(List.of(), filesInBinDir());
    }

    @Test
    void aBinaryThatDoesNotStartIsNotInstalled() throws Exception {
        runResults.clear();
        runResults.add(new CliResult(126, "", "cannot execute binary file"));
        var installer = installer();

        var outcome = installer.install(release(null), true);

        assertFalse(outcome.success());
        assertTrue(outcome.message().contains("does not start"));
        assertFalse(Files.exists(installer.installedPath()));
        assertEquals(List.of(), filesInBinDir());
    }

    @Test
    void staleDownloadsFromACrashedAttemptAreSweptAway() throws Exception {
        Files.createDirectories(paths.binDir());
        Files.writeString(paths.binDir().resolve("proton-drive-0.8.0-123.download"), "partial");

        installer().install(release(null), true);

        assertEquals(List.of("proton-drive"), filesInBinDir());
    }

    // ---- install: baseline CPU fallback -------------------------------------------------

    @Test
    void installFallsBackToBaselineOnIllegalInstruction() throws Exception {
        runResults.clear();
        runResults.add(new CliResult(132, "", "Illegal instruction")); // default build crashes
        runResults.add(new CliResult(0, "ok", "")); // baseline build starts
        var release = new CliRelease("0.9.0", List.of(
                new CliDownload("linux/x64", "https://proton.me/.../proton-drive", null),
                new CliDownload("linux/x64-baseline", "https://proton.me/.../proton-drive", null)));

        var outcome = installer().install(release, true);

        assertTrue(outcome.success());
        assertEquals(CliSettings.BASELINE_PLATFORM, store.getSetting("cli_platform"));
        assertEquals(2, runCalls.size()); // one "version" smoke test per attempt
    }

    @Test
    void anIllegalInstructionOnTheBaselineBuildIsNotRetriedForever() throws Exception {
        new CliSettings(store).setPlatform(CliSettings.BASELINE_PLATFORM);
        runResults.clear();
        runResults.add(new CliResult(132, "", "Illegal instruction"));

        var outcome = installer().install(release(null), true);

        assertFalse(outcome.success());
        assertEquals(1, runCalls.size());
    }

    // ---- install: rollback after a broken session ---------------------------------------

    @Test
    void installRollsBackWhenTheSessionBreaksAfterUpdating() throws Exception {
        runResults.clear();
        runResults.add(new CliResult(0, "ok", "")); // smoke test of the new binary
        runResults.add(new CliResult(1, "", "You need to login first")); // session check after the swap
        var installer = installer();
        Files.createDirectories(paths.binDir());
        Files.write(installer.installedPath(), "old version".getBytes(StandardCharsets.UTF_8));

        var outcome = installer.install(release(null), true);

        assertFalse(outcome.success());
        assertTrue(outcome.message().contains("previous version was restored"));
        assertEquals("old version", Files.readString(installer.installedPath()));
        assertEquals(List.of("proton-drive"), filesInBinDir());
    }

    @Test
    void theOldVersionIsKeptAsPreviousAfterASuccessfulUpdate() throws Exception {
        var installer = installer();
        Files.createDirectories(paths.binDir());
        Files.write(installer.installedPath(), "old version".getBytes(StandardCharsets.UTF_8));

        var outcome = installer.install(release(null), true);

        assertTrue(outcome.success());
        assertEquals("old version", Files.readString(installer.previousPath()));
        assertEquals(new String(downloadedBytes, StandardCharsets.UTF_8), Files.readString(installer.installedPath()));
    }

    @Test
    void aSessionCheckThatFailsForAnotherReasonDoesNotRollBack() throws Exception {
        runResults.clear();
        runResults.add(new CliResult(0, "ok", ""));
        runResults.add(new CliResult(1, "", "network unreachable"));
        var installer = installer();
        Files.createDirectories(paths.binDir());
        Files.writeString(installer.installedPath(), "old");

        assertTrue(installer.install(release(null), true).success());
    }

    // ---- rollback() ---------------------------------------------------------------------

    @Test
    void rollbackRestoresThePreviousVersion() throws Exception {
        var installer = installer();
        Files.createDirectories(paths.binDir());
        Files.writeString(installer.previousPath(), "previous");
        Files.writeString(installer.installedPath(), "current");

        var outcome = installer.rollback();

        assertTrue(outcome.success());
        assertEquals("previous", Files.readString(installer.installedPath()));
        assertFalse(Files.exists(installer.previousPath()));
    }

    @Test
    void rollbackFailsWhenNothingWasKept() {
        var outcome = installer().rollback();

        assertFalse(outcome.success());
        assertTrue(outcome.message().contains("No previous version"));
    }
}
