package protonbackup.ui.mvvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import protonbackup.ui.mvvm.Background.Lane;

@Timeout(30)
class BackgroundTest {

    /** A stand-in for the JavaFX thread: tasks wait in a queue until the test drains it. */
    static final class ManualUi implements Executor {
        private final Deque<Runnable> queue = new ArrayDeque<>();

        @Override
        public synchronized void execute(Runnable task) {
            queue.add(task);
            notifyAll();
        }

        synchronized void awaitQueued(int count) throws InterruptedException {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (queue.size() < count) {
                var left = deadline - System.nanoTime();
                if (left <= 0) throw new AssertionError("expected " + count + " queued UI task(s), have " + queue.size());
                TimeUnit.NANOSECONDS.timedWait(this, left);
            }
        }

        void drain() {
            while (true) {
                Runnable task;
                synchronized (this) {
                    task = queue.poll();
                }
                if (task == null) return;
                task.run();
            }
        }

        synchronized int pending() {
            return queue.size();
        }
    }

    private final List<ExecutorService> executors = new ArrayList<>();

    private ExecutorService single() {
        var executor = Executors.newSingleThreadExecutor();
        executors.add(executor);
        return executor;
    }

    private ExecutorService pool() {
        var executor = Executors.newCachedThreadPool();
        executors.add(executor);
        return executor;
    }

    @AfterEach
    void shutDown() {
        executors.forEach(ExecutorService::shutdownNow);
    }

    private Background background(ManualUi ui) {
        return Background.withExecutors(single(), single(), pool(), ui, () -> Thread.currentThread() == uiThreadHolder.get());
    }

    private final java.util.concurrent.atomic.AtomicReference<Thread> uiThreadHolder = new java.util.concurrent.atomic.AtomicReference<>(Thread.currentThread());

    @Test
    void workRunsOffTheCallingThreadAndTheResultComesBackThroughTheUiExecutor() throws Exception {
        var ui = new ManualUi();
        var background = background(ui);

        var future = background.run(Lane.FAST, () -> Thread.currentThread());
        var completedOn = future.thenApply(worker -> Thread.currentThread());
        ui.awaitQueued(1);

        assertFalse(future.isDone(), "the result must wait for the UI thread");
        ui.drain();

        assertTrue(future.isDone());
        assertNotEquals(Thread.currentThread(), future.get(), "the work must have run on a worker thread");
        assertEquals(Thread.currentThread(), completedOn.get(), "what is chained on the future runs on the UI thread");
    }

    @Test
    void aSlowLaneDoesNotHoldUpAFastOne() throws Exception {
        var ui = new ManualUi();
        var background = background(ui);
        var release = new CountDownLatch(1);

        var slow = background.run(Lane.PROBE, () -> {
            release.await();
            return "slow";
        });
        var fast = background.run(Lane.FAST, () -> "fast");
        ui.awaitQueued(1);
        ui.drain();

        assertEquals("fast", fast.get());
        assertFalse(slow.isDone());
        release.countDown();
        ui.awaitQueued(1);
        ui.drain();
        assertEquals("slow", slow.get());
    }

    @Test
    void aLaneRunsOneJobAtATimeInTheOrderGiven() throws Exception {
        var ui = new ManualUi();
        var background = background(ui);
        var running = new AtomicInteger();
        var highest = new AtomicInteger();
        var order = Collections.synchronizedList(new ArrayList<Integer>());

        var futures = new ArrayList<CompletableFuture<Integer>>();
        for (var i = 0; i < 5; i++) {
            var number = i;
            futures.add(background.run(Lane.FAST, () -> {
                highest.accumulateAndGet(running.incrementAndGet(), Math::max);
                Thread.sleep(20);
                order.add(number);
                running.decrementAndGet();
                return number;
            }));
        }
        ui.awaitQueued(5);
        ui.drain();

        assertEquals(List.of(0, 1, 2, 3, 4), order);
        assertEquals(1, highest.get());
        assertEquals(4, futures.get(4).get());
    }

    @Test
    void userActionsRunAtTheSameTime() throws Exception {
        var ui = new ManualUi();
        var background = background(ui);
        var barrier = new CyclicBarrier(2);

        // Both jobs wait for each other: this only finishes if the lane runs them in parallel.
        var first = background.run(Lane.ACTIONS, () -> barrier.await(10, TimeUnit.SECONDS));
        var second = background.run(Lane.ACTIONS, () -> barrier.await(10, TimeUnit.SECONDS));
        ui.awaitQueued(2);
        ui.drain();

        assertTrue(first.isDone() && second.isDone());
        assertFalse(first.isCompletedExceptionally());
    }

    @Test
    void aFailureCompletesTheFutureExceptionallyWithTheOriginalException() throws Exception {
        var ui = new ManualUi();
        var background = background(ui);

        var future = background.run(Lane.FAST, () -> {
            throw new IllegalStateException("boom");
        });
        ui.awaitQueued(1);
        ui.drain();

        var error = assertThrows(ExecutionException.class, future::get);
        assertTrue(error.getCause() instanceof IllegalStateException);
        assertEquals("boom", error.getCause().getMessage());
    }

    @Test
    void progressMessagesArriveOnTheUiExecutorInOrderBeforeTheResult() throws Exception {
        var ui = new ManualUi();
        var background = background(ui);
        var seen = new ArrayList<String>();

        var future = background.run(Lane.ACTIONS, status -> {
            status.accept("one");
            status.accept("two");
            return "done";
        }, seen::add);
        ui.awaitQueued(3);

        assertEquals(List.of(), seen, "nothing is delivered until the UI thread takes it");
        ui.drain();

        assertEquals(List.of("one", "two"), seen);
        assertEquals("done", future.get());
    }

    @Test
    void directModeRunsEverythingInlineOnTheCallingThread() throws Exception {
        var background = Background.direct();

        var future = background.run(Lane.FAST, () -> Thread.currentThread());

        assertTrue(future.isDone(), "completed before run() returned");
        assertEquals(Thread.currentThread(), future.get());
        assertTrue(background.isUiThread());
        background.requireUiThread();
    }

    @Test
    void touchingUiStateFromAWorkerThreadFailsLoudly() throws Exception {
        var ui = new ManualUi();
        var background = background(ui);
        var failure = background.run(Lane.FAST, () -> {
            try {
                background.requireUiThread();
                return "no error";
            } catch (IllegalStateException e) {
                return e.getMessage();
            }
        });
        ui.awaitQueued(1);
        ui.drain();

        assertTrue(failure.get().startsWith("This must run on the JavaFX application thread"), failure.get());
        background.requireUiThread(); // the test thread is the "UI thread" here
    }

    @Test
    void closeInterruptsWorkInFlightSoAChildProcessCanBeKilled() throws Exception {
        var background = Background.production(); // no JavaFX toolkit here: late results are dropped quietly
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);

        background.run(Lane.ACTIONS, () -> {
            started.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
            return null;
        });
        assertTrue(started.await(10, TimeUnit.SECONDS));

        background.close();

        assertTrue(interrupted.await(10, TimeUnit.SECONDS), "the worker must be interrupted on close");
    }

    @Test
    void workerThreadsAreDaemonThreadsSoTheyNeverKeepTheApplicationAlive() throws Exception {
        var background = Background.production();
        try {
            var daemon = new CompletableFuture<Boolean>();
            background.run(Lane.FAST, () -> {
                daemon.complete(Thread.currentThread().isDaemon());
                return null;
            });

            assertTrue(daemon.get(10, TimeUnit.SECONDS));
        } finally {
            background.close();
        }
    }
}
