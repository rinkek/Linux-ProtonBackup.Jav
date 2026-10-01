package protonbackup.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import protonbackup.core.Database;
import protonbackup.daemon.DaemonProcess.World;

/**
 * The daemon as the machine's real {@code systemd --user} runs and stops it: a transient service (so no
 * unit file is installed and nothing of the user's own is touched), stopped with {@code systemctl stop},
 * which sends SIGTERM to every process of the service at once. Off by default; run it with
 * {@code -Dprotonbackup.realSystemd=true}.
 */
@EnabledIfSystemProperty(named = "protonbackup.realSystemd", matches = "true")
@Timeout(180)
class RealSystemdStopTest {

    @TempDir Path root;

    private static String run(String... command) throws Exception {
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor(60, TimeUnit.SECONDS);
        return output;
    }

    @Test
    void systemctlStopEndsARunCleanlyWithExitStatusZero() throws Exception {
        var world = new World(root).withCli();
        world.environment.put("FAKE_UPLOAD_DELAY", "6");
        var source = world.source("docs");
        world.writeFile(source.resolve("dir_a/a.txt"), "aaa");
        world.writeFile(source.resolve("dir_b/b.txt"), "bbb");
        assertEquals(0, DaemonProcess.start(world, false, "add-source", source.toString(), "/my-files/Backup").waitFor());

        var unit = "protonbackup-test-" + UUID.randomUUID().toString().substring(0, 8);
        var command = new ArrayList<String>(List.of("systemd-run", "--user", "--wait", "--collect", "--unit=" + unit));
        command.add("--setenv=HOME=" + world.home);
        command.add("--setenv=LC_ALL=C.UTF-8");
        world.environment.forEach((name, value) -> command.add("--setenv=" + name + "=" + value));
        command.addAll(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), "protonbackup.daemon.Main", "--run-once"));

        var transcript = Files.createTempFile(root, "systemd-run", ".txt");
        var systemdRun = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(transcript.toFile()).start();
        try {
            DaemonProcess.awaitText(world.log, " upload ", Duration.ofSeconds(60));

            var stop = run("systemctl", "--user", "stop", unit + ".service");
            assertTrue(systemdRun.waitFor(60, TimeUnit.SECONDS), "systemd-run must return once the unit is stopped; " + stop);
        } finally {
            if (systemdRun.isAlive()) {
                run("systemctl", "--user", "kill", "--signal=KILL", unit + ".service");
                systemdRun.destroyForcibly();
            }
        }

        var report = Files.readString(transcript);
        assertTrue(report.contains("Main processes terminated with: code=exited, status=0/SUCCESS"),
                "the daemon must exit with status 0 after the stop request (a plain JVM would say 143):\n" + report);

        var journal = run("journalctl", "--user", "-u", unit + ".service", "-o", "cat", "--no-pager");
        assertTrue(journal.contains("Cancelled after"), journal);

        try (var db = new Database(world.paths.databasePath())) {
            var runRow = db.getRecentRuns(1).get(0);
            assertEquals("cancelled", runRow.result());
            assertTrue(runRow.finishedUtc() != null, "the run must be finished and recorded, not left open");
            // systemd killed the CLI too, so the batch in flight is an error to be retried, never a lost file
            assertEquals(1, runRow.failed());
            assertEquals(0, runRow.uploaded());
        }
        assertTrue(Files.notExists(root.resolve("remote/my-files/Backup/dir_b")), "the second batch must not have started");
    }
}
