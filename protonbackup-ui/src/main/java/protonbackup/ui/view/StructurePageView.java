package protonbackup.ui.view;

import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import protonbackup.core.FileEntry;
import protonbackup.core.SyncSource;
import protonbackup.ui.viewmodel.FolderNode;
import protonbackup.ui.viewmodel.StructurePageViewModel;

/** The "File sync" page: a source folder's tracked files as a tree that opens folder by folder, or the failed files. */
public final class StructurePageView extends VBox {

    /** A tree item that mirrors a {@link FolderNode}, and asks it to load its contents when it is opened. */
    private static final class NodeItem extends TreeItem<FolderNode> {
        NodeItem(FolderNode node) {
            super(node);
            sync(node);
            node.children().addListener((ListChangeListener<FolderNode>) change -> sync(node));
            expandedProperty().addListener((observable, before, expanded) -> {
                if (expanded) node.expand();
            });
        }

        private void sync(FolderNode node) {
            getChildren().setAll(node.children().stream().map(NodeItem::new).toList());
        }
    }

    public StructurePageView(StructurePageViewModel page) {
        super(0);

        var source = new ComboBox<SyncSource>(page.sources());
        source.setPromptText("Choose a source folder");
        source.setMaxWidth(Double.MAX_VALUE);
        source.setConverter(new StringConverter<>() {
            @Override
            public String toString(SyncSource value) {
                return value == null ? "" : value.localPath();
            }

            @Override
            public SyncSource fromString(String text) {
                return null;
            }
        });
        source.valueProperty().bindBidirectional(page.selectedSource());
        HBox.setHgrow(source, Priority.ALWAYS);

        var errorsOnly = new CheckBox("Errors only");
        errorsOnly.selectedProperty().bindBidirectional(page.onlyErrors());
        var refresh = Widgets.commandButton("Refresh", page.reload());

        var toolbar = new HBox(14, source, errorsOnly, refresh);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(16, 0, 14, 0));

        var none = Widgets.muted("No source folders yet. Add one under Source folders.");
        none.visibleProperty().bind(page.hasSources().not());
        none.managedProperty().bind(none.visibleProperty());

        var body = new StackPane(tree(page), failures(page));
        VBox.setVgrow(body, Priority.ALWAYS);

        getChildren().addAll(Widgets.pageTitle("File sync"), toolbar, none, body);
    }

    private static TreeView<FolderNode> tree(StructurePageViewModel page) {
        var root = new TreeItem<FolderNode>();
        var tree = new TreeView<>(root);
        tree.setShowRoot(false);
        // The page may have loaded before this view was built, so start from what it already holds.
        Runnable fill = () -> root.getChildren().setAll(page.nodes().stream().map(NodeItem::new).toList());
        fill.run();
        page.nodes().addListener((ListChangeListener<FolderNode>) change -> fill.run());
        tree.getStyleClass().add("file-tree");
        tree.setCellFactory(view -> new TreeCell<>() {
            @Override
            protected void updateItem(FolderNode node, boolean empty) {
                super.updateItem(node, empty);
                if (empty || node == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                var name = new Label(node.name());
                var summary = new Label(node.summary());
                summary.getStyleClass().add("muted");
                var spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                var line = new HBox(18, name, spacer, summary);
                line.setAlignment(Pos.CENTER_LEFT);
                setText(null);
                setGraphic(line);
            }
        });
        tree.visibleProperty().bind(page.onlyErrors().not());
        return tree;
    }

    private static ListView<FileEntry> failures(StructurePageViewModel page) {
        var list = new ListView<>(page.failures());
        list.setCellFactory(view -> new ListCell<>() {
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
                reason.maxWidthProperty().bind(list.widthProperty().subtract(40));
                setGraphic(new VBox(2, path, reason));
            }
        });
        list.getStyleClass().add("failed-files");
        list.visibleProperty().bind(page.onlyErrors());
        return list;
    }
}
