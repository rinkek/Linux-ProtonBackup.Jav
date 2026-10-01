package protonbackup.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes and controls the systemd <em>user</em> units that run the daemon. Everything here is plain
 * {@code systemctl --user} calls plus unit files; no systemd binding is needed.
 *
 * <p>Two details cost real debugging time in the original and must never regress silently, because
 * they only show hours later (a timer that never fires):
 * <ol>
 *   <li><b>{@code OnActiveSec}, not {@code OnStartupSec}.</b> The latter counts from boot, so when the
 *       timer is enabled later in the day it has already "elapsed", and systemd does not catch up an
 *       elapsed monotonic timer. {@code OnActiveSec} counts from the moment the timer unit itself
 *       starts, which is always in the future.
 *   <li><b>The interval lives in a drop-in that re-specifies both keys.</b> An empty
 *       {@code OnUnitActiveSec=} clears the <em>whole</em> list of monotonic timers on the unit, not
 *       just that key, so {@code OnActiveSec} must be written again after it, every time.
 * </ol>
 *
 * <p>{@code systemctl start} gets {@code --no-block}: the service is {@code Type=oneshot}, so without
 * it the call would wait for the whole run and freeze whoever called it (the UI).
 */
public final class SystemdManager implements SystemdControl {

    public static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(30);
    public static final Duration STARTUP_DELAY = Duration.ofMinutes(2);

    private final AppPaths paths;
    private final UnitNames names;
    private final String executable;
    private final CommandRunner runner;

    /**
     * @param executable the daemon's launcher, as written into {@code ExecStart}; normally {@code /usr/bin/protonbackup}
     */
    public SystemdManager(AppPaths paths, String executable, CommandRunner runner) {
        this(paths, UnitNames.DEFAULT, executable, runner);
    }

    public SystemdManager(AppPaths paths, UnitNames names, String executable, CommandRunner runner) {
        this.paths = paths;
        this.names = names;
        this.executable = executable;
        this.runner = runner;
    }

    public String executable() {
        return executable;
    }

    public UnitNames names() {
        return names;
    }

    public void installUnits() throws IOException, InterruptedException {
        installUnits(null);
    }

    /** Writes the service, the per-source service template and the timer, then reloads systemd. */
    public void installUnits(Duration interval) throws IOException, InterruptedException {
        var directory = paths.userUnitDir();
        Files.createDirectories(directory);
        var command = execStartCommand(executable);

        write(directory.resolve(names.service()), String.join("\n",
                "[Unit]",
                "Description=Proton Drive backup, a sync run",
                "",
                "[Service]",
                "Type=oneshot",
                "ExecStart=" + command + " --run-once",
                ""));

        write(directory.resolve(names.sourceTemplate()), String.join("\n",
                "[Unit]",
                "Description=Proton Drive backup, a sync run for source %i",
                "",
                "[Service]",
                "Type=oneshot",
                "ExecStart=" + command + " --run-once --source %i",
                ""));

        write(directory.resolve(names.timer()), String.join("\n",
                "[Unit]",
                "Description=Proton Drive backup on an interval",
                "",
                "[Timer]",
                "OnActiveSec=" + formatInterval(STARTUP_DELAY),
                "OnUnitActiveSec=" + formatInterval(interval != null ? interval : DEFAULT_INTERVAL),
                "Unit=" + names.service(),
                "",
                "[Install]",
                "WantedBy=timers.target",
                ""));

        reload();
    }

    /** Changes the interval through a drop-in, so the unit itself stays untouched (see the class comment). */
    public void setInterval(Duration interval) throws IOException, InterruptedException {
        var directory = paths.userUnitDir().resolve(names.timerDropInDir());
        Files.createDirectories(directory);
        write(directory.resolve("interval.conf"), String.join("\n",
                "[Timer]",
                "OnUnitActiveSec=",
                "OnActiveSec=" + formatInterval(STARTUP_DELAY),
                "OnUnitActiveSec=" + formatInterval(interval),
                ""));
        reload();
    }

    @Override
    public CliResult reload() throws IOException, InterruptedException {
        return systemctl("daemon-reload");
    }

    public CliResult enableTimer() throws IOException, InterruptedException {
        return systemctl("enable", "--now", names.timer());
    }

    @Override
    public CliResult disableTimer() throws IOException, InterruptedException {
        return systemctl("disable", "--now", names.timer());
    }

    public boolean isTimerEnabled() throws IOException, InterruptedException {
        return systemctl("is-enabled", names.timer()).stdOut().trim().equals("enabled");
    }

    /** Starts a run now ({@code --no-block}, see the class comment); for one source when an id is given. */
    public CliResult startSync(Long sourceId) throws IOException, InterruptedException {
        return systemctl("start", "--no-block", sourceId == null ? names.service() : names.sourceService(sourceId));
    }

    @Override
    public CliResult stopSync() throws IOException, InterruptedException {
        return systemctl("stop", names.service());
    }

    public boolean isSyncRunning() throws IOException, InterruptedException {
        var state = systemctl("is-active", names.service()).stdOut().trim();
        return state.equals("active") || state.equals("activating");
    }

    public String describeTimer() throws IOException, InterruptedException {
        return systemctl("list-timers", names.timer(), "--no-pager").stdOut().trim();
    }

    /** When the timer fires next, as systemd prints it (e.g. {@code Thu 2026-10-01 08:47:17 CEST}), or {@code null}. */
    public String nextRun() throws IOException, InterruptedException {
        return parseNextRun(describeTimer(), names.timer());
    }

    /**
     * The NEXT column of {@code systemctl list-timers}. The table pads its columns with a <em>single</em>
     * space when a column is as wide as its longest cell, so splitting on double spaces (as the .NET
     * original did) can return several columns glued together; the timestamp itself is matched instead.
     */
    static String parseNextRun(String listTimersOutput, String timerName) {
        for (var line : listTimersOutput.split("\n")) {
            if (!line.contains(timerName)) continue;
            var trimmed = line.trim();
            if (trimmed.startsWith("-")) return null; // "n/a": the timer is not scheduled
            var matcher = NEXT_RUN.matcher(trimmed);
            if (matcher.find()) return matcher.group();
            var columns = trimmed.split("\\s{2,}");
            return columns.length > 0 && !columns[0].isBlank() && !columns[0].equals("-") ? columns[0] : null;
        }
        return null;
    }

    private static final java.util.regex.Pattern NEXT_RUN =
            java.util.regex.Pattern.compile("^\\S+ \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(?: [A-Za-z0-9+:-]+)?");

    private CliResult systemctl(String... arguments) throws IOException, InterruptedException {
        var all = new ArrayList<String>(List.of("--user"));
        all.addAll(List.of(arguments));
        return runner.run("systemctl", all);
    }

    private static void write(java.nio.file.Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    /** Whole minutes as {@code 30min}, anything else in seconds. */
    static String formatInterval(Duration interval) {
        var seconds = interval.toSeconds();
        return seconds >= 60 && seconds % 60 == 0 ? (seconds / 60) + "min" : seconds + "s";
    }

    /**
     * The program path as {@code ExecStart} wants it: a {@code %} would start a systemd specifier, and
     * whitespace would split the path into arguments.
     */
    static String execStartCommand(String path) {
        var escaped = path.replace("%", "%%");
        if (escaped.chars().noneMatch(c -> Character.isWhitespace(c) || c == '"' || c == '\\' || c == '\'')) {
            return escaped;
        }
        return "\"" + escaped.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
