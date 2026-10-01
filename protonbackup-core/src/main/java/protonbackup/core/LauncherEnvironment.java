package protonbackup.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

/**
 * Undoes what the jpackage launcher does to the environment before the JVM starts.
 *
 * <p>Measured on the real launcher: it appends {@code :<app>/lib/app} to {@code LD_LIBRARY_PATH}
 * (leaving a leading empty entry, which means "the current directory", when the variable was unset)
 * and sets {@code _JPACKAGE_LAUNCHER=0}. Every child process inherits both. The Python port hit the
 * same thing with PyInstaller: the independently downloaded {@code proton-drive} binary then failed
 * to find its own system libraries. Children must see the environment the user had.
 *
 * <p>Only acts when {@code _JPACKAGE_LAUNCHER} is present, which is the sign that the launcher
 * touched the environment; a plain {@code java -jar} run (development, tests) is left alone.
 */
public final class LauncherEnvironment {

    private static final String MARKER = "_JPACKAGE_LAUNCHER";
    private static final String LIBRARY_PATH = "LD_LIBRARY_PATH";

    private LauncherEnvironment() {}

    /**
     * @param environment a child's environment, modified in place
     * @param appPath the launcher's path ({@code jpackage.app-path}), e.g. {@code /usr/lib/protonbackup/bin/protonbackup};
     *     {@code null} when unknown
     */
    public static void sanitize(Map<String, String> environment, String appPath) {
        if (environment.remove(MARKER) == null) return;

        var libraryPath = environment.get(LIBRARY_PATH);
        if (libraryPath == null) return;

        var appRoot = appRoot(appPath);
        var kept = new ArrayList<String>();
        for (var entry : libraryPath.split(":", -1)) {
            if (entry.isEmpty() || belongsToTheApp(entry, appRoot)) continue;
            kept.add(entry);
        }
        if (kept.isEmpty()) environment.remove(LIBRARY_PATH);
        else environment.put(LIBRARY_PATH, String.join(":", kept));
    }

    /** {@code <root>/bin/<launcher>} gives {@code <root>}. */
    private static Path appRoot(String appPath) {
        if (appPath == null || appPath.isBlank()) return null;
        var bin = Path.of(appPath).getParent();
        return bin == null ? null : bin.getParent();
    }

    private static boolean belongsToTheApp(String entry, Path appRoot) {
        if (appRoot != null) return Path.of(entry).normalize().startsWith(appRoot);
        // Root unknown: fall back to the folder the launcher is known to add.
        return entry.endsWith("/lib/app");
    }
}
