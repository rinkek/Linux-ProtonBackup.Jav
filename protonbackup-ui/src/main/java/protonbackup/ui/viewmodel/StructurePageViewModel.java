package protonbackup.ui.viewmodel;

import java.time.ZoneId;
import java.util.List;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import protonbackup.core.FileEntry;
import protonbackup.core.SyncSource;
import protonbackup.ui.mvvm.Background;
import protonbackup.ui.mvvm.Background.Lane;
import protonbackup.ui.mvvm.Command;
import protonbackup.ui.mvvm.PageViewModel;
import protonbackup.ui.mvvm.Refresher;
import protonbackup.ui.service.BackupService;

/**
 * What the database knows about every file of a source folder, as a tree that opens folder by folder, or
 * (with "Errors only") as the list of files that failed and why.
 *
 * <p>The list of sources follows the database every two seconds, but the tree does not: reloading it on a
 * timer would collapse what the user just opened. It loads when the source or the filter changes, and
 * when Refresh is pressed.
 */
public final class StructurePageViewModel extends PageViewModel {

    private record Request(long sourceId, boolean errorsOnly) {}

    private record Tree(Request request, List<FolderNode.Data> roots, List<FileEntry> failures) {}

    private static final int FAILURES_SHOWN = 200;

    private final ZoneId zone;
    private final ObservableList<SyncSource> sources = FXCollections.observableArrayList();
    private final ObservableList<FolderNode> nodes = FXCollections.observableArrayList();
    private final ObservableList<FileEntry> failures = FXCollections.observableArrayList();
    private final ObjectProperty<SyncSource> selectedSource = new SimpleObjectProperty<>();
    private final BooleanProperty onlyErrors = new SimpleBooleanProperty();
    private final ReadOnlyBooleanWrapper hasSources = new ReadOnlyBooleanWrapper();

    private final Refresher<List<SyncSource>> sourcesRefresher = refresher(Lane.FAST, () -> service.database().getSources(), this::showSources);
    private final Refresher<Tree> treeRefresher = refresher(Lane.FAST, this::loadTree, this::showTree);
    private final Command<Void> reload = Command.immediate(this::requestReload, this::fail);

    /** What the next tree load is for; written on the UI thread before the load starts, read by the worker. */
    private volatile Request request;

    public StructurePageViewModel(BackupService service, Background background) {
        this(service, background, ZoneId.systemDefault());
    }

    public StructurePageViewModel(BackupService service, Background background, ZoneId zone) {
        super(service, background);
        this.zone = zone;
        selectedSource.addListener((observable, before, now) -> requestReload());
        onlyErrors.addListener((observable, before, now) -> requestReload());
    }

    @Override
    public String title() {
        return "File sync";
    }

    public ObservableList<SyncSource> sources() {
        return sources;
    }

    public ObjectProperty<SyncSource> selectedSource() {
        return selectedSource;
    }

    public BooleanProperty onlyErrors() {
        return onlyErrors;
    }

    public ReadOnlyBooleanProperty hasSources() {
        return hasSources.getReadOnlyProperty();
    }

    /** The top level of the tree for the selected source (folders first, then files). */
    public ObservableList<FolderNode> nodes() {
        return nodes;
    }

    /** The failed files, shown instead of the tree while "Errors only" is on. */
    public ObservableList<FileEntry> failures() {
        return failures;
    }

    public Command<Void> reload() {
        return reload;
    }

    // ---- refreshing ---------------------------------------------------------------------

    @Override
    public void refresh() {
        sourcesRefresher.refresh();
    }

    private void showSources(List<SyncSource> fresh) {
        var sameSources = sources.stream().map(SyncSource::id).toList().equals(fresh.stream().map(SyncSource::id).toList());
        if (!sameSources) {
            var previous = selectedSource.get() == null ? null : selectedSource.get().id();
            sources.setAll(fresh);
            selectedSource.set(fresh.stream().filter(source -> previous != null && source.id() == previous).findFirst()
                    .orElse(fresh.isEmpty() ? null : fresh.get(0)));
        }
        hasSources.set(!sources.isEmpty());
    }

    private void requestReload() {
        var source = selectedSource.get();
        if (source == null) {
            request = null;
            nodes.clear();
            failures.clear();
            return;
        }
        request = new Request(source.id(), onlyErrors.get());
        treeRefresher.refresh();
    }

    private Tree loadTree() {
        var wanted = request;
        if (wanted == null) return null;
        if (wanted.errorsOnly()) return new Tree(wanted, List.of(), service.database().getFailedFiles(FAILURES_SHOWN));
        return new Tree(wanted, FolderNode.read(service, wanted.sourceId(), ""), List.of());
    }

    private void showTree(Tree tree) {
        if (tree == null) return;
        // A load for a source the user has since left must not overwrite the current one.
        var current = request;
        if (current == null || !current.equals(tree.request())) return;
        if (tree.request().errorsOnly()) {
            nodes.clear();
            failures.setAll(tree.failures());
        } else {
            failures.clear();
            nodes.setAll(FolderNode.toNodes(service, background, zone, tree.request().sourceId(), tree.roots()));
        }
    }
}
