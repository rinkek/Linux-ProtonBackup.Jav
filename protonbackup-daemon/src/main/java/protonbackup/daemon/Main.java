package protonbackup.daemon;

import java.time.Duration;
import protonbackup.core.CancelToken;

/**
 * Entry point of the daemon.
 *
 * <p>{@code systemctl --user stop} (and Ctrl-C) end the process with a signal. A plain JVM then exits
 * with 143 whatever the program does, but the original returns 0 after a cancelled run, and the run
 * must stop <em>cleanly</em>: the current batch finishes, the next never starts, and the run's row is
 * recorded as cancelled with accurate partial counts. So:
 * <ul>
 *   <li>a shutdown hook, which the JVM runs only on a signal, cancels the {@link CancelToken};
 *   <li>the main thread notices at the next checkpoint, finishes its bookkeeping and ends the JVM
 *       itself with {@link Runtime#halt(int)} and its own exit code. {@code System.exit} is not used:
 *       it would run the hook as well, which must not happen on a normal exit;
 *   <li>if the main thread does not finish within a minute (systemd's own stop timeout is 90 s), the
 *       hook gives up and halts with 143.
 * </ul>
 */
public final class Main {

    private static final Duration SHUTDOWN_PATIENCE = Duration.ofSeconds(60);

    private Main() {}

    public static void main(String[] args) {
        var token = new CancelToken();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> onSignal(token), "signal-handler"));

        int exitCode;
        try {
            exitCode = new Daemon(Daemon.Environment.real()).run(args, token);
        } catch (Throwable unexpected) {
            System.err.println("protonbackup: " + unexpected);
            exitCode = 1;
        }

        System.out.flush();
        System.err.flush();
        Runtime.getRuntime().halt(exitCode);
    }

    private static void onSignal(CancelToken token) {
        token.cancel();
        try {
            Thread.sleep(SHUTDOWN_PATIENCE.toMillis());
        } catch (InterruptedException e) {
            return;
        }
        System.err.println("protonbackup: did not finish in time after the stop request; giving up.");
        System.err.flush();
        Runtime.getRuntime().halt(143);
    }
}
