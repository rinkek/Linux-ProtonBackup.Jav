package protonbackup.ui.view;

import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import protonbackup.core.FileEntry;
import protonbackup.ui.viewmodel.LogPageViewModel;
import protonbackup.ui.viewmodel.LogPageViewModel.RunRow;

/** The recent runs and the files that currently have an error, with the reason for each. */
public final class LogPageView extends VBox {

    public LogPageView(LogPageViewModel page) {
        super(8);

        var runs = new ListView<>(page.runs());
        runs.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(RunRow run, boolean empty) {
                super.updateItem(run, empty);
                if (empty || run == null) {
                    setGraphic(null);
                    return;
                }
                var started = new Label(run.started());
                started.setMinWidth(150);
                var outcome = new Label(run.outcome());
                outcome.getStyleClass().add("muted");
                var spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                var line = new HBox(16, started, outcome, spacer, new Label(run.counts()));
                line.setAlignment(Pos.CENTER_LEFT);
                setGraphic(line);
            }
        });
        VBox.setVgrow(runs, Priority.ALWAYS);

        var failedTitle = Widgets.sectionTitle("Files with an error");
        failedTitle.setPadding(new Insets(10, 0, 0, 0));
        var failures = new ListView<>(page.failures());
        failures.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(FileEntry file, boolean empty) {
                super.updateItem(file, empty);
                if (empty || file == null) {
                    setGraphic(null);
                    return;
                }
                var path = new Label(file.relativePath());
                path.getStyleClass().add("row-title");
                var reason = new Label(file.lastError() == null ? "" : file.lastError());
                reason.getStyleClass().add("muted");
                reason.setWrapText(true);
                reason.maxWidthProperty().bind(failures.widthProperty().subtract(40));
                setGraphic(new VBox(2, path, reason));
            }
        });
        VBox.setVgrow(failures, Priority.ALWAYS);
        for (var node : new javafx.scene.Node[] {failedTitle, failures}) {
            node.visibleProperty().bind(Bindings.isNotEmpty(page.failures()));
            node.managedProperty().bind(node.visibleProperty());
        }

        var emptyNote = Widgets.muted("No runs yet.");
        emptyNote.visibleProperty().bind(Bindings.isEmpty(page.runs()));
        emptyNote.managedProperty().bind(emptyNote.visibleProperty());

        getChildren().addAll(Widgets.pageTitle("Log"), Widgets.sectionTitle("Recent runs"), emptyNote, runs, failedTitle, failures);
        setSpacing(8);
    }
}
