package protonbackup.ui.view;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import protonbackup.ui.viewmodel.SettingsPageViewModel;

/**
 * Account, sync interval, the Proton CLI and removal. All buttons here are the grey ("plain") style;
 * the purple accent is kept for the pages' main actions.
 */
public final class SettingsPageView extends ScrollPane {

    public SettingsPageView(SettingsPageViewModel page) {
        var content = new VBox(20,
                Widgets.pageTitle("Settings"),
                Widgets.messageBar(page),
                account(page),
                syncing(page),
                cli(page),
                maintenance(page),
                removal(page));
        content.setPadding(new Insets(0, 4, 0, 0));
        setContent(content);
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
    }

    private static Node card(Node... children) {
        var card = new VBox(14, children);
        card.getStyleClass().add("card");
        return card;
    }

    private static Node account(SettingsPageViewModel page) {
        var caption = new Label("Account:");
        caption.getStyleClass().add("muted");
        var session = new Label();
        session.getStyleClass().add("row-title");
        session.textProperty().bind(page.sessionText());
        var row = new HBox(12, caption, session, Widgets.plainButton("Sign in to Proton", page.login()), Widgets.plainButton("Sign out", page.logout()));
        row.setAlignment(Pos.CENTER_LEFT);
        return card(row);
    }

    private static Node syncing(SettingsPageViewModel page) {
        var spinner = new Spinner<Integer>();
        var factory = new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 1440, page.intervalMinutes().get(), 5);
        spinner.setValueFactory(factory);
        spinner.setEditable(true);
        spinner.setPrefWidth(150);
        // A bidirectional binding only holds its two properties weakly. asObject() makes a new one that nothing
        // else refers to, so without this the garbage collector eventually ends the binding without a sound.
        var intervalAsObject = page.intervalMinutes().asObject();
        spinner.getProperties().put("interval-binding", intervalAsObject);
        factory.valueProperty().bindBidirectional(intervalAsObject);
        // A typed value only counts once the field is left; without this the spinner keeps the old number.
        spinner.focusedProperty().addListener((observable, before, focused) -> {
            if (focused) return;
            try {
                factory.setValue(Integer.parseInt(spinner.getEditor().getText().trim()));
            } catch (NumberFormatException notANumber) {
                spinner.getEditor().setText(String.valueOf(factory.getValue()));
            }
        });

        var caption = new Label("Interval (minutes)");
        caption.getStyleClass().add("muted");
        var save = Widgets.iconButton(Icons.SAVE, "Save interval");
        save.setOnAction(event -> {
            spinner.getEditor().getParent().requestFocus(); // commits what is typed
            page.applyInterval().execute();
        });
        save.disableProperty().bind(page.applyInterval().disabled());
        var row = new HBox(14, caption, spinner, save);
        row.setAlignment(Pos.CENTER_LEFT);
        return card(Widgets.sectionTitle("Syncing"), row);
    }

    private static Node cli(SettingsPageViewModel page) {
        var path = new TextField();
        path.setEditable(false);
        path.setPromptText("not found");
        path.textProperty().bind(page.cliPath());

        var actions = new HBox(12,
                Widgets.plainButton("Check for updates", page.checkForUpdate()),
                Widgets.plainButton("Update", page.updateCli()),
                Widgets.plainButton("Restore previous version", page.rollbackCli()));

        var advanced = new TitledPane("Advanced CLI settings", advancedSettings(page));
        advanced.setExpanded(false);

        return card(Widgets.sectionTitle("Proton CLI"), path, actions, advanced);
    }

    private static Node maintenance(SettingsPageViewModel page) {
        var hint = Widgets.muted("Write the systemd units again, or set the interval and the CLI settings back to what they were at the start.");
        hint.getStyleClass().add("field-caption");
        var buttons = new HBox(12, Widgets.plainButton("Rewrite systemd units", page.installUnits()), Widgets.plainButton("Restore defaults", page.restoreDefaults()));
        return card(Widgets.sectionTitle("Maintenance"), hint, buttons);
    }

    private static Node advancedSettings(SettingsPageViewModel page) {
        var skip = new CheckBox("Skip checksum");
        skip.selectedProperty().bindBidirectional(page.skipChecksum());
        var hint = Widgets.muted("Only turn this on if a custom location offers no checksum.");
        hint.getStyleClass().add("field-caption");

        var box = new VBox(12,
                labelled("Version page URL", Widgets.textField(page.versionPageUrl(), "")),
                labelled("Download URL template", Widgets.textField(page.downloadTemplate(), "")),
                labelled("Platform", Widgets.textField(page.platform(), "")),
                skip,
                hint,
                Widgets.plainButton("Save CLI settings", page.saveCliSettings()));
        box.setPadding(new Insets(10, 0, 4, 0));
        return box;
    }

    private static Node labelled(String caption, TextField field) {
        return new VBox(4, Widgets.caption(caption), field);
    }

    private static Node removal(SettingsPageViewModel page) {
        var hint = Widgets.muted("Clean up here first, then remove the package with apt remove; apt deliberately leaves your personal data in place.");
        hint.getStyleClass().add("field-caption");
        var remove = Widgets.plainButton("Remove and clean up", page.cleanup());
        return card(Widgets.sectionTitle("Removal"), hint, remove);
    }
}
