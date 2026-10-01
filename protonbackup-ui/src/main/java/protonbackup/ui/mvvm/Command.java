package protonbackup.ui.mvvm;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableValue;

/**
 * What a button does, with the two states a view needs: {@link #running()} (show progress, ignore
 * further clicks) and {@link #canExecute()} (enable or disable the button).
 *
 * <p>An action may start background work: it returns a future that completes on the UI thread (what
 * {@link Background#run} returns), and the command counts as running until then. A failure is handed
 * to {@code onError}; it never escapes into the JavaFX event loop.
 *
 * <p>A command ignores {@link #execute} while it is running or disabled, so a double click cannot
 * start the same thing twice.
 */
public final class Command<T> {

    private static final System.Logger LOG = System.getLogger("protonbackup.ui");

    private final Function<T, CompletableFuture<?>> action;
    private final Consumer<Throwable> onError;
    private final ReadOnlyBooleanWrapper running = new ReadOnlyBooleanWrapper(false);
    private final BooleanProperty gate = new SimpleBooleanProperty(true);
    private final ReadOnlyBooleanWrapper canExecute = new ReadOnlyBooleanWrapper(true);

    /** @param action starts the work and returns a future completed on the UI thread, or {@code null} when it is already done */
    public Command(Function<T, CompletableFuture<?>> action, Consumer<Throwable> onError) {
        this.action = action;
        this.onError = onError;
        canExecute.bind(gate.and(running.not()));
    }

    /** A command without a parameter whose action starts background work. */
    public static Command<Void> async(Supplier<CompletableFuture<?>> action, Consumer<Throwable> onError) {
        return new Command<>(ignored -> action.get(), onError);
    }

    /** A command without a parameter that does its work on the spot, on the UI thread. */
    public static Command<Void> immediate(Runnable action, Consumer<Throwable> onError) {
        return new Command<>(ignored -> {
            action.run();
            return null;
        }, onError);
    }

    /** A command with a parameter that does its work on the spot, on the UI thread. */
    public static <T> Command<T> immediateWith(Consumer<T> action, Consumer<Throwable> onError) {
        return new Command<>(parameter -> {
            action.accept(parameter);
            return null;
        }, onError);
    }

    /** Disables the command while the condition is false (it stays disabled while running as well). */
    public Command<T> enabledWhen(ObservableValue<Boolean> condition) {
        gate.bind(condition);
        return this;
    }

    public void execute() {
        execute(null);
    }

    public void execute(T parameter) {
        if (!canExecute.get()) return;
        running.set(true);
        CompletableFuture<?> started;
        try {
            started = action.apply(parameter);
        } catch (RuntimeException e) {
            running.set(false);
            onError.accept(e);
            return;
        }
        if (started == null) {
            running.set(false);
            return;
        }
        started.whenComplete((result, failure) -> {
            running.set(false);
            if (failure == null) return;
            try {
                onError.accept(failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure);
            } catch (RuntimeException inTheErrorHandler) {
                LOG.log(System.Logger.Level.ERROR, "The error handler of a command failed", inTheErrorHandler);
            }
        });
    }

    public ReadOnlyBooleanProperty running() {
        return running.getReadOnlyProperty();
    }

    public ReadOnlyBooleanProperty canExecute() {
        return canExecute.getReadOnlyProperty();
    }

    /** For {@code button.disableProperty().bind(command.disabled())}. */
    public BooleanBinding disabled() {
        return Bindings.not(canExecute);
    }
}
