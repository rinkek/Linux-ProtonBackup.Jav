package protonbackup.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Wrapper around the external {@code proton-drive} binary. */
public final class ProtonDriveCli implements DriveClient {

    private static final int SESSION_ATTEMPTS = 3;

    private final String executablePath;
    private final CommandRunner runner;
    private final Sleeper sleeper;

    public ProtonDriveCli(String executablePath) {
        this(executablePath, new ProcessRunner(), Sleeper.SYSTEM);
    }

    public ProtonDriveCli(String executablePath, CommandRunner runner, Sleeper sleeper) {
        this.executablePath = executablePath;
        this.runner = runner;
        this.sleeper = sleeper;
    }

    public String executablePath() {
        return executablePath;
    }

    /** Search order: the path from the settings, the app's own data folder, then {@code PATH}. */
    public static String locate(String configuredPath, AppPaths paths) {
        return locate(configuredPath, paths, System.getenv("PATH"));
    }

    public static String locate(String configuredPath, AppPaths paths, String pathVariable) {
        if (configuredPath != null && !configuredPath.isBlank() && Files.isRegularFile(Path.of(configuredPath))) {
            return configuredPath;
        }
        var inDataDir = paths.binDir().resolve("proton-drive");
        if (Files.isRegularFile(inDataDir)) return inDataDir.toString();

        if (pathVariable != null) {
            for (var directory : pathVariable.split(":")) {
                if (directory.isEmpty()) continue;
                var candidate = Path.of(directory, "proton-drive");
                if (Files.isRegularFile(candidate)) return candidate.toString();
            }
        }
        return null;
    }

    public CliResult run(List<String> arguments) throws IOException, InterruptedException {
        return runner.run(executablePath, arguments);
    }

    /** First line of {@code proton-drive version}, or {@code "unknown"}. */
    public String getVersion() throws IOException, InterruptedException {
        var firstLine = run(List.of("version")).stdOut().split("\n", 2)[0].trim();
        return firstLine.isEmpty() ? "unknown" : firstLine;
    }

    /**
     * The CLI sometimes reports "You need to login first" while the session is perfectly valid,
     * especially shortly after heavy traffic. Only after several failed attempts is the session
     * considered expired; otherwise the daemon would pause wrongly and ask to sign in again.
     */
    @Override
    public SessionState checkSession() throws IOException, InterruptedException {
        var state = SessionState.UNKNOWN;
        for (var attempt = 1; attempt <= SESSION_ATTEMPTS; attempt++) {
            var result = run(List.of("filesystem", "list", "/my-files", "--json"));
            if (result.ok()) return SessionState.ACTIVE;

            state = result.notLoggedIn() ? SessionState.EXPIRED : SessionState.UNKNOWN;
            if (attempt < SESSION_ATTEMPTS) sleeper.sleep(Duration.ofSeconds(2L * attempt));
        }
        return state;
    }

    /**
     * {@code null} means the listing could not be fetched, so that a failed call cannot be mistaken
     * for an empty folder. This sometimes happens right after a large upload.
     */
    @Override
    public List<RemoteNode> list(String remotePath) throws IOException, InterruptedException {
        var result = run(List.of("filesystem", "list", remotePath, "--json"));
        if (!result.ok()) return null;
        try {
            return RemoteNode.parseList(result.stdOut());
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    public boolean exists(String remotePath) throws IOException, InterruptedException {
        return run(List.of("filesystem", "info", remotePath, "--json")).ok();
    }

    /** Creates missing folders from top to bottom; the CLI does not do that itself on an upload. */
    @Override
    public void ensureFolder(String remotePath) throws IOException, InterruptedException {
        if (exists(remotePath)) return;

        var parent = RemotePath.parentOf(remotePath);
        if (!parent.equals("/") && !parent.equals(remotePath)) ensureFolder(parent);

        var result = run(List.of("filesystem", "create-folder", parent, RemotePath.nameOf(remotePath)));
        if (!result.ok() && !result.alreadyExists()) {
            throw new IOException("Could not create folder " + remotePath + ": " + result.output().trim());
        }
    }

    /**
     * One call with several files is about 23 times faster than one per file. The strategy flags
     * are mandatory: without them the CLI waits for input and, with stdin closed, silently skips
     * the file with exit code 0. {@code replace} is never used: it would trash the remote file first.
     */
    @Override
    public CliResult upload(List<String> localPaths, String remoteParent) throws IOException, InterruptedException {
        var arguments = new ArrayList<String>(List.of(
                "filesystem", "upload",
                "--file-conflict-strategy", "create-new-revision",
                "--folder-conflict-strategy", "merge",
                "--skip-thumbnails"));
        arguments.addAll(localPaths);
        arguments.add(remoteParent);
        return run(arguments);
    }
}
