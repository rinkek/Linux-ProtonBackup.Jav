package protonbackup.ui.tray;

import protonbackup.ui.viewmodel.TrayState;

/** What the tray icon says and shows for each state. Pure, so it can be tested without a desktop. */
public final class TrayTexts {

    private TrayTexts() {}

    public static String tooltip(TrayState state) {
        return switch (state) {
            case OK -> "Proton Drive backup — up to date";
            case BUSY -> "Proton Drive backup — syncing";
            case ERROR -> "Proton Drive backup — some files failed";
        };
    }

    /** The classpath location of the icon for a state. */
    public static String iconResource(TrayState state) {
        return switch (state) {
            case OK -> "/protonbackup/ui/assets/tray-ok.png";
            case BUSY -> "/protonbackup/ui/assets/tray-busy.png";
            case ERROR -> "/protonbackup/ui/assets/tray-error.png";
        };
    }
}
