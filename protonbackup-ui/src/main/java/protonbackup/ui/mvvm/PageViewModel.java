package protonbackup.ui.mvvm;

import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import protonbackup.ui.mvvm.Background.Lane;
import protonbackup.ui.service.BackupService;

/**
 * One page of the main window. All of it runs on the JavaFX thread; see {@link Background} for the rule.
 *
 * <p>The conventions every page follows, so they only have to be learnt once:
 * <ul>
 *   <li>state is JavaFX properties, exposed as {@code name()} accessors (read-only unless the view edits it);
 *   <li>reading state from disk or processes is a {@link Refresher}: a loader producing an immutable snapshot
 *       in a lane, and a {@code show} method applying it;
 *   <li>what a button does is a {@link Command}, wired to {@link #fail} for errors;
 *   <li>a control the user toggles that has a side effect (like "Sync automatically") is <em>not</em> bound to
 *       a property both ways: the view calls a view-model method from the control's own action event, and the
 *       next refresh shows the true state. A change made by a refresh then can never trigger the side effect.
 * </ul>
 */
public abstract class PageViewModel {

    private static final System.Logger LOG = System.getLogger("protonbackup.ui");

    protected final BackupService service;
    protected final Background background;
    private final ReadOnlyStringWrapper message = new ReadOnlyStringWrapper();

    protected PageViewModel(BackupService service, Background background) {
        this.service = service;
        this.background = background;
    }

    public abstract String title();

    /** Called about every two seconds while the page is visible, and when it is shown. Starts work; never blocks. */
    public void refresh() {}

    /** A short message for the user about the last action; {@code null} when there is none. */
    public ReadOnlyStringProperty message() {
        return message.getReadOnlyProperty();
    }

    public void say(String text) {
        message.set(text);
    }

    public void clearMessage() {
        message.set(null);
    }

    /** What every command does with an unexpected failure: log it and tell the user in one line. */
    protected void fail(Throwable error) {
        if (service.isClosed()) return; // work that was still in flight when the app's data was removed
        LOG.log(System.Logger.Level.WARNING, "Unexpected failure on page " + title(), error);
        var reason = error.getMessage() != null && !error.getMessage().isBlank() ? error.getMessage() : error.toString();
        message.set("Something went wrong: " + reason);
    }

    protected <S> Refresher<S> refresher(Lane lane, Callable<S> loader, Consumer<S> show) {
        return new Refresher<>(background, lane, loader, show, this::fail);
    }
}
