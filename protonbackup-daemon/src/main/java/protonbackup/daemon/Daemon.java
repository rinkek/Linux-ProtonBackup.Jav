package protonbackup.daemon;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import protonbackup.core.AppPaths;
import protonbackup.core.CancelToken;
import protonbackup.core.Cleanup;
import protonbackup.core.CliInstaller;
import protonbackup.core.CliResult;
import protonbackup.core.CliUpdateCheck;
import protonbackup.core.CommandRunner;
import protonbackup.core.Database;
import protonbackup.core.HttpFetcher;
import protonbackup.core.ProcessRunner;
import protonbackup.core.ProtonDriveCli;
import protonbackup.core.Scanner;
import protonbackup.core.Sleeper;
import protonbackup.core.SyncCancelledException;
import protonbackup.core.SyncEngine;
import protonbackup.core.SystemdManager;
import protonbackup.core.UnitNames;

/**
 * The command line of the daemon. The command names and flags are a contract, not an implementation
 * detail: the systemd units and the package hard-code {@code protonbackup --run-once} verbatim.
 *
 * <p>Exit codes: {@code --run-once} returns 2 when the CLI is missing, 1 when any file failed (or the
 * run failed), 0 otherwise, including a run that was blocked (another run is active, the session is
 * unusable) and a run that was cancelled by SIGTERM. Usage errors return 2.
 */
public final class Daemon {

    /** Everything the daemon talks to, so tests can replace it. */
    public record Environment(
            AppPaths paths,
            CommandRunner runner,
            CliInstaller.TextFetcher fetcher,
            CliInstaller.Downloader downloader,
            Sleeper sleeper,
            Scanner scanner,
            Duration listRetryDelay,
            String executable,
            String pathVariable,
            PrintStream out,
            PrintStream err,
            BufferedReader in) {

        /** The real thing: real processes, real HTTP, the user's own folders and the standard streams. */
        public static Environment real() {
            var http = new HttpFetcher();
            return new Environment(
                    AppPaths.fromEnvironment(),
                    new ProcessRunner(),
                    http,
                    http,
                    Sleeper.SYSTEM,
                    new Scanner(),
                    Duration.ofSeconds(3),
                    ownExecutable(),
                    System.getenv("PATH"),
                    System.out,
                    System.err,
                    new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
        }

        /**
         * The path the units must call. The launcher wrapper passes its own path; without it the
         * jpackage launcher's path, and finally the installed location.
         */
        static String ownExecutable() {
            var fromWrapper = System.getenv("PROTONBACKUP_EXEC");
            if (fromWrapper != null && !fromWrapper.isBlank()) return fromWrapper;
            var launcher = System.getProperty("jpackage.app-path");
            if (launcher != null && !launcher.isBlank()) return launcher;
            return "/usr/bin/protonbackup";
        }
    }

    static final String HELP = """
            protonbackup
              --run-once [--source ID] [--force]
                                         Runs a sync; --force ignores what the
                                         database already considers synced
              add-source <folder> <destination>
                                         Adds a source folder
              remove-source <id>         Removes a source folder
              list-sources               Lists the source folders
              force-all [source-id]      Queues everything again
              install-units [minutes]    Writes the systemd user units
              enable-timer               Turns automatic syncing on
              disable-timer              Turns automatic syncing off
              set-interval <minutes>     Changes the interval via a drop-in
              start [source-id]          Starts a run via systemd
              stop                       Stops the run in progress
              status                     Shows CLI, session, timer and sources
              check-update               Checks whether a newer CLI is available
              update-cli                 Downloads and installs the latest CLI
              rollback-cli               Restores the previous CLI
              --cleanup [--yes]          Removes database, settings, CLI and units""";

    private final Environment env;
    private final PrintStream out;
    private final PrintStream err;
    private Database database;

    public Daemon(Environment env) {
        this.env = env;
        this.out = env.out();
        this.err = env.err();
    }

    /** Runs one command line and returns the process exit code. */
    public int run(String[] argv, CancelToken token) {
        var args = List.of(argv);
        var command = args.isEmpty() ? "--help" : args.get(0);
        try {
            return switch (command) {
                case "--run-once" -> runOnce(args, token);
                case "add-source" -> addSource(args);
                case "remove-source" -> removeSource(args);
                case "list-sources" -> listSources();
                case "install-units" -> installUnits(args);
                case "enable-timer" -> report(systemd().enableTimer(), "Timer is on.");
                case "disable-timer" -> report(systemd().disableTimer(), "Timer is off.");
                case "set-interval" -> setInterval(args);
                case "start" -> start(args);
                case "stop" -> report(systemd().stopSync(), "Run stopped; the next one continues where this one left off.");
                case "force-all" -> forceAll(args);
                case "check-update" -> checkUpdate();
                case "update-cli" -> updateCli(args);
                case "rollback-cli" -> rollbackCli();
                case "--cleanup" -> cleanup(args);
                case "status" -> status();
                default -> help();
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        } catch (IOException | RuntimeException e) {
            err.println("protonbackup: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            return 1;
        } finally {
            closeDatabase();
        }
    }

    // ---- the sync -----------------------------------------------------------------------

    private int runOnce(List<String> args, CancelToken token) throws IOException {
        var cliPath = requireCli();
        if (cliPath == null) return 2;

        Long sourceId = null;
        var index = args.indexOf("--source");
        if (index >= 0 && index + 1 < args.size()) {
            sourceId = parseLong(args.get(index + 1));
            if (sourceId == null) {
                err.println("Usage: --run-once [--source ID] [--force]   (ID must be a number)");
                return 2;
            }
        }

        if (args.contains("--force")) {
            var reset = database().markAllPending(sourceId);
            out.println("Force: " + reset + " file(s) queued again.");
        }

        var engine = new SyncEngine(
                env.paths(), database(), new ProtonDriveCli(cliPath, env.runner(), env.sleeper()), out::println,
                env.scanner(), env.listRetryDelay());
        try {
            var result = engine.runOnce(sourceId, token);
            checkForUpdateAfterTheRun(token);
            return result.failed() > 0 ? 1 : 0;
        } catch (SyncCancelledException cancelled) {
            return 0; // systemctl stop: the run was recorded as cancelled and the next one continues
        } catch (IOException | RuntimeException failure) {
            return 1; // the engine already recorded and logged the failure
        }
    }

    /** Only after the run, and a failed check must never change the outcome. */
    private void checkForUpdateAfterTheRun(CancelToken token) throws IOException {
        if (token.isCancelled()) return;
        try {
            updateCheck().check(false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            err.println("Update check failed: " + e.getMessage());
        }
    }

    // ---- sources ------------------------------------------------------------------------

    private int addSource(List<String> args) throws IOException {
        if (args.size() < 3) {
            err.println("Usage: add-source <local folder> <destination path on Proton>");
            return 2;
        }
        var id = database().addSource(args.get(1), args.get(2));
        out.println("Source " + id + ": " + Path.of(args.get(1)).toAbsolutePath().normalize() + " -> " + args.get(2));
        return 0;
    }

    private int removeSource(List<String> args) throws IOException {
        var id = args.size() > 1 ? parseLong(args.get(1)) : null;
        if (id == null) {
            err.println("Usage: remove-source <id>");
            return 2;
        }
        database().removeSource(id);
        return 0;
    }

    private int listSources() throws IOException {
        for (var source : database().getSources()) {
            out.println(source.id() + "\t" + (source.enabled() ? "on" : "off") + "\t" + source.localPath() + " -> " + source.remotePath());
        }
        return 0;
    }

    private int forceAll(List<String> args) throws IOException {
        Long sourceId = args.size() > 1 ? parseLong(args.get(1)) : null;
        var reset = database().markAllPending(sourceId);
        out.println(reset + " file(s) queued again.");
        out.println("Run a sync with: protonbackup --run-once");
        return 0;
    }

    // ---- systemd ------------------------------------------------------------------------

    private int installUnits(List<String> args) throws IOException, InterruptedException {
        var systemd = systemd();
        systemd.installUnits(readInterval(args));
        out.println("Units written for " + systemd.executable());
        out.println("Turn on with: protonbackup enable-timer");
        return 0;
    }

    private int setInterval(List<String> args) throws IOException, InterruptedException {
        var interval = readInterval(args);
        if (interval == null) {
            err.println("Usage: set-interval <minutes>");
            return 2;
        }
        systemd().setInterval(interval);
        database().setSetting("interval_minutes", Long.toString(interval.toMinutes()));
        out.println("Interval set to " + interval.toMinutes() + " minutes.");
        return 0;
    }

    private int start(List<String> args) throws IOException, InterruptedException {
        Long sourceId = args.size() > 1 ? parseLong(args.get(1)) : null;
        var systemd = systemd();
        if (systemd.isSyncRunning()) {
            out.println("A run is already in progress.");
            return 0;
        }
        return report(systemd.startSync(sourceId), "Run started.");
    }

    private int report(CliResult result, String success) {
        out.println(result.ok() ? success : result.output().trim());
        return result.ok() ? 0 : 1;
    }

    // ---- the Proton CLI -----------------------------------------------------------------

    private int checkUpdate() throws IOException, InterruptedException {
        var status = updateCheck().check(true);
        if (status == null) {
            out.println("The version page could not be read.");
            return 1;
        }
        out.println("Installed: " + (status.installed() != null ? status.installed() : "none"));
        out.println("Available: " + status.available());
        out.println(status.updateAvailable() ? "A newer version is available." : "You are up to date.");
        return 0;
    }

    private int updateCli(List<String> args) throws IOException, InterruptedException {
        var installer = installer();
        var release = installer.fetchRelease();
        if (release == null) {
            err.println("The version page could not be read.");
            return 1;
        }
        var outcome = installer.install(release, args.contains("--allow-missing-checksum"));
        out.println(outcome.message());
        return outcome.success() ? 0 : 1;
    }

    private int rollbackCli() throws IOException {
        var outcome = installer().rollback();
        out.println(outcome.message());
        return outcome.success() ? 0 : 1;
    }

    private int status() throws IOException, InterruptedException {
        var db = database();
        var cliPath = ProtonDriveCli.locate(db.getSetting("cli_path"), env.paths(), env.pathVariable());
        out.println("CLI:      " + (cliPath != null ? cliPath : "not found"));
        if (cliPath != null) {
            var cli = new ProtonDriveCli(cliPath, env.runner(), env.sleeper());
            out.println("Version:  " + cli.getVersion());
            var session = cli.checkSession().name().toLowerCase(Locale.ROOT);
            out.println("Session:  " + Character.toUpperCase(session.charAt(0)) + session.substring(1));
        }
        var systemd = systemd();
        out.println("Database: " + env.paths().databasePath());
        out.println("Timer:    " + (systemd.isTimerEnabled() ? "on" : "off"));
        out.println("Run:      " + (systemd.isSyncRunning() ? "in progress" : "not active"));
        for (var source : db.getSources()) {
            out.println("Source " + source.id() + ": " + source.localPath() + " -> " + source.remotePath()
                    + " (" + (source.enabled() ? "on" : "off") + ")");
        }

        var timers = systemd.describeTimer();
        if (!timers.isBlank()) out.println("\n" + timers);
        return 0;
    }

    // ---- removal ------------------------------------------------------------------------

    private int cleanup(List<String> args) throws IOException, InterruptedException {
        out.println("This deletes the database, the settings, the downloaded CLI and the systemd units.");
        out.println("Whatever is already on Proton Drive stays there.");
        if (!args.contains("--yes")) {
            out.print("Continue? [y/N] ");
            out.flush();
            var answer = env.in().readLine();
            if (answer == null || !(answer.trim().equalsIgnoreCase("y") || answer.trim().equalsIgnoreCase("yes"))) {
                out.println("Cancelled.");
                return 1;
            }
        }

        var steps = new Cleanup(
                env.paths(),
                UnitNames.DEFAULT,
                systemd(),
                () -> ProtonDriveCli.locate(null, env.paths(), env.pathVariable()),
                cliPath -> env.runner().run(cliPath, List.of("auth", "logout")),
                this::closeDatabase,
                env.sleeper()).run();

        for (var step : steps) {
            out.println("  " + (step.succeeded() ? "ok " : "failed") + "  " + step.description()
                    + (step.detail() == null ? "" : " (" + step.detail() + ")"));
        }
        out.println("Done. Remove the package with: sudo apt remove protonbackup");
        return Cleanup.succeeded(steps) ? 0 : 1;
    }

    private int help() {
        out.println(HELP);
        return 0;
    }

    // ---- shared pieces ------------------------------------------------------------------

    private Database database() throws IOException {
        if (database == null) database = Database.open(env.paths());
        return database;
    }

    /** Idempotent: the removal command closes it early, and {@link #run} closes it again at the end. */
    private void closeDatabase() {
        if (database == null) return;
        var open = database;
        database = null;
        open.close();
    }

    private SystemdManager systemd() {
        return new SystemdManager(env.paths(), env.executable(), env.runner());
    }

    private CliInstaller installer() throws IOException {
        return new CliInstaller(env.paths(), database(), env.fetcher(), env.downloader(), env.runner(), out::println);
    }

    private CliUpdateCheck updateCheck() throws IOException {
        return new CliUpdateCheck(database(), installer(), out::println);
    }

    /** The CLI's path, or {@code null} after explaining where it is looked for. */
    private String requireCli() throws IOException {
        var path = ProtonDriveCli.locate(database().getSetting("cli_path"), env.paths(), env.pathVariable());
        if (path == null) {
            err.println("The proton-drive CLI was not found. Put it in " + env.paths().binDir() + " or on PATH.");
        }
        return path;
    }

    private static Duration readInterval(List<String> args) {
        if (args.size() < 2) return null;
        var minutes = parseLong(args.get(1));
        return minutes != null && minutes > 0 ? Duration.ofMinutes(minutes) : null;
    }

    private static Long parseLong(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
