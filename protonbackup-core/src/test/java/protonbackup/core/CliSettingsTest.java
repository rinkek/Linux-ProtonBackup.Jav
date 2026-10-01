package protonbackup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CliSettingsTest {

    private final CliSettings settings = new CliSettings(new FakeSettingsStore());

    @Test
    void defaultsApplyWhenNothingIsStored() {
        assertEquals(CliSettings.DEFAULT_VERSION_PAGE_URL, settings.versionPageUrl());
        assertEquals(CliSettings.DEFAULT_DOWNLOAD_TEMPLATE, settings.downloadTemplate());
        assertEquals(CliSettings.DEFAULT_PLATFORM, settings.platform());
        assertFalse(settings.skipChecksum());
        assertNull(settings.lastSeenVersion());
        assertNull(settings.dismissedVersion());
    }

    @Test
    void valuesRoundTripThroughTheStore() {
        settings.setVersionPageUrl("https://example.test/versions.html");
        settings.setPlatform(CliSettings.BASELINE_PLATFORM);
        settings.setSkipChecksum(true);
        settings.setLastSeenVersion("0.9.0");
        settings.setDismissedVersion("0.9.0");

        assertEquals("https://example.test/versions.html", settings.versionPageUrl());
        assertEquals(CliSettings.BASELINE_PLATFORM, settings.platform());
        assertTrue(settings.skipChecksum());
        assertEquals("0.9.0", settings.lastSeenVersion());
        assertEquals("0.9.0", settings.dismissedVersion());
    }

    @Test
    void restoreDefaultsResetsEverythingExceptLastSeenAndDismissed() {
        settings.setVersionPageUrl("https://example.test/versions.html");
        settings.setDownloadTemplate("https://example.test/{version}/{platform}");
        settings.setPlatform(CliSettings.BASELINE_PLATFORM);
        settings.setSkipChecksum(true);
        settings.setLastSeenVersion("0.9.0");

        settings.restoreDefaults();

        assertEquals(CliSettings.DEFAULT_VERSION_PAGE_URL, settings.versionPageUrl());
        assertEquals(CliSettings.DEFAULT_DOWNLOAD_TEMPLATE, settings.downloadTemplate());
        assertEquals(CliSettings.DEFAULT_PLATFORM, settings.platform());
        assertFalse(settings.skipChecksum());
        assertEquals("0.9.0", settings.lastSeenVersion());
    }

    @Test
    void buildDownloadUrlSubstitutesVersionAndPlatform() {
        assertEquals(
                "https://proton.me/download/drive/cli/0.9.0/linux-x64/proton-drive",
                settings.buildDownloadUrl("0.9.0", "linux-x64"));
    }
}
