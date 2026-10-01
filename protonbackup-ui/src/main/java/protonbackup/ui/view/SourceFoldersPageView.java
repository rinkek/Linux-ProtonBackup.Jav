package protonbackup.ui.view;

import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import protonbackup.core.SyncSource;
import protonbackup.ui.viewmodel.SourceFoldersPageViewModel;

/** The folders being backed up, each with Sync now / Go through everything again / Remove, and a form to add one. */
public final class SourceFoldersPageView extends ScrollPane {

    private final SourceFoldersPageViewModel page;
    private final VBox rows = new VBox(10);

    public SourceFoldersPageView(SourceFoldersPageViewModel page) {
        this.page = page;

        var none = Widgets.muted("No source folders yet. Add one below.");
        none.visibleProperty().bind(javafx.beans.binding.Bindings.isEmpty(page.sources()));
        none.managedProperty().bind(none.visibleProperty());

        rebuild();
        page.sources().addListener((ListChangeListener<SyncSource>) change -> rebuild());

        var content = new VBox(20, Widgets.pageTitle("Source folders"), Widgets.messageBar(page), none, rows, addCard());
        content.setPadding(new Insets(0, 4, 0, 0));
        setContent(content);
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
    }

    private void rebuild() {
        rows.getChildren().setAll(page.sources().stream().map(this::row).toList());
    }

    private Node row(SyncSource source) {
        var local = new Label(source.localPath());
        local.getStyleClass().add("row-title");
        local.setWrapText(true);
        var remote = new Label(source.remotePath());
        remote.getStyleClass().add("muted");
        remote.setWrapText(true);
        var texts = new VBox(2, local, remote);
        texts.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(texts, Priority.ALWAYS);

        var sync = Widgets.iconButton(Icons.UPLOAD, "Sync now");
        sync.setOnAction(event -> page.syncSource().execute(source));
        var again = Widgets.iconButton(Icons.REFRESH,
                "Go through everything again, including files already considered synced. Use this if something was deleted on Proton.");
        again.setOnAction(event -> page.forceSource().execute(source));
        var remove = Widgets.iconButton(Icons.DELETE, "Remove this source folder from the list. Whatever is already on Proton stays there.");
        remove.getStyleClass().add("danger");
        remove.setOnAction(event -> page.removeSource().execute(source));

        var box = new HBox(4, texts, sync, again, remove);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().addAll("card", "row-card");
        return box;
    }

    private Node addCard() {
        var local = Widgets.textField(page.newLocalPath(), "Local folder");
        Widgets.grow(local);
        var browse = Widgets.commandButton("Browse", page.browse());
        var localRow = new HBox(8, local, browse);

        var remote = Widgets.textField(page.newRemotePath(), "Destination path on Proton, for example /my-files/Backup");
        var add = Widgets.commandButton("Add", page.addSource());

        var card = new VBox(12, Widgets.sectionTitle("Add a source folder"), localRow, remote, add);
        card.getStyleClass().add("card");
        add.setMaxWidth(Region.USE_PREF_SIZE);
        return card;
    }
}
