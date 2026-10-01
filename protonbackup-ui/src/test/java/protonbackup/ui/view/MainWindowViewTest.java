package protonbackup.ui.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javafx.css.CssParser;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import protonbackup.core.ScannedFile;
import protonbackup.ui.mvvm.Background;
import protonbackup.ui.testing.Fixture;
import protonbackup.ui.testing.FxToolkit;
import protonbackup.ui.viewmodel.FolderPicker;
import protonbackup.ui.viewmodel.MainWindowViewModel;

/**
 * Builds the real scene graph with the real stylesheet. Needs the JavaFX toolkit, so it skips itself
 * on a machine without a display. Besides behavior it checks the stylesheet: a CSS mistake in JavaFX is
 * only a log line, so a misspelt property or an undefined colour would otherwise go unnoticed.
 */
class MainWindowViewTest {

    @TempDir Path home;

    private Fixture fixture;
    private final List<String> cssProblems = new ArrayList<>();
    private final Logger cssLogger = Logger.getLogger("javafx.css");
    private final Handler cssHandler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) cssProblems.add(record.getMessage());
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    };

    @BeforeAll
    static void toolkit() {
        assumeTrue(FxToolkit.start(), "no JavaFX toolkit (no display) on this machine");
    }

    @BeforeEach
    void setUp() throws Exception {
        fixture = new Fixture(home).withCli();
        CssParser.errorsProperty().clear();
        cssProblems.clear();
        cssLogger.addHandler(cssHandler);
    }

    /**
     * Checked after every test, not in one test only: JavaFX reports a given CSS problem once, so only the
     * first test to apply the stylesheet would ever see it.
     */
    @AfterEach
    void tearDown() {
        cssLogger.removeHandler(cssHandler);
        fixture.close();
        assertEquals(List.of(), CssParser.errorsProperty().stream().map(Object::toString).toList(), "CSS parse errors");
        assertEquals(List.of(), cssProblems, "CSS warnings (e.g. a colour that is not defined)");
    }

    private record Window(MainWindowViewModel viewModel, MainWindow window, Scene scene) {}

    private Window open(boolean welcome, int width, int height) throws Exception {
        return FxToolkit.onFxThread(() -> {
            var viewModel = new MainWindowViewModel(fixture.service, Background.direct(), welcome, FolderPicker.NONE);
            var window = new MainWindow(viewModel, PageViews.standard());
            var scene = new Scene(window.root(), width, height);
            scene.getStylesheets().add(MainWindow.stylesheetUrl());
            window.root().applyCss();
            window.root().layout();
            return new Window(viewModel, window, scene);
        });
    }

    private static List<Button> buttons(Parent root, String styleClass) {
        return root.lookupAll("." + styleClass).stream().map(Button.class::cast).toList();
    }

    private interface Parent {
        java.util.Set<Node> lookupAll(String selector);
    }

    private static Parent parent(Node node) {
        return node::lookupAll;
    }

    /** The part of the window that shows the selected page; the hidden wizard and the sidebar are not in it. */
    private static Node content(Window window) {
        return window.window().root().lookup(".content");
    }

    /**
     * Shows a page and lets JavaFX build the skins of its controls. Until then the children of a ScrollPane,
     * a TreeView or a ListView do not exist yet, and a lookup would not find them.
     */
    private static Node go(Window window, protonbackup.ui.mvvm.PageViewModel page) {
        window.viewModel().select(page);
        window.window().root().applyCss();
        window.window().root().layout();
        return content(window);
    }

    private static Label labelWithText(Node root, String text) {
        return root.lookupAll(".label").stream()
                .map(Label.class::cast)
                .filter(label -> text.equals(label.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no label \"" + text + "\""));
    }

    // ---- the stylesheet -----------------------------------------------------------------

    /** The assertions are in {@link #tearDown()}, which every test here runs; this one only makes sure the page is styled. */
    @Test
    void theStylesheetIsAppliedToEveryPartOfTheWindow() throws Exception {
        var window = open(false, 1180, 800);

        FxToolkit.onFxThread(() -> {
            window.window().root().applyCss();
            window.window().root().layout();
            var button = button(window.window().root(), "Sync now");
            assertTrue(button.getStyleClass().contains("button"));
            assertTrue(button.getBackground() != null, "the stylesheet gave the button a background");
        });
    }

    // ---- the sidebar --------------------------------------------------------------------

    @Test
    void theSidebarListsThePagesAndHasSettingsAndLogIcons() throws Exception {
        var window = open(false, 1180, 800);

        FxToolkit.onFxThread(() -> {
            var root = parent(window.window().root());
            assertEquals(List.of("Status", "Source folders", "File sync"), buttons(root, "nav-item").stream().map(Button::getText).toList());
            assertEquals(List.of("Settings", "Log"), buttons(root, "nav-icon").stream().map(Button::getAccessibleText).toList());
            assertNotNull(labelWithText(window.window().root(), "Proton Drive"));
            assertNotNull(labelWithText(window.window().root(), "backup"));
        });
    }

    @Test
    void theFooterFollowsTheSessionAndTheLastRun() throws Exception {
        var window = open(false, 1180, 800);
        var db = fixture.service.database();
        var run = db.startRun();
        db.finishRun(run, 3, 0, "ok");

        FxToolkit.onFxThread(() -> {
            assertNotNull(labelWithText(window.window().root(), "unknown"), "before the first probe");
            window.viewModel().start();
            assertNotNull(labelWithText(window.window().root(), "signed in"));
            var lastRun = window.window().root().lookupAll(".sidebar-lastrun").stream().map(Label.class::cast).findFirst().orElseThrow();
            assertTrue(lastRun.getText().contains("3 uploaded, 0 failed (completed)"), lastRun.getText());
        });
    }

    @Test
    void clickingANavigationItemShowsThatPage() throws Exception {
        var window = open(false, 1180, 800);

        FxToolkit.onFxThread(() -> {
            var items = buttons(parent(window.window().root()), "nav-item");
            assertTrue(items.get(0).getPseudoClassStates().stream().anyMatch(p -> p.getPseudoClassName().equals("selected")), "Status starts selected");

            items.get(1).fire();

            assertSame(window.viewModel().sourceFolders(), window.viewModel().selectedPage().get());
            assertFalse(items.get(0).getPseudoClassStates().stream().anyMatch(p -> p.getPseudoClassName().equals("selected")));
            assertTrue(items.get(1).getPseudoClassStates().stream().anyMatch(p -> p.getPseudoClassName().equals("selected")));
            window.window().root().applyCss();
            assertNotNull(labelWithText(content(window), "Source folders"), "the page's own title");

            items.get(0).fire();
            window.window().root().applyCss();
            assertSame(window.viewModel().status(), window.viewModel().selectedPage().get());
            assertNotNull(labelWithText(content(window), "Status"));
        });
    }

    @Test
    void theIconButtonsSelectSettingsAndLog() throws Exception {
        var window = open(false, 1180, 800);

        FxToolkit.onFxThread(() -> {
            var icons = buttons(parent(window.window().root()), "nav-icon");
            icons.get(0).fire();
            assertSame(window.viewModel().settings(), window.viewModel().selectedPage().get());
            icons.get(1).fire();
            assertSame(window.viewModel().log(), window.viewModel().selectedPage().get());
        });
    }

    @Test
    void theCliMissingBannerAppearsOnlyWithoutACli() throws Exception {
        try (var bare = new Fixture(home.resolve("bare"))) {
            var shown = FxToolkit.onFxThread(() -> {
                var viewModel = new MainWindowViewModel(bare.service, Background.direct(), false, FolderPicker.NONE);
                var window = new MainWindow(viewModel, PageViews.standard());
                var banner = labelWithText(window.root(), "The Proton Drive CLI is not installed yet");
                return banner.getParent().getParent().isVisible();
            });
            assertTrue(shown);
        }

        var window = open(false, 1180, 800);
        FxToolkit.onFxThread(() -> {
            var banner = labelWithText(window.window().root(), "The Proton Drive CLI is not installed yet");
            assertFalse(banner.getParent().getParent().isVisible());
        });
    }

    @Test
    void theWelcomeReplacesTheNormalLayoutUntilSetupIsFinished() throws Exception {
        var window = open(true, 1180, 800);

        FxToolkit.onFxThread(() -> {
            var sidebar = window.window().root().lookup(".sidebar");
            assertFalse(sidebar.getParent().isVisible(), "the sidebar layout is hidden");

            window.viewModel().showWelcome().set(false);

            assertTrue(sidebar.getParent().isVisible());
        });
    }

    // ---- the Status page ----------------------------------------------------------------

    private static Button button(Node root, String text) {
        return root.lookupAll(".button").stream()
                .map(Button.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no button \"" + text + "\""));
    }

    @Test
    void theButtonsOfTheStatusPageFollowTheRunState() throws Exception {
        var window = open(false, 1180, 800);

        FxToolkit.onFxThread(() -> {
            var root = window.window().root();
            window.viewModel().status().refresh();
            assertFalse(button(root, "Sync now").isDisabled());
            assertTrue(button(root, "Cancel").isDisabled());

            button(root, "Sync now").fire();

            assertTrue(fixture.system.syncRunning);
            assertTrue(button(root, "Sync now").isDisabled(), "disabled while a run is going");
            assertFalse(button(root, "Cancel").isDisabled());

            button(root, "Cancel").fire();
            assertFalse(fixture.system.syncRunning);
        });
    }

    @Test
    void theAutomaticSyncBoxOnlyActsOnTheUsersOwnClick() throws Exception {
        var window = open(false, 1180, 800);

        FxToolkit.onFxThread(() -> {
            var root = window.window().root();
            var box = root.lookupAll(".check-box").stream().map(CheckBox.class::cast).findFirst().orElseThrow();

            fixture.system.timerEnabled = true; // changed outside the app
            window.viewModel().status().refresh();

            assertTrue(box.isSelected(), "the box follows the timer");
            assertTrue(fixture.system.systemctlCalls.stream().noneMatch(c -> c.contains(" enable ") || c.contains(" disable ")),
                    "reflecting the state must not run the side effect");

            box.fire(); // the user switches it off

            assertTrue(fixture.system.systemctlCalls.contains("--user disable --now protonbackup-sync.timer"));
            assertFalse(box.isSelected());
        });
    }

    @Test
    void theUpdateBannerOffersTheUpdateAndCanBeDismissed() throws Exception {
        var window = open(false, 1180, 800);

        FxToolkit.onFxThread(() -> {
            var root = window.window().root();
            var title = labelWithText(root, "A newer Proton Drive CLI is available");
            var banner = title.getParent().getParent();
            assertFalse(banner.isVisible());

            window.viewModel().status().refreshSlow();
            assertTrue(banner.isVisible());
            assertNotNull(labelWithText(root, "Version 0.9.0 is available."));
            assertInstanceOf(Button.class, button(root, "Update now"));

            ((Button) banner.lookup(".info-bar-close")).fire(); // the banner's own close button
            assertFalse(banner.isVisible());
        });
    }

    // ---- the other pages ----------------------------------------------------------------

    private static javafx.scene.control.Button[] none() {
        return new javafx.scene.control.Button[0];
    }

    private static List<Button> buttonsIn(Node root) {
        return root.lookupAll(".button").stream().map(Button.class::cast).toList();
    }

    @Test
    void everyPageHasItsOwnViewAndNoPlaceholderIsLeft() throws Exception {
        var window = open(false, 1180, 800);

        FxToolkit.onFxThread(() -> {
            for (var page : List.of(window.viewModel().status(), window.viewModel().sourceFolders(), window.viewModel().structure(),
                    window.viewModel().log(), window.viewModel().settings(), window.viewModel().welcome())) {
                var view = PageViews.standard().viewFor(page);
                assertFalse(view instanceof PlaceholderPageView, page.title() + " still shows a placeholder");
            }
        });
    }

    @Test
    void theSourceFoldersPageShowsARowPerFolderWithThreeActions() throws Exception {
        var db = fixture.service.database();
        db.addSource(home.resolve("docs").toString(), "/my-files/Docs");
        db.addSource(home.resolve("photos").toString(), "/my-files/Photos");
        var window = open(false, 1180, 800);

        FxToolkit.onFxThread(() -> {
            window.viewModel().sourceFolders().refresh();
            var root = go(window, window.viewModel().sourceFolders());

            assertEquals(2, root.lookupAll(".row-card").size());
            assertEquals(6, root.lookupAll(".icon-button").size(), "three actions per folder");
            assertNotNull(labelWithText(root, home.resolve("docs").toString()));
            assertNotNull(labelWithText(root, "/my-files/Photos"));
            var tips = root.lookupAll(".icon-button").stream().map(Button.class::cast).map(b -> b.getTooltip().getText()).toList();
            assertTrue(tips.get(0).equals("Sync now") && tips.get(2).startsWith("Remove this source folder"), tips.toString());

            // removing the first folder takes its row away
            ((Button) root.lookupAll(".icon-button.danger").iterator().next()).fire();
            assertEquals(1, root.lookupAll(".row-card").size());
        });
    }

    @Test
    void theAddFormOfTheSourceFoldersPageIsBoundToTheViewModel() throws Exception {
        var window = open(false, 1180, 800);
        Files.createDirectories(home.resolve("new-folder"));

        FxToolkit.onFxThread(() -> {
            var root = go(window, window.viewModel().sourceFolders());
            var fields = root.lookupAll(".text-field").stream().map(javafx.scene.control.TextField.class::cast).toList();
            assertEquals("/my-files/Backup", fields.get(1).getText());

            fields.get(0).setText(home.resolve("new-folder").toString());
            assertEquals(home.resolve("new-folder").toString(), window.viewModel().sourceFolders().newLocalPath().get());

            button(root, "Add").fire();

            assertEquals(1, window.viewModel().sourceFolders().sources().size());
            assertEquals("", fields.get(0).getText(), "the field is cleared after adding");
            assertNotNull(labelWithText(content(window), "Source folder added."));
        });
    }

    @Test
    void theFileSyncPageShowsATreeThatLoadsFolderByFolder() throws Exception {
        var db = fixture.service.database();
        var source = db.addSource(home.toString(), "/my-files/Backup");
        db.upsertPending(source, List.of(new ScannedFile("docs/a.txt", 1, 1, null), new ScannedFile("top.txt", 1, 1, null)));
        var window = open(false, 1180, 800);

        FxToolkit.onFxThread(() -> {
            window.viewModel().structure().refresh();
            var tree = (javafx.scene.control.TreeView<?>) go(window, window.viewModel().structure()).lookup(".file-tree");

            assertEquals(2, tree.getRoot().getChildren().size());
            var docs = tree.getRoot().getChildren().get(0);
            assertEquals(1, docs.getChildren().size(), "a loading marker makes the folder look expandable");

            docs.setExpanded(true);

            assertEquals(1, docs.getChildren().size());
            assertEquals("a.txt", ((protonbackup.ui.viewmodel.FolderNode) docs.getChildren().get(0).getValue()).name());
        });
    }

    @Test
    void errorsOnlySwitchesTheFileSyncPageToTheFailedFiles() throws Exception {
        var db = fixture.service.database();
        var source = db.addSource(home.toString(), "/my-files/Backup");
        db.upsertPending(source, List.of(new ScannedFile("a.txt", 1, 1, null)));
        db.markError(source, List.of("a.txt"), "network unreachable");
        var window = open(false, 1180, 800);

        FxToolkit.onFxThread(() -> {
            window.viewModel().structure().refresh();
            var root = go(window, window.viewModel().structure());
            var tree = root.lookup(".file-tree");
            var list = root.lookup(".failed-files");
            assertTrue(tree.isVisible());
            assertFalse(list.isVisible());

            window.viewModel().structure().onlyErrors().set(true);

            assertFalse(tree.isVisible());
            assertTrue(list.isVisible());
        });
    }

    @Test
    void theLogPageListsRunsAndFailures() throws Exception {
        var db = fixture.service.database();
        db.finishRun(db.startRun(), 3, 0, "ok");
        var window = open(false, 1180, 800);

        FxToolkit.onFxThread(() -> {
            window.viewModel().log().refresh();
            var root = go(window, window.viewModel().log());

            var runs = (javafx.scene.control.ListView<?>) root.lookup(".list-view");
            assertEquals(1, runs.getItems().size());
            assertNotNull(labelWithText(root, "Recent runs"));
        });
    }

    @Test
    void theSettingsButtonsAreGreyAndTheIntervalFieldFollowsTheViewModel() throws Exception {
        var window = open(false, 1180, 800);

        FxToolkit.onFxThread(() -> {
            var root = go(window, window.viewModel().settings());
            var settings = (ScrollPane) root.lookup(".scroll-pane");
            var pageButtons = buttonsIn(settings.getContent()).stream()
                    .filter(b -> !b.getStyleClass().contains("icon-button") && b.getParent() != null && !b.getStyleClass().contains("info-bar-close"))
                    .toList();

            assertTrue(pageButtons.size() >= 8, "sign in/out, three CLI buttons, save, units, defaults, remove: " + pageButtons.size());
            for (var b : pageButtons) {
                assertTrue(b.getStyleClass().contains("plain") || b.getText() == null || b.getText().isEmpty() || b.getStyleClass().contains("title"),
                        "'" + b.getText() + "' should use the grey style on the Settings page");
            }
            var spinner = (javafx.scene.control.Spinner<?>) root.lookup(".spinner");
            window.viewModel().settings().intervalMinutes().set(45);
            assertEquals(45, spinner.getValue());
        });
    }

    /** JavaFX keeps bidirectional bindings through weak references; a temporary on one side silently ends the binding. */
    @Test
    void theIntervalFieldKeepsFollowingTheViewModelAfterGarbageCollection() throws Exception {
        var window = open(false, 1180, 800);

        FxToolkit.onFxThread(() -> {
            var root = go(window, window.viewModel().settings());
            var spinner = (javafx.scene.control.Spinner<?>) root.lookup(".spinner");
            for (var round = 0; round < 5; round++) {
                System.gc();
                System.runFinalization();
            }

            window.viewModel().settings().intervalMinutes().set(45);
            assertEquals(45, spinner.getValue());

            spinner.getValueFactory();
            @SuppressWarnings("unchecked")
            var typed = (javafx.scene.control.Spinner<Integer>) spinner;
            typed.getValueFactory().setValue(60);
            assertEquals(60, window.viewModel().settings().intervalMinutes().get(), "and the other way round");
        });
    }

    @Test
    void signingInFromTheSettingsPageUpdatesTheAccountLine() throws Exception {
        fixture.system.loggedIn = false;
        var window = open(false, 1180, 800);

        FxToolkit.onFxThread(() -> {
            var root = go(window, window.viewModel().settings());

            button(root, "Sign in to Proton").fire();

            assertNotNull(labelWithText(window.window().root().lookup(".sidebar"), "signed in"), "the sidebar says so");
            assertNotNull(labelWithText(root, "signed in"), "and so does the Account line");
            assertNotNull(labelWithText(root, "Signed in."));
        });
    }

    @Test
    void theWizardShowsAGreenLineForEveryFinishedStepAndGetStartedWaitsForTheEssentials() throws Exception {
        var window = open(true, 1180, 800);

        FxToolkit.onFxThread(() -> {
            var root = window.window().root();
            var getStarted = button(root, "Get started");
            assertTrue(getStarted.isDisabled());

            fixture.service.database().addSource(home.toString(), "/my-files/Backup");
            window.viewModel().welcome().refresh();
            window.viewModel().welcome().refreshSlow();

            assertNotNull(labelWithText(root, "Found and ready to use."));
            assertNotNull(labelWithText(root, "Signed in."));
            assertNotNull(labelWithText(root, "Source folder set."));
            assertFalse(getStarted.isDisabled());

            getStarted.fire();
            assertFalse(window.viewModel().showWelcome().get(), "setup is over: the normal layout is back");
        });
    }

    @Test
    void afterTheRemovalOnlyTheResultAndAQuitButtonAreLeft() throws Exception {
        var window = open(false, 1180, 800);

        FxToolkit.onFxThread(() -> {
            var content = go(window, window.viewModel().settings());

            button(content, "Remove and clean up").fire(); // asks
            button(content, "Remove and clean up").fire(); // does it
            var root = window.window().root();
            root.applyCss();

            assertNotNull(labelWithText(root, "Everything was removed"));
            assertNotNull(labelWithText(root, "Remove the package with: sudo apt remove protonbackup"));
            assertNotNull(button(root, "Quit"));
            assertFalse(root.lookup(".sidebar").getParent().isVisible(), "the normal layout is gone");
        });
    }

    // ---- pictures for reviewing the layout (only when asked for) --------------------------

    @Test
    void writeSnapshotsWhenAskedTo() throws Exception {
        var directory = System.getProperty("protonbackup.snapshots");
        assumeTrue(directory != null && !directory.isBlank(), "set -Dprotonbackup.snapshots=<folder> to write pictures");
        Files.createDirectories(Path.of(directory));

        var db = fixture.service.database();
        var docs = db.addSource(Files.createDirectories(home.resolve("Documenten")).toString(), "/my-files/Backup");
        var photos = db.addSource(Files.createDirectories(home.resolve("Foto's")).toString(), "/my-files/Photos");
        db.upsertPending(docs, List.of(
                new ScannedFile("report.txt", 120, 1, null), new ScannedFile("notes/todo.md", 20, 1, null),
                new ScannedFile("notes/ideas.md", 20, 1, null), new ScannedFile("archive/2025/old.pdf", 900, 1, null),
                new ScannedFile("archive/2024/older.pdf", 900, 1, null), new ScannedFile("very-long-file-name-that-goes-on-and-on-and-on-for-a-while.txt", 5, 1, null)));
        db.markSynced(docs, List.of("report.txt", "notes/todo.md", "archive/2025/old.pdf", "archive/2024/older.pdf"));
        db.markError(docs, List.of("notes/ideas.md"), "Node not found: the folder was removed on Proton in the meantime");
        db.upsertPending(photos, List.of(new ScannedFile("holiday/1.jpg", 1, 1, null)));
        db.markError(photos, List.of("holiday/1.jpg"), "network unreachable");
        db.finishRun(db.startRun(), 4, 0, "ok");
        db.finishRun(db.startRun(), 1, 2, "partial");
        db.finishRun(db.startRun(), 0, 1, "cancelled");
        db.startRun();
        fixture.system.listTimers = "NEXT LEFT LAST PASSED UNIT ACTIVATES\nThu 2026-10-01 08:47:17 CEST 9min -  - protonbackup-sync.timer protonbackup-sync.service\n";
        fixture.system.timerEnabled = true;

        snapshot(directory, "status", 1180, 800, w -> w.viewModel().start());
        snapshot(directory, "status-running-with-message", 1180, 800, w -> {
            fixture.system.syncRunning = true;
            w.viewModel().start();
            w.viewModel().status().setAutoSync().execute(false);
        });
        snapshot(directory, "status-minimum-size", 860, 540, w -> w.viewModel().start());
        snapshot(directory, "source-folders", 1180, 800, w -> go(w, w.viewModel().sourceFolders()));
        snapshot(directory, "source-folders-minimum-size", 860, 540, w -> go(w, w.viewModel().sourceFolders()));
        snapshot(directory, "file-sync", 1180, 800, w -> {
            w.viewModel().structure().refresh();
            go(w, w.viewModel().structure());
            var tree = (javafx.scene.control.TreeView<?>) content(w).lookup(".file-tree");
            tree.getRoot().getChildren().get(0).setExpanded(true); // archive
            tree.getRoot().getChildren().get(1).setExpanded(true); // notes
        });
        snapshot(directory, "file-sync-errors-only", 1180, 800, w -> {
            w.viewModel().structure().refresh();
            go(w, w.viewModel().structure());
            w.viewModel().structure().onlyErrors().set(true);
        });
        snapshot(directory, "log", 1180, 800, w -> {
            w.viewModel().log().refresh();
            go(w, w.viewModel().log());
        });
        snapshot(directory, "settings", 1180, 800, w -> go(w, w.viewModel().settings()));
        snapshot(directory, "settings-complete", 960, 1180, w -> {
            go(w, w.viewModel().settings());
            var advanced = (javafx.scene.control.TitledPane) content(w).lookup(".titled-pane");
            advanced.setAnimated(false); // a snapshot is taken at once, so the opening must not be animated
            advanced.setExpanded(true);
            w.viewModel().settings().say("Interval set to 30 minutes.");
        });
        snapshot(directory, "welcome-fresh", 1180, 800, w -> {});
        snapshot(directory, "welcome-progress", 960, 760, w -> {
            w.viewModel().welcome().refresh();
            w.viewModel().welcome().refreshSlow();
        }, true);
        snapshot(directory, "removal-finished", 1180, 800, w -> {
            go(w, w.viewModel().settings());
            button(content(w), "Remove and clean up").fire();
            button(content(w), "Remove and clean up").fire();
        });
    }

    private void snapshot(String directory, String name, int width, int height, java.util.function.Consumer<Window> arrange) throws Exception {
        snapshot(directory, name, width, height, arrange, false);
    }

    private void snapshot(String directory, String name, int width, int height, java.util.function.Consumer<Window> arrange, boolean welcome) throws Exception {
        var window = open(welcome || name.equals("welcome-fresh"), width, height);
        FxToolkit.onFxThread(() -> {
            arrange.accept(window);
            window.window().root().applyCss();
            window.window().root().layout();
            write(window.scene(), Path.of(directory, name + ".png"));
        });
    }

    private static void write(Scene scene, Path file) {
        var image = scene.snapshot(null);
        var width = (int) image.getWidth();
        var height = (int) image.getHeight();
        var out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var reader = image.getPixelReader();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) out.setRGB(x, y, reader.getArgb(x, y));
        }
        try {
            javax.imageio.ImageIO.write(out, "png", new File(file.toString()));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
