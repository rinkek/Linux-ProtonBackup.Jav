package protonbackup.ui.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import protonbackup.core.AppPaths;

class DaemonLocatorTest {

    private static final AppPaths PATHS = AppPaths.forHome(Path.of("/home/someone"));

    private static String locate(String own, String... existing) {
        var present = Set.of(existing);
        return DaemonLocator.locate(own, PATHS, path -> present.contains(path.toString()));
    }

    @Test
    void theDaemonNextToTheUiLauncherWins() {
        assertEquals("/opt/pb/protonbackup",
                locate("/opt/pb/protonbackup-ui", "/opt/pb/protonbackup", "/usr/bin/protonbackup"));
    }

    @Test
    void thePackagedLocationComesNext() {
        assertEquals("/usr/bin/protonbackup", locate("/opt/pb/protonbackup-ui", "/usr/bin/protonbackup"));
    }

    @Test
    void theCopyInTheAppDataFolderIsTheLastCandidate() {
        assertEquals("/home/someone/.local/share/ProtonBackup/app/protonbackup",
                locate("/opt/pb/protonbackup-ui", "/home/someone/.local/share/ProtonBackup/app/protonbackup"));
    }

    @Test
    void withNothingFoundTheLastResortIsNamedSoTheUnitShowsAPathTheUserCanFix() {
        assertEquals("/home/someone/.local/share/ProtonBackup/app/protonbackup", locate("/opt/pb/protonbackup-ui"));
    }

    @Test
    void withoutAnOwnLauncherOnlyThePackagedLocationAndTheLastResortAreTried() {
        assertEquals("/usr/bin/protonbackup", locate(null, "/usr/bin/protonbackup"));
        assertEquals("/usr/bin/protonbackup", locate("  ", "/usr/bin/protonbackup"));
    }

    @Test
    void aRelativeLauncherPathIsMadeAbsoluteBeforeLookingNextToIt() {
        var expected = Path.of("bin").toAbsolutePath().resolve("protonbackup").toString();

        assertEquals(expected, locate("bin/protonbackup-ui", expected));
    }
}
