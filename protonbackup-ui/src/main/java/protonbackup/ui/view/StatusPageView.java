package protonbackup.ui.view;

import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import protonbackup.ui.viewmodel.StatusPageViewModel;

/** The Status page: counts, run and session information, Sync now / Cancel, automatic syncing. */
public final class StatusPageView extends ScrollPane {

    public StatusPageView(StatusPageViewModel status) {
        var title = new Label("Status");
        title.getStyleClass().add("page-title");

        var content = new VBox(20,
                title,
                updateBanner(status),
                messageBar(status),
                counts(status),
                details(status),
                buttons(status),
                autoSync(status));
        content.setPadding(new Insets(0, 4, 0, 0));
        content.setMaxWidth(Double.MAX_VALUE);

        setContent(content);
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
    }

    private static InfoBar updateBanner(StatusPageViewModel status) {
        var banner = new InfoBar(InfoBar.Severity.INFO).closable(() -> status.dismissUpdate().execute());
        banner.titleLabel().setText("A newer Proton Drive CLI is available");
        banner.messageLabel().textProperty().bind(Bindings.concat("Version ", status.availableVersion(), " is available."));
        var update = banner.addAction("Update now");
        update.setOnAction(event -> status.updateCli().execute());
        update.disableProperty().bind(status.updateCli().disabled());
        banner.openProperty().bind(status.updateAvailable());
        return banner;
    }

    private static InfoBar messageBar(StatusPageViewModel status) {
        var bar = new InfoBar(InfoBar.Severity.INFO).closable(status::clearMessage);
        bar.messageLabel().textProperty().bind(status.message());
        bar.openProperty().bind(status.message().isNotNull().and(status.message().isNotEmpty()));
        return bar;
    }

    /** Three equal columns across the card, as in the reference. */
    private static GridPane counts(StatusPageViewModel status) {
        var grid = new GridPane();
        grid.getStyleClass().add("card");
        for (var i = 0; i < 3; i++) {
            var column = new ColumnConstraints();
            column.setPercentWidth(100.0 / 3);
            grid.getColumnConstraints().add(column);
        }
        grid.add(stat("Synced", status.syncedCount()), 0, 0);
        grid.add(stat("Queued", status.pendingCount()), 1, 0);
        grid.add(stat("Failed", status.errorCount()), 2, 0);
        return grid;
    }

    private static VBox stat(String caption, ReadOnlyIntegerProperty value) {
        var label = new Label(caption);
        label.getStyleClass().add("muted");
        var number = new Label();
        number.getStyleClass().add("stat-value");
        number.textProperty().bind(value.asString());
        return new VBox(4, label, number);
    }

    private static GridPane details(StatusPageViewModel status) {
        var grid = new GridPane();
        grid.setHgap(32);
        grid.setVgap(10);
        var captions = new ColumnConstraints();
        captions.setMinWidth(150);
        captions.setPrefWidth(170);
        var values = new ColumnConstraints();
        values.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(captions, values);

        row(grid, 0, "Last run", status.lastRun());
        row(grid, 1, "Next run", status.nextRun());
        row(grid, 2, "Session", status.sessionText());
        row(grid, 3, "CLI version", status.cliVersion());
        return grid;
    }

    private static void row(GridPane grid, int index, String caption, ReadOnlyStringProperty value) {
        var name = new Label(caption);
        name.getStyleClass().add("muted");
        var text = new Label();
        text.setWrapText(true);
        text.textProperty().bind(value);
        grid.addRow(index, name, text);
    }

    private static HBox buttons(StatusPageViewModel status) {
        var syncNow = new Button("Sync now");
        syncNow.setOnAction(event -> status.syncNow().execute());
        syncNow.disableProperty().bind(status.syncNow().disabled());

        var cancel = new Button("Cancel");
        cancel.setOnAction(event -> status.cancel().execute());
        cancel.disableProperty().bind(status.cancel().disabled());

        var progress = new ProgressIndicator();
        progress.setPrefSize(24, 24);
        progress.setMaxSize(24, 24);
        progress.visibleProperty().bind(status.running());
        progress.managedProperty().bind(status.running());

        var row = new HBox(12, syncNow, cancel, progress);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return row;
    }

    /**
     * Not bound both ways: the box follows the state the view-model shows, and only a click of the user
     * asks for a change. A change made by a refresh therefore can never enable or disable the timer.
     */
    private static CheckBox autoSync(StatusPageViewModel status) {
        var box = new CheckBox("Sync automatically");
        box.setSelected(status.autoSync().get());
        status.autoSync().addListener((observable, before, now) -> box.setSelected(now));
        box.setOnAction(event -> status.setAutoSync().execute(box.isSelected()));
        box.disableProperty().bind(status.setAutoSync().running());
        return box;
    }
}
