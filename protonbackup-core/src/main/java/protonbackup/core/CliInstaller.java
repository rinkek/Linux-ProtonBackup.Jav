package protonbackup.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Fetches the Proton CLI, verifies its checksum and puts it in place. The previous version is
 * kept, so a failed session check after updating can be rolled back.
 */
public final class CliInstaller implements CliVersionSource {

    /** Result of an install or rollback; {@code version} is set after a successful install. */
    public record InstallOutcome(boolean success, String message, String version) {}

    @FunctionalInterface
    public interface TextFetcher {
        String fetchText(String url) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    public interface Downloader {
        void downloadTo(String url, Path destination) throws IOException, InterruptedException;
    }

    private final AppPaths paths;
    private final CliSettings settings;
    private final TextFetcher fetcher;
    private final Downloader downloader;
    private final CommandRunner runner;
    private final Consumer<String> log;

    public CliInstaller(
            AppPaths paths,
            SettingsStore store,
            TextFetcher fetcher,
            Downloader downloader,
            CommandRunner runner,
            Consumer<String> log) {
        this.paths = paths;
        this.settings = new CliSettings(store);
        this.fetcher = fetcher;
        this.downloader = downloader;
        this.runner = runner;
        this.log = log;
    }

    /** The installer for real use: HTTPS downloads and real processes. */
    public static CliInstaller create(AppPaths paths, SettingsStore store, Consumer<String> log) {
        var http = new HttpFetcher();
        return new CliInstaller(paths, store, http, http, new ProcessRunner(), log);
    }

    public Path installedPath() {
        return paths.binDir().resolve("proton-drive");
    }

    public Path previousPath() {
        return paths.binDir().resolve("proton-drive.previous");
    }

    @Override
    public CliRelease fetchRelease() throws InterruptedException {
        try {
            var release = CliVersionPage.parse(fetcher.fetchText(settings.versionPageUrl()));
            if (release == null) log.accept("The version page could not be read; its layout may have changed.");
            return release;
        } catch (IOException e) {
            // A failed check must never block a sync; only log it.
            log.accept("Update check failed: " + e.getMessage());
            return null;
        }
    }

    @Override
    public String getInstalledVersion() throws InterruptedException {
        if (!Files.exists(installedPath())) return null;
        CliResult result;
        try {
            result = runner.run(installedPath().toString(), List.of("version"));
        } catch (IOException e) {
            return null;
        }
        if (!result.ok()) return null;

        var line = result.stdOut().split("\n", 2)[0];
        var at = line.lastIndexOf('@');
        if (at < 0) return null;
        var version = line.substring(at + 1).split("\\+", 2)[0].trim();
        return version.isEmpty() ? null : version;
    }

    public InstallOutcome install(CliRelease release) throws InterruptedException {
        return install(release, false);
    }

    public InstallOutcome install(CliRelease release, boolean allowMissingChecksum) throws InterruptedException {
        var platform = settings.platform();
        var download = release.forPlatform(platform);
        var url = download != null ? download.url() : settings.buildDownloadUrl(release.version(), platform);
        var expectedChecksum = download != null ? download.sha512() : null;

        if (expectedChecksum == null && !settings.skipChecksum() && !allowMissingChecksum) {
            return failure("No checksum is listed for " + platform + ". Only proceed if you trust the source.");
        }

        Path temporary = null;
        try {
            Files.createDirectories(paths.binDir());
            removeStaleDownloads();
            // Next to the final file, not in /tmp: the same filesystem (so the move is an atomic
            // rename, never a cross-device copy) and room for a large binary even when /tmp is small.
            temporary = Files.createTempFile(paths.binDir(), "proton-drive-" + release.version() + "-", ".download");

            log.accept("Downloading " + url);
            downloader.downloadTo(url, temporary);

            if (expectedChecksum != null && !settings.skipChecksum()) {
                var actual = sha512(temporary);
                if (!actual.equalsIgnoreCase(expectedChecksum)) {
                    Files.deleteIfExists(temporary);
                    return failure("The checksum did not match; the file was not installed.");
                }
                log.accept("Checksum matches.");
            }

            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rwxr-xr-x"));

            var check = runner.run(temporary.toString(), List.of("version"));
            if (isIllegalInstruction(check) && platform.equals(CliSettings.DEFAULT_PLATFORM)) {
                Files.deleteIfExists(temporary);
                log.accept("This processor does not support the default build; switching to the baseline version.");
                settings.setPlatform(CliSettings.BASELINE_PLATFORM);
                return install(release, allowMissingChecksum);
            }
            if (!check.ok()) {
                Files.deleteIfExists(temporary);
                return failure("The downloaded CLI does not start: " + check.output().trim());
            }

            var hadPrevious = Files.exists(installedPath());
            if (hadPrevious) replace(installedPath(), previousPath());
            replace(temporary, installedPath());

            if (hadPrevious && !sessionStillWorks()) {
                replace(previousPath(), installedPath());
                return failure("The session stopped working after updating; the previous version was restored.");
            }

            settings.setLastSeenVersion(release.version());
            log.accept("CLI " + release.version() + " installed.");
            return new InstallOutcome(true, "CLI updated to " + release.version() + ".", release.version());
        } catch (IOException e) {
            deleteQuietly(temporary);
            return failure("Download failed: " + e.getMessage());
        }
    }

    /** A download that was cut off by a crash or kill leaves a large file behind; sweep those. */
    private void removeStaleDownloads() throws IOException {
        try (var entries = Files.list(paths.binDir())) {
            for (var entry : entries.filter(e -> e.getFileName().toString().endsWith(".download")).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    public InstallOutcome rollback() {
        if (!Files.exists(previousPath())) return failure("No previous version was kept.");
        try {
            replace(previousPath(), installedPath());
        } catch (IOException e) {
            return failure("Could not restore the previous version: " + e.getMessage());
        }
        return new InstallOutcome(true, "The previous version was restored.", null);
    }

    /** A list command shows whether the stored session still works with the new version. */
    private boolean sessionStillWorks() throws IOException, InterruptedException {
        var result = runner.run(installedPath().toString(), List.of("filesystem", "list", "/my-files", "--json"));
        return result.ok() || !result.notLoggedIn();
    }

    private static boolean isIllegalInstruction(CliResult result) {
        return result.exitCode() == 132
                || result.exitCode() == -4
                || result.output().toLowerCase(Locale.ROOT).contains("illegal instruction");
    }

    /** Moves over an existing file; all paths are in one folder, so this is an atomic rename. */
    private static void replace(Path from, Path to) throws IOException {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String sha512(Path file) throws IOException {
        try (InputStream stream = Files.newInputStream(file)) {
            var digest = MessageDigest.getInstance("SHA-512");
            var buffer = new byte[64 * 1024];
            int read;
            while ((read = stream.read(buffer)) != -1) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 is not available", e);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // nothing more to do
        }
    }

    private static InstallOutcome failure(String message) {
        return new InstallOutcome(false, message, null);
    }
}
