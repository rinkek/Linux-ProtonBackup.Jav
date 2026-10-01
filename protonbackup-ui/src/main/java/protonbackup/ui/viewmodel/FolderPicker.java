package protonbackup.ui.viewmodel;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Lets the user choose a folder. Implemented by the window (it owns the stage a dialog needs) and called
 * on the JavaFX thread; a dialog blocks there while it is open, which is fine for a modal dialog.
 */
@FunctionalInterface
public interface FolderPicker {

    /** For tests and for windows without a dialog: the user never picks anything. */
    FolderPicker NONE = title -> Optional.empty();

    /** The chosen folder, or empty when the user cancelled. */
    Optional<Path> pick(String title);
}
