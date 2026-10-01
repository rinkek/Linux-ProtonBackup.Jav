package protonbackup.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import protonbackup.core.RemoteNode.RemoteRevision;
import protonbackup.core.RemoteNode.RemoteValue;

/**
 * A realistic in-memory stand-in for the Proton CLI: it really stores what is "uploaded" and answers
 * listings from that store, so the whole scan, compare, upload, confirm, mark-synced cycle runs without
 * a network. It has no delete operation at all: if the engine ever tried to delete something on
 * Proton, it would not compile against this fake's interface.
 *
 * <p>It reproduces the quirks the original development discovered: the CLI can exit 0 yet store nothing
 * ({@link #silentlySkipped}), and a listing can fail right after a big upload ({@link #listFailuresLeft}).
 */
final class FakeDriveClient implements DriveClient {

    SessionState session = SessionState.ACTIVE;
    /** remote folder -> (file name -> size) */
    final Map<String, Map<String, Long>> remoteFiles = new LinkedHashMap<>();
    final List<List<String>> uploadCalls = new ArrayList<>();
    final List<String> uploadParents = new ArrayList<>();
    final List<String> listCalls = new ArrayList<>();
    final List<String> ensuredFolders = new ArrayList<>();
    /** Names the fake accepts but does not store, exit code 0: the exact bug behind confirm-after-upload. */
    final Set<String> silentlySkipped = new HashSet<>();
    int listFailuresLeft;
    Runnable onUpload;
    /** Runs at the very start of an upload, before anything is stored (the file may change right here). */
    Runnable beforeUpload;
    Runnable onFirstListFailure;
    IOException ensureFolderFailure;
    InterruptedException uploadInterruption;
    /** What upload() reports; null means "ok". */
    CliResult uploadResult;
    /** When set, the claimed size of every listed file is this offset away from the real size. */
    long claimedSizeOffset;

    @Override
    public SessionState checkSession() {
        return session;
    }

    @Override
    public void ensureFolder(String remotePath) throws IOException {
        ensuredFolders.add(remotePath);
        if (ensureFolderFailure != null) throw ensureFolderFailure;
        remoteFiles.computeIfAbsent(remotePath, key -> new LinkedHashMap<>());
    }

    @Override
    public CliResult upload(List<String> localPaths, String remoteParent) throws IOException, InterruptedException {
        if (beforeUpload != null) beforeUpload.run();
        uploadCalls.add(List.copyOf(localPaths));
        uploadParents.add(remoteParent);
        if (uploadInterruption != null) throw uploadInterruption;
        var bucket = remoteFiles.computeIfAbsent(remoteParent, key -> new LinkedHashMap<>());
        for (var local : localPaths) {
            var name = Path.of(local).getFileName().toString();
            if (silentlySkipped.contains(name)) continue;
            bucket.put(name, Files.size(Path.of(local)));
        }
        if (onUpload != null) onUpload.run();
        return uploadResult != null ? uploadResult : new CliResult(0, "Transfer summary: " + localPaths.size() + " items", "");
    }

    @Override
    public List<RemoteNode> list(String remotePath) {
        listCalls.add(remotePath);
        if (listFailuresLeft > 0) {
            listFailuresLeft--;
            if (onFirstListFailure != null) {
                var hook = onFirstListFailure;
                onFirstListFailure = null;
                hook.run();
            }
            return null;
        }
        var nodes = new ArrayList<RemoteNode>();
        for (var entry : remoteFiles.getOrDefault(remotePath, Map.of()).entrySet()) {
            nodes.add(new RemoteNode(
                    "uid-" + entry.getKey(),
                    "file",
                    new RemoteValue<>(true, entry.getKey()),
                    new RemoteRevision(entry.getValue() + claimedSizeOffset, null)));
        }
        return nodes;
    }
}
