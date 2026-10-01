package protonbackup.ui.viewmodel;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import protonbackup.core.FileStatus;
import protonbackup.ui.mvvm.Background;
import protonbackup.ui.mvvm.Background.Lane;
import protonbackup.ui.service.BackupService;

/**
 * A folder or file in the tree of the "File sync" page. Children are only loaded when a folder is
 * expanded, so tens of thousands of tracked files do not slow the window down: a folder with files
 * starts with one "loading..." child (which is what makes it look expandable), and {@link #expand()}
 * replaces it with the real contents, read in a worker.
 */
public final class FolderNode {

    /** What a worker reads for one node; turned into a {@code FolderNode} on the UI thread. */
    record Data(String name, String relativePath, boolean file, String status, Instant lastSync, String error,
            int fileCount, int errorCount, int pendingCount) {}

    private final BackupService service;
    private final Background background;
    private final ZoneId zone;
    private final long sourceId;
    private final Data data;
    private final boolean placeholder;
    private final ObservableList<FolderNode> children = FXCollections.observableArrayList();
    private final BooleanProperty expanded = new SimpleBooleanProperty();
    private boolean loaded;

    FolderNode(BackupService service, Background background, ZoneId zone, long sourceId, Data data) {
        this(service, background, zone, sourceId, data, false);
        if (!data.file() && data.fileCount() > 0) children.add(loadingNode());
    }

    private FolderNode(BackupService service, Background background, ZoneId zone, long sourceId, Data data, boolean placeholder) {
        this.service = service;
        this.background = background;
        this.zone = zone;
        this.sourceId = sourceId;
        this.data = data;
        this.placeholder = placeholder;
    }

    private FolderNode loadingNode() {
        var node = new FolderNode(service, background, zone, sourceId,
                new Data("loading…", data.relativePath(), true, null, null, null, 0, 0, 0), true);
        node.loaded = true;
        return node;
    }

    public String name() {
        return data.name();
    }

    public String relativePath() {
        return data.relativePath();
    }

    public boolean isFile() {
        return data.file();
    }

    /** True only for the temporary "loading..." child. */
    public boolean isPlaceholder() {
        return placeholder;
    }

    public ObservableList<FolderNode> children() {
        return children;
    }

    public BooleanProperty expanded() {
        return expanded;
    }

    /** The grey text next to the name: the state of a file, or how many files a folder holds and how they are doing. */
    public String summary() {
        if (placeholder) return "";
        if (data.file()) {
            var status = data.status() == null ? "" : data.status();
            return switch (status) {
                case FileStatus.SYNCED -> data.lastSync() != null ? "synced " + Descriptions.shortTime(data.lastSync(), zone) : "synced";
                case FileStatus.PENDING -> "waiting to upload";
                case FileStatus.ERROR -> data.error() != null ? data.error() : "error";
                case FileStatus.MISSING -> "gone locally";
                default -> status;
            };
        }
        if (data.errorCount() > 0) return counted(data.fileCount()) + ", " + data.errorCount() + " failed";
        if (data.pendingCount() > 0) return counted(data.fileCount()) + ", " + data.pendingCount() + " queued";
        return counted(data.fileCount());
    }

    private static String counted(int count) {
        return count == 1 ? "1 file" : count + " files";
    }

    /** Loads the contents the first time a folder is opened. Call on the UI thread. */
    public void expand() {
        if (loaded || data.file()) return;
        loaded = true;
        background
                .run(Lane.FAST, () -> read(service, sourceId, data.relativePath()))
                .whenComplete((contents, failure) -> {
                    if (failure != null) {
                        loaded = false; // let the next expand try again
                        children.setAll(failureNode(failure));
                        return;
                    }
                    children.setAll(toNodes(service, background, zone, sourceId, contents));
                });
    }

    private FolderNode failureNode(Throwable failure) {
        var reason = failure.getMessage() != null ? failure.getMessage() : failure.toString();
        var node = new FolderNode(service, background, zone, sourceId,
                new Data("could not load: " + reason, data.relativePath(), true, null, null, null, 0, 0, 0), true);
        node.loaded = true;
        return node;
    }

    // ---- reading and building -----------------------------------------------------------

    /** Reads the folders, then the files, directly inside a folder. Blocks: call from a worker. */
    static List<Data> read(BackupService service, long sourceId, String relativeDirectory) {
        var result = new ArrayList<Data>();
        var db = service.database();
        for (var folder : db.getChildFolders(sourceId, relativeDirectory)) {
            result.add(new Data(folder.name(), folder.relativePath(), false, null, null, null,
                    folder.fileCount(), folder.errorCount(), folder.pendingCount()));
        }
        for (var file : db.getFilesIn(sourceId, relativeDirectory)) {
            result.add(new Data(file.name(), file.relativePath(), true, file.status(), file.lastSyncUtc(), file.lastError(), 0, 0, 0));
        }
        return result;
    }

    static List<FolderNode> toNodes(BackupService service, Background background, ZoneId zone, long sourceId, List<Data> contents) {
        return contents.stream().map(data -> new FolderNode(service, background, zone, sourceId, data)).toList();
    }
}
