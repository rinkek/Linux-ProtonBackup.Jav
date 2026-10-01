package protonbackup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The values below are exactly what the real jpackage launcher produced in the phase 6 check. */
class LauncherEnvironmentTest {

    private static final String APP_PATH = "/usr/lib/protonbackup/bin/protonbackup";

    private static Map<String, String> env(String... pairs) {
        var map = new HashMap<String, String>();
        for (var i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return map;
    }

    @Test
    void theLaunchersOwnEntryIsRemovedWhenTheVariableWasUnsetBefore() {
        var environment = env("_JPACKAGE_LAUNCHER", "0", "LD_LIBRARY_PATH", ":/usr/lib/protonbackup/lib/app", "PATH", "/usr/bin");

        LauncherEnvironment.sanitize(environment, APP_PATH);

        assertFalse(environment.containsKey("LD_LIBRARY_PATH"), "an empty variable would mean the current directory");
        assertFalse(environment.containsKey("_JPACKAGE_LAUNCHER"));
        assertEquals("/usr/bin", environment.get("PATH"));
    }

    @Test
    void theUsersOwnEntriesAreKept() {
        var environment = env("_JPACKAGE_LAUNCHER", "0", "LD_LIBRARY_PATH", "/opt/x:/usr/lib/protonbackup/lib/app");

        LauncherEnvironment.sanitize(environment, APP_PATH);

        assertEquals("/opt/x", environment.get("LD_LIBRARY_PATH"));
    }

    @Test
    void everythingUnderTheAppRootGoesButALookAlikeFolderStays() {
        var environment = env(
                "_JPACKAGE_LAUNCHER", "0",
                "LD_LIBRARY_PATH", "/a:/usr/lib/protonbackup/lib/runtime/lib:/usr/lib/protonbackup-other/lib:/b:/usr/lib/protonbackup/lib/app");

        LauncherEnvironment.sanitize(environment, APP_PATH);

        assertEquals("/a:/usr/lib/protonbackup-other/lib:/b", environment.get("LD_LIBRARY_PATH"));
    }

    @Test
    void withoutTheLauncherMarkerNothingIsTouched() {
        var environment = env("LD_LIBRARY_PATH", ":/usr/lib/protonbackup/lib/app:/opt/x");

        LauncherEnvironment.sanitize(environment, APP_PATH);

        assertEquals(":/usr/lib/protonbackup/lib/app:/opt/x", environment.get("LD_LIBRARY_PATH"));
    }

    @Test
    void theMarkerAloneIsRemovedWhenThereIsNoLibraryPath() {
        var environment = env("_JPACKAGE_LAUNCHER", "0");

        LauncherEnvironment.sanitize(environment, APP_PATH);

        assertEquals(Map.of(), environment);
    }

    @Test
    void whenTheLauncherPathIsUnknownTheLibAppFolderAndEmptyEntriesAreStillRemoved() {
        var environment = env("_JPACKAGE_LAUNCHER", "0", "LD_LIBRARY_PATH", "/opt/x::/somewhere/lib/app");

        LauncherEnvironment.sanitize(environment, null);

        assertEquals("/opt/x", environment.get("LD_LIBRARY_PATH"));
    }
}
