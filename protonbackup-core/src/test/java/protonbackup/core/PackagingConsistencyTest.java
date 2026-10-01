package protonbackup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The files of the package live outside the Java code and are only exercised when a package is
 * built and installed, which is far too late to find out that they have drifted from the code they
 * depend on. These tests keep the loose ends tied together: the units, the instructions for removing
 * the user's data by hand, the maintainer scripts and the names the build script installs.
 */
class PackagingConsistencyTest {

    /** Maven runs the tests of a module with the module's folder as the working directory. */
    private static final Path PACKAGING = Path.of("..", "packaging");

    @TempDir Path home;

    private static String read(String relative) throws IOException {
        var file = PACKAGING.resolve(relative);
        assertTrue(Files.isRegularFile(file), file.toAbsolutePath().normalize() + " is missing");
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** What systemd reads of a unit: no comments, no blank lines. */
    private static List<String> effective(String unit) {
        return unit.lines().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
    }

    /** {@code /home/someone/.config/x} as the user would type it: {@code ~/.config/x}. */
    private static String tilde(Path path) {
        return "~/" + Path.of("/home/someone").relativize(path);
    }

    // ---- the units ------------------------------------------------------------------------

    /**
     * The package ships the three units in {@code /usr/lib/systemd/user}, and the app writes its own
     * copies into the user's folder. They must say the same, or a user who has only the packaged ones
     * gets a different timer than a user who has used the app.
     */
    @Test
    void theUnitsThePackageShipsAreTheOnesTheAppWrites() throws Exception {
        new SystemdManager(AppPaths.forHome(home), "/usr/bin/protonbackup", (executable, arguments) -> new CliResult(0, "", ""))
                .installUnits();
        var units = UnitNames.DEFAULT;

        for (var name : List.of(units.service(), units.sourceTemplate(), units.timer())) {
            var written = Files.readString(home.resolve(".config/systemd/user").resolve(name));
            assertEquals(
                    effective(written),
                    effective(read("templates/" + name)),
                    name + ": the packaged unit differs from the one the app writes");
        }
    }

    /** The units start the wrapper, never the launcher inside the app folder (see launcher.sh). */
    @Test
    void theUnitsCallTheWrapperInUsrBin() throws Exception {
        assertTrue(read("templates/protonbackup-sync.service").contains("\nExecStart=/usr/bin/protonbackup --run-once\n"));
        assertTrue(read("templates/protonbackup-sync@.service").contains("\nExecStart=/usr/bin/protonbackup --run-once --source %i\n"));
    }

    // ---- removal by hand ------------------------------------------------------------------

    /** The text a package prints when it leaves the user's data alone: the commands between the fences. */
    private static String manualCleanupText() throws IOException {
        var common = read("debian/common.sh");
        var start = common.indexOf("print_manual_cleanup() {");
        assertTrue(start >= 0, "print_manual_cleanup is missing from common.sh");
        var heredoc = common.indexOf("<<'EOF'\n", start);
        var end = common.indexOf("\nEOF\n", heredoc);
        assertTrue(heredoc > start && end > heredoc, "the here-document of print_manual_cleanup was not found");
        return common.substring(heredoc + "<<'EOF'\n".length(), end);
    }

    /**
     * When the package is gone, so is {@code protonbackup --cleanup}; the person is left with the
     * list the package printed. It must name everything {@link Cleanup} removes, or "I followed the
     * instructions" still leaves files in the home folder (which is exactly what went wrong before).
     */
    @Test
    void theManualInstructionsNameEverythingTheCleanupRemoves() throws Exception {
        var text = manualCleanupText();
        var paths = AppPaths.forHome(Path.of("/home/someone"));
        var units = UnitNames.DEFAULT;
        var unitDir = paths.userUnitDir();

        var everything = List.of(
                tilde(paths.dataDir()),
                tilde(paths.configDir()),
                tilde(paths.cacheDir()),
                tilde(paths.openJfxDir()),
                tilde(unitDir.resolve(units.service())),
                tilde(unitDir.resolve(units.sourceTemplate())),
                tilde(unitDir.resolve(units.timer())),
                tilde(unitDir.resolve(units.timerDropInDir())),
                tilde(unitDir.resolve(units.timerWantsDir()).resolve(units.timer())),
                tilde(paths.timerStampDir().resolve(units.timerStamp())));
        for (var path : everything) {
            assertTrue(text.contains(path), "the manual instructions do not mention " + path);
        }
        assertTrue(text.contains("systemctl --user disable --now " + units.timer()), "the timer must be turned off first");
        assertTrue(text.contains("systemctl --user daemon-reload"), "systemd must be told that the units are gone");
        assertTrue(text.contains("protonbackup --cleanup"), "the preferred way must be named first");
    }

    // ---- the maintainer scripts -----------------------------------------------------------

    /**
     * Each script is a file of its own plus the shared helpers, pasted in by the build at the marker
     * line. A script that cannot be parsed would fail every installation, upgrade and removal.
     */
    @Test
    void theMaintainerScriptsAreValidShellOnceTheHelpersAreInserted() throws Exception {
        var common = read("debian/common.sh");
        for (var script : List.of("postinst", "prerm", "postrm")) {
            var body = read("debian/" + script);
            assertTrue(body.startsWith("#!/bin/sh\n"), script + " must be a plain POSIX sh script");
            assertEquals(1, body.split("# @COMMON@\n", -1).length - 1, script + " must contain the marker line exactly once");
            assertTrue(body.stripTrailing().endsWith("exit 0"), script + " must always end with exit 0");

            var assembled = body.replace("# @COMMON@\n", common);
            // A removal must go on whatever fails: nothing in these scripts may abort on the first error.
            assertFalse(assembled.contains("set -e"), script + " must not use set -e");
            assertFalse(assembled.contains("@COMMON@"), script + ": the marker is mentioned outside its line");

            var file = Files.writeString(home.resolve(script), assembled, StandardCharsets.UTF_8);
            var check = new ProcessBuilder("sh", "-n", file.toString()).redirectErrorStream(true).start();
            var output = new String(check.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, check.waitFor(), script + " does not parse: " + output);
        }
    }

    // ---- what the build installs ----------------------------------------------------------

    /** The desktop entry, the units and the build script must agree on the names. */
    @Test
    void theDesktopEntryAndTheBuildAgreeOnTheNames() throws Exception {
        var build = read("build-in-container.sh");
        var desktop = read("templates/protonbackup.desktop");

        assertTrue(desktop.contains("\nExec=protonbackup-ui\n"));
        assertTrue(build.contains("--add-launcher \"protonbackup-ui="), "the build must create the launcher the desktop entry starts");
        assertTrue(build.contains("\"$STAGE/usr/bin/protonbackup-ui\""), "the build must install the wrapper under that name");
        assertTrue(build.contains("\"$STAGE/usr/bin/protonbackup\""), "the build must install the wrapper the units call");

        assertTrue(desktop.contains("\nIcon=protonbackup\n"));
        assertTrue(build.contains("hicolor/256x256/apps/protonbackup.png"), "the build must install the icon the desktop entry names");
    }
}
