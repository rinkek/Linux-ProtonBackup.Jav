package protonbackup.ui.mvvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.beans.property.SimpleBooleanProperty;
import org.junit.jupiter.api.Test;

class CommandTest {

    private final List<Throwable> errors = new ArrayList<>();

    @Test
    void anAsyncCommandIsRunningUntilItsFutureCompletes() {
        var future = new CompletableFuture<Void>();
        var command = Command.async(() -> future, errors::add);
        assertFalse(command.running().get());
        assertTrue(command.canExecute().get());

        command.execute();

        assertTrue(command.running().get());
        assertFalse(command.canExecute().get());
        assertTrue(command.disabled().get());

        future.complete(null);

        assertFalse(command.running().get());
        assertTrue(command.canExecute().get());
        assertTrue(errors.isEmpty());
    }

    @Test
    void anExecuteWhileRunningIsIgnoredSoADoubleClickStartsNothingTwice() {
        var started = new AtomicInteger();
        var command = Command.async(() -> {
            started.incrementAndGet();
            return new CompletableFuture<Void>();
        }, errors::add);

        command.execute();
        command.execute();
        command.execute();

        assertEquals(1, started.get());
    }

    @Test
    void aDisabledCommandDoesNothing() {
        var allowed = new SimpleBooleanProperty(false);
        var started = new AtomicInteger();
        var command = Command.immediate(started::incrementAndGet, errors::add).enabledWhen(allowed);

        command.execute();
        assertEquals(0, started.get());
        assertTrue(command.disabled().get());

        allowed.set(true);
        command.execute();
        assertEquals(1, started.get());
        assertFalse(command.disabled().get());
    }

    @Test
    void theGateAndRunningBothMatterForCanExecute() {
        var allowed = new SimpleBooleanProperty(true);
        var future = new CompletableFuture<Void>();
        var command = Command.async(() -> future, errors::add).enabledWhen(allowed);

        command.execute();
        allowed.set(false);
        future.complete(null);

        assertFalse(command.canExecute().get(), "still gated off after the run ended");
        allowed.set(true);
        assertTrue(command.canExecute().get());
    }

    @Test
    void anImmediateCommandRunsOnTheSpotAndNeverStaysRunning() {
        var count = new AtomicInteger();
        var command = Command.immediate(count::incrementAndGet, errors::add);

        command.execute();
        command.execute();

        assertEquals(2, count.get());
        assertFalse(command.running().get());
    }

    @Test
    void theParameterReachesTheAction() {
        var seen = new ArrayList<String>();
        var command = Command.<String>immediateWith(seen::add, errors::add);

        command.execute("a");
        command.execute("b");

        assertEquals(List.of("a", "b"), seen);
    }

    @Test
    void aFailingFutureGoesToTheErrorHandlerWithTheOriginalCause() {
        var future = new CompletableFuture<Void>();
        var command = Command.async(() -> future, errors::add);
        command.execute();

        future.completeExceptionally(new CompletionException(new IllegalStateException("boom")));

        assertEquals(1, errors.size());
        assertEquals("boom", errors.get(0).getMessage());
        assertFalse(command.running().get());
    }

    @Test
    void anExceptionThrownWhileStartingGoesToTheErrorHandlerToo() {
        var command = Command.async(() -> {
            throw new IllegalArgumentException("bad input");
        }, errors::add);

        command.execute();

        assertEquals(1, errors.size());
        assertEquals("bad input", errors.get(0).getMessage());
        assertFalse(command.running().get(), "a failed start must not leave the command stuck");
        assertTrue(command.canExecute().get());
    }

    @Test
    void anActionThatReturnsNullIsAlreadyDone() {
        var command = Command.async(() -> null, errors::add);

        command.execute();

        assertFalse(command.running().get());
        assertTrue(errors.isEmpty());
    }

    @Test
    void aCommandCanRunAgainAfterItFailed() {
        var attempts = new AtomicInteger();
        var command = Command.async(() -> {
            var future = new CompletableFuture<Void>();
            if (attempts.incrementAndGet() == 1) future.completeExceptionally(new IllegalStateException("first fails"));
            else future.complete(null);
            return future;
        }, errors::add);

        command.execute();
        command.execute();

        assertEquals(2, attempts.get());
        assertEquals(1, errors.size());
    }
}
