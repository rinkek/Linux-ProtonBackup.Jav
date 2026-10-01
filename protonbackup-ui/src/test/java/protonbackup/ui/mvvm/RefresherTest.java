package protonbackup.ui.mvvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import protonbackup.ui.mvvm.Background.Lane;

/**
 * Deterministic: both the "worker" and the "UI thread" are queues the test steps through by hand, so
 * every interleaving can be spelled out.
 */
class RefresherTest {

    /** Runs tasks only when told to. */
    static final class Manual implements Executor {
        final Deque<Runnable> queue = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            queue.add(task);
        }

        boolean runNext() {
            var task = queue.poll();
            if (task == null) return false;
            task.run();
            return true;
        }

        void runAll() {
            while (runNext()) {
                // keep going
            }
        }
    }

    private final Manual worker = new Manual();
    private final Manual ui = new Manual();
    private final Background background = Background.withExecutors(worker, worker, worker, ui, () -> true);
    private final List<Integer> shown = new ArrayList<>();
    private final List<Throwable> errors = new ArrayList<>();
    private final AtomicInteger source = new AtomicInteger();
    private final AtomicInteger loads = new AtomicInteger();

    private Refresher<Integer> refresher() {
        return new Refresher<>(background, Lane.FAST, () -> {
            loads.incrementAndGet();
            return source.get();
        }, shown::add, errors::add);
    }

    /** One complete cycle: the worker does its load, then the UI shows it. */
    private void finishLoad() {
        assertTrue(worker.runNext(), "a load was expected to be waiting");
        ui.runAll();
    }

    @Test
    void aRefreshLoadsOnAWorkerAndShowsTheSnapshotOnTheUiThread() {
        var refresher = refresher();
        source.set(7);

        refresher.refresh();
        assertEquals(0, loads.get(), "nothing runs on the calling thread");
        assertTrue(refresher.isLoading());
        finishLoad();

        assertEquals(List.of(7), shown);
        assertFalse(refresher.isLoading());
    }

    @Test
    void requestsDuringALoadAreCoalescedIntoOneMoreLoad() {
        var refresher = refresher();

        refresher.refresh();
        for (var i = 0; i < 100; i++) refresher.refresh(); // 100 ticks while the first load is slow
        assertEquals(1, worker.queue.size(), "no pile-up: only one load is queued");
        finishLoad();

        assertEquals(1, worker.queue.size(), "exactly one trailing load");
        finishLoad();

        assertEquals(0, worker.queue.size());
        assertEquals(2, loads.get());
        assertEquals(2, shown.size());
    }

    /** The reason for the trailing load: a refresh after a user action must not show state from before it. */
    @Test
    void theTrailingLoadStartsAfterTheRequestSoItSeesTheNewState() {
        var refresher = refresher();
        source.set(1);
        refresher.refresh();
        worker.runNext(); // the load reads "1" ...

        source.set(2); // ... then the user's action changes the state ...
        refresher.refresh(); // ... and asks for a refresh while the old load is still on its way
        ui.runAll(); // old snapshot is shown
        finishLoad(); // the trailing load reads "2"

        assertEquals(List.of(1, 2), shown, "ends on the newest state, in order");
    }

    @Test
    void snapshotsAreShownInTheOrderTheyWereLoaded() {
        var refresher = refresher();
        for (var value = 1; value <= 3; value++) {
            source.set(value);
            refresher.refresh();
            finishLoad();
        }

        assertEquals(List.of(1, 2, 3), shown);
    }

    @Test
    void aFailedLoadIsReportedAndTheNextRefreshStillWorks() {
        var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        var refresher = new Refresher<Integer>(background, Lane.FAST, () -> {
            if (fail.get()) throw new IllegalStateException("disk gone");
            return 5;
        }, shown::add, errors::add);

        refresher.refresh();
        finishLoad();
        assertEquals(1, errors.size());
        assertEquals("disk gone", errors.get(0).getMessage());
        assertTrue(shown.isEmpty());
        assertFalse(refresher.isLoading());

        fail.set(false);
        refresher.refresh();
        finishLoad();
        assertEquals(List.of(5), shown);
    }

    @Test
    void aBugInTheShowStepIsReportedNotLostAndTheTrailingLoadStillRuns() {
        var refresher = new Refresher<Integer>(background, Lane.FAST, source::incrementAndGet, value -> {
            throw new IllegalStateException("show broke");
        }, errors::add);

        refresher.refresh();
        refresher.refresh(); // arrives during the first load
        finishLoad();

        assertEquals(1, errors.size());
        assertEquals("show broke", errors.get(0).getMessage());
        assertEquals(1, worker.queue.size(), "the trailing load was still scheduled");
        assertFalse(refresher.isLoading() && worker.queue.isEmpty());
    }

    @Test
    void refreshingFromAnotherThreadThanTheUiThreadIsRejected() {
        var elsewhere = Background.withExecutors(worker, worker, worker, ui, () -> false);
        var refresher = new Refresher<Integer>(elsewhere, Lane.FAST, () -> 1, shown::add, errors::add);

        assertThrows(IllegalStateException.class, refresher::refresh);
    }
}
