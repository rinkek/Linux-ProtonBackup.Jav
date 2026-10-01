package protonbackup.ui.view;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * A strip with a message, an optional action button and an optional close button, in the style of
 * Avalonia's {@code InfoBar}. It takes no space while it is not {@link #openProperty() open}.
 */
public final class InfoBar extends HBox {

    public enum Severity {
        INFO("info-bar-info"),
        WARNING("info-bar-warning");

        private final String styleClass;

        Severity(String styleClass) {
            this.styleClass = styleClass;
        }
    }

    private final Label title = new Label();
    private final Label message = new Label();
    private final BooleanProperty open = new SimpleBooleanProperty(false);

    public InfoBar(Severity severity) {
        getStyleClass().addAll("info-bar", severity.styleClass);
        title.getStyleClass().add("info-bar-title");
        title.setWrapText(true);
        message.setWrapText(true);
        title.managedProperty().bind(title.textProperty().isNotEmpty());
        title.visibleProperty().bind(title.textProperty().isNotEmpty());

        var texts = new VBox(2, title, message);
        HBox.setHgrow(texts, Priority.ALWAYS);
        getChildren().add(texts);

        managedProperty().bind(open);
        visibleProperty().bind(open);
    }

    public BooleanProperty openProperty() {
        return open;
    }

    public Label titleLabel() {
        return title;
    }

    public Label messageLabel() {
        return message;
    }

    /** Adds a button on the right, before the close button. */
    public Button addAction(String text) {
        var button = new Button(text);
        getChildren().add(button);
        return button;
    }

    /** Adds a close button that runs {@code onClose}. */
    public InfoBar closable(Runnable onClose) {
        var close = new Button("×");
        close.getStyleClass().add("info-bar-close");
        close.setAccessibleText("Dismiss");
        close.setOnAction(event -> onClose.run());
        getChildren().add(close);
        return this;
    }
}
