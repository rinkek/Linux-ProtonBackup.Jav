package protonbackup.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The desktop entry lives outside the Java code; this keeps it tied to the window it starts. */
class DesktopEntryTest {

    /**
     * JavaFX gives its window the window-manager class of the {@code Application} subclass (measured on
     * the packaged app with xprop: {@code protonbackup.ui.App}). The desktop uses
     * {@code StartupWMClass} to recognise that window as belonging to the launcher entry: without a
     * match, the task bar shows a second, generic icon and "starting" feedback never ends.
     */
    @Test
    void theStartupWmClassIsTheClassOfTheWindow() throws IOException {
        var entry = Files.readString(Path.of("..", "packaging", "templates", "protonbackup.desktop"));

        assertTrue(
                entry.contains("\nStartupWMClass=" + App.class.getName() + "\n"),
                "protonbackup.desktop must say StartupWMClass=" + App.class.getName());
    }
}
