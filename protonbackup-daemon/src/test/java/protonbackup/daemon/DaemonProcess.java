package protonbackup.daemon;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import protonbackup.core.AppPaths;

/**
 * Runs the real {@code Main} in a second JVM against a throw-away home folder and a fake Proton CLI,
 * so exit codes and signals can be tested the way systemd would see them.
 */
final class DaemonProcess {

    /** One test world: a home folder, a fake remote, and the fake CLI installed where the daemon looks for it. */
    static final class World {
        final Path home;
        final Path remote;
        final Path log;
        final AppPaths paths;
        final Map<String, String> environment = new LinkedHashMap<>();

        World(Path root) throws IOException {
            home = Files.createDirectories(root.resolve("home"));
            remote = Files.createDirectories(root.resolve("remote/my-files"));
            log = root.resolve("cli-calls.log");
            paths = AppPaths.forHome(home);
            Files.createFile(log);
            environment.put("FAKE_REMOTE", root.resolve("remote").toString());
            environment.put("FAKE_LOG", log.toString());
        }

        World withCli() throws IOException {
            Files.createDirectories(paths.binDir());
            var target = paths.binDir().resolve("proton-drive");
            try (InputStream script = DaemonProcess.class.getResourceAsStream("/fake-proton-drive.sh")) {
                Files.copy(script, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"));
            return this;
        }

        Path source(String name) throws IOException {
            return Files.createDirectories(home.resolve(name));
        }

        /**
         * Writes a source file that already looks old: the daemon leaves files modified in the last
         * five seconds for the next round (so a half-written file is never uploaded), and a test
         * must not have to wait for that.
         */
        Path writeFile(Path file, String content) throws IOException {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(Duration.ofHours(1))));
            return file;
        }

        String logText() throws IOException {
            return Files.readString(log, StandardCharsets.UTF_8);
        }
    }

    /** A started daemon; output goes to files so nothing can block on a full pipe. */
    static final class Running {
        final Process process;
        final Path stdout;
        final Path stderr;

        Running(Process process, Path stdout, Path stderr) {
            this.process = process;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        int waitFor() throws Exception {
            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("the daemon did not finish in 90 s\n" + out() + err());
            }
            return process.exitValue();
        }

        String out() throws IOException {
            return Files.readString(stdout, StandardCharsets.UTF_8);
        }

        String err() throws IOException {
            return Files.readString(stderr, StandardCharsets.UTF_8);
        }
    }

    private DaemonProcess() {}

    /** Starts {@code Main} with the same class path as the test. */
    static Running start(World world, boolean ownProcessGroup, String... args) throws IOException {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var command = new ArrayList<String>();
        if (ownProcessGroup) command.add("setsid"); // its own group, so a signal can reach it and its children like systemd's does
        command.addAll(List.of(java, "-cp", System.getProperty("java.class.path"), "protonbackup.daemon.Main"));
        command.addAll(List.of(args));
        return launch(world, command, "C.UTF-8");
    }

    /** Starts any command line with the world's environment. */
    static Running launch(World world, List<String> command, String locale) throws IOException {
        var stdout = Files.createTempFile(world.log.getParent(), "stdout", ".txt");
        var stderr = Files.createTempFile(world.log.getParent(), "stderr", ".txt");
        var builder = new ProcessBuilder(command)
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
        var env = builder.environment();
        for (var name : List.of("XDG_DATA_HOME", "XDG_CONFIG_HOME", "XDG_CACHE_HOME", "LANG", "LC_ALL", "LC_CTYPE", "JAVA_TOOL_OPTIONS")) {
            env.remove(name);
        }
        env.put("HOME", world.home.toString());
        if (locale != null) env.put("LC_ALL", locale);
        env.putAll(world.environment);
        return new Running(builder.start(), stdout, stderr);
    }

    /** Polls until the file contains the text. */
    static void awaitText(Path file, String text, Duration timeout) throws Exception {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.readString(file, StandardCharsets.UTF_8).contains(text)) return;
            Thread.sleep(50);
        }
        throw new AssertionError("\"" + text + "\" never appeared in " + file + ":\n" + Files.readString(file));
    }

    /** Sends SIGTERM to a whole process group, as {@code systemctl stop} does with control-group kill mode. */
    static void terminateGroup(Process groupLeader) throws Exception {
        terminateGroupWith("TERM", groupLeader);
    }

    static void terminateGroupWith(String signal, Process groupLeader) throws Exception {
        var kill = new ProcessBuilder("kill", "-" + signal, "--", "-" + groupLeader.pid()).inheritIO().start();
        kill.waitFor(10, TimeUnit.SECONDS);
    }
}
