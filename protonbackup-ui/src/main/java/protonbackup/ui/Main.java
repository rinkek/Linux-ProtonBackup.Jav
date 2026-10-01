package protonbackup.ui;

import javafx.application.Application;

/**
 * Plain launcher class. It must not extend {@link Application}: a main class that does is started
 * through the JavaFX-aware launcher, which fails on a class path without the module path set up.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        // The tray icon uses AWT, which must be told before it starts that there is a display.
        System.setProperty("java.awt.headless", "false");
        Application.launch(App.class, args);
    }
}
