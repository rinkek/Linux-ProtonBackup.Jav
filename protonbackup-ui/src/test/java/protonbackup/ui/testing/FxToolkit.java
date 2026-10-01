package protonbackup.ui.testing;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;

/**
 * Starts the real JavaFX toolkit for the few tests that need scene-graph objects, and skips them
 * (instead of failing) on a machine without a display, such as a build server.
 */
public final class FxToolkit {

    private static Boolean available;

    private FxToolkit() {}

    public static synchronized boolean start() {
        if (available == null) {
            try {
                Platform.startup(() -> {});
                Platform.setImplicitExit(false); // keep the toolkit alive between test classes
                available = true;
            } catch (IllegalStateException alreadyRunning) {
                available = true;
            } catch (Throwable noDisplay) {
                available = false;
            }
        }
        return available;
    }

    /** Runs something on the JavaFX thread and returns what it produced; failures are rethrown here. */
    public static <T> T onFxThread(Callable<T> task) throws Exception {
        var result = new CompletableFuture<T>();
        Platform.runLater(() -> {
            try {
                result.complete(task.call());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        try {
            return result.get(60, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }

    public static void onFxThread(Runnable task) throws Exception {
        onFxThread(() -> {
            task.run();
            return null;
        });
    }
}
