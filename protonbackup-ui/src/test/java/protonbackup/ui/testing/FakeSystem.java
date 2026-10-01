package protonbackup.ui.testing;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import protonbackup.core.CliResult;
import protonbackup.core.CommandRunner;

/**
 * Stands in for the two programs the UI starts: {@code systemctl} and the Proton CLI. State changes the
 * way the real ones would: enabling the timer makes {@code is-enabled} say so, a successful login makes the
 * session active.
 */
public final class FakeSystem implements CommandRunner {

    // ---- systemctl ----------------------------------------------------------------------
    public final List<String> systemctlCalls = new ArrayList<>();
    public boolean timerEnabled;
    public boolean syncRunning;
    public boolean systemctlMissing;
    public String listTimers = "NEXT LEFT LAST PASSED UNIT ACTIVATES\n\n0 timers listed.\n";
    /** A forced result for a systemctl verb such as "start"; the state does not change then. */
    public final Map<String, CliResult> systemctlResults = new LinkedHashMap<>();

    // ---- proton-drive -------------------------------------------------------------------
    public final List<String> cliCalls = new ArrayList<>();
    public boolean loggedIn = true;
    public boolean cliBroken;
    public String version = "Proton Drive CLI cli-drive@0.8.0+06e8c605";
    public CliResult loginResult = new CliResult(0, "", "");
    public CliResult logoutResult = new CliResult(0, "", "");
    /** Runs while a login is in progress (the browser step), e.g. to look at what the UI shows meanwhile. */
    public Runnable duringLogin;

    @Override
    public CliResult run(String executable, List<String> arguments) throws IOException {
        if (executable.equals("systemctl")) return systemctl(arguments);
        return cli(executable, arguments);
    }

    private CliResult systemctl(List<String> arguments) throws IOException {
        if (systemctlMissing) throw new IOException("Cannot run program \"systemctl\"");
        systemctlCalls.add(String.join(" ", arguments));
        var verb = arguments.get(1);
        var forced = systemctlResults.get(verb);
        if (forced != null) return forced;
        switch (verb) {
            case "is-enabled":
                return new CliResult(timerEnabled ? 0 : 1, timerEnabled ? "enabled\n" : "disabled\n", "");
            case "is-active":
                return new CliResult(syncRunning ? 0 : 3, syncRunning ? "active\n" : "inactive\n", "");
            case "list-timers":
                return new CliResult(0, listTimers, "");
            case "enable":
                timerEnabled = true;
                return new CliResult(0, "", "");
            case "disable":
                timerEnabled = false;
                return new CliResult(0, "", "");
            case "start":
                syncRunning = true;
                return new CliResult(0, "", "");
            case "stop":
                syncRunning = false;
                return new CliResult(0, "", "");
            default:
                return new CliResult(0, "", "");
        }
    }

    private CliResult cli(String executable, List<String> arguments) throws IOException {
        cliCalls.add(String.join(" ", arguments));
        if (cliBroken) throw new IOException("Cannot run program \"" + executable + "\": error=8, Exec format error");
        if (arguments.get(0).equals("version")) return new CliResult(0, version + "\n", "");
        if (arguments.get(0).equals("auth")) {
            if (arguments.get(1).equals("login")) {
                if (duringLogin != null) duringLogin.run();
                if (loginResult.ok()) loggedIn = true;
                return loginResult;
            }
            if (logoutResult.ok()) loggedIn = false;
            return logoutResult;
        }
        if (arguments.get(0).equals("filesystem") && arguments.get(1).equals("list")) {
            return loggedIn ? new CliResult(0, "[]", "") : new CliResult(1, "", "You need to login first");
        }
        return new CliResult(2, "", "unknown command " + arguments);
    }
}
