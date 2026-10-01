package protonbackup.core;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Removes everything the app placed in the user's home folder. A package must not touch that, so
 * this runs from within the app and not from {@code apt remove}. Whatever is on Proton Drive is
 * never touched.
 *
 * <p>Differences from the .NET original (see {@code MIGRATION_PLAN.md}, section 6.9):
 * <ul>
 *   <li>the running app is stopped from using its files first, and {@link AppPaths#retire()} makes
 *       sure nothing recreates them;
 *   <li>also removed: the cache folder, the systemd timer stamp file, the enable symlink and
 *       JavaFX's {@code ~/.openjfx};
 *   <li>the result is checked at the end: a path that still exists is reported as a failure.
 * </ul>
 */
public final class Cleanup {

    public record Step(String description, boolean succeeded, String detail) {}

    /** Signs out of Proton with the CLI at the given path. */
    @FunctionalInterface
    public interface CliSignOut {
        CliResult signOut(String cliPath) throws IOException, InterruptedException;
    }

    private static final Duration RETRY_DELAY = Duration.ofMillis(300);

    private final AppPaths paths;
    private final UnitNames units;
    private final SystemdControl systemd;
    private final Supplier<String> locateCli;
    private final CliSignOut signOut;
    private final Runnable quiesce;
    private final Sleeper sleeper;

    /**
     * @param quiesce stops background work and closes the database and lock; called before anything is deleted
     * @param locateCli the path of the Proton CLI, or {@code null} when there is none
     */
    public Cleanup(
            AppPaths paths,
            UnitNames units,
            SystemdControl systemd,
            Supplier<String> locateCli,
            CliSignOut signOut,
            Runnable quiesce,
            Sleeper sleeper) {
        this.paths = paths;
        this.units = units;
        this.systemd = systemd;
        this.locateCli = locateCli;
        this.signOut = signOut;
        this.quiesce = quiesce;
        this.sleeper = sleeper;
    }

    /** True when every step succeeded. */
    public static boolean succeeded(List<Step> steps) {
        return steps.stream().allMatch(Step::succeeded);
    }

    public List<Step> run() throws InterruptedException {
        var steps = new ArrayList<Step>();

        // 1. The app must stop using its files before they are deleted, and must not bring them back.
        try {
            quiesce.run();
            paths.retire();
            steps.add(new Step("Background work stopped", true, null));
        } catch (RuntimeException e) {
            paths.retire();
            steps.add(new Step("Background work stopped", false, e.getMessage()));
        }

        // 2. systemd and the Proton session.
        steps.add(systemdStep("Timer turned off", systemd::disableTimer));
        steps.add(systemdStep("Run in progress stopped", systemd::stopSync));
        steps.add(signOutStep());

        // 3. Files.
        steps.add(removeTree("Data folder removed", paths.dataDir()));
        steps.add(removeTree("Config folder removed", paths.configDir()));
        steps.add(removeTree("Cache folder removed", paths.cacheDir()));
        steps.add(removeUnits());
        steps.add(removeTimerStamps());
        steps.add(removeTree("Interface cache removed", paths.openJfxDir()));

        // 4. Tell systemd the units are gone.
        steps.add(systemdStep("systemd reloaded", systemd::reload));

        // 5. Prove it: look at the disk again.
        steps.add(verify());
        return steps;
    }

    @FunctionalInterface
    private interface SystemdCall {
        CliResult call() throws IOException, InterruptedException;
    }

    private static Step systemdStep(String description, SystemdCall call) throws InterruptedException {
        try {
            var result = call.call();
            return new Step(description, result.ok(), result.ok() ? null : result.output().trim());
        } catch (IOException e) {
            return new Step(description, false, e.getMessage());
        }
    }

    private Step signOutStep() throws InterruptedException {
        var cli = locateCli.get();
        if (cli == null) return new Step("Sign-out skipped", true, "the CLI was not found");
        try {
            var result = signOut.signOut(cli);
            return new Step("Signed out of Proton", result.ok(), result.ok() ? null : result.output().trim());
        } catch (IOException e) {
            return new Step("Signed out of Proton", false, e.getMessage());
        }
    }

    private static Step removeTree(String description, Path path) {
        try {
            deleteTree(path);
            return new Step(description, true, path.toString());
        } catch (IOException e) {
            return new Step(description, false, e.getMessage());
        }
    }

    /** Only the units and drop-ins in the home folder; what the package installed is left to apt. */
    private Step removeUnits() {
        var directory = paths.userUnitDir();
        var removed = new ArrayList<String>();
        try {
            for (var name : List.of(units.service(), units.timer(), units.sourceTemplate())) {
                if (deleteIfPresent(directory.resolve(name))) removed.add(name);
            }
            if (deleteIfPresent(directory.resolve(units.timerDropInDir()))) removed.add("drop-ins");
            if (deleteIfPresent(directory.resolve(units.timerWantsDir()).resolve(units.timer()))) {
                removed.add("enable link");
            }
            return new Step("systemd units cleaned up", true, removed.isEmpty() ? "nothing to remove" : String.join(", ", removed));
        } catch (IOException e) {
            return new Step("systemd units cleaned up", false, e.getMessage());
        }
    }

    private Step removeTimerStamps() {
        try {
            var removed = 0;
            for (var stamp : timerStamps()) {
                deleteTree(stamp);
                removed++;
            }
            return new Step("Timer stamp files removed", true, removed == 0 ? "nothing to remove" : removed + " file(s)");
        } catch (IOException e) {
            return new Step("Timer stamp files removed", false, e.getMessage());
        }
    }

    /** Everything that is ours and still on disk. */
    private List<Path> leftovers() {
        var candidates = new ArrayList<Path>(List.of(paths.dataDir(), paths.configDir(), paths.cacheDir(), paths.openJfxDir()));
        var unitDir = paths.userUnitDir();
        for (var name : List.of(units.service(), units.timer(), units.sourceTemplate())) {
            candidates.add(unitDir.resolve(name));
        }
        candidates.add(unitDir.resolve(units.timerDropInDir()));
        candidates.add(unitDir.resolve(units.timerWantsDir()).resolve(units.timer()));
        candidates.addAll(timerStamps());
        return candidates.stream().filter(path -> Files.exists(path, LinkOption.NOFOLLOW_LINKS)).toList();
    }

    private List<Path> timerStamps() {
        var directory = paths.timerStampDir();
        if (!Files.isDirectory(directory)) return List.of();
        try (var entries = Files.list(directory)) {
            return entries.filter(entry -> entry.getFileName().toString().equals(units.timerStamp())).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private Step verify() throws InterruptedException {
        var remaining = leftovers();
        if (!remaining.isEmpty()) {
            // A file that was still being closed can be gone a moment later: try once more.
            sleeper.sleep(RETRY_DELAY);
            for (var path : remaining) {
                try {
                    deleteTree(path);
                } catch (IOException ignored) {
                    // reported below if it is still there
                }
            }
            remaining = leftovers();
        }
        if (remaining.isEmpty()) return new Step("Verified that nothing is left behind", true, null);
        return new Step(
                "Verified that nothing is left behind",
                false,
                "still present: " + String.join(", ", remaining.stream().map(Path::toString).toList()));
    }

    private static boolean deleteIfPresent(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
        deleteTree(path);
        return true;
    }

    /** Deletes a file, symlink or folder tree; symlinks are removed, never followed. */
    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) throw error;
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
