package protonbackup.ui.view;

import java.util.Objects;
import javafx.beans.binding.Bindings;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import protonbackup.ui.mvvm.PageViewModel;
import protonbackup.ui.viewmodel.MainWindowViewModel;

/**
 * The window's content: the dark sidebar (logo, navigation, Settings and Log icons, session and last
 * run), a banner when the Proton CLI is missing, and the page on show. While the first-run wizard is
 * needed it replaces all of that.
 */
public final class MainWindow {

    public static final String STYLESHEET = "/protonbackup/ui/app.css";
    public static final String LOGO = "/protonbackup/ui/assets/protonbackup.png";

    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    private final MainWindowViewModel viewModel;
    private final PageViews views;
    private final StackPane root = new StackPane();
    private final StackPane contentHolder = new StackPane();

    public MainWindow(MainWindowViewModel viewModel, PageViews views) {
        this.viewModel = viewModel;
        this.views = views;

        var layout = new BorderPane();
        layout.setLeft(sidebar());
        layout.setCenter(content());

        var welcome = new StackPane(views.viewFor(viewModel.welcome()));
        welcome.setPadding(new Insets(16, 32, 16, 32));
        welcome.getStyleClass().add("page-background");

        // After the removal nothing else may be shown: the app's data is gone, so every other page would only fail.
        var removed = new StackPane();
        removed.getStyleClass().add("page-background");
        removed.managedProperty().bind(viewModel.removal().isNotNull());
        removed.visibleProperty().bind(viewModel.removal().isNotNull());
        viewModel.removal().addListener((observable, before, steps) -> {
            if (steps != null) removed.getChildren().setAll(new RemovalFinishedView(steps));
        });
        layout.managedProperty().bind(viewModel.showWelcome().not().and(viewModel.removal().isNull()));
        layout.visibleProperty().bind(viewModel.showWelcome().not().and(viewModel.removal().isNull()));
        welcome.managedProperty().bind(viewModel.showWelcome().and(viewModel.removal().isNull()));
        welcome.visibleProperty().bind(viewModel.showWelcome().and(viewModel.removal().isNull()));

        root.getChildren().addAll(welcome, layout, removed);
    }

    public Parent root() {
        return root;
    }

    public static String stylesheetUrl() {
        return Objects.requireNonNull(MainWindow.class.getResource(STYLESHEET), "app.css is missing").toExternalForm();
    }

    // ---- the content area ---------------------------------------------------------------

    private Node content() {
        var missing = new InfoBar(InfoBar.Severity.WARNING);
        missing.titleLabel().setText("The Proton Drive CLI is not installed yet");
        missing.messageLabel().setText("It is downloaded when you sign in. You can also put it in ~/.local/share/ProtonBackup/bin or on your PATH.");
        missing.openProperty().bind(viewModel.cliMissing());
        VBox.setMargin(missing, new Insets(18, 24, 0, 24));

        contentHolder.setPadding(new Insets(22, 28, 24, 28));
        VBox.setVgrow(contentHolder, Priority.ALWAYS);
        showPage(viewModel.selectedPage().get());
        viewModel.selectedPage().addListener((observable, before, page) -> showPage(page));

        var column = new VBox(missing, contentHolder);
        column.getStyleClass().add("content");
        return column;
    }

    private void showPage(PageViewModel page) {
        if (page == null) return;
        contentHolder.getChildren().setAll(views.viewFor(page));
    }

    // ---- the sidebar --------------------------------------------------------------------

    private Node sidebar() {
        var logo = new ImageView(new Image(Objects.requireNonNull(MainWindow.class.getResourceAsStream(LOGO), "logo is missing")));
        logo.setFitWidth(30);
        logo.setFitHeight(30);
        logo.setPreserveRatio(true);
        logo.setSmooth(true);

        var name = new Label("Proton Drive");
        name.getStyleClass().add("sidebar-title");
        var subtitle = new Label("backup");
        subtitle.getStyleClass().add("sidebar-subtitle");
        var brand = new HBox(10, logo, new VBox(0, name, subtitle));
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.setPadding(new Insets(0, 8, 22, 8));

        var navigation = new VBox(2);
        for (var page : viewModel.navigation()) navigation.getChildren().add(navItem(page));

        var settings = iconButton(NavIcons.gear(18), "Settings", viewModel.settings());
        var log = iconButton(NavIcons.log(18), "Log", viewModel.log());
        var icons = new HBox(8, settings, log);
        icons.setPadding(new Insets(8, 8, 8, 8));

        var session = new Label();
        session.getStyleClass().add("sidebar-session");
        session.textProperty().bind(viewModel.status().sessionText());
        var lastRun = new Label();
        lastRun.getStyleClass().add("sidebar-lastrun");
        lastRun.setWrapText(true);
        lastRun.textProperty().bind(viewModel.status().lastRun());
        var footer = new VBox(6, session, lastRun);
        footer.setPadding(new Insets(4, 8, 4, 8));

        var spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        var sidebar = new VBox(brand, navigation, spacer, icons, footer);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(240);
        sidebar.setMinWidth(270);
        sidebar.setMaxWidth(240);
        return sidebar;
    }

    private Button navItem(PageViewModel page) {
        var button = new Button(page.title());
        button.getStyleClass().add("nav-item");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(event -> viewModel.select(page));
        followSelection(button, page);
        return button;
    }

    private Button iconButton(Node icon, String name, PageViewModel page) {
        var button = new Button();
        button.setGraphic(new StackPane(icon));
        button.getStyleClass().add("nav-icon");
        button.setAccessibleText(name);
        button.setTooltip(new Tooltip(name));
        button.setOnAction(event -> viewModel.select(page));
        followSelection(button, page);
        return button;
    }

    private void followSelection(Button button, PageViewModel page) {
        button.pseudoClassStateChanged(SELECTED, viewModel.selectedPage().get() == page);
        viewModel.selectedPage().addListener((observable, before, now) -> button.pseudoClassStateChanged(SELECTED, now == page));
    }
}
