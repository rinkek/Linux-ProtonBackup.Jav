package protonbackup.ui.mvvm;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javafx.application.Platform;

/**
 * How work moves between the UI thread and everything else. The one rule of the whole UI:
 *
 * <blockquote>View models and views are touched on the JavaFX thread only. Anything that can block
 * (the database, {@code systemctl}, the Proton CLI, the network) runs through here, and what it
 * produces comes back to the JavaFX thread as immutable data.</blockquote>
 *
 * <p>Work runs in one of three <b>lanes</b>, so a slow thing can never hold up a fast one:
 * <ul>
 *   <li>{@link Lane#FAST}: reads of the local database and {@code systemctl} calls, one at a time, in
 *       order. The 2 s refresh of the visible page lives here and must never wait behind a CLI probe.
 *   <li>{@link Lane#PROBE}: the Proton CLI (version, session) and the update check, one at a time. These
 *       can take seconds, and a hung network call must only delay other probes.
 *   <li>{@link Lane#ACTIONS}: what the user asked for (sync now, sign in, update the CLI, clean up).
 *       Several can run at once, and a sign-in that waits minutes for the browser blocks nothing.
 * </ul>
 *
 * <p>The future returned by {@code run} is completed <em>on the UI thread</em>, so whatever is chained
 * onto it may update properties directly.
 */
public final class Background implements AutoCloseable {

    public enum Lane {
        FAST,
        PROBE,
        ACTIONS
    }

    /** Work that reports progress as text while it runs, e.g. "Downloading and verifying...". */
    @FunctionalInterface
    public interface Job<T> {
        T run(Consumer<String> status) throws Exception;
    }

    private final Map<Lane, Executor> lanes;
    private final Executor ui;
    private final BooleanSupplier onUiThread;
    private final ExecutorService[] owned;

    private Background(Map<Lane, Executor> lanes, Executor ui, BooleanSupplier onUiThread, ExecutorService... owned) {
        this.lanes = lanes;
        this.ui = ui;
        this.onUiThread = onUiThread;
        this.owned = owned;
    }

    /** The real thing: worker threads, results delivered with {@link Platform#runLater}. */
    public static Background production() {
        var fast = Executors.newSingleThreadExecutor(threads("pb-fast"));
        var probe = Executors.newSingleThreadExecutor(threads("pb-probe"));
        var actions = Executors.newCachedThreadPool(threads("pb-action"));
        var lanes = new EnumMap<Lane, Executor>(Lane.class);
        lanes.put(Lane.FAST, fast);
        lanes.put(Lane.PROBE, probe);
        lanes.put(Lane.ACTIONS, actions);
        Executor ui = task -> {
            try {
                Platform.runLater(task);
            } catch (IllegalStateException toolkitGone) {
                // The application is shutting down: nobody is left to show the result to.
            }
        };
        return new Background(lanes, ui, Platform::isFxApplicationThread, fast, probe, actions);
    }

    /** Everything runs at once on the calling thread. For tests that are about logic, not about threads. */
    public static Background direct() {
        Executor inline = Runnable::run;
        var lanes = new EnumMap<Lane, Executor>(Lane.class);
        for (var lane : Lane.values()) lanes.put(lane, inline);
        return new Background(lanes, inline, () -> true);
    }

    /** Your own executors, for tests that are about ordering and threads. */
    public static Background withExecutors(Executor fast, Executor probe, Executor actions, Executor ui, BooleanSupplier onUiThread) {
        var lanes = new EnumMap<Lane, Executor>(Lane.class);
        lanes.put(Lane.FAST, fast);
        lanes.put(Lane.PROBE, probe);
        lanes.put(Lane.ACTIONS, actions);
        return new Background(lanes, ui, onUiThread);
    }

    /** Runs {@code work} in a lane. The future completes on the UI thread. */
    public <T> CompletableFuture<T> run(Lane lane, Callable<T> work) {
        return run(lane, status -> work.call(), null);
    }

    /**
     * Runs {@code job} in a lane; every message it publishes through {@code status} is delivered to
     * {@code onStatus} on the UI thread, before the future completes.
     */
    public <T> CompletableFuture<T> run(Lane lane, Job<T> job, Consumer<String> onStatus) {
        var result = new CompletableFuture<T>();
        Consumer<String> publish = message -> {
            if (onStatus != null) ui.execute(() -> onStatus.accept(message));
        };
        lanes.get(lane).execute(() -> {
            T value = null;
            Throwable failure = null;
            try {
                value = job.run(publish);
            } catch (Throwable t) {
                failure = t;
                if (t instanceof InterruptedException) Thread.currentThread().interrupt();
            }
            var finalValue = value;
            var finalFailure = failure;
            ui.execute(() -> {
                if (finalFailure != null) result.completeExceptionally(finalFailure);
                else result.complete(finalValue);
            });
        });
        return result;
    }

    /** Fails loudly when UI state is touched from another thread, instead of corrupting it quietly. */
    public void requireUiThread() {
        if (!onUiThread.getAsBoolean()) {
            throw new IllegalStateException("This must run on the JavaFX application thread, but ran on " + Thread.currentThread().getName());
        }
    }

    public boolean isUiThread() {
        return onUiThread.getAsBoolean();
    }

    /** Stops the workers. Work in flight is interrupted (a child process it started is killed) and its result dropped. */
    @Override
    public void close() {
        for (var executor : owned) executor.shutdownNow();
    }

    private static ThreadFactory threads(String prefix) {
        var counter = new AtomicInteger();
        return task -> {
            var thread = new Thread(task, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
