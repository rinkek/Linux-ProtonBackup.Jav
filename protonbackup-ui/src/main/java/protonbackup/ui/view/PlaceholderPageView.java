package protonbackup.ui.view;

import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import protonbackup.ui.mvvm.PageViewModel;

/** Shown for a page whose view is not built yet (phase 11 replaces these one by one). */
public final class PlaceholderPageView extends VBox {

    public PlaceholderPageView(PageViewModel page) {
        super(12);
        var title = new Label(page.title());
        title.getStyleClass().add("page-title");
        var note = new Label("This page is not built yet.");
        note.getStyleClass().add("muted");
        getChildren().addAll(title, note);
    }
}
