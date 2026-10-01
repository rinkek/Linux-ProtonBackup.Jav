package protonbackup.core;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Cooperative cancellation, the counterpart of .NET's {@code CancellationToken}. A SIGTERM handler
 * (or the UI's Cancel button) calls {@link #cancel()}; the engine looks at it at fixed checkpoints,
 * so work that is already in flight always finishes and a run stops cleanly.
 */
public final class CancelToken {

    private final CountDownLatch cancelled = new CountDownLatch(1);

    public void cancel() {
        cancelled.countDown();
    }

    public boolean isCancelled() {
        return cancelled.getCount() == 0;
    }

    /** Throws {@link SyncCancelledException} when cancellation was requested. */
    public void throwIfCancelled() {
        if (isCancelled()) throw new SyncCancelledException();
    }

    /**
     * Waits for the given time, ending early when cancelled.
     *
     * @return {@code true} when cancellation was requested during the wait
     */
    public boolean sleep(Duration duration) throws InterruptedException {
        return cancelled.await(duration.toMillis(), TimeUnit.MILLISECONDS);
    }
}
