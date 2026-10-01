package protonbackup.ui.tray;

import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import protonbackup.ui.viewmodel.TrayState;

/**
 * The tray icon, through AWT's {@link SystemTray}: JavaFX has no tray API. On KDE Plasma this registers as a
 * status notifier item (verified in phase 2 and again in phase 11). A desktop without a tray (GNOME without
 * an extension) simply gets no icon; the window shows the same state, so nothing is lost.
 *
 * <p>All AWT calls go through the AWT event thread, never the JavaFX thread. The icon follows
 * {@link TrayState}; a click on it, and "Open" in its menu, bring the window forward.
 */
public final class AwtTray {

    private static final System.Logger LOG = System.getLogger("protonbackup.ui");

    private final TrayIcon icon;
    private final Map<TrayState, Image> images;

    private AwtTray(TrayIcon icon, Map<TrayState, Image> images) {
        this.icon = icon;
        this.images = images;
    }

    /**
     * Puts the icon in the tray, or returns empty when there is no tray, no display, or it cannot be added.
     * {@code java.awt.headless=false} must be set before AWT starts ({@code Main} does that).
     */
    public static Optional<AwtTray> install(TrayState initial, Runnable onOpen, Runnable onQuit) {
        try {
            if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
                LOG.log(System.Logger.Level.INFO, "No system tray here; the window shows the state instead.");
                return Optional.empty();
            }
            var images = new EnumMap<TrayState, Image>(TrayState.class);
            for (var state : TrayState.values()) images.put(state, load(state));

            var menu = new PopupMenu();
            var open = new MenuItem("Open");
            open.addActionListener(event -> onOpen.run());
            var quit = new MenuItem("Quit");
            quit.addActionListener(event -> onQuit.run());
            menu.add(open);
            menu.add(quit);

            var icon = new TrayIcon(images.get(initial), TrayTexts.tooltip(initial), menu);
            icon.setImageAutoSize(true);
            icon.addActionListener(event -> onOpen.run());
            SystemTray.getSystemTray().add(icon);
            return Optional.of(new AwtTray(icon, images));
        } catch (Throwable unavailable) {
            // The tray is a convenience; whatever goes wrong here must not stop the app.
            LOG.log(System.Logger.Level.WARNING, "The tray icon could not be added: " + unavailable);
            return Optional.empty();
        }
    }

    /** Shows the state in the icon and its tooltip. Safe to call from any thread. */
    public void show(TrayState state) {
        EventQueue.invokeLater(() -> {
            icon.setImage(images.get(state));
            icon.setToolTip(TrayTexts.tooltip(state));
        });
    }

    /** Takes the icon out of the tray. */
    public void remove() {
        EventQueue.invokeLater(() -> SystemTray.getSystemTray().remove(icon));
    }

    private static Image load(TrayState state) throws IOException {
        try (var stream = AwtTray.class.getResourceAsStream(TrayTexts.iconResource(state))) {
            if (stream == null) throw new IOException("missing icon " + TrayTexts.iconResource(state));
            return ImageIO.read(stream);
        }
    }
}
