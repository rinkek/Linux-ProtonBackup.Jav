package protonbackup.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Starts an external program and captures its output.
 *
 * <p>Two behaviors are load-bearing:
 * <ul>
 *   <li><b>Standard input is connected to /dev/null.</b> The {@code proton-drive} CLI has an
 *       interactive conflict prompt that would wait forever on input that never comes.
 *   <li><b>The child gets the user's environment, not the launcher's</b> (see {@link LauncherEnvironment}).
 *   <li><b>Standard output and error are drained at the same time.</b> Reading one to the end
 *       before the other, or not at all, blocks a child that writes more than the pipe buffer
 *       (64 KiB) for ever.
 * </ul>
 *
 * <p>Output is decoded as UTF-8 regardless of the locale.
 */
public final class ProcessRunner implements CommandRunner {

    private static final Duration KILL_GRACE = Duration.ofSeconds(2);
    private static final Duration READER_GRACE = Duration.ofSeconds(5);

    @Override
    public CliResult run(String executable, List<String> arguments) throws IOException, InterruptedException {
        return run(executable, arguments, null);
    }

    /**
     * @param timeout how long to wait, or {@code null} to wait as long as it takes
     * @throws ProcessTimeoutException when the timeout passed (the child and its children are killed)
     */
    public CliResult run(String executable, List<String> arguments, Duration timeout)
            throws IOException, InterruptedException {
        var command = new ArrayList<String>(arguments.size() + 1);
        command.add(executable);
        command.addAll(arguments);

        var builder = new ProcessBuilder(command);
        builder.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        LauncherEnvironment.sanitize(builder.environment(), System.getProperty("jpackage.app-path"));
        var process = builder.start();

        var out = new Reader(process.getInputStream());
        var err = new Reader(process.getErrorStream());
        out.start();
        err.start();

        try {
            if (timeout == null) {
                process.waitFor();
            } else if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                kill(process);
                throw new ProcessTimeoutException(executable, timeout);
            }
            // A grandchild that inherited the pipes can keep them open after the child exited.
            out.join(READER_GRACE);
            err.join(READER_GRACE);
        } catch (InterruptedException e) {
            kill(process);
            throw e;
        }
        return new CliResult(process.exitValue(), out.text(), err.text());
    }

    /** Stops the process and everything it started, politely first. */
    private static void kill(Process process) throws InterruptedException {
        var descendants = process.descendants().toList();
        process.destroy();
        descendants.forEach(ProcessHandle::destroy);
        if (!process.waitFor(KILL_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            descendants.forEach(ProcessHandle::destroyForcibly);
            process.waitFor(KILL_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** Reads one stream to its end on its own thread. */
    private static final class Reader {
        private final InputStream stream;
        private final ByteArrayOutputStream collected = new ByteArrayOutputStream();
        private Thread thread;

        Reader(InputStream stream) {
            this.stream = stream;
        }

        void start() {
            thread = Thread.ofVirtual().unstarted(() -> {
                try (stream) {
                    var buffer = new byte[8192];
                    int read;
                    while ((read = stream.read(buffer)) != -1) {
                        synchronized (collected) {
                            collected.write(buffer, 0, read);
                        }
                    }
                } catch (IOException e) {
                    // The stream was closed after the grace period (or broke): keep what was read.
                }
            });
            thread.start();
        }

        void join(Duration grace) throws InterruptedException {
            thread.join(grace.toMillis());
            if (thread.isAlive()) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // nothing more to do
                }
            }
        }

        String text() {
            synchronized (collected) {
                return collected.toString(StandardCharsets.UTF_8);
            }
        }
    }
}
