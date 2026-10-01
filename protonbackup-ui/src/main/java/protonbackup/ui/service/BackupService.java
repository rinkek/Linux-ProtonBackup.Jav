package protonbackup.ui.service;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import protonbackup.core.AppPaths;
import protonbackup.core.Cleanup;
import protonbackup.core.CliInstaller;
import protonbackup.core.CliInstaller.InstallOutcome;
import protonbackup.core.CliResult;
import protonbackup.core.CliSettings;
import protonbackup.core.CliUpdateCheck;
import protonbackup.core.CommandRunner;
import protonbackup.core.Database;
import protonbackup.core.HttpFetcher;
import protonbackup.core.ProcessRunner;
import protonbackup.core.ProtonDriveCli;
import protonbackup.core.SessionState;
import protonbackup.core.Sleeper;
import protonbackup.core.SystemdManager;
import protonbackup.core.UnitNames;

/**
 * The only way the UI reaches the core. The UI never uploads anything itself: it reads the database,
 * drives the daemon through systemd, and talks to the Proton CLI directly for sign-in, sign-out and
 * the version (things the daemon does not need).
 *
 * <p>Everything here <b>blocks</b> (disk, processes, network) and is meant to be called from
 * {@link protonbackup.ui.mvvm.Background}, never from the JavaFX thread. It is safe to call from several
 * threads at once: the {@link Database} serialises its own calls, and the rest is stateless or volatile.
 */
public final class BackupService implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger("protonbackup.ui");

    /** What the service talks to; tests replace it. */
    public record Environment(
            CommandRunner runner,
            CliInstaller.TextFetcher fetcher,
            CliInstaller.Downloader downloader,
            Sleeper sleeper,
            String pathVariable,
            String ownExecutable) {

        public static Environment real() {
            var http = new HttpFetcher();
            return new Environment(
                    new ProcessRunner(), http, http, Sleeper.SYSTEM, System.getenv("PATH"), System.getenv("PROTONBACKUP_EXEC"));
        }
    }

    private final AppPaths paths;
    private final Environment environment;
    private final Database database;
    private final SystemdManager systemd;
    private final CliSettings cliSettings;
    private final CliInstaller installer;
    private final CliUpdateCheck updateCheck;
    private volatile String cliPath;
    private volatile boolean closed;

    public BackupService(AppPaths paths, Environment environment) throws IOException {
        this.paths = paths;
        this.environment = environment;
        this.database = Database.open(paths);
        this.systemd = new SystemdManager(paths, DaemonLocator.locate(environment.ownExecutable(), paths), environment.runner());
        this.cliSettings = new CliSettings(database);
        this.installer = new CliInstaller(
                paths, database, environment.fetcher(), environment.downloader(), environment.runner(),
                message -> LOG.log(System.Logger.Level.INFO, message));
        this.updateCheck = new CliUpdateCheck(database, installer, message -> LOG.log(System.Logger.Level.INFO, message));
        refreshCliPath();
    }

    /** The service for the real application. */
    public static BackupService create() throws IOException {
        return new BackupService(AppPaths.fromEnvironment(), Environment.real());
    }

    public AppPaths paths() {
        return paths;
    }

    public Database database() {
        return database;
    }

    public SystemdManager systemd() {
        return systemd;
    }

    public CliSettings cliSettings() {
        return cliSettings;
    }

    public CliInstaller installer() {
        return installer;
    }

    public CliUpdateCheck updateCheck() {
        return updateCheck;
    }

    // ---- the Proton CLI -----------------------------------------------------------------

    /** Where the CLI is, as last looked up; {@code null} when it is not installed. Cheap, safe on any thread. */
    public String cliPath() {
        return cliPath;
    }

    /** Looks the CLI up again, e.g. after installing it. */
    public void refreshCliPath() {
        cliPath = ProtonDriveCli.locate(database.getSetting("cli_path"), paths, environment.pathVariable());
    }

    private ProtonDriveCli cli() {
        var path = cliPath;
        return path == null ? null : new ProtonDriveCli(path, environment.runner(), environment.sleeper());
    }

    /** One probe, not the daemon's three attempts: the UI asks every half minute and must stay light. */
    public SessionState session() throws InterruptedException {
        var cli = cli();
        if (cli == null) return SessionState.UNKNOWN;
        try {
            var result = cli.run(List.of("filesystem", "list", "/my-files", "--json"));
            if (result.ok()) return SessionState.ACTIVE;
            return result.notLoggedIn() ? SessionState.EXPIRED : SessionState.UNKNOWN;
        } catch (IOException e) {
            return SessionState.UNKNOWN;
        }
    }

    /** The CLI's version line, {@code "not found"} without a CLI, {@code "unknown"} when it will not start. */
    public String cliVersion() throws InterruptedException {
        var cli = cli();
        if (cli == null) return "not found";
        try {
            return cli.getVersion();
        } catch (IOException e) {
            return "unknown";
        }
    }

    /** Opens the browser sign-in and waits until the user is done (or gives up). Can take minutes. */
    public CliResult login() throws InterruptedException {
        return authCommand("login");
    }

    public CliResult logout() throws InterruptedException {
        return authCommand("logout");
    }

    private CliResult authCommand(String verb) throws InterruptedException {
        var cli = cli();
        if (cli == null) return new CliResult(1, "", "CLI not found");
        try {
            return cli.run(List.of("auth", verb));
        } catch (IOException e) {
            return new CliResult(1, "", e.getMessage());
        }
    }

    /**
     * Downloads, verifies and installs the CLI, then looks it up again.
     *
     * @param status receives progress messages as the work goes on
     */
    public InstallOutcome installCli(Consumer<String> status) throws InterruptedException {
        status.accept("Downloading and verifying...");
        var release = installer.fetchRelease();
        if (release == null) return new InstallOutcome(false, "The version page could not be read.", null);
        var outcome = installer.install(release);
        refreshCliPath();
        return outcome;
    }

    /**
     * Sign in, installing the CLI first when it is not there. Without the CLI there is no sign-in at all
     * and the CLI is not in the package, so every sign-in button must do this in one click, with no
     * detour through another page.
     */
    public SignInOutcome signIn(Consumer<String> status) throws InterruptedException {
        if (cliPath == null) {
            status.accept("The Proton Drive CLI is not installed yet; downloading it first...");
            var release = installer.fetchRelease();
            if (release == null) return new SignInOutcome(false, "The version page could not be read.");
            var outcome = installer.install(release);
            refreshCliPath();
            if (!outcome.success()) return new SignInOutcome(false, outcome.message());
            if (cliPath == null) return new SignInOutcome(false, "The CLI was installed, but could not be found afterwards.");
        }
        status.accept("Your browser will open; finish signing in there.");
        var result = login();
        return result.ok()
                ? new SignInOutcome(true, "Signed in.")
                : new SignInOutcome(false, result.output().trim());
    }

    // ---- first start --------------------------------------------------------------------

    /** True until the first-run wizard has been finished. */
    public boolean setupNeeded() {
        return !"1".equals(database.getSetting("setup_completed"));
    }

    /**
     * Removes everything the app put in the user's home folder (database, settings, the downloaded CLI,
     * the systemd units) and signs out. The database is closed first, so afterwards this service is unusable:
     * the caller must stop using it. See plan section 6.9.
     */
    public List<Cleanup.Step> removeEverything() throws InterruptedException {
        return new Cleanup(
                paths,
                UnitNames.DEFAULT,
                systemd,
                () -> ProtonDriveCli.locate(null, paths, environment.pathVariable()),
                path -> environment.runner().run(path, List.of("auth", "logout")),
                this::close,
                environment.sleeper()).run();
    }

    /** True once {@link #close()} ran, e.g. after the removal: late background work must not complain. */
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
        database.close();
    }
}
