package protonbackup.ui.view;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import protonbackup.ui.viewmodel.FolderPicker;

/**
 * The folder picker: a small dialog of the app's own, modal to the main window. It lists folders only and
 * never shows hidden ones (names starting with a dot). The system's folder dialog could not be used: it
 * shows hidden folders as soon as anybody has switched that on once, and JavaFX has no way to say otherwise.
 */
public final class DialogFolderPicker implements FolderPicker {

    private final Window owner;

    public DialogFolderPicker(Window owner) {
        this.owner = owner;
    }

    /** The folders directly inside {@code directory}, sorted by name, without hidden ones; empty when it cannot be read. */
    static List<Path> visibleSubfolders(Path directory) {
        try (var entries = Files.list(directory)) {
            return entries
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()))
                    .toList();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /** A folder in the tree; its children are read when it is opened, off the JavaFX thread. */
    private static final class FolderItem extends TreeItem<Path> {
        private boolean loaded;

        FolderItem(Path path) {
            super(path);
            expandedProperty().addListener((observable, was, expanded) -> {
                if (expanded) load();
            });
        }

        void load() {
            if (loaded) return;
            loaded = true;
            var path = getValue();
            var thread = new Thread(() -> {
                var folders = visibleSubfolders(path);
                Platform.runLater(() -> {
                    getChildren().setAll(folders.stream().map(FolderItem::new).toList());
                    // isLeaf() changes with the children; nudge the tree so the arrow disappears for an empty folder.
                    setExpanded(false);
                    setExpanded(!folders.isEmpty());
                });
            }, "folder-list");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public boolean isLeaf() {
            return loaded && getChildren().isEmpty();
        }
    }

    @Override
    public Optional<Path> pick(String title) {
        var home = Path.of(System.getProperty("user.home", "/"));
        var roots = new TreeItem<Path>();
        var homeItem = new FolderItem(Files.isDirectory(home) ? home : Path.of("/"));
        roots.getChildren().add(homeItem);
        if (!homeItem.getValue().equals(Path.of("/"))) roots.getChildren().add(new FolderItem(Path.of("/")));

        var tree = new TreeView<>(roots);
        tree.setShowRoot(false);
        tree.setCellFactory(view -> new TreeCell<>() {
            @Override
            protected void updateItem(Path path, boolean empty) {
                super.updateItem(path, empty);
                if (empty || path == null) {
                    setText(null);
                } else if (path.equals(home)) {
                    setText("Home (" + path + ")");
                } else if (path.getParent() == null) {
                    setText("/ (the whole computer)");
                } else {
                    setText(path.getFileName().toString());
                }
            }
        });
        VBox.setVgrow(tree, Priority.ALWAYS);

        var chosen = new Label();
        chosen.getStyleClass().add("field-caption");
        var choose = new Button("Choose this folder");
        var cancel = new Button("Cancel");
        cancel.getStyleClass().add("plain");
        choose.setDisable(true);
        tree.getSelectionModel().selectedItemProperty().addListener((observable, was, item) -> {
            choose.setDisable(item == null);
            chosen.setText(item == null ? "" : item.getValue().toString());
        });

        Path[] result = new Path[1];
        var stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(title);
        choose.setDefaultButton(true);
        choose.setOnAction(event -> {
            var item = tree.getSelectionModel().getSelectedItem();
            if (item != null) result[0] = item.getValue();
            stage.close();
        });
        cancel.setCancelButton(true);
        cancel.setOnAction(event -> stage.close());

        var buttons = new HBox(10, chosen, new HBox(), choose, cancel);
        HBox.setHgrow(chosen, Priority.ALWAYS);
        chosen.setMaxWidth(Double.MAX_VALUE);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        var content = new VBox(12, tree, buttons);
        content.setPadding(new Insets(16));

        var scene = new Scene(content, 640, 520);
        scene.getStylesheets().add(MainWindow.stylesheetUrl());
        stage.setScene(scene);
        stage.setMinWidth(480);
        stage.setMinHeight(360);

        homeItem.setExpanded(true);
        tree.getSelectionModel().select(homeItem);
        stage.showAndWait();
        return Optional.ofNullable(result[0]);
    }
}
