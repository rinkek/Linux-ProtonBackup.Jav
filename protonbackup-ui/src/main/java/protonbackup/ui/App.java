package protonbackup.ui;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.util.Duration;
import protonbackup.ui.mvvm.Background;
import protonbackup.ui.service.BackupService;
import protonbackup.ui.tray.AwtTray;
import protonbackup.ui.view.DialogFolderPicker;
import protonbackup.ui.view.MainWindow;
import protonbackup.ui.view.PageViews;
import protonbackup.ui.viewmodel.MainWindowViewModel;

/**
 * The JavaFX application: opens the service (off the UI thread, in {@link #init()}), builds the window,
 * and runs the periodic refresh.
 */
public final class App extends Application {

    private static final System.Logger LOG = System.getLogger("protonbackup.ui");

    private BackupService service;
    private boolean setupNeeded;
    private Exception startupFailure;
    private Background background;
    private Timeline poller;
    private Stage primaryStage;
    private java.util.Optional<AwtTray> tray = java.util.Optional.empty();

    /** Runs on the launcher thread, not the UI thread: the right place for blocking start-up work. */
    @Override
    public void init() {
        try {
            service = BackupService.create();
            setupNeeded = service.setupNeeded();
        } catch (Exception e) {
            startupFailure = e;
        }
    }

    @Override
    public void start(Stage stage) {
        if (startupFailure != null) {
            LOG.log(System.Logger.Level.ERROR, "Could not start", startupFailure);
            var alert = new Alert(Alert.AlertType.ERROR, "Proton Drive backup could not start:\n\n" + startupFailure.getMessage());
            alert.setHeaderText(null);
            alert.showAndWait();
            Platform.exit();
            return;
        }

        background = Background.production();
        // A SIGTERM ends the JVM without calling stop(); closing the workers interrupts them, which kills a
        // child process they started (a sign-in waiting for the browser) instead of leaving it behind.
        var workers = background;
        Runtime.getRuntime().addShutdownHook(new Thread(workers::close, "stop-workers"));
        var viewModel = new MainWindowViewModel(service, background, setupNeeded, new DialogFolderPicker(stage));
        var window = new MainWindow(viewModel, PageViews.standard());

        var scene = new Scene(window.root(), 1180, 800);
        scene.getStylesheets().add(MainWindow.stylesheetUrl());
        stage.setTitle("Proton Drive backup");
        stage.getIcons().add(new Image(App.class.getResourceAsStream(MainWindow.LOGO)));
        stage.setMinWidth(860);
        stage.setMinHeight(540);
        stage.setScene(scene);
        stage.show();

        primaryStage = stage;
        tray = AwtTray.install(
                viewModel.trayState().getValue(),
                () -> Platform.runLater(this::bringWindowForward),
                () -> Platform.runLater(Platform::exit));
        tray.ifPresent(icon -> viewModel.trayState().addListener((observable, before, state) -> icon.show(state)));

        viewModel.start();
        poller = new Timeline(new KeyFrame(Duration.millis(MainWindowViewModel.TICK.toMillis()), event -> viewModel.tick()));
        poller.setCycleCount(Animation.INDEFINITE);
        poller.play();
    }

    private void bringWindowForward() {
        primaryStage.setIconified(false);
        primaryStage.show();
        primaryStage.toFront();
        primaryStage.requestFocus();
    }

    @Override
    public void stop() {
        tray.ifPresent(AwtTray::remove);
        if (poller != null) poller.stop();
        if (background != null) background.close(); // interrupts work in flight; a child process it started is killed
        if (service != null) service.close();
    }
}
