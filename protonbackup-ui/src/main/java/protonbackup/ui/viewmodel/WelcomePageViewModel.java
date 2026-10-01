package protonbackup.ui.viewmodel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import protonbackup.core.SessionState;
import protonbackup.ui.mvvm.Background;
import protonbackup.ui.mvvm.Background.Lane;
import protonbackup.ui.mvvm.Command;
import protonbackup.ui.mvvm.PageViewModel;
import protonbackup.ui.mvvm.Refresher;
import protonbackup.ui.service.BackupService;

/**
 * The first-run wizard: the four things needed before anything can be backed up (the Proton CLI, a
 * sign-in, a source folder, the timer). It is not in the navigation; the main window shows it instead of
 * the normal layout until it is finished.
 */
public final class WelcomePageViewModel extends PageViewModel {

    private record Setup(boolean hasCli, boolean hasSource, boolean timerEnabled) {}

    private final FolderPicker picker;
    private final ReadOnlyBooleanWrapper hasCli = new ReadOnlyBooleanWrapper();
    private final ReadOnlyBooleanWrapper signedIn = new ReadOnlyBooleanWrapper();
    private final ReadOnlyBooleanWrapper hasSource = new ReadOnlyBooleanWrapper();
    private final ReadOnlyBooleanWrapper timerEnabled = new ReadOnlyBooleanWrapper();
    private final StringProperty localPath = new SimpleStringProperty("");
    private final StringProperty remotePath = new SimpleStringProperty("/my-files/Backup");

    private final Refresher<Setup> setup = refresher(Lane.FAST, this::loadSetup, this::showSetup);
    private final Refresher<SessionState> session = refresher(Lane.PROBE, service::session, state -> signedIn.set(hasCli.get() && state == SessionState.ACTIVE));

    private final Command<Void> downloadCli = Command.async(this::downloadTheCli, this::fail);
    private final Command<Void> signIn = Command.async(this::signInToProton, this::fail);
    private final Command<Void> browse = Command.immediate(this::browseForFolder, this::fail);
    private final Command<Void> addSource = Command.async(this::addTheSource, this::fail);
    private final Command<Void> enableTimer = Command.async(this::turnOnTheTimer, this::fail);
    private final Command<Void> finish = Command.async(this::finishSetup, this::fail);

    private final BooleanBinding canFinish = hasCli.and(signedIn).and(hasSource);
    private Runnable onFinished = () -> {};

    public WelcomePageViewModel(BackupService service, Background background, FolderPicker picker) {
        super(service, background);
        this.picker = picker;
        finish.enabledWhen(canFinish);
    }

    @Override
    public String title() {
        return "Welcome";
    }

    /** Called once the wizard has been completed; the main window then switches to the normal layout. */
    public void onFinished(Runnable action) {
        this.onFinished = action;
    }

    // ---- state --------------------------------------------------------------------------

    public ReadOnlyBooleanProperty hasCli() {
        return hasCli.getReadOnlyProperty();
    }

    public ReadOnlyBooleanProperty signedIn() {
        return signedIn.getReadOnlyProperty();
    }

    public ReadOnlyBooleanProperty hasSource() {
        return hasSource.getReadOnlyProperty();
    }

    public ReadOnlyBooleanProperty timerEnabled() {
        return timerEnabled.getReadOnlyProperty();
    }

    public StringProperty localPath() {
        return localPath;
    }

    public StringProperty remotePath() {
        return remotePath;
    }

    /** The CLI is there, the user is signed in and a folder is set: "Get started" is enabled. */
    public BooleanBinding canFinish() {
        return canFinish;
    }

    public Command<Void> downloadCli() {
        return downloadCli;
    }

    /** Signs in, installing the CLI first when it is not there yet. */
    public Command<Void> signIn() {
        return signIn;
    }

    public Command<Void> browse() {
        return browse;
    }

    public Command<Void> addSource() {
        return addSource;
    }

    public Command<Void> enableTimer() {
        return enableTimer;
    }

    /** Marks setup as done and has the main window switch to the normal layout. */
    public Command<Void> finish() {
        return finish;
    }

    // ---- refreshing ---------------------------------------------------------------------

    /** The cheap part (database, systemctl, the CLI's location): every tick while the wizard is shown. */
    @Override
    public void refresh() {
        setup.refresh();
    }

    /** The session check starts the CLI, so it runs about every 30 seconds and after an action. */
    public void refreshSlow() {
        session.refresh();
    }

    private Setup loadSetup() throws InterruptedException {
        service.refreshCliPath();
        boolean enabled;
        try {
            enabled = service.systemd().isTimerEnabled();
        } catch (java.io.IOException e) {
            enabled = false;
        }
        return new Setup(service.cliPath() != null, !service.database().getSources().isEmpty(), enabled);
    }

    private void showSetup(Setup state) {
        hasCli.set(state.hasCli());
        hasSource.set(state.hasSource());
        timerEnabled.set(state.timerEnabled());
        if (!state.hasCli()) signedIn.set(false);
    }

    private void refreshAll() {
        setup.refresh();
        session.refresh();
    }

    // ---- the four steps -----------------------------------------------------------------

    private CompletableFuture<?> downloadTheCli() {
        return background.run(Lane.ACTIONS, service::installCli, this::say).thenAccept(outcome -> {
            say(outcome.message());
            refreshAll();
        });
    }

    private CompletableFuture<?> signInToProton() {
        return background.run(Lane.ACTIONS, service::signIn, this::say).thenAccept(outcome -> {
            say(outcome.message());
            refreshAll();
        });
    }

    private void browseForFolder() {
        picker.pick("Choose the folder you want to back up").ifPresent(folder -> localPath.set(folder.toString()));
    }

    private CompletableFuture<?> addTheSource() {
        var local = localPath.get() == null ? "" : localPath.get().trim();
        var remote = remotePath.get() == null ? "" : remotePath.get().trim();
        return background
                .run(Lane.ACTIONS, () -> {
                    if (local.isEmpty() || !Files.isDirectory(Path.of(local))) return "Choose an existing folder first.";
                    if (!remote.startsWith("/")) return "The destination path must start with /, for example /my-files/Backup.";
                    var destination = remote.replaceAll("/+$", "");
                    if (destination.isEmpty()) return "The destination must be a folder below /, for example /my-files/Backup.";
                    service.database().addSource(local, destination);
                    return null;
                })
                .thenAccept(problem -> {
                    say(problem != null ? problem : "Source folder added.");
                    setup.refresh();
                });
    }

    private CompletableFuture<?> turnOnTheTimer() {
        return background.run(Lane.ACTIONS, () -> service.systemd().enableTimer()).thenAccept(result -> {
            say(result.ok() ? "Automatic syncing is on." : result.output().trim());
            setup.refresh();
        });
    }

    private CompletableFuture<?> finishSetup() {
        return background.run(Lane.ACTIONS, () -> {
            service.database().setSetting("setup_completed", "1");
            return null;
        }).thenAccept(done -> onFinished.run());
    }
}
