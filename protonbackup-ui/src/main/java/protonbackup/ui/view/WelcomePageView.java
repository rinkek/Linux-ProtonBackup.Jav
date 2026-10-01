package protonbackup.ui.view;

import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import protonbackup.ui.viewmodel.WelcomePageViewModel;

/** The first-run wizard: four steps, each showing "done" once it is. */
public final class WelcomePageView extends BorderPane {

    public WelcomePageView(WelcomePageViewModel page) {
        var intro = Widgets.muted("Four steps and your backup is running. Files are only copied to Proton Drive; nothing is ever deleted there.");

        var steps = new VBox(13,
                Widgets.pageTitle("Welcome"),
                intro,
                Widgets.messageBar(page),
                step("1. The Proton Drive CLI", page.hasCli(), "Found and ready to use.",
                        Widgets.muted("The CLI is not part of the package. It is fetched from Proton and its SHA-512 is verified."),
                        Widgets.commandButton("Download CLI", page.downloadCli())),
                step("2. Sign in to Proton", page.signedIn(), "Signed in.",
                        Widgets.muted("Signing in happens in your browser. The app stores no password; the session goes into your desktop keyring. "
                                + "If the CLI is not there yet, it is downloaded first."),
                        Widgets.commandButton("Sign in to Proton", page.signIn())),
                step("3. What do you want to back up?", page.hasSource(), "Source folder set.",
                        sourceForm(page)),
                step("4. Sync automatically", page.timerEnabled(), "The timer is on.",
                        Widgets.muted("Turn on the timer so syncing also happens when this app is closed. You can change this later."),
                        Widgets.commandButton("Turn on the timer", page.enableTimer())));
        steps.setMaxWidth(700);
        steps.setPadding(new Insets(4, 0, 16, 0));

        var scroll = new ScrollPane(new StackPane(steps));
        ((StackPane) scroll.getContent()).setAlignment(Pos.TOP_CENTER);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        setCenter(scroll);

        var start = Widgets.commandButton("Get started", page.finish());
        var footer = new HBox(start);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setMaxWidth(700);
        footer.getStyleClass().add("footer-bar");
        var footerHolder = new StackPane(footer);
        footerHolder.setAlignment(Pos.CENTER);
        footerHolder.setPadding(new Insets(4, 0, 0, 0));
        setBottom(footerHolder);
    }

    /** A card whose body is a green "done" line once the step is done, and the controls until then. */
    private static Node step(String title, ObservableBooleanValue done, String doneText, Node... controls) {
        var heading = new Label(title);
        heading.getStyleClass().add("row-title");

        var finished = new Label(doneText);
        finished.getStyleClass().add("success");
        finished.visibleProperty().bind(done);
        finished.managedProperty().bind(done);

        var open = new VBox(10, controls);
        open.visibleProperty().bind(javafx.beans.binding.Bindings.not(done));
        open.managedProperty().bind(javafx.beans.binding.Bindings.not(done));
        for (var control : controls) {
            if (control instanceof Button button) button.setMaxWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        }

        var card = new VBox(8, heading, finished, open);
        card.getStyleClass().add("card");
        return card;
    }

    private static Node[] sourceForm(WelcomePageViewModel page) {
        var local = Widgets.textField(page.localPath(), "Local folder");
        Widgets.grow(local);
        var row = new HBox(8, local, Widgets.commandButton("Browse", page.browse()));
        var remote = Widgets.textField(page.remotePath(), "Destination path on Proton");
        return new Node[] {row, remote, Widgets.commandButton("Add", page.addSource())};
    }
}
