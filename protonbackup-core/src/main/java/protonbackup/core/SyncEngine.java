package protonbackup.core;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * One sync round: scan every enabled source, compare with the database, upload what is new or
 * changed in batches, and confirm every batch against the remote folder listing.
 *
 * <p>The invariants that must never be lost:
 * <ul>
 *   <li><b>Nothing is ever deleted or replaced on Proton.</b> A file that disappeared locally is
 *       only marked {@code missing}; the engine has no delete operation at all.
 *   <li><b>The CLI's exit code is no proof of an upload.</b> Without the conflict-strategy flags it
 *       silently skips files with exit code 0, so each batch is verified against the folder listing
 *       (name present, and the claimed size equals the size scanned).
 *   <li><b>A batch whose listing cannot be fetched is not lost</b>: it stays pending for the next run.
 *   <li><b>Cancellation lands at checkpoints</b> (before each source, each folder group and each
 *       batch), never in the middle of a batch, and the run's row records accurate partial counts.
 * </ul>
 */
public final class SyncEngine {

    /** Batching per target folder is about 23 times faster than per file; the limit keeps the argument list short and progress visible. */
    static final int BATCH_SIZE = 200;

    private static final Duration DEFAULT_LIST_RETRY_DELAY = Duration.ofSeconds(3);

    private final AppPaths paths;
    private final Database database;
    private final DriveClient cli;
    private final Consumer<String> log;
    private final Scanner scanner;
    private final Duration listRetryDelay;

    public SyncEngine(AppPaths paths, Database database, DriveClient cli, Consumer<String> log) {
        this(paths, database, cli, log, new Scanner(), DEFAULT_LIST_RETRY_DELAY);
    }

    public SyncEngine(
            AppPaths paths,
            Database database,
            DriveClient cli,
            Consumer<String> log,
            Scanner scanner,
            Duration listRetryDelay) {
        this.paths = paths;
        this.database = database;
        this.cli = cli;
        this.log = log;
        this.scanner = scanner;
        this.listRetryDelay = listRetryDelay;
    }

    private static final class Progress {
        int uploaded;
        int failed;
        int missing;
    }

    /**
     * @param onlySourceId restrict the run to one source, or {@code null} for all enabled sources
     * @throws SyncCancelledException when cancelled; the run is recorded as {@code cancelled} first
     * @throws IOException when the run fails (the run is recorded as {@code error} first)
     */
    public SyncResult runOnce(Long onlySourceId, CancelToken token) throws IOException {
        SyncLock lock;
        try {
            lock = SyncLock.tryAcquire(paths);
        } catch (IOException | RuntimeException e) {
            log.accept("Run failed: " + e.getMessage());
            throw e;
        }
        if (lock == null) {
            log.accept("A run is already in progress; this one is skipped.");
            return SyncResult.blocked("already-running");
        }
        try (lock) {
            return runLocked(onlySourceId, token);
        }
    }

    private SyncResult runLocked(Long onlySourceId, CancelToken token) throws IOException {
        SessionState session;
        try {
            session = cli.checkSession();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SyncCancelledException();
        }
        if (session != SessionState.ACTIVE) {
            log.accept(session == SessionState.EXPIRED
                    ? "Session expired. Sign in again with: proton-drive auth login"
                    : "Cannot check the session; the run is paused.");
            return SyncResult.blocked(session == SessionState.EXPIRED ? "session-expired" : "session-unknown");
        }

        var runId = database.startRun();
        var progress = new Progress();

        try {
            for (var source : database.getSources(true)) {
                if (onlySourceId != null && source.id() != onlySourceId) continue;
                token.throwIfCancelled();
                syncSource(source, progress, token);
            }

            var outcome = progress.failed == 0 ? "ok" : "partial";
            database.finishRun(runId, progress.uploaded, progress.failed, outcome);
            log.accept("Done: " + progress.uploaded + " uploaded, " + progress.failed + " failed, " + progress.missing + " gone locally.");
            return new SyncResult(progress.uploaded, progress.failed, progress.missing, outcome);
        } catch (SyncCancelledException e) {
            // The counters advance per batch, so an aborted run is not logged as zero.
            database.finishRun(runId, progress.uploaded, progress.failed, "cancelled");
            log.accept("Cancelled after " + progress.uploaded + " file(s); the next run continues where this one left off.");
            throw e;
        } catch (InterruptedException e) {
            database.finishRun(runId, progress.uploaded, progress.failed, "cancelled");
            log.accept("Cancelled after " + progress.uploaded + " file(s); the next run continues where this one left off.");
            Thread.currentThread().interrupt();
            throw new SyncCancelledException();
        } catch (IOException | RuntimeException e) {
            database.finishRun(runId, progress.uploaded, progress.failed, "error");
            log.accept("Run failed: " + e.getMessage());
            throw e;
        }
    }

    private void syncSource(SyncSource source, Progress progress, CancelToken token)
            throws IOException, InterruptedException {
        log.accept("Source " + source.localPath() + " -> " + source.remotePath());

        var scanned = scanner.scan(source.localPath());
        var tracked = database.getTrackedFiles(source.id());

        var changed = scanned.stream()
                .filter(file -> Scanner.needsUpload(file, tracked.get(file.relativePath())))
                .toList();
        if (!changed.isEmpty()) database.upsertPending(source.id(), changed);

        var missing = database.markMissing(source.id(), scanned.stream().map(ScannedFile::relativePath).toList());
        progress.missing += missing;
        if (missing > 0) log.accept("  " + missing + " file(s) gone locally; they stay on Proton.");

        var pending = database.getPending(source.id());
        if (pending.isEmpty()) {
            log.accept("  Nothing to do.");
            return;
        }
        log.accept("  " + pending.size() + " file(s) to upload.");

        for (var group : groupByDirectory(pending).entrySet()) {
            token.throwIfCancelled();

            var directory = group.getKey();
            var remoteParent = directory.isEmpty()
                    ? source.remotePath()
                    : RemotePath.combine(source.remotePath(), directory);
            cli.ensureFolder(remoteParent);

            var files = group.getValue();
            for (var from = 0; from < files.size(); from += BATCH_SIZE) {
                token.throwIfCancelled();
                uploadBatch(source, remoteParent, files.subList(from, Math.min(from + BATCH_SIZE, files.size())), progress, token);
            }
        }
    }

    private void uploadBatch(
            SyncSource source, String remoteParent, List<TrackedFile> batch, Progress progress, CancelToken token)
            throws IOException, InterruptedException {
        var localRoot = Path.of(source.localPath());
        var localPaths = batch.stream().map(file -> localRoot.resolve(file.relativePath()).toString()).toList();
        var result = cli.upload(localPaths, remoteParent);

        var confirmed = confirmUploaded(remoteParent, batch, token);
        if (confirmed == null) {
            log.accept("  " + remoteParent + ": could not fetch the folder listing; this batch is left for the next run.");
            return;
        }

        var succeeded = new ArrayList<String>();
        var rejected = new ArrayList<String>();
        for (var file : batch) {
            (confirmed.contains(fileName(file.relativePath())) ? succeeded : rejected).add(file.relativePath());
        }

        if (!succeeded.isEmpty()) database.markSynced(source.id(), succeeded);
        if (!rejected.isEmpty()) {
            database.markError(source.id(), rejected, failureMessage(result));
        }

        progress.uploaded += succeeded.size();
        progress.failed += rejected.size();
        log.accept("  " + remoteParent + ": " + succeeded.size() + " ok, " + rejected.size() + " failed");
    }

    /** Why a file was not on Proton afterwards. A CLI that exits 0 yet stored nothing gets its own message. */
    private static String failureMessage(CliResult result) {
        if (result.ok()) return "Not found on Proton after uploading.";
        var output = result.output().trim();
        return output.isEmpty() ? "Upload failed with exit code " + result.exitCode() + "." : output;
    }

    /**
     * The exit code alone is no proof: without a strategy flag the CLI silently skips files with exit
     * code 0. So every batch is checked against the folder listing; a failed listing is retried once.
     *
     * @return the names confirmed on Proton, or {@code null} when the listing could not be fetched
     */
    private Set<String> confirmUploaded(String remoteParent, List<TrackedFile> batch, CancelToken token)
            throws IOException, InterruptedException {
        Map<String, Long> expected = new LinkedHashMap<>();
        for (var file : batch) expected.put(fileName(file.relativePath()), file.size());

        var nodes = cli.list(remoteParent);
        if (nodes == null) {
            if (token.sleep(listRetryDelay)) throw new SyncCancelledException();
            nodes = cli.list(remoteParent);
        }
        if (nodes == null) return null;

        var confirmed = new HashSet<String>();
        for (var node : nodes) {
            var name = node.fileName();
            if (node.isFolder() || name == null) continue;
            var size = expected.get(name);
            if (size == null) continue;
            var claimed = node.activeRevision() == null ? null : node.activeRevision().claimedSize();
            if (claimed != null && !claimed.equals(size)) continue;
            confirmed.add(name);
        }
        return confirmed;
    }

    /** Groups appear in order of first occurrence and keep their relative order, like LINQ's GroupBy. */
    static Map<String, List<TrackedFile>> groupByDirectory(List<TrackedFile> files) {
        Map<String, List<TrackedFile>> groups = new LinkedHashMap<>();
        for (var file : files) {
            groups.computeIfAbsent(directoryOf(file.relativePath()), key -> new ArrayList<>()).add(file);
        }
        return groups;
    }

    static String directoryOf(String relativePath) {
        var slash = relativePath.lastIndexOf('/');
        return slash < 0 ? "" : relativePath.substring(0, slash);
    }

    static String fileName(String relativePath) {
        return relativePath.substring(relativePath.lastIndexOf('/') + 1);
    }
}
