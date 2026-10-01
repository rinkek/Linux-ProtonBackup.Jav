package protonbackup.ui.mvvm;

import java.util.concurrent.Callable;
import java.util.function.Consumer;
import protonbackup.ui.mvvm.Background.Lane;

/**
 * Reloads one piece of displayed state: {@code loader} runs in a lane and produces an immutable
 * snapshot; {@code show} applies it on the UI thread.
 *
 * <p>Three rules make polling safe, and they are why this class exists:
 * <ul>
 *   <li><b>No pile-up.</b> At most one load is in flight. A request that arrives meanwhile is
 *       remembered and answered by <em>one</em> more load afterwards, however many requests came.
 *   <li><b>That extra load starts after the request.</b> So a refresh asked for right after a user action
 *       always reads state from after the action, never an in-flight read from before it.
 *   <li><b>Order.</b> Snapshots are shown in the order they were loaded; an old one cannot overwrite a newer one.
 * </ul>
 *
 * <p>All methods are for the UI thread.
 */
public final class Refresher<S> {

    private static final System.Logger LOG = System.getLogger("protonbackup.ui");

    private final Background background;
    private final Lane lane;
    private final Callable<S> loader;
    private final Consumer<S> show;
    private final Consumer<Throwable> onError;

    private boolean loading;
    private boolean again;

    public Refresher(Background background, Lane lane, Callable<S> loader, Consumer<S> show, Consumer<Throwable> onError) {
        this.background = background;
        this.lane = lane;
        this.loader = loader;
        this.show = show;
        this.onError = onError;
    }

    /** Starts a load, or schedules one right after the load that is running. Never blocks. */
    public void refresh() {
        background.requireUiThread();
        if (loading) {
            again = true;
            return;
        }
        loading = true;
        background.run(lane, loader).whenComplete((snapshot, failure) -> {
            loading = false;
            try {
                if (failure != null) {
                    onError.accept(failure);
                } else {
                    try {
                        show.accept(snapshot);
                    } catch (RuntimeException bug) {
                        // An exception thrown here would vanish inside the future; report it instead.
                        onError.accept(bug);
                    }
                }
            } catch (RuntimeException inTheErrorHandler) {
                LOG.log(System.Logger.Level.ERROR, "The error handler of a refresh failed", inTheErrorHandler);
            } finally {
                if (again) {
                    again = false;
                    refresh();
                }
            }
        });
    }

    public boolean isLoading() {
        return loading;
    }
}
