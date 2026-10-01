package protonbackup.ui.viewmodel;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import protonbackup.core.Cleanup;
import protonbackup.core.CliSettings;
import protonbackup.core.SystemdManager;
import protonbackup.ui.mvvm.Background;
import protonbackup.ui.mvvm.Background.Lane;
import protonbackup.ui.mvvm.Command;
import protonbackup.ui.mvvm.PageViewModel;
import protonbackup.ui.mvvm.Refresher;
import protonbackup.ui.service.BackupService;

/**
 * Account (sign in and out), the sync interval, the Proton CLI (update, roll back, advanced settings)
 * and removal of everything the app put in the home folder.
 *
 * <p>The form is loaded once when the page is first shown, and again after something was saved or
 * restored, <em>not</em> on every two-second tick: the original overwrote the text boxes on each tick,
 * which threw away whatever the user was typing.
 */
public final class SettingsPageViewModel extends PageViewModel {

    /** What the form fields hold; read together in a worker. */
    private record Form(Integer intervalMinutes, String versionPageUrl, String downloadTemplate, String platform, boolean skipChecksum) {}

    /** The state of the Remove step: a first press asks to confirm, a second does it. */
    static final String CONFIRM_CLEANUP = "This deletes the database, the settings and the downloaded CLI. "
            + "Whatever is already on Proton stays there. Press again to continue.";

    private final ReadOnlyStringWrapper sessionText = new ReadOnlyStringWrapper("unknown");
    /** True while the CLI reports a working session: signing in again would do nothing, so the button is faded. */
    private final javafx.beans.property.SimpleBooleanProperty signedIn = new javafx.beans.property.SimpleBooleanProperty();
    private final ReadOnlyStringWrapper cliPath = new ReadOnlyStringWrapper("");
    private final IntegerProperty intervalMinutes = new SimpleIntegerProperty((int) SystemdManager.DEFAULT_INTERVAL.toMinutes());
    private final StringProperty versionPageUrl = new SimpleStringProperty("");
    private final StringProperty downloadTemplate = new SimpleStringProperty("");
    private final StringProperty platform = new SimpleStringProperty("");
    private final BooleanProperty skipChecksum = new SimpleBooleanProperty();
    private final ReadOnlyBooleanWrapper confirmCleanup = new ReadOnlyBooleanWrapper();

    private final Refresher<Form> formLoader = refresher(Lane.FAST, this::loadForm, this::showForm);
    private final Refresher<protonbackup.core.SessionState> sessionProbe =
            refresher(Lane.PROBE, service::session, state -> {
                sessionText.set(Descriptions.session(state));
                signedIn.set(state == protonbackup.core.SessionState.ACTIVE);
            });

    private final Command<Void> login = Command.async(this::signIn, this::fail);
    private final Command<Void> logout = Command.async(this::signOut, this::fail);
    private final Command<Void> applyInterval = Command.async(this::applyTheInterval, this::fail);
    private final Command<Void> installUnits = Command.async(this::writeTheUnits, this::fail);
    private final Command<Void> saveCliSettings = Command.async(this::saveTheCliSettings, this::fail);
    private final Command<Void> checkForUpdate = Command.async(this::checkTheVersionPage, this::fail);
    private final Command<Void> updateCli = Command.async(this::installTheCli, this::fail);
    private final Command<Void> rollbackCli = Command.async(this::rollBackTheCli, this::fail);
    private final Command<Void> cleanup = Command.async(this::cleanUp, this::fail);
    private final Command<Void> restoreDefaults = Command.async(this::restoreTheDefaults, this::fail);

    private final BooleanBinding busy = login.running().or(logout.running()).or(checkForUpdate.running())
            .or(updateCli.running()).or(rollbackCli.running()).or(cleanup.running());

    private boolean formLoaded;
    private boolean everLoaded;
    private boolean intervalPending;
    private Runnable onAccountChanged = () -> {};
    private Runnable onRemovalStarted = () -> {};
    private Consumer<List<Cleanup.Step>> onRemoved = steps -> {};

    public SettingsPageViewModel(BackupService service, Background background) {
        super(service, background);
        for (var command : List.of(logout, checkForUpdate, updateCli, rollbackCli, cleanup)) command.enabledWhen(busy.not());
        login.enabledWhen(busy.not().and(signedIn.not()));
    }

    @Override
    public String title() {
        return "Settings";
    }

    // ---- wiring to the main window ------------------------------------------------------

    /** Called after a sign-in, sign-out or CLI install, so the rest of the window re-reads the session at once. */
    public void onAccountChanged(Runnable action) {
        this.onAccountChanged = action;
    }

    /** Called on the UI thread when the removal begins (polling must stop) and with the steps when it is over. */
    public void onRemoval(Runnable started, Consumer<List<Cleanup.Step>> finished) {
        this.onRemovalStarted = started;
        this.onRemoved = finished;
    }

    // ---- state --------------------------------------------------------------------------

    public ReadOnlyStringProperty sessionText() {
        return sessionText.getReadOnlyProperty();
    }

    /** Where the CLI is, or empty. */
    public ReadOnlyStringProperty cliPath() {
        return cliPath.getReadOnlyProperty();
    }

    public IntegerProperty intervalMinutes() {
        return intervalMinutes;
    }

    public StringProperty versionPageUrl() {
        return versionPageUrl;
    }

    public StringProperty downloadTemplate() {
        return downloadTemplate;
    }

    public StringProperty platform() {
        return platform;
    }

    public BooleanProperty skipChecksum() {
        return skipChecksum;
    }

    /** True while one of the long actions (sign in, update, removal...) runs; their buttons are disabled meanwhile. */
    public BooleanBinding busy() {
        return busy;
    }

    /** True after the first press of "Remove and clean up". */
    public ReadOnlyBooleanProperty confirmCleanup() {
        return confirmCleanup.getReadOnlyProperty();
    }

    public Command<Void> login() {
        return login;
    }

    public Command<Void> logout() {
        return logout;
    }

    public Command<Void> applyInterval() {
        return applyInterval;
    }

    public Command<Void> installUnits() {
        return installUnits;
    }

    public Command<Void> saveCliSettings() {
        return saveCliSettings;
    }

    public Command<Void> checkForUpdate() {
        return checkForUpdate;
    }

    public Command<Void> updateCli() {
        return updateCli;
    }

    public Command<Void> rollbackCli() {
        return rollbackCli;
    }

    public Command<Void> cleanup() {
        return cleanup;
    }

    public Command<Void> restoreDefaults() {
        return restoreDefaults;
    }

    // ---- refreshing ---------------------------------------------------------------------

    @Override
    public void refresh() {
        cliPath.set(service.cliPath() == null ? "" : service.cliPath());
        if (!formLoaded) formLoader.refresh();
    }

    /** Re-reads the session from the CLI; part of the window's slow refresh. */
    public void refreshSession() {
        sessionProbe.refresh();
    }

    private void reloadForm(boolean includingInterval) {
        formLoaded = false;
        intervalPending = includingInterval;
        formLoader.refresh();
    }

    private Form loadForm() {
        Integer minutes = null;
        var stored = service.database().getSetting("interval_minutes");
        try {
            if (stored != null) minutes = Integer.valueOf(stored.trim());
        } catch (NumberFormatException ignored) {
            // a damaged value: keep what the page shows
        }
        var settings = service.cliSettings();
        return new Form(minutes, settings.versionPageUrl(), settings.downloadTemplate(), settings.platform(), settings.skipChecksum());
    }

    private void showForm(Form form) {
        // The interval field is only filled from the database the first time, or when asked to; a reload after
        // saving the CLI settings must not undo an interval the user is editing or has just reset.
        if (!everLoaded || intervalPending) {
            if (form.intervalMinutes() != null && form.intervalMinutes() >= 1) intervalMinutes.set(form.intervalMinutes());
        }
        versionPageUrl.set(form.versionPageUrl());
        downloadTemplate.set(form.downloadTemplate());
        platform.set(form.platform());
        skipChecksum.set(form.skipChecksum());
        formLoaded = true;
        everLoaded = true;
        intervalPending = false;
    }

    // ---- account ------------------------------------------------------------------------

    /** The one place a sign-in starts: installs the CLI first when it is missing (plan section 6.10). */
    private CompletableFuture<?> signIn() {
        return background.run(Lane.ACTIONS, service::signIn, this::say).thenAccept(outcome -> {
            say(outcome.message());
            cliPath.set(service.cliPath() == null ? "" : service.cliPath());
            sessionProbe.refresh();
            onAccountChanged.run();
        });
    }

    private CompletableFuture<?> signOut() {
        return background.run(Lane.ACTIONS, service::logout).thenAccept(result -> {
            say(result.ok() ? "Signed out." : result.output().trim());
            sessionProbe.refresh();
            onAccountChanged.run();
        });
    }

    // ---- syncing ------------------------------------------------------------------------

    private CompletableFuture<?> applyTheInterval() {
        var minutes = intervalMinutes.get();
        if (minutes < 1) {
            say("The interval must be at least one minute.");
            return null;
        }
        return background.run(Lane.ACTIONS, () -> {
            service.systemd().setInterval(Duration.ofMinutes(minutes));
            service.database().setSetting("interval_minutes", Integer.toString(minutes));
            return null;
        }).thenAccept(done -> say("Interval set to " + minutes + " minutes."));
    }

    private CompletableFuture<?> writeTheUnits() {
        var minutes = Math.max(1, intervalMinutes.get());
        return background.run(Lane.ACTIONS, () -> {
            service.systemd().installUnits(Duration.ofMinutes(minutes));
            return null;
        }).thenAccept(done -> say("systemd units written."));
    }

    // ---- the Proton CLI -----------------------------------------------------------------

    private CompletableFuture<?> saveTheCliSettings() {
        var url = versionPageUrl.get();
        var template = downloadTemplate.get();
        var chosenPlatform = platform.get();
        var skip = skipChecksum.get();
        return background.run(Lane.ACTIONS, () -> {
            var settings = service.cliSettings();
            settings.setVersionPageUrl(url);
            settings.setDownloadTemplate(template);
            settings.setPlatform(chosenPlatform);
            settings.setSkipChecksum(skip);
            return null;
        }).thenAccept(done -> {
            say("CLI settings saved.");
            reloadForm(false);
        });
    }

    private CompletableFuture<?> checkTheVersionPage() {
        return background.run(Lane.ACTIONS, () -> service.updateCheck().check(true)).thenAccept(status -> {
            if (status == null) {
                say("The version page could not be read.");
            } else if (status.updateAvailable()) {
                say("Version " + status.available() + " is available (currently " + status.installed() + ").");
            } else {
                say("You are up to date on version " + (status.installed() != null ? status.installed() : status.available()) + ".");
            }
        });
    }

    private CompletableFuture<?> installTheCli() {
        return background.run(Lane.ACTIONS, service::installCli, this::say).thenAccept(outcome -> {
            say(outcome.message());
            refresh();
            reloadForm(false);
            onAccountChanged.run();
        });
    }

    private CompletableFuture<?> rollBackTheCli() {
        return background.run(Lane.ACTIONS, () -> {
            var outcome = service.installer().rollback();
            service.refreshCliPath();
            return outcome;
        }).thenAccept(outcome -> {
            say(outcome.message());
            refresh();
            onAccountChanged.run();
        });
    }

    private CompletableFuture<?> restoreTheDefaults() {
        intervalMinutes.set((int) SystemdManager.DEFAULT_INTERVAL.toMinutes());
        return background.run(Lane.ACTIONS, () -> {
            service.cliSettings().restoreDefaults();
            return null;
        }).thenAccept(done -> {
            say("Defaults restored; apply the interval with Save interval.");
            reloadForm(false); // the interval just set to the default is not stored yet and must stay
        });
    }

    // ---- removal ------------------------------------------------------------------------

    /**
     * A package must not reach into the home folder, so removal happens here. Two presses: the first asks,
     * the second stops all polling and removes everything; the window then shows the result and the user quits.
     */
    private CompletableFuture<?> cleanUp() {
        if (!confirmCleanup.get()) {
            confirmCleanup.set(true);
            say(CONFIRM_CLEANUP);
            return null;
        }
        confirmCleanup.set(false);
        onRemovalStarted.run();
        return background.run(Lane.ACTIONS, service::removeEverything).thenAccept(onRemoved);
    }
}
