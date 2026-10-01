package protonbackup.ui.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import protonbackup.core.AppPaths;

/**
 * The systemd units the UI writes must call the <em>daemon</em>, not the UI itself. Candidates, in the
 * order of the .NET original: the daemon next to the UI's own launcher, the packaged location, and a
 * copy in the app's data folder. With none present the last one is returned, so the unit at least
 * names a path the user can see and fix.
 */
public final class DaemonLocator {

    static final String DAEMON_NAME = "protonbackup";
    static final String PACKAGED_LOCATION = "/usr/bin/protonbackup";

    private DaemonLocator() {}

    /** @param ownExecutable the UI's own launcher (the wrapper), or {@code null} when started some other way */
    public static String locate(String ownExecutable, AppPaths paths) {
        return locate(ownExecutable, paths, Files::isRegularFile);
    }

    static String locate(String ownExecutable, AppPaths paths, Predicate<Path> exists) {
        List<Path> candidates = new ArrayList<>();
        if (ownExecutable != null && !ownExecutable.isBlank()) {
            var directory = Path.of(ownExecutable).toAbsolutePath().getParent();
            if (directory != null) candidates.add(directory.resolve(DAEMON_NAME));
        }
        candidates.add(Path.of(PACKAGED_LOCATION));
        var lastResort = paths.dataDir().resolve("app").resolve(DAEMON_NAME);
        candidates.add(lastResort);

        return candidates.stream().filter(exists).findFirst().orElse(lastResort).toString();
    }
}
