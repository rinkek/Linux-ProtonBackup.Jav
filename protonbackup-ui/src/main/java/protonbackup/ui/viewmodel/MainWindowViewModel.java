package protonbackup.ui.viewmodel;

import java.time.Duration;
import java.util.List;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;
import protonbackup.core.Cleanup;
import protonbackup.ui.mvvm.Background;
import protonbackup.ui.mvvm.PageViewModel;
import protonbackup.ui.service.BackupService;

/**
 * Owns the pages, which one is selected, and the periodic refresh.
 *
 * <p>The database is the only link with the daemon, so the UI polls it: {@link #tick()} every
 * {@link #TICK} refreshes the Status page (the sidebar footer and the tray read from it) and the page
 * on screen; every {@link #SLOW_EVERY}th tick (about 30 seconds) also runs the slower probes of the
 * Proton CLI. A tick only <em>starts</em> work: nothing here blocks, and a slow load cannot pile up
 * (see {@link protonbackup.ui.mvvm.Refresher}).
 *
 * <p>Settings and Log are not in the navigation list: the sidebar shows them as icon buttons at the bottom.
 */
public final class MainWindowViewModel {

    public static final Duration TICK = Duration.ofSeconds(2);
    static final int SLOW_EVERY = 15;

    private final BackupService service;
    private final StatusPageViewModel status;
    private final SourceFoldersPageViewModel sourceFolders;
    private final StructurePageViewModel structure;
    private final LogPageViewModel log;
    private final SettingsPageViewModel settings;
    private final WelcomePageViewModel welcome;
    private final List<PageViewModel> navigation;

    private final ObjectProperty<PageViewModel> selectedPage = new SimpleObjectProperty<>();
    private final BooleanProperty showWelcome = new SimpleBooleanProperty();
    private final BooleanProperty cliMissing = new SimpleBooleanProperty();
    private final BooleanProperty removing = new SimpleBooleanProperty();
    private final ObjectProperty<List<Cleanup.Step>> removal = new SimpleObjectProperty<>();
    private final ObjectBinding<TrayState> trayState;

    private int ticks;

    /** @param showWelcomeFirst whether setup was never finished (decided off the UI thread, by the caller) */
    public MainWindowViewModel(BackupService service, Background background, boolean showWelcomeFirst, FolderPicker picker) {
        this.service = service;
        this.status = new StatusPageViewModel(service, background);
        this.sourceFolders = new SourceFoldersPageViewModel(service, background, picker);
        this.structure = new StructurePageViewModel(service, background);
        this.log = new LogPageViewModel(service, background);
        this.settings = new SettingsPageViewModel(service, background);
        this.welcome = new WelcomePageViewModel(service, background, picker);
        this.navigation = List.of(status, sourceFolders, structure);

        selectedPage.set(status);
        showWelcome.set(showWelcomeFirst);
        welcome.onFinished(() -> {
            showWelcome.set(false);
            refreshSlow();
        });
        settings.onAccountChanged(this::refreshSlow);
        settings.onRemoval(() -> removing.set(true), steps -> removal.set(steps));
        cliMissing.set(service.cliPath() == null);

        trayState = Bindings.createObjectBinding(
                () -> status.running().get() ? TrayState.BUSY : status.errorCount().get() > 0 ? TrayState.ERROR : TrayState.OK,
                status.running(),
                status.errorCount());
    }

    // ---- state --------------------------------------------------------------------------

    public StatusPageViewModel status() {
        return status;
    }

    public SourceFoldersPageViewModel sourceFolders() {
        return sourceFolders;
    }

    public StructurePageViewModel structure() {
        return structure;
    }

    public LogPageViewModel log() {
        return log;
    }

    public SettingsPageViewModel settings() {
        return settings;
    }

    public WelcomePageViewModel welcome() {
        return welcome;
    }

    /** The pages of the sidebar list, in order. */
    public List<PageViewModel> navigation() {
        return navigation;
    }

    public ObjectProperty<PageViewModel> selectedPage() {
        return selectedPage;
    }

    public BooleanProperty showWelcome() {
        return showWelcome;
    }

    /** The Proton CLI is not installed: the window shows a banner. */
    public BooleanProperty cliMissing() {
        return cliMissing;
    }

    public ObservableValue<TrayState> trayState() {
        return trayState;
    }

    /** The removal of everything the app put in the home folder has started: polling stops for good. */
    public BooleanProperty removing() {
        return removing;
    }

    /** The outcome of the removal (one step per line, failed ones marked), or {@code null} while it has not finished. */
    public ObjectProperty<List<Cleanup.Step>> removal() {
        return removal;
    }

    // ---- behavior -----------------------------------------------------------------------

    /** Shows a page and has it load its state right away. */
    public void select(PageViewModel page) {
        selectedPage.set(page);
        page.refresh();
    }

    /** First load; call once the window is up. */
    public void start() {
        tick();
        refreshSlow();
    }

    /** One step of the periodic refresh. */
    public void tick() {
        if (removing.get()) return; // the database is gone; nothing may touch it again
        if (showWelcome.get()) {
            welcome.refresh();
            if (++ticks % SLOW_EVERY == 0) welcome.refreshSlow();
            return;
        }
        status.refresh();
        var page = selectedPage.get();
        if (page != status) page.refresh();
        cliMissing.set(service.cliPath() == null);
        if (++ticks % SLOW_EVERY == 0) refreshSlow();
    }

    private void refreshSlow() {
        if (removing.get()) return;
        if (showWelcome.get()) welcome.refreshSlow();
        status.refreshSlow();
        settings.refreshSession();
    }
}
