package protonbackup.ui.viewmodel;

import java.io.IOException;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import protonbackup.core.CliUpdateCheck.UpdateStatus;
import protonbackup.core.FileStatus;
import protonbackup.core.SessionState;
import protonbackup.ui.mvvm.Background;
import protonbackup.ui.mvvm.Background.Lane;
import protonbackup.ui.mvvm.Command;
import protonbackup.ui.mvvm.PageViewModel;
import protonbackup.ui.mvvm.Refresher;
import protonbackup.ui.service.BackupService;

/**
 * The first page: file counts, the last and next run, the session, the CLI version, Sync now / Cancel,
 * automatic syncing, and the banner announcing a newer CLI. It is also the reference implementation of
 * the conventions in {@link PageViewModel}.
 *
 * <p>Two tiers of refresh, as in the original: the <b>fast</b> one (database and systemd, every two
 * seconds) and the <b>slow</b> one (CLI version and session, about every 30 seconds, and the daily update check).
 */
public final class StatusPageViewModel extends PageViewModel {

    /** Everything the fast refresh reads, as one immutable snapshot. */
    record FastSnapshot(
            int synced, int pending, int errors, String lastRun, String nextRun, boolean running, boolean autoSync, String systemdProblem) {}

    /** Everything the slow refresh reads. */
    record ProbeSnapshot(String cliVersion, SessionState session) {}

    private final ZoneId zone;

    private final ReadOnlyStringWrapper lastRun = new ReadOnlyStringWrapper("no runs yet");
    private final ReadOnlyStringWrapper nextRun = new ReadOnlyStringWrapper("no timer");
    private final ReadOnlyStringWrapper sessionText = new ReadOnlyStringWrapper("unknown");
    private final ReadOnlyStringWrapper cliVersion = new ReadOnlyStringWrapper("unknown");
    private final ReadOnlyIntegerWrapper syncedCount = new ReadOnlyIntegerWrapper();
    private final ReadOnlyIntegerWrapper pendingCount = new ReadOnlyIntegerWrapper();
    private final ReadOnlyIntegerWrapper errorCount = new ReadOnlyIntegerWrapper();
    private final ReadOnlyBooleanWrapper running = new ReadOnlyBooleanWrapper();
    private final ReadOnlyBooleanWrapper autoSync = new ReadOnlyBooleanWrapper();
    private final ReadOnlyBooleanWrapper updateAvailable = new ReadOnlyBooleanWrapper();
    private final ReadOnlyStringWrapper availableVersion = new ReadOnlyStringWrapper();

    private final Refresher<FastSnapshot> fast = refresher(Lane.FAST, this::loadFast, this::showFast);
    private final Refresher<ProbeSnapshot> probe = refresher(Lane.PROBE, this::loadProbe, this::showProbe);
    private final Refresher<UpdateStatus> updateCheck = refresher(Lane.PROBE, this::loadUpdateStatus, this::showUpdateStatus);

    private final Command<Void> syncNow = Command.async(this::startSyncNow, this::fail);
    private final Command<Void> cancel = Command.async(this::stopSync, this::fail);
    private final Command<Void> updateCli = Command.async(this::installNewerCli, this::fail);
    private final Command<Void> dismissUpdate = Command.async(this::dismissUpdateBanner, this::fail);
    private final Command<Boolean> setAutoSync = new Command<>(this::applyAutoSync, this::fail);

    private String shownSystemdProblem;

    public StatusPageViewModel(BackupService service, Background background) {
        this(service, background, ZoneId.systemDefault());
    }

    public StatusPageViewModel(BackupService service, Background background, ZoneId zone) {
        super(service, background);
        this.zone = zone;
        syncNow.enabledWhen(running.not());
        cancel.enabledWhen(running);
    }

    @Override
    public String title() {
        return "Status";
    }

    // ---- state --------------------------------------------------------------------------

    public ReadOnlyStringProperty lastRun() {
        return lastRun.getReadOnlyProperty();
    }

    public ReadOnlyStringProperty nextRun() {
        return nextRun.getReadOnlyProperty();
    }

    public ReadOnlyStringProperty sessionText() {
        return sessionText.getReadOnlyProperty();
    }

    public ReadOnlyStringProperty cliVersion() {
        return cliVersion.getReadOnlyProperty();
    }

    public ReadOnlyIntegerProperty syncedCount() {
        return syncedCount.getReadOnlyProperty();
    }

    public ReadOnlyIntegerProperty pendingCount() {
        return pendingCount.getReadOnlyProperty();
    }

    public ReadOnlyIntegerProperty errorCount() {
        return errorCount.getReadOnlyProperty();
    }

    /** A run is in progress (as systemd reports it). */
    public ReadOnlyBooleanProperty running() {
        return running.getReadOnlyProperty();
    }

    /** Whether the timer is enabled. Shown by the view; changed only through {@link #setAutoSync()}. */
    public ReadOnlyBooleanProperty autoSync() {
        return autoSync.getReadOnlyProperty();
    }

    public ReadOnlyBooleanProperty updateAvailable() {
        return updateAvailable.getReadOnlyProperty();
    }

    public ReadOnlyStringProperty availableVersion() {
        return availableVersion.getReadOnlyProperty();
    }

    /** True while the CLI is being downloaded and installed. */
    public ReadOnlyBooleanProperty updating() {
        return updateCli.running();
    }

    // ---- commands -----------------------------------------------------------------------

    public Command<Void> syncNow() {
        return syncNow;
    }

    public Command<Void> cancel() {
        return cancel;
    }

    public Command<Void> updateCli() {
        return updateCli;
    }

    public Command<Void> dismissUpdate() {
        return dismissUpdate;
    }

    /** For the automatic-sync control's own action event, with the state the user just chose. */
    public Command<Boolean> setAutoSync() {
        return setAutoSync;
    }

    // ---- refreshing ---------------------------------------------------------------------

    @Override
    public void refresh() {
        fast.refresh();
    }

    /** CLI version, session, and the daily update check; roughly every 30 seconds from the main window. */
    public void refreshSlow() {
        probe.refresh();
        updateCheck.refresh();
    }

    private FastSnapshot loadFast() throws InterruptedException {
        var counts = service.database().getStatusCounts();
        var text = Descriptions.lastRun(service.database().getRecentRuns(1), zone);

        var systemd = service.systemd();
        String problem = null;
        var isRunning = false;
        var timerEnabled = false;
        String next = null;
        try {
            isRunning = systemd.isSyncRunning();
            timerEnabled = systemd.isTimerEnabled();
            next = systemd.nextRun();
        } catch (IOException e) {
            problem = "systemd could not be reached: " + e.getMessage();
        }
        return new FastSnapshot(
                counts.getOrDefault(FileStatus.SYNCED, 0),
                counts.getOrDefault(FileStatus.PENDING, 0),
                counts.getOrDefault(FileStatus.ERROR, 0),
                text,
                next != null ? next : "no timer",
                isRunning,
                timerEnabled,
                problem);
    }

    private void showFast(FastSnapshot snapshot) {
        syncedCount.set(snapshot.synced());
        pendingCount.set(snapshot.pending());
        errorCount.set(snapshot.errors());
        lastRun.set(snapshot.lastRun());
        nextRun.set(snapshot.nextRun());
        running.set(snapshot.running());
        autoSync.set(snapshot.autoSync());
        if (!Objects.equals(snapshot.systemdProblem(), shownSystemdProblem)) {
            shownSystemdProblem = snapshot.systemdProblem();
            if (shownSystemdProblem != null) say(shownSystemdProblem);
        }
    }

    private ProbeSnapshot loadProbe() throws InterruptedException {
        return new ProbeSnapshot(service.cliVersion(), service.session());
    }

    private void showProbe(ProbeSnapshot snapshot) {
        cliVersion.set(snapshot.cliVersion());
        sessionText.set(Descriptions.session(snapshot.session()));
    }

    private UpdateStatus loadUpdateStatus() throws InterruptedException {
        return service.updateCheck().check(false);
    }

    private void showUpdateStatus(UpdateStatus status) {
        if (status == null) return;
        availableVersion.set(status.available());
        updateAvailable.set(status.updateAvailable() && !status.dismissed());
    }

    // ---- actions ------------------------------------------------------------------------

    private CompletableFuture<?> startSyncNow() {
        return background
                .run(Lane.ACTIONS, () -> {
                    // A fresh check, not the possibly stale cached flag.
                    var systemd = service.systemd();
                    return systemd.isSyncRunning() ? null : systemd.startSync(null);
                })
                .thenAccept(result -> {
                    say(result == null ? "A run is already in progress." : result.ok() ? "Run started." : result.output().trim());
                    fast.refresh();
                });
    }

    private CompletableFuture<?> stopSync() {
        return background.run(Lane.ACTIONS, () -> service.systemd().stopSync()).thenAccept(result -> {
            say(result.ok() ? "Stopped; the next run continues where this one left off." : result.output().trim());
            fast.refresh();
        });
    }

    private CompletableFuture<?> applyAutoSync(Boolean enabled) {
        return background
                .run(Lane.ACTIONS, () -> enabled ? service.systemd().enableTimer() : service.systemd().disableTimer())
                .thenAccept(result -> {
                    say(result.ok() ? (enabled ? "Automatic syncing is on." : "Automatic syncing is off.") : result.output().trim());
                    fast.refresh(); // shows the real state, which also puts the control back if this failed
                });
    }

    /** What the CLI update ended with; {@code null} means it did not start because a run is going. */
    private record UpdateResult(boolean success, String message) {}

    private CompletableFuture<?> installNewerCli() {
        return background
                .run(Lane.ACTIONS, status -> {
                    if (service.systemd().isSyncRunning()) {
                        return new UpdateResult(false, "A run is in progress and will finish first. Try again shortly.");
                    }
                    var outcome = service.installCli(status);
                    return new UpdateResult(outcome.success(), outcome.message());
                }, this::say)
                .thenAccept(result -> {
                    say(result.message());
                    if (result.success()) updateAvailable.set(false);
                    refreshSlow();
                });
    }

    private CompletableFuture<?> dismissUpdateBanner() {
        var version = availableVersion.get();
        updateAvailable.set(false);
        if (version == null) return null;
        return background.run(Lane.ACTIONS, () -> {
            service.updateCheck().dismiss(version);
            return null;
        });
    }
}
