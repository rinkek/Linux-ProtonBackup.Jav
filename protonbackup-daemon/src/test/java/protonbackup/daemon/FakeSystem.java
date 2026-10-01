package protonbackup.daemon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import protonbackup.core.CliResult;
import protonbackup.core.CommandRunner;

/**
 * Stands in for everything the daemon starts as a process: {@code systemctl} and the Proton CLI. The
 * CLI half keeps an in-memory remote, so a real sync round can run end to end. It is as strict as the
 * real CLI where that matters: without the conflict-strategy flags it silently stores nothing, exit 0.
 */
final class FakeSystem implements CommandRunner {

    // ---- systemctl ----------------------------------------------------------------------
    final List<String> systemctlCalls = new ArrayList<>();
    boolean timerEnabled;
    boolean syncRunning;
    boolean systemctlMissing;
    /** Result for a systemctl verb such as "enable"; absent verbs succeed. */
    final Map<String, CliResult> systemctlResults = new LinkedHashMap<>();

    // ---- proton-drive -------------------------------------------------------------------
    final List<List<String>> cliCalls = new ArrayList<>();
    final Map<String, Map<String, Long>> remote = new LinkedHashMap<>();
    final Set<String> silentlySkipped = new HashSet<>();
    boolean loggedIn = true;
    String version = "Proton Drive CLI cli-drive@0.8.0+06e8c605";
    CliResult logout = new CliResult(0, "", "");
    Runnable onUpload;

    FakeSystem() {
        remote.put("/my-files", new LinkedHashMap<>());
    }

    @Override
    public CliResult run(String executable, List<String> arguments) throws IOException {
        if (executable.equals("systemctl")) return systemctl(arguments);
        return cli(arguments);
    }

    private CliResult systemctl(List<String> arguments) throws IOException {
        if (systemctlMissing) throw new IOException("Cannot run program \"systemctl\"");
        systemctlCalls.add(String.join(" ", arguments));
        var verb = arguments.get(1); // after --user
        var override = systemctlResults.get(verb);
        if (override != null) return override;
        return switch (verb) {
            case "is-enabled" -> new CliResult(timerEnabled ? 0 : 1, timerEnabled ? "enabled\n" : "disabled\n", "");
            case "is-active" -> new CliResult(syncRunning ? 0 : 3, syncRunning ? "active\n" : "inactive\n", "");
            case "list-timers" -> new CliResult(0, timerEnabled ? "NEXT LEFT\nin 2 min\n" : "0 timers listed.\n", "");
            default -> new CliResult(0, "", "");
        };
    }

    private CliResult cli(List<String> arguments) throws IOException {
        cliCalls.add(List.copyOf(arguments));
        if (arguments.get(0).equals("version")) return new CliResult(0, version + "\n", "");
        if (arguments.get(0).equals("auth") && arguments.get(1).equals("logout")) return logout;
        if (!loggedIn) return new CliResult(1, "", "You need to login first");

        var verb = arguments.get(1);
        switch (verb) {
            case "list": {
                var folder = remote.get(arguments.get(2));
                if (folder == null) return new CliResult(1, "", "Node not found");
                var json = new StringBuilder("[");
                for (var entry : folder.entrySet()) {
                    if (json.length() > 1) json.append(',');
                    json.append("{\"uid\":\"u-").append(entry.getKey()).append("\",\"type\":\"file\",\"name\":{\"ok\":true,\"value\":\"")
                            .append(entry.getKey()).append("\"},\"activeRevision\":{\"claimedSize\":").append(entry.getValue()).append("}}");
                }
                return new CliResult(0, json.append("]").toString(), "");
            }
            case "info":
                return remote.containsKey(arguments.get(2)) ? new CliResult(0, "{}", "") : new CliResult(1, "", "Node not found");
            case "create-folder": {
                var path = arguments.get(2).equals("/") ? "/" + arguments.get(3) : arguments.get(2) + "/" + arguments.get(3);
                if (remote.containsKey(path)) return new CliResult(1, "", "A file or folder with that name already exists");
                remote.put(path, new LinkedHashMap<>());
                return new CliResult(0, "", "");
            }
            case "upload": {
                var positional = new ArrayList<String>();
                var honoursFlags = false;
                for (var i = 2; i < arguments.size(); i++) {
                    var argument = arguments.get(i);
                    if (argument.equals("--file-conflict-strategy")) {
                        honoursFlags = arguments.get(i + 1).equals("create-new-revision");
                        i++;
                    } else if (argument.equals("--folder-conflict-strategy")) {
                        i++;
                    } else if (!argument.startsWith("--")) {
                        positional.add(argument);
                    }
                }
                var parent = positional.remove(positional.size() - 1);
                var folder = remote.computeIfAbsent(parent, key -> new LinkedHashMap<>());
                if (honoursFlags) {
                    for (var local : positional) {
                        var name = Path.of(local).getFileName().toString();
                        if (!silentlySkipped.contains(name)) folder.put(name, Files.size(Path.of(local)));
                    }
                }
                if (onUpload != null) onUpload.run();
                return new CliResult(0, "Transfer summary: " + positional.size() + " items", "");
            }
            default:
                return new CliResult(2, "", "unknown command " + verb);
        }
    }
}
