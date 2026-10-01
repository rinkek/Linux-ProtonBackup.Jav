package protonbackup.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Where the app keeps its files, following the XDG layout like the .NET original
 * ({@code LocalApplicationData} and {@code ApplicationData} resolve to {@code $XDG_DATA_HOME} and
 * {@code $XDG_CONFIG_HOME} on Linux).
 *
 * <p>An instance is passed to everything that touches the disk, so tests can work in a temporary
 * home folder. Once {@link #retire()} has been called (removal of the app's data), {@link
 * #ensureCreated()} refuses to create the folders again: the running UI must not bring back what
 * the cleanup just deleted.
 */
public final class AppPaths {

    private static final String APP_DIR_NAME = "ProtonBackup";

    private final Path home;
    private final Path dataHome;
    private final Path configHome;
    private final Path cacheHome;
    private final AtomicBoolean retired = new AtomicBoolean();

    public AppPaths(Path home, Path dataHome, Path configHome, Path cacheHome) {
        this.home = home;
        this.dataHome = dataHome;
        this.configHome = configHome;
        this.cacheHome = cacheHome;
    }

    /** The default XDG layout below a given home folder, ignoring the environment (used by tests). */
    public static AppPaths forHome(Path home) {
        return new AppPaths(
                home, home.resolve(".local/share"), home.resolve(".config"), home.resolve(".cache"));
    }

    /**
     * The real layout. {@code $HOME} wins over the JVM's {@code user.home}, because the JVM takes
     * that from the password database and ignores a different {@code $HOME}.
     */
    public static AppPaths fromEnvironment() {
        return fromEnvironment(System.getenv(), System.getProperty("user.home"));
    }

    static AppPaths fromEnvironment(Map<String, String> env, String userHome) {
        var homeText = nonBlank(env.get("HOME"));
        var home = Path.of(homeText != null ? homeText : userHome);
        return new AppPaths(
                home,
                xdg(env, "XDG_DATA_HOME", home.resolve(".local/share")),
                xdg(env, "XDG_CONFIG_HOME", home.resolve(".config")),
                xdg(env, "XDG_CACHE_HOME", home.resolve(".cache")));
    }

    private static Path xdg(Map<String, String> env, String variable, Path fallback) {
        var value = nonBlank(env.get(variable));
        return value != null ? Path.of(value) : fallback;
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public Path home() {
        return home;
    }

    /** {@code ~/.local/share/ProtonBackup}: database, lock file, downloaded CLI. */
    public Path dataDir() {
        return dataHome.resolve(APP_DIR_NAME);
    }

    /** {@code ~/.config/ProtonBackup}. */
    public Path configDir() {
        return configHome.resolve(APP_DIR_NAME);
    }

    /** {@code ~/.cache/ProtonBackup}: nothing the app cannot recreate. */
    public Path cacheDir() {
        return cacheHome.resolve(APP_DIR_NAME);
    }

    public Path binDir() {
        return dataDir().resolve("bin");
    }

    public Path databasePath() {
        return dataDir().resolve("protonbackup.db");
    }

    public Path lockPath() {
        return dataDir().resolve("sync.lock");
    }

    /** {@code ~/.config/systemd/user}: the user's own units and drop-ins. */
    public Path userUnitDir() {
        return configHome.resolve("systemd").resolve("user");
    }

    /** {@code ~/.local/share/systemd/timers}: where systemd keeps timer stamp files. */
    public Path timerStampDir() {
        return dataHome.resolve("systemd").resolve("timers");
    }

    /** JavaFX's native-library cache. The packaged app never creates it; removal still checks. */
    public Path openJfxDir() {
        return home.resolve(".openjfx");
    }

    public void ensureCreated() throws IOException {
        if (retired.get()) {
            throw new IllegalStateException("The app's data has been removed; nothing may recreate it.");
        }
        Files.createDirectories(dataDir());
        Files.createDirectories(configDir());
        Files.createDirectories(binDir());
    }

    /** After this call {@link #ensureCreated()} fails. Called when the app's data is being removed. */
    public void retire() {
        retired.set(true);
    }

    public boolean isRetired() {
        return retired.get();
    }
}
