package protonbackup.ui.view;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import javafx.scene.Node;
import protonbackup.ui.mvvm.PageViewModel;
import protonbackup.ui.viewmodel.LogPageViewModel;
import protonbackup.ui.viewmodel.SettingsPageViewModel;
import protonbackup.ui.viewmodel.SourceFoldersPageViewModel;
import protonbackup.ui.viewmodel.StatusPageViewModel;
import protonbackup.ui.viewmodel.StructurePageViewModel;
import protonbackup.ui.viewmodel.WelcomePageViewModel;

/**
 * Which view shows which page view-model. An explicit table: the original found the view by
 * rewriting the type name with reflection, which is exactly the kind of thing that breaks quietly.
 * Views are created once per page and kept, so switching pages does not reset them.
 */
public final class PageViews {

    private final Map<Class<? extends PageViewModel>, Function<PageViewModel, Node>> factories = new LinkedHashMap<>();
    private final Map<PageViewModel, Node> created = new HashMap<>();

    @SuppressWarnings("unchecked")
    public <V extends PageViewModel> PageViews register(Class<V> type, Function<V, Node> factory) {
        factories.put(type, page -> factory.apply((V) page));
        return this;
    }

    /** The view for a page; a placeholder when none is registered yet. */
    public Node viewFor(PageViewModel page) {
        return created.computeIfAbsent(page, key -> {
            var factory = factories.get(key.getClass());
            return factory != null ? factory.apply(key) : new PlaceholderPageView(key);
        });
    }

    /** Every page of the application. */
    public static PageViews standard() {
        return new PageViews()
                .register(StatusPageViewModel.class, StatusPageView::new)
                .register(SourceFoldersPageViewModel.class, SourceFoldersPageView::new)
                .register(StructurePageViewModel.class, StructurePageView::new)
                .register(LogPageViewModel.class, LogPageView::new)
                .register(SettingsPageViewModel.class, SettingsPageView::new)
                .register(WelcomePageViewModel.class, WelcomePageView::new);
    }
}
