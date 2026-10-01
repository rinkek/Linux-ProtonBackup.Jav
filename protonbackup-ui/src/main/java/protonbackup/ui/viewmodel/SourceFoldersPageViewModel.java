package protonbackup.ui.viewmodel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import protonbackup.core.SyncSource;
import protonbackup.ui.mvvm.Background;
import protonbackup.ui.mvvm.Background.Lane;
import protonbackup.ui.mvvm.Command;
import protonbackup.ui.mvvm.PageViewModel;
import protonbackup.ui.mvvm.Refresher;
import protonbackup.ui.service.BackupService;

/** The folders being backed up, each with its destination on Proton Drive, and a form to add one. */
public final class SourceFoldersPageViewModel extends PageViewModel {

    private static final String EXAMPLE_DESTINATION = "/my-files/Backup";

    private final FolderPicker picker;
    private final ObservableList<SyncSource> sources = FXCollections.observableArrayList();
    private final StringProperty newLocalPath = new SimpleStringProperty("");
    private final StringProperty newRemotePath = new SimpleStringProperty(EXAMPLE_DESTINATION);

    private final Refresher<List<SyncSource>> list = refresher(Lane.FAST, () -> service.database().getSources(), this::showSources);

    private final Command<Void> browse = Command.immediate(this::browseForFolder, this::fail);
    private final Command<Void> addSource = Command.async(this::addTheSource, this::fail);
    private final Command<SyncSource> removeSource = new Command<>(this::removeTheSource, this::fail);
    private final Command<SyncSource> syncSource = new Command<>(this::syncTheSource, this::fail);
    private final Command<SyncSource> forceSource = new Command<>(this::forceTheSource, this::fail);

    public SourceFoldersPageViewModel(BackupService service, Background background, FolderPicker picker) {
        super(service, background);
        this.picker = picker;
    }

    @Override
    public String title() {
        return "Source folders";
    }

    // ---- state --------------------------------------------------------------------------

    public ObservableList<SyncSource> sources() {
        return sources;
    }

    /** What the user typed or picked in the "Local folder" field. */
    public StringProperty newLocalPath() {
        return newLocalPath;
    }

    public StringProperty newRemotePath() {
        return newRemotePath;
    }

    public Command<Void> browse() {
        return browse;
    }

    public Command<Void> addSource() {
        return addSource;
    }

    public Command<SyncSource> removeSource() {
        return removeSource;
    }

    /** Runs a sync for one source right now. */
    public Command<SyncSource> syncSource() {
        return syncSource;
    }

    /** Goes through everything again, for when something was deleted on Proton. */
    public Command<SyncSource> forceSource() {
        return forceSource;
    }

    // ---- refreshing ---------------------------------------------------------------------

    @Override
    public void refresh() {
        list.refresh();
    }

    /** The list only changes when it really differs, so the view is not rebuilt every two seconds. */
    private void showSources(List<SyncSource> fresh) {
        if (!sources.equals(fresh)) sources.setAll(fresh);
    }

    // ---- actions ------------------------------------------------------------------------

    private void browseForFolder() {
        picker.pick("Choose a source folder").ifPresent(folder -> newLocalPath.set(folder.toString()));
    }

    private java.util.concurrent.CompletableFuture<?> addTheSource() {
        var local = newLocalPath.get() == null ? "" : newLocalPath.get().trim();
        var remote = newRemotePath.get() == null ? "" : newRemotePath.get().trim();
        return background
                .run(Lane.ACTIONS, () -> {
                    if (local.isEmpty() || !Files.isDirectory(Path.of(local))) return "Choose an existing folder first.";
                    if (!remote.startsWith("/")) return "The destination path must start with /, for example " + EXAMPLE_DESTINATION + ".";
                    var destination = trimTrailingSlashes(remote);
                    if (destination.isEmpty()) return "The destination must be a folder below /, for example " + EXAMPLE_DESTINATION + ".";
                    service.database().addSource(local, destination);
                    return null;
                })
                .thenAccept(problem -> {
                    if (problem != null) {
                        say(problem);
                        return;
                    }
                    newLocalPath.set("");
                    say("Source folder added.");
                    list.refresh();
                });
    }

    private java.util.concurrent.CompletableFuture<?> removeTheSource(SyncSource source) {
        if (source == null) return null;
        return background
                .run(Lane.ACTIONS, () -> {
                    service.database().removeSource(source.id());
                    return null;
                })
                .thenAccept(done -> {
                    say("Source folder removed. Whatever is already on Proton stays there.");
                    list.refresh();
                });
    }

    private java.util.concurrent.CompletableFuture<?> syncTheSource(SyncSource source) {
        if (source == null) return null;
        return background
                .run(Lane.ACTIONS, () -> service.systemd().startSync(source.id()))
                .thenAccept(result -> say(result.ok() ? "Run started for " + source.localPath() + "." : result.output().trim()));
    }

    private java.util.concurrent.CompletableFuture<?> forceTheSource(SyncSource source) {
        if (source == null) return null;
        return background
                .run(Lane.ACTIONS, () -> {
                    var reset = service.database().markAllPending(source.id());
                    return new Object[] {reset, service.systemd().startSync(source.id())};
                })
                .thenAccept(outcome -> {
                    var result = (protonbackup.core.CliResult) outcome[1];
                    say(result.ok() ? outcome[0] + " file(s) queued again; the run has started." : result.output().trim());
                });
    }

    private static String trimTrailingSlashes(String text) {
        var end = text.length();
        while (end > 0 && text.charAt(end - 1) == '/') end--;
        return text.substring(0, end);
    }
}
