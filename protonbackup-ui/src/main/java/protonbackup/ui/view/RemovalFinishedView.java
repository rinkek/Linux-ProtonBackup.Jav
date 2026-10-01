package protonbackup.ui.view;

import java.util.List;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import protonbackup.core.Cleanup;

/**
 * Shown, instead of everything else, once the app's data was removed. The app must not carry on as if
 * nothing happened (it would recreate what was just deleted), so the only thing left to do is to quit.
 */
public final class RemovalFinishedView extends StackPane {

    public RemovalFinishedView(List<Cleanup.Step> steps) {
        var ok = Cleanup.succeeded(steps);
        var title = Widgets.pageTitle(ok ? "Everything was removed" : "Removal was only partly successful");

        var lines = new VBox(4);
        for (var step : steps) {
            var text = (step.succeeded() ? "✓  " : "✗  ") + step.description() + (step.detail() == null || step.succeeded() ? "" : " (" + step.detail() + ")");
            var line = new Label(text);
            line.setWrapText(true);
            if (!step.succeeded()) line.getStyleClass().add("row-title");
            lines.getChildren().add(line);
        }

        var next = Widgets.muted("Remove the package with: sudo apt remove protonbackup");
        var quit = new Button("Quit");
        quit.setOnAction(event -> Platform.exit());
        quit.setMaxWidth(Region.USE_PREF_SIZE);

        var card = new VBox(16, title, Widgets.muted("Whatever is on Proton Drive was left as it is."), lines, next, quit);
        card.getStyleClass().add("card");
        card.setMaxWidth(560);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        setAlignment(Pos.CENTER);
        getChildren().add(card);
        setPadding(new javafx.geometry.Insets(24));
    }
}
