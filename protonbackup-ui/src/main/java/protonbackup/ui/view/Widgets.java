package protonbackup.ui.view;

import javafx.beans.property.StringProperty;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import protonbackup.ui.mvvm.PageViewModel;

/** The few building blocks every page uses, so the pages read as layout and not as plumbing. */
final class Widgets {

    private Widgets() {}

    static Label pageTitle(String text) {
        var label = new Label(text);
        label.getStyleClass().add("page-title");
        return label;
    }

    static Label sectionTitle(String text) {
        var label = new Label(text);
        label.getStyleClass().add("section-title");
        return label;
    }

    static Label muted(String text) {
        var label = new Label(text);
        label.getStyleClass().add("muted");
        label.setWrapText(true);
        return label;
    }

    static Label caption(String text) {
        var label = new Label(text);
        label.getStyleClass().add("field-caption");
        return label;
    }

    /** The strip showing the page's last message, with a close button. */
    static InfoBar messageBar(PageViewModel page) {
        var bar = new InfoBar(InfoBar.Severity.INFO).closable(page::clearMessage);
        bar.messageLabel().textProperty().bind(page.message());
        bar.openProperty().bind(page.message().isNotNull().and(page.message().isNotEmpty()));
        return bar;
    }

    /** A text field kept in step with a view-model property in both directions. */
    static TextField textField(StringProperty property, String prompt) {
        var field = new TextField();
        field.setPromptText(prompt);
        field.textProperty().bindBidirectional(property);
        return field;
    }

    /** A button for a command: disabled while the command cannot run. */
    static Button commandButton(String text, protonbackup.ui.mvvm.Command<Void> command) {
        var button = new Button(text);
        button.setOnAction(event -> command.execute());
        button.disableProperty().bind(command.disabled());
        return button;
    }

    /** A grey button, as used on the Settings page. */
    static Button plainButton(String text, protonbackup.ui.mvvm.Command<Void> command) {
        var button = commandButton(text, command);
        button.getStyleClass().add("plain");
        return button;
    }

    static Button iconButton(String pathData, String tooltip) {
        var button = new Button();
        button.setGraphic(Icons.of(pathData, 17));
        button.getStyleClass().add("icon-button");
        button.setAccessibleText(tooltip);
        var hint = new Tooltip(tooltip);
        hint.setWrapText(true);
        hint.setMaxWidth(360);
        button.setTooltip(hint);
        return button;
    }

    static void grow(Node node) {
        javafx.scene.layout.HBox.setHgrow(node, javafx.scene.layout.Priority.ALWAYS);
    }
}
